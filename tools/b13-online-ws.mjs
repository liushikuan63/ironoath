/**
 * B13-9 WebSocket 连接池。无命令行入口；实际负载入口须先核对 holder 与资源准入。
 * 只有收到与本账号一致的 bound 才计入在线数，HTTP 登录或 open 事件均不算在线。
 */
import { setTimeout as delay } from 'node:timers/promises'

const fail = (reason) => { throw new Error(reason) }

function socketUrl(origin, player) {
  const url = new URL('/ws', origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('playerId', player.playerId)
  url.searchParams.set('token', player.token)
  return url.toString()
}

function connectOne(player, origin, WebSocketCtor, timeoutMs, state, opened) {
  return new Promise((resolve, reject) => {
    let socket
    try { socket = new WebSocketCtor(socketUrl(origin, player)) } catch { reject(new Error('WebSocket 构造失败')); return }
    opened.add(socket)
    let settled = false
    let bound = false
    let heartbeatSeconds = null
    const timeout = setTimeout(() => stop('握手或 bind 超时'), timeoutMs)
    const stop = (reason) => {
      if (settled) {
        if (bound) try { socket.close() } catch { /* close 事件会计数 */ }
        return
      }
      settled = true
      clearTimeout(timeout)
      try { socket.close() } catch { /* 后续统一清理 */ }
      reject(new Error(reason))
    }
    socket.addEventListener('open', () => {
      try { socket.send(JSON.stringify({ type: 'bind', playerId: player.playerId, token: player.token })) }
      catch { stop('bind 发送失败') }
    })
    socket.addEventListener('message', (event) => {
      let message
      try { message = JSON.parse(String(event.data)) } catch { stop('WebSocket 回执不是 JSON'); return }
      if (message.type === 'error') { stop('WebSocket 身份或协议被服务端拒绝'); return }
      if (message.type === 'connected') {
        if (!Number.isFinite(message.heartbeatSeconds) || message.heartbeatSeconds <= 0) {
          stop('服务端未下发有效心跳间隔')
        } else heartbeatSeconds = message.heartbeatSeconds
      }
      if (message.type === 'bound') {
        if (message.playerId !== player.playerId || heartbeatSeconds === null) {
          stop('bound 身份与握手不一致或缺 connected 回执')
          return
        }
        if (settled) return
        settled = true
        bound = true
        clearTimeout(timeout)
        state.bound++
        state.peakBound = Math.max(state.peakBound, state.bound)
        resolve({ playerId: player.playerId, socket, heartbeatSeconds })
      }
    })
    socket.addEventListener('close', () => {
      if (bound) {
        bound = false
        state.bound--
        if (!state.closing) state.unexpectedClosures++
      }
      if (!settled) stop('bind 前连接关闭')
    })
    socket.addEventListener('error', () => stop('WebSocket 连接错误'))
  })
}

export async function closeFleet(fleet, timeoutMs = 5000) {
  fleet.state.closing = true
  for (const socket of fleet.opened) {
    try { socket.close() } catch { /* 继续关闭其它连接 */ }
  }
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline && [...fleet.opened].some((socket) => socket.readyState !== 3)) {
    await delay(20)
  }
  return { closed: [...fleet.opened].filter((socket) => socket.readyState === 3).length,
    total: fleet.opened.size }
}

export async function connectFleet(players, { targetOrigin, WebSocketCtor = globalThis.WebSocket,
  concurrency = 50, timeoutMs = 15_000, expectedCount = 5000,
  shouldContinue = () => true } = {}) {
  if (!Array.isArray(players) || players.length !== expectedCount || !WebSocketCtor
      || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 100
      || !Number.isInteger(timeoutMs) || timeoutMs < 1
      || typeof shouldContinue !== 'function') fail('WebSocket 连接池前置不满足')
  const fleet = { opened: new Set(), connections: [],
    state: { bound: 0, peakBound: 0, unexpectedClosures: 0, closing: false } }
  try {
    for (let offset = 0; offset < players.length; offset += concurrency) {
      if (!shouldContinue()) fail('准入窗口在连接爬升期间结束')
      const batch = players.slice(offset, offset + concurrency)
      const results = await Promise.allSettled(batch.map((player) => connectOne(player, targetOrigin,
        WebSocketCtor, timeoutMs, fleet.state, fleet.opened)))
      const rejected = results.find((result) => result.status === 'rejected')
      if (rejected) fail(`WebSocket 批次 ${Math.floor(offset / concurrency) + 1} 未全部 bind：${rejected.reason.message}`)
      fleet.connections.push(...results.map((result) => result.value))
      if (fleet.state.bound !== fleet.connections.length) fail('连接爬升期间已有已绑定会话断开')
    }
    if (fleet.state.bound !== expectedCount) fail('已绑定在线数没有达到目标')
    return fleet
  } catch (error) {
    await closeFleet(fleet)
    throw error
  }
}

export function startFleetHeartbeat(fleet) {
  const seconds = Math.min(...fleet.connections.map((connection) => connection.heartbeatSeconds))
  if (!Number.isFinite(seconds) || seconds <= 0) fail('没有服务端心跳间隔')
  const periodMs = Math.max(1000, Math.floor(seconds * 500))
  const tickMs = 100
  const ticksPerPeriod = Math.ceil(periodMs / tickMs)
  let tick = 0
  const timer = setInterval(() => {
    // 把 5000 条心跳摊到整段周期，避免同一瞬间再制造一波与行军无关的突发。
    const from = Math.floor(tick * fleet.connections.length / ticksPerPeriod)
    const to = Math.floor((tick + 1) * fleet.connections.length / ticksPerPeriod)
    for (let i = from; i < to; i++) {
      const { socket } = fleet.connections[i]
      if (socket.readyState !== 1) continue
      try { socket.send('{"type":"ping"}') } catch { /* close 事件会计入断连 */ }
    }
    tick = (tick + 1) % ticksPerPeriod
  }, tickMs)
  return () => clearInterval(timer)
}
