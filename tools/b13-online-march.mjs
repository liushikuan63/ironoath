/** B13-9 行军负载调度。无 CLI；只有上层完成外部准入核验后才可传入真实 fetch。 */
import { performance } from 'node:perf_hooks'
import { setTimeout as delay } from 'node:timers/promises'

const percentile = (values, ratio) => {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.ceil(sorted.length * ratio) - 1]
}
const count = (map, key) => { map[key] = (map[key] ?? 0) + 1 }

/**
 * One slot = one scheduled MarchReq. Missed slots stay missed: never bunch delayed requests
 * into a burst to make the aggregate count look like 200 QPS.
 */
export async function runMarchLoad(players, fleet, {
  targetOrigin, runId, durationSeconds, rate = 200, maxInFlight = 400,
  requestTimeoutMs = 10_000, notAfterUtc,
  fetchImpl = globalThis.fetch, monotonicNow = () => performance.now(),
  wallNow = () => Date.now(), sleep = delay,
} = {}) {
  if (!Array.isArray(players) || players.length === 0 || !fleet?.state
      || fleet.state.bound !== players.length || !['http:', 'https:'].includes(new URL(targetOrigin).protocol)
      || !/^[A-Za-z0-9_-]{1,64}$/.test(runId)
      || !Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 600
      || !Number.isInteger(rate) || rate < 1 || rate > 200
      || !Number.isInteger(maxInFlight) || maxInFlight < 1
      || !Number.isInteger(requestTimeoutMs) || requestTimeoutMs < 1
      || !Number.isFinite(Date.parse(notAfterUtc)) || typeof fetchImpl !== 'function') {
    throw new Error('行军负载前置无效')
  }
  const planned = durationSeconds * rate
  const startedAt = monotonicNow()
  const deadlineUtc = Date.parse(notAfterUtc)
  const pending = new Set()
  const samples = []
  const launchedBySecond = Array(durationSeconds).fill(0)
  let missedCapacity = 0
  let missedSchedule = 0
  let stoppedReason = null
  let minBound = fleet.state.bound
  let lastLaunchAt = Number.NEGATIVE_INFINITY

  const launch = (slot, player) => {
    const started = monotonicNow()
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs)
    const request = (async () => {
      let http = 0
      let code = null
      try {
        const response = await fetchImpl(`${targetOrigin}/world/march`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'X-Player-Id': player.playerId,
            authorization: `Bearer ${player.token}` },
          body: JSON.stringify({ ...player.march, requestId: `${runId}-${slot}` }),
          signal: controller.signal,
        })
        http = response.status
        const body = await response.json()
        if (Number.isInteger(body?.code)) code = body.code
      } catch { /* transport failure is a sample, not a swallowed success */ }
      finally { clearTimeout(timeout) }
      samples.push({ slot, playerIndex: slot % players.length,
        launchedAtMs: started - startedAt, http, code,
        latencyMs: Math.max(0, monotonicNow() - started) })
    })()
    pending.add(request)
    request.finally(() => pending.delete(request))
  }

  for (let slot = 0; slot < planned; slot++) {
    const scheduledAt = startedAt + slot * 1000 / rate
    const waitMs = scheduledAt - monotonicNow()
    if (waitMs > 0) await sleep(waitMs)
    minBound = Math.min(minBound, fleet.state.bound)
    if (fleet.state.bound !== players.length || fleet.state.unexpectedClosures > 0) {
      stoppedReason = 'bound_connections_lost'
      break
    }
    if (wallNow() >= deadlineUtc) { stoppedReason = 'admission_window_expired'; break }
    if (monotonicNow() - scheduledAt > 2 * 1000 / rate) {
      missedSchedule++
      continue
    }
    if (monotonicNow() - lastLaunchAt < 1000 / rate - 0.1) {
      missedSchedule++
      continue
    }
    if (pending.size >= maxInFlight) { missedCapacity++; continue }
    const actualSecond = Math.floor((monotonicNow() - startedAt) / 1000)
    if (actualSecond >= durationSeconds) { stoppedReason = 'scheduler_late'; break }
    launchedBySecond[actualSecond]++
    lastLaunchAt = monotonicNow()
    launch(slot, players[slot % players.length])
  }
  await Promise.allSettled([...pending])
  minBound = Math.min(minBound, fleet.state.bound)
  samples.sort((a, b) => a.slot - b.slot)
  const httpStatusCounts = {}
  const businessCodeCounts = {}
  const successfulLatencies = []
  for (const sample of samples) {
    count(httpStatusCounts, String(sample.http))
    count(businessCodeCounts, String(sample.code))
    if (sample.http === 200 && sample.code === 0) successfulLatencies.push(sample.latencyMs)
  }
  return {
    status: 'MEASUREMENT_ONLY_NOT_B13_ACCEPTANCE', planned, launched: samples.length,
    missedCapacity, missedSchedule, stoppedReason, rate, durationSeconds, launchedBySecond,
    minBound, peakBound: fleet.state.peakBound,
    unexpectedClosures: fleet.state.unexpectedClosures,
    httpStatusCounts, businessCodeCounts,
    successful: successfulLatencies.length,
    successLatencyP50Ms: percentile(successfulLatencies, 0.5),
    successLatencyP95Ms: percentile(successfulLatencies, 0.95),
    successLatencyP99Ms: percentile(successfulLatencies, 0.99),
    samples,
  }
}
