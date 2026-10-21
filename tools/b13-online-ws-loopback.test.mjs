import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import test from 'node:test'
import { closeFleet, connectFleet } from './b13-online-ws.mjs'

const frame = (value) => {
  const body = Buffer.from(JSON.stringify(value))
  assert.ok(body.length < 126, '夹具只使用短 WebSocket 文本帧')
  return Buffer.concat([Buffer.from([0x81, body.length]), body])
}

test('Node native WebSocket completes real loopback upgrade, bind and close', async () => {
  assert.equal(typeof globalThis.WebSocket, 'function', '这份集成夹具要用 Node 24 原生 WebSocket')
  const observed = []
  const server = createServer()
  server.on('upgrade', (request, socket) => {
    const key = request.headers['sec-websocket-key']
    const accept = createHash('sha1').update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
      .digest('base64')
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
      + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`)
    socket.write(frame({ type: 'connected', heartbeatSeconds: 10, serverNow: 1 }))
    let buffered = Buffer.alloc(0)
    socket.on('data', (chunk) => {
      buffered = Buffer.concat([buffered, chunk])
      while (buffered.length >= 6) {
        const opcode = buffered[0] & 15
        let length = buffered[1] & 127
        let offset = 2
        if (length === 126) {
          if (buffered.length < 8) return
          length = buffered.readUInt16BE(2)
          offset = 4
        }
        if (buffered.length < offset + 4 + length) return
        const mask = buffered.subarray(offset, offset + 4)
        const payload = buffered.subarray(offset + 4, offset + 4 + length)
        buffered = buffered.subarray(offset + 4 + length)
        if (opcode === 8) { socket.end(); return }
        if (opcode !== 1) continue
        const bytes = Buffer.from(payload.map((value, i) => value ^ mask[i % 4]))
        const message = JSON.parse(bytes.toString('utf8'))
        if (message.type === 'bind') {
          const query = new URL(request.url, 'http://127.0.0.1')
          assert.equal(query.searchParams.get('playerId'), message.playerId)
          assert.equal(query.searchParams.get('token'), message.token)
          observed.push(message.playerId)
          socket.write(frame({ type: 'bound', playerId: message.playerId, serverNow: 1 }))
        }
      }
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  let fleet
  try {
    const origin = `http://127.0.0.1:${server.address().port}`
    fleet = await connectFleet([{ playerId: 'fixture-a', token: 'fixture-token-a' },
      { playerId: 'fixture-b', token: 'fixture-token-b' }],
    { targetOrigin: origin, expectedCount: 2, concurrency: 2, timeoutMs: 5000 })
    assert.equal(fleet.state.bound, 2)
    assert.deepEqual(observed.sort(), ['fixture-a', 'fixture-b'])
  } finally {
    if (fleet) assert.deepEqual(await closeFleet(fleet), { closed: 2, total: 2 })
    await new Promise((resolve) => server.close(resolve))
  }
})
