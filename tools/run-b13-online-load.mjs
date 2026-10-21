#!/usr/bin/env node
/**
 * B13-9 独立运行入口。只在 holder、资源和窗口经外部独立读回后由执行者调用。
 * 离线文件门只绑定输入，不能自己授予运行权；本脚本永不把票据写入报告。
 */
import { open, realpath } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import process from 'node:process'
import { readValidatedInputs, verifyCheckoutInputs } from './b13-online-admission.mjs'
import { closeFleet, connectFleet, startFleetHeartbeat } from './b13-online-ws.mjs'
import { runMarchLoad } from './b13-online-march.mjs'

const fail = (reason) => { throw new Error(reason) }
const inWindow = (admission, now) =>
  now >= Date.parse(admission.notBeforeUtc) && now < Date.parse(admission.notAfterUtc)

/** Replace our exclusive placeholder from byte zero; truncate old tail after a short report. */
export async function replaceReservedReport(handle, report) {
  const payload = Buffer.from(JSON.stringify(report, null, 2))
  for (let offset = 0; offset < payload.length;) {
    const { bytesWritten } = await handle.write(payload, offset, payload.length - offset, offset)
    if (bytesWritten <= 0) fail('报告写入未前进')
    offset += bytesWritten
  }
  await handle.truncate(payload.length)
}

/** All injection points are for isolated offline fixtures; the CLI fixes the canonical 5000/200. */
export async function executeB13Load({ manifest, admission, receipt,
  WebSocketCtor = globalThis.WebSocket, fetchImpl = globalThis.fetch,
  wallNow = () => Date.now(), monotonicNow, sleep, cancelled = () => false,
  connectionConcurrency = 50,
}) {
  if (manifest.players.length !== 5000 || admission.maxConnections < 5000
      || admission.maxMarchQps < 200 || manifest.targetOrigin !== admission.targetOrigin
      || receipt.sourceSha !== admission.sourceSha || !inWindow(admission, wallNow())) {
    fail('运行入口前置不满足')
  }
  const result = { status: 'RUNNING_NOT_ACCEPTED', runId: admission.runId,
    sourceSha: receipt.sourceSha, manifestSha256: receipt.manifestSha256,
    resourceProofSha256: receipt.resourceProofSha256,
    targetOrigin: receipt.targetOrigin, plannedConnections: 5000, plannedMarchQps: 200,
    durationSeconds: manifest.durationSeconds, startedUtc: new Date(wallNow()).toISOString(),
    authority: 'INPUT_BINDINGS_ONLY_EXTERNAL_HOLDER_READBACK_REQUIRED',
    failureStage: null, load: null, cleanup: null }
  let fleet = null
  let stopHeartbeat = () => {}
  try {
    result.failureStage = 'connect'
    const rampStart = monotonicNow ? monotonicNow() : Date.now()
    fleet = await connectFleet(manifest.players, { targetOrigin: receipt.targetOrigin,
      WebSocketCtor, concurrency: connectionConcurrency, expectedCount: 5000,
      shouldContinue: () => !cancelled() && inWindow(admission, wallNow()) })
    result.connectionRampMs = (monotonicNow ? monotonicNow() : Date.now()) - rampStart
    stopHeartbeat = startFleetHeartbeat(fleet)
    result.failureStage = 'march'
    result.load = await runMarchLoad(manifest.players, fleet, {
      targetOrigin: receipt.targetOrigin, runId: admission.runId,
      durationSeconds: manifest.durationSeconds, rate: 200,
      notAfterUtc: admission.notAfterUtc, fetchImpl, wallNow,
      ...(monotonicNow ? { monotonicNow } : {}), ...(sleep ? { sleep } : {}),
      shouldContinue: () => !cancelled(),
    })
    result.failureStage = null
  } catch {
    result.status = 'FAILED_BEFORE_COMPLETE_MEASUREMENT'
  } finally {
    stopHeartbeat()
    if (fleet !== null) {
      try { result.cleanup = await closeFleet(fleet) }
      catch { result.cleanup = { closed: null, total: fleet.opened.size, failed: true } }
    }
    result.endedUtc = new Date(wallNow()).toISOString()
  }
  if (result.load !== null) {
    const load = result.load
    const complete = load.stoppedReason === null && load.planned === load.launched
      && load.successful === load.planned && load.missedCapacity === 0
      && load.missedSchedule === 0 && load.minBound === 5000
      && load.unexpectedClosures === 0
      && load.launchedBySecond.every((count) => count === 200)
      && result.cleanup?.closed === result.cleanup?.total
      && result.cleanup?.total === 5000
    result.status = complete ? 'CANDIDATE_MEASURED_NOT_ACCEPTED'
      : 'MEASURED_TARGET_NOT_MET_OR_CLEANUP_INCOMPLETE'
  }
  return result
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const flags = Object.fromEntries(process.argv.slice(2).map((arg) => {
    const i = arg.indexOf('=')
    return i < 0 ? [arg, ''] : [arg.slice(0, i), arg.slice(i + 1)]
  }))
  let reserved = null
  try {
    if (flags['--mode'] !== 'run' || !flags['--manifest'] || !flags['--admission']
        || !flags['--proof'] || !flags['--output']) {
      fail('用法：--mode=run --manifest=仓库外文件 --admission=仓库外文件 --proof=仓库外文件 --output=仓库外目录')
    }
    if (typeof globalThis.WebSocket !== 'function') fail('此运行入口需要带原生 WebSocket 的 Node 24')
    const inputPaths = [flags['--manifest'], flags['--admission'], flags['--proof']]
    const { repoRoot, sourceSha } = await verifyCheckoutInputs(inputPaths)
    const outputDir = await realpath(flags['--output'])
    const relative = path.relative(repoRoot, outputDir)
    if (relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))) {
      fail('输出目录必须在仓库外')
    }
    const { receipt, manifest, admission } = await readValidatedInputs({
      manifestPath: flags['--manifest'], admissionPath: flags['--admission'],
      proofPath: flags['--proof'], sourceSha,
    })
    const reportPath = path.join(outputDir, `b13-online-${admission.runId}.json`)
    reserved = await open(reportPath, 'wx')
    await reserved.writeFile(JSON.stringify({ status: 'RUNNING_NO_RESULT_YET', runId: admission.runId,
      sourceSha, startedUtc: new Date().toISOString() }))
    let interrupted = false
    const onSignal = () => { interrupted = true }
    process.on('SIGINT', onSignal)
    process.on('SIGTERM', onSignal)
    let report
    try {
      report = await executeB13Load({ manifest, admission, receipt,
        cancelled: () => interrupted })
    } finally {
      process.off('SIGINT', onSignal)
      process.off('SIGTERM', onSignal)
    }
    try {
      const after = await verifyCheckoutInputs(inputPaths)
      report.checkoutChangedDuringRun = after.sourceSha !== sourceSha
    } catch { report.checkoutChangedDuringRun = true }
    if (report.checkoutChangedDuringRun) report.status = 'FAILED_SOURCE_CHANGED_DURING_RUN'
    await replaceReservedReport(reserved, report)
    console.log(JSON.stringify({ reportPath, status: report.status,
      launched: report.load?.launched ?? 0, successful: report.load?.successful ?? 0,
      minBound: report.load?.minBound ?? 0, cleanup: report.cleanup }))
    process.exitCode = report.status === 'CANDIDATE_MEASURED_NOT_ACCEPTED' ? 0 : 1
  } catch (error) {
    console.error(`[b13-online-load] ${error.message}`)
    process.exitCode = 2
  } finally {
    if (reserved !== null) await reserved.close()
  }
}
