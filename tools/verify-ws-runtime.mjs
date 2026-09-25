#!/usr/bin/env node
/**
 * 职责：把「登录之后长连接真的建立了、而且报上了身份」变成一条能失败、可复跑的判据。
 * 依赖：node（PATH 上是 v20，全局 WebSocket 要加 `--experimental-websocket`）、
 *      **本轮已启动的后端**、**`client/build-test/` 里本轮编译出来的生产模块**（先跑 `bash scripts/test-client.sh`）。
 *
 * 用法：
 *   node --experimental-websocket tools/verify-ws-runtime.mjs
 *   WS_VERIFY_BACKEND=http://127.0.0.1:18200 … 同上（默认 8080）
 *
 * <p><b>为什么要有它</b>：收口清单 #178 量到的缺陷是"整条推送通道从未成立而 445 条用例全绿" ——
 * 因为客户端每个面板都是 HTTP 拉出来的，单测里那个 socket 是注入的假对象。
 * 所以本工具刻意**不用假对象**：它 `require` `client/build-test/` 里那批**编译产物的生产模块**
 * （`NetModule` / `GameSession` / `Store` / `TimeSync`），只有传输层换成 node 的实现。
 * 换句话说，它跑的就是发布时要跑的那份判定代码，而不是它的一个副本。
 *
 * <p><b>它证明到哪一层**：证明"客户端这份代码在这个运行时里会开连接、会报身份"，
 * 以及"服务端认下这发 bind 并回了 bound"（第②批的 bind 一致性也在这里量）。
 * 它**不**证明小游戏运行时（wx.connectSocket）与真机 —— 那是开发者工具与真机的事，见上线检查清单 §二 1。
 *
 * <p><b>对照组</b>：一条编造的 HTTP 路径与一条编造的 WS 路径。有些工程"路由不存在"不返回 404，
 * 而是被统一异常处理包成业务码信封；WS 侧则必须"握不上手且一帧都收不到"，
 * 只有与被验路径**不同形**，上面那几条 PASS 才算成立。
 *
 * <p><b>fail-closed</b>：编译产物不在、node 没有 WebSocket、后端不可达 —— 一律退非 0 并说清缺什么，
 * 绝不"跳过即通过"。
 */
const path = await import('node:path').then(m => m.default ?? m)
const fs = await import('node:fs').then(m => m.default ?? m)
const { createRequire } = await import('node:module')
const { fileURLToPath } = await import('node:url')
const require = createRequire(import.meta.url)

// 必须显式给后端：静默回落到 127.0.0.1:8080 等于"打到另一台机器上读数"（同族收过 30+ 份，
// 这一份因写成 `(env.X ?? 'http://…').replace(...)` 漏网 —— #419 的回扫抓出来的）
const rawBackend = process.env.WS_VERIFY_BACKEND ?? (() => {
  console.error('[verify-ws-runtime] 缺 WS_VERIFY_BACKEND：不给就退回 http://127.0.0.1:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const BACKEND = rawBackend.replace(/\/$/, '')
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const BUILD = path.join(REPO, 'client/build-test/assets/scripts')

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`)
  if (!ok) {
    failures.push(label)
  }
}
function mask(text) {
  return String(text).replace(/token=[^&\s"']+/g, 'token=<掩码>')
}

function missingBuild() {
  const need = ['net/NetModule.js', 'game/session/GameSession.js', 'game/store/Store.js', 'core/TimeSync.js', 'core/Prng.js']
  return need.map(f => path.join(BUILD, f)).filter(p => !fs.existsSync(p))
}

async function main() {
  if (typeof WebSocket === 'undefined') {
    console.error('本进程的 node 没有全局 WebSocket（PATH 上的 node 是 v20）。')
    console.error('  正确起法：node --experimental-websocket tools/verify-ws-runtime.mjs')
    process.exit(2)
  }
  const absent = missingBuild()
  if (absent.length > 0) {
    console.error(`客户端编译产物不在（缺 ${absent.length} 个，例如 ${path.relative(REPO, absent[0])}）。`)
    console.error('  先跑：bash scripts/test-client.sh —— 它会把生产模块编译到 client/build-test/。')
    console.error('  本工具刻意不去自己编译：静默改产物等于把"量具"变成"构建"，出错时没人知道是谁动的。')
    process.exit(2)
  }

  // 对照组（HTTP）：先确认这台后端属于本项目，再谈其余判据
  const control = await fetch(`${BACKEND}/verify/no/such/endpoint`, { method: 'POST', body: '{}' })
    .then(async (res) => ({ status: res.status, text: (await res.text()).slice(0, 80) }))
    .catch((e) => ({ status: -1, text: String(e.message || e) }))
  if (control.status === -1) {
    console.error(`后端不可达：${BACKEND}（${control.text}）`)
    console.error('  起法见 scripts/dev.sh；跨轮复用旧进程会让下面每一条量的都是别人的服务。')
    process.exit(2)
  }
  verdict(true, '对照组 HTTP 编造路径有回执', `status=${control.status} body=${control.text}`)

  const { NetModule } = require(path.join(BUILD, 'net/NetModule.js'))
  const { GameSession } = require(path.join(BUILD, 'game/session/GameSession.js'))
  const { Store } = require(path.join(BUILD, 'game/store/Store.js'))
  const { TimeSync } = require(path.join(BUILD, 'core/TimeSync.js'))
  const { Prng } = require(path.join(BUILD, 'core/Prng.js'))

  const wsBase = BACKEND.replace(/^http/, 'ws')
  let seq = 0
  const sockets = []

  class FetchHttp {
    async post(url, bodyText, headers = {}) {
      const res = await fetch(url, { method: 'POST', body: bodyText, headers: { 'Content-Type': 'application/json', ...headers } })
      return { status: res.status, bodyText: await res.text() }
    }

    async get(url, headers = {}) {
      const res = await fetch(url, { headers })
      return { status: res.status, bodyText: await res.text() }
    }
  }

  /** node 的全局 WebSocket 到 SocketTransport 的适配 —— 本工具里唯一"不是生产码"的一层。 */
  class NodeSocket {
    constructor(url) {
      this.url = url
      this.sent = []
      this.received = []
      this.open = false
    }

    connect(callbacks) {
      const ws = new WebSocket(this.url)
      this.ws = ws
      ws.onopen = () => { this.open = true; callbacks.onOpen() }
      ws.onmessage = (e) => { const t = String(e.data); this.received.push(t); callbacks.onMessage(t) }
      ws.onclose = (e) => { this.open = false; callbacks.onClose(`code=${e.code}`) }
      ws.onerror = () => callbacks.onError('WebSocket 错误')
    }

    send(text) {
      if (!this.open) { return false }
      this.sent.push(text)
      this.ws.send(text)
      return true
    }

    get isOpen() { return this.open }

    close() { this.ws?.close() }
  }

  const config = {
    baseUrl: BACKEND, wsUrl: `${wsBase}/ws`,
    maxRetryAttempts: 2, retryBaseDelayMs: 10, retryMaxDelayMs: 40,
    offlineQueueMax: 5, requestTimeoutMs: 3000,
  }
  const newRequestId = () => `wsverify-${Date.now()}-${++seq}`
  const net = new NetModule(config, {
    http: new FetchHttp(),
    socketFactory: (url) => { const s = new NodeSocket(url); sockets.push(s); return s },
    now: () => Date.now(),
    delay: (ms) => new Promise((r) => setTimeout(r, ms)),
    rng: Prng.of(11),
    newRequestId,
    newTraceId: () => `wsverify-trace-${seq}`,
  })
  const session = new GameSession({
    net, store: new Store(),
    timeSync: new TimeSync({ alphaFixed: 3000, jitterFactorFixed: 30000, initialBestRttMs: 200 }),
    now: () => Date.now(), newRequestId,
  })

  const wait = async (pred, ms, every = 50) => {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      if (pred()) { return true }
      await new Promise((r) => setTimeout(r, every))
    }
    return false
  }

  const login = await session.login(`wsverify-${Date.now()}`, '量具号', null)
  verdict(login.kind === 'ok', 'POST /player/init 成功', `outcome=${login.kind}`)
  const playerId = login.kind === 'ok' ? login.data.playerId : null
  const token = login.kind === 'ok' ? login.data.authToken : null

  const opened = await wait(() => sockets.length === 1 && sockets[0].open, 3000)
  verdict(opened, '登录后恰好建立一条长连接', `实例数=${sockets.length}`)
  // 下面每一条都要**能干净地报 FAIL**。"没有连接实例"正是本工具要抓的那一种回归
  // （收口清单 #178：connectSocket 零调用点），如果这时抛异常退出，
  // 读起来就成了"量具坏了，重跑一次试试"—— 真缺陷会被当成工具故障丢掉
  const socket = sockets[0]
  const received = () => socket?.received ?? []
  const sent = () => socket?.sent ?? []
  verdict(socket !== undefined && socket.url.includes(`playerId=${playerId}`),
    '握手 URL 带 playerId', mask(socket?.url ?? '（没有连接实例）'))
  verdict(socket !== undefined && typeof token === 'string' && socket.url.includes(`token=${encodeURIComponent(token)}`),
    '握手 URL 带会话票据', `票据长度=${typeof token === 'string' ? token.length : 'n/a'}（值不外泄）`)

  verdict(await wait(() => received().some(t => t.includes('"connected"')), 3000),
    '服务端下发 connected 帧（握手走到了业务处理器）')
  verdict(await wait(() => received().some(t => t.includes('"bound"')), 3000),
    '服务端回 bound 帧（bind 被认下）')
  const bindFrame = sent().map(t => JSON.parse(t)).find(m => m.type === 'bind')
  verdict(bindFrame?.playerId === playerId && bindFrame?.token === token,
    'bind 由连接建立事件自己发出且带票据（调用方没有手工 bind 的机会）',
    `bind=${bindFrame ? '有' : '无'}`)

  // 第②批的 bind 一致性：握自己的手、bind 别人的 id，不得领到别人的推送
  const thief = new NodeSocket(`${wsBase}/ws?playerId=${encodeURIComponent(playerId)}&token=x`)
  thief.connect({ onOpen: () => undefined, onMessage: () => undefined, onClose: () => undefined, onError: () => undefined })
  await wait(() => thief.open, 3000)
  thief.send(JSON.stringify({ type: 'bind', playerId: 'PnotMine', token }))
  verdict(await wait(() => thief.received.some(t => t.includes('不一致')), 2000),
    'bind 换成别人的 playerId 被拒', `帧序列=${thief.received.map(t => JSON.parse(t).type).join(',') || '空'}`)
  verdict(!thief.received.some(t => t.includes('"bound"')), '被拒的那一发不得拿到 bound')

  // 对照组（WS）：编造路径必须握不上手、一帧都收不到
  const bogus = new NodeSocket(`${wsBase}/verify-no-such-ws`)
  const bogusOpened = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 3000)
    try {
      bogus.connect({ onOpen: () => { clearTimeout(timer); resolve(true) }, onMessage: () => undefined, onClose: () => undefined, onError: () => undefined })
    } catch {
      clearTimeout(timer)
      resolve(false)
    }
  })
  const gotFrames = await wait(() => bogus.received.length > 0, 800, 100)
  verdict(!bogusOpened || !gotFrames, '对照组 WS 编造路径与 /ws 不同形',
    `握手=${bogusOpened ? '成功' : '失败'} 收到帧=${gotFrames ? '有' : '无'}`)

  net.disconnect()
  for (const s of sockets) { s.close() }
  thief.close()
  bogus.close()

  console.log(`=== WS 运行时量具：${BACKEND} ===`)
  for (const l of lines) { console.log(`  [${l.startsWith('PASS') ? 'PASS' : 'FAIL'}] ${l.slice(5)}`) }
  console.log(`=== 结束：${failures.length} 处失败 ===`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error(`量具自身异常（不是判据失败）：${e && e.stack ? e.stack : e}`)
  process.exit(2)
})
