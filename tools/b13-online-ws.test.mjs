import assert from 'node:assert/strict'
import test from 'node:test'
import { closeFleet, connectFleet, startFleetHeartbeat } from './b13-online-ws.mjs'

const sockets = []
class FakeSocket {
  constructor(url) {
    this.url = new URL(url)
    this.readyState = 0
    this.listeners = new Map()
    sockets.push(this)
    queueMicrotask(() => {
      this.readyState = 1
      this.emit('open', {})
      this.emit('message', { data: JSON.stringify({ type: 'connected', heartbeatSeconds: 10 }) })
    })
  }
  addEventListener(type, listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  emit(type, event) { for (const listener of this.listeners.get(type) ?? []) listener(event) }
  send(raw) {
    const message = JSON.parse(raw)
    if (message.type !== 'bind') return
    const playerId = this.url.searchParams.get('playerId')
    const resultId = playerId === 'mismatch' ? 'other-player' : playerId
    queueMicrotask(() => this.emit('message', { data: JSON.stringify({ type: 'bound', playerId: resultId }) }))
  }
  close() {
    if (this.readyState === 3) return
    this.readyState = 3
    this.emit('close', {})
  }
}
const players = (ids) => ids.map((playerId) => ({ playerId, token: `fixture-${playerId}` }))

test('only matching bound receipts count online; all sockets close afterward', async () => {
  sockets.length = 0
  const fleet = await connectFleet(players(['p1', 'p2', 'p3']), {
    targetOrigin: 'http://127.0.0.1:8299', WebSocketCtor: FakeSocket,
    expectedCount: 3, concurrency: 2,
  })
  assert.equal(fleet.state.bound, 3)
  assert.equal(fleet.state.peakBound, 3)
  assert.equal(sockets[0].url.pathname, '/ws')
  assert.equal(sockets[0].url.searchParams.get('token'), 'fixture-p1')
  const stopHeartbeat = startFleetHeartbeat(fleet)
  stopHeartbeat()
  assert.deepEqual(await closeFleet(fleet), { closed: 3, total: 3 })
  assert.equal(fleet.state.bound, 0)
  assert.equal(fleet.state.unexpectedClosures, 0, '主动清理不得记成运行中断连')
})

test('a wrong bound identity fails the whole batch and cleans every opened socket', async () => {
  sockets.length = 0
  await assert.rejects(connectFleet(players(['p1', 'mismatch', 'p3']), {
    targetOrigin: 'http://127.0.0.1:8299', WebSocketCtor: FakeSocket,
    expectedCount: 3, concurrency: 3,
  }), /未全部 bind/)
  assert.equal(sockets.length, 3)
  assert.ok(sockets.every((socket) => socket.readyState === 3))
})

test('open without bound is never accepted as online', async () => {
  class NoBindSocket extends FakeSocket {
    send() { /* 保持 open，但不给 bound 回执 */ }
  }
  sockets.length = 0
  await assert.rejects(connectFleet(players(['p1']), {
    targetOrigin: 'http://127.0.0.1:8299', WebSocketCtor: NoBindSocket,
    expectedCount: 1, timeoutMs: 5,
  }), /未全部 bind.*超时/)
  assert.equal(sockets[0].readyState, 3)
})

test('a connection lost after bound reduces online count and records an unexpected closure', async () => {
  sockets.length = 0
  const fleet = await connectFleet(players(['p1', 'p2']), {
    targetOrigin: 'http://127.0.0.1:8299', WebSocketCtor: FakeSocket,
    expectedCount: 2,
  })
  sockets[0].close()
  assert.equal(fleet.state.bound, 1)
  assert.equal(fleet.state.unexpectedClosures, 1)
  await closeFleet(fleet)
  assert.equal(fleet.state.unexpectedClosures, 1)
})
