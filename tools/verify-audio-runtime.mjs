/**
 * 职责：音效层的**运行时**验收 —— 产物里真的有这四张 clip、装上了监听、
 *        并且"第二次点击"确实走到了引擎的发声调用。
 * 依赖：playwright / tools/lib/preview-server.mjs / client/build/web-mobile 产物。
 *
 * <p>为什么单测撑不住这一条：`Sfx.test.ts` 量的是纯判定（该不该放、取哪张、静音读写），
 * 而"音频模块被裁掉了""clip 路径写错""AudioSource 挂在未激活节点上"这三种故障
 * 全都只发生在真产物里 —— 前两种尤其阴：一声不出，但没有任何报错。
 *
 * <p><b>判据为什么是 createBufferSource 的次数而不是"听"</b>：无头浏览器没有声卡，
 * 但 Cocos 的 web 音频后端在真正发声前必须向 AudioContext 要一个 BufferSource。
 * 所以要不到 = 没发声，要到了 = 引擎确实把这段音频排给了平台。这是这里能拿到的
 * **最接近"真的响了"**的可失败证据；再往下（扬声器里有没有声音）本工具不声称验过。
 *
 * 退出码：0 全绿；1 判据失败（逐条打出来）；2 前置不满足（没产物 / 起不了服务）。
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
if (!existsSync(path.join(ROOT, 'index.html'))) {
  console.error('[verify-audio][前置] 没有 ' + ROOT + '/index.html —— '
    + '先跑：CocosCreator.exe --project client --build "platform=web-mobile;debug=false;startScene=<Boot uuid>" --force')
  process.exit(2)
}

const PORT = Number(process.env.AUDIO_VERIFY_PORT ?? 8191)
// 2026-10-04：此处原先**写死** `backend: 'http://localhost:8080'`，等于告诉预览服务"不重写"。
// 而产物里的地址本就是打 8080 的（`preview-server.mjs:24` 的 BAKED_HTTP）⇒ **页面直连 8080**，
// 那儿什么都没有 ⇒ `/player/init` 连不上 ⇒ 页面拿不到玩家 ⇒ 连点无效、`taps` 停在 0，
// 于是「连点五次一次都没发声」与「页面报错 4 条」两条判据一起红 —— **与音频毫无关系**。
// 上一轮我只拿到 `ERR_CONNECTION_REFUSED`（没有 URL）判不出来；补上 `requestfailed` 读数后
// 立刻看到是 `POST http://localhost:8080/ops/app/version` 与 `/player/init` —— 一条一条对上。
// 现在传真实后端，由预览服务做重写（换不到时它会抛错，见 preview-server.mjs:120）。
const AUDIO_BACKEND = process.env.AUDIO_BACKEND ?? process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const preview = await startPreviewServer({ root: ROOT, backend: AUDIO_BACKEND, port: PORT })
console.log(`[verify-audio] 预览 http://localhost:${PORT}，页面后端指向 ${AUDIO_BACKEND}`)

const browser = await chromium.launch({ headless: true })
// 2026-10-04：输入通路做成**可切换**，默认走触摸。
// 起因：`armed` 恒为 false，而本会话 `verify-city-phone` 早已实测「触摸上下文能点到格子、鼠标点不到」
// ⇒ 嫌疑是**上下文 hasTouch 的差异**，不是 AudioService 选错事件名
//（上一格补 `MOUSE_DOWN` 实测无效已证伪那个推断）。
// `AUDIO_HAS_TOUCH=0` 可切回鼠标，两条通路的读数放一起比。
const HAS_TOUCH = process.env.AUDIO_HAS_TOUCH !== '0'
// 2026-10-04：点击点必须落在**已知有节点的 UI 上**（底部导航栏的「内城」）。
// 原先固定 (720,500) —— 那多半是空处，节点级 touch 自然 0，量出来的对照是无效的。
const TAP_X = Number(process.env.AUDIO_TAP_X ?? 125)
const TAP_Y = Number(process.env.AUDIO_TAP_Y ?? 857)
// 2026-10-04：与 verify-city-multi-types **逐字一致** —— 它是 newContext({viewport})，
// **根本不传 hasTouch 这个键**；而这里一直显式传（哪怕值是 false）。
// Chromium 里「显式 hasTouch:false」与「不传」不是同一回事（前者会走去碰点仿真开关），
// 这条差异此前**从没试过**。⇒ 鼠标模式下**整个省略**该键。
const context = HAS_TOUCH
  ? await browser.newContext({ viewport: { width: 1440, height: 900 }, hasTouch: true })
  : await browser.newContext({ viewport: { width: 1440, height: 900 } })
console.log(`[verify-audio] 输入通路：${HAS_TOUCH ? 'touchscreen.tap（hasTouch=true）' : 'page.mouse.click（hasTouch=false）'}`)
/** 按当前通路打一发点击。两种都用同一坐标，避免"点在哪"成为变量。 */
const tapAt = async (x, y) => {
  if (HAS_TOUCH) {
    await page.touchscreen.tap(x, y)
  } else {
    await page.mouse.click(x, y)
  }
}

/**
 * 2026-10-04：**这才是 `armed` 恒 false 的真根因** —— 引导层 `GuideView` 压在画面上把点击全吃了。
 *
 * <p>本探针此前**从不摘引导层**（实测命中数 0），而两份额内的内城探针都在每次点击前摘
 * （`verify-city-multi-types.mjs` 原话：「它压在画面正中央，别的内城探针也都这么摘」）。
 * 实测症状：170 个节点都挂了 `touch-start` 监听、点的是**已知有节点的**底部导航按钮，
 * 节点级与全局 `input.on` **双双 0 次** ⇒ 不是"选错事件名"，是**点击压根没进 Cocos**。
 *
 * <p>⚠️ 这条同时作废我此前三条"被证伪"的解释（换事件名 / 桌面鼠标不映射 / hasTouch 差异）——
 * 它们解释的是**症状**不是**原因**：点击被引导层吃掉时，任何事件名、任何上下文都一样不会响。
 */
const hideGuideAndPopup = async () => {
  await page.evaluate(() => {
    const scene = window.cc?.director?.getScene?.()
    if (scene === null || scene === undefined) return
    const kill = (n) => {
      if (/Guide|Gift|Popup|Modal/i.test(n.name)) {
        n.removeFromParent()
        return
      }
      for (const c of n.children) kill(c)
    }
    kill(scene)
  })
  await page.waitForTimeout(200)
}
if (process.env.AUDIO_NO_PATCH !== '1') await context.addInitScript(() => { // 2026-10-04：这个 initScript 是本探针与内城探针最后一处差异，用 AUDIO_NO_PATCH=1 可关掉对照
  localStorage.setItem('ironoath.deviceId', `audio-verify-${Date.now()}`)
  // 在页面脚本之前包住 BufferSource 的创建：Cocos 的 web 音频后端每次真正发声都要要一个
  globalThis.__bufferSources = 0
  for (const name of ['AudioContext', 'webkitAudioContext']) {
    const ctor = globalThis[name]
    const proto = ctor && ctor.prototype
    if (!proto || typeof proto.createBufferSource !== 'function' || proto.__sfxPatched) {
      continue
    }
    proto.__sfxPatched = true
    const inner = proto.createBufferSource
    proto.createBufferSource = function patched() {
      globalThis.__bufferSources += 1
      return inner.apply(this, arguments)
    }
  }
})
const page = await context.newPage()
const errors = []
const audioWarnings = []
// 2026-10-04 诊断读数（先有读数再下结论）：此前只记 `m.text()`，拿到的是
// 「Failed to load resource: net::ERR_CONNECTION_REFUSED」——**没有 URL**，
// 于是无法分辨"是哪个资源、连的是谁"。这里把失败请求的**方法 / URL / 失败原因**逐条记下来，
// 下一格判读就有据可依。
const failedRequests = []
page.on('requestfailed', (r) => {
  failedRequests.push(`${r.method()} ${r.url()} :: ${r.failure()?.errorText ?? '?'}`)
})
page.on('pageerror', (e) => errors.push(e.message))
page.on('console', (m) => {
  if (m.type() === 'error') {
    errors.push(m.text())
  }
  if (m.text().includes('[audio]')) {
    audioWarnings.push(m.text())
  }
})

// 2026-10-04：**照抄内城探针的进入方式**。
// 原先走裸 `/`、只等场景非空 ⇒ 页面多半停在一个面板都还没就绪的状态，
// 于是 `calls:0` / `nodeTouch:0` 是**必然**的，量出来的东西不能用来判产品。
// 内城探针（`verify-city-multi-types.mjs:119-129`）做的是三件事，本探针原先一件都没做：
//   ① `?panel=city` 直接进内城面板；② 等 `PanelNav.currentKey === 'city'`；
//   ③ 再多等 2000ms。
const audioUrl = new URL(`${preview.origin}/`)
audioUrl.searchParams.set('panel', 'city')
await page.goto(audioUrl.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
const panelReady = await page.waitForFunction(() => {
  const scene = window.cc?.director?.getScene?.()
  if (scene === null || scene === undefined) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'city'
}, null, { timeout: 25_000 }).then(() => true).catch(() => false)
console.log(`[verify-audio] 进入方式 ?panel=city，PanelNav.currentKey==='city' 达成 = ${panelReady}`)
await page.waitForTimeout(2000)

const checks = {
  serviceNode: await page.evaluate(() => {
    const find = (node, name) => {
      if (node.name === name) {
        return node
      }
      for (const child of node.children) {
        const hit = find(child, name)
        if (hit !== null) {
          return hit
        }
      }
      return null
    }
    const hit = find(window.cc.director.getScene(), 'AudioService')
    if (hit === null) {
      return { found: false, hasSource: false }
    }
    return { found: true, hasSource: hit.getComponent('cc.AudioSource') !== null }
  }),
  // 四张 clip 是否真的从分包里加载进来了（没加载 = 路径错或产物里没有）
  clips: await page.evaluate(() => {
    const wanted = ['audio/ui-tap', 'audio/ui-tap-alt', 'audio/ui-switch', 'audio/ui-click-alt']
    const out = {}
    for (const key of wanted) {
      out[key] = window.cc.resources.get(key, window.cc.AudioClip) !== null
    }
    return out
  }),
}

const beforeTaps = await page.evaluate(() => globalThis.__bufferSources)
// 2026-10-04 诊断读数：此前只知道"没发声"，分不清「点击根本没到页面」与「到了但被 armed/muted 拦下」。
// 在 canvas 上数最底层的 pointer 事件 —— 它在 Cocos 的触摸分发**之前**，
// 于是点数为 0 ⇒ Playwright 的点击没送达；点数 > 0 而发声数为 0 ⇒ 拦在 armed/muted/节流那一侧。
// ⚠️ 另：AudioService.ts:139-146 明写「第一次触摸只解锁、不配音效」（浏览器与微信禁止交互前播音频）
// ⇒ **第一次点击发声数为 0 是符合设计的**，判据必须从第二次点起算。
await page.evaluate(() => {
  globalThis.__pointerEvents = 0
  const c = document.querySelector('canvas')
  if (c !== null) {
    for (const type of ['pointerdown', 'pointerup', 'touchstart']) {
      c.addEventListener(type, () => { globalThis.__pointerEvents += 1 }, true)
    }
  }
})
/**
 * 2026-10-04：等游戏**真的进入可交互态**再点。
 *
 * <p>为什么：本探针此前只用 `waitUntil:'networkidle'` 就开始点，而内城探针都会等具体 UI 出现。
 * 若点在"引擎还没跑起来"的时候，`calls:0` 与 `armed:false` 都是**必然**的，
 * 量出来的东西不能用来判产品（`NO-RUN` ≠ 红，同一条纪律）。
 *
 * <p>判据用**引擎帧数推进**（`cc.director.getTotalFrames()`）而不是猜某个节点：
 * 帧在走 = 引擎活着；再配 AudioService 节点已存在 = `installAudio` 跑完。
 * ⚠️ 注意 `hasTouch=false` 时 `getTotalFrames` 仍会推进（rAF 不依赖触屏）。
 */
const totalFrames = () => page.evaluate(() => {
  const d = window.cc?.director
  return typeof d?.getTotalFrames === 'function' ? d.getTotalFrames() : null
})
const waitForGameRunning = async (timeoutMs = 20000) => {
  const t0 = Date.now()
  let last = -1
  while (Date.now() - t0 < timeoutMs) {
    const [frames, hasSvc] = await page.evaluate(() => {
      const d = window.cc?.director
      const f = typeof d?.getTotalFrames === 'function' ? d.getTotalFrames() : -1
      let svc = false
      const scene = d?.getScene?.()
      const walk = (n) => { if (n.name === 'AudioService') svc = true; for (const c of n.children) walk(c) }
      if (scene) walk(scene)
      return [f, svc]
    })
    if (frames > last && last >= 0 && frames > 20 && hasSvc) return { ready: true, frames, waitedMs: Date.now() - t0 }
    last = frames
    await page.waitForTimeout(250)
  }
  return { ready: false, frames: last, waitedMs: Date.now() - t0 }
}

/**
 * 2026-10-04：按**节点中心**点，而不是按固定坐标。
 *
 * <p>为什么：城市探针点的是"某节点算出来的屏幕坐标"，而本探针一直点固定坐标
 * （原 (720,500)，后改 (125,857)）。即使 170 个节点都挂了 `touch-start` 监听，
 * **落点不在任何节点矩形内时节点级自然 0** —— 那时读数是"量具没对准"，不是"输入不通"。
 *
 * <p>所以这里照抄城市探针的换算（`camera.worldToScreen` + canvas 矩形 + y 翻转），
 * 取一个**真实按钮**的中心来点，让"点在哪"不再是变量。
 */
const clickNodeCenter = async () => {
  const info = await page.evaluate(() => {
    const cc = window.cc
    const scene = cc.director.getScene()
    const camera = scene.getComponentInChildren('cc.Camera')
    const rect = document.querySelector('canvas').getBoundingClientRect()
    const pixel = cc.view.getVisibleSizeInPixel()
    const cands = []
    const visit = (n) => {
      if (n.activeInHierarchy !== true) return
      const ui = n.getComponent('cc.UITransform')
      if (ui !== null && ui !== undefined && /Button$/.test(n.name)) {
        const s = camera.worldToScreen(ui.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
        cands.push({
          name: n.name, w: ui.width, h: ui.height,
          x: rect.left + (s.x / pixel.width) * rect.width,
          y: rect.top + rect.height - (s.y / pixel.height) * rect.height,
        })
      }
      for (const c of n.children) visit(c)
    }
    visit(scene)
    const inCanvas = cands.filter((c) => c.x > rect.left && c.x < rect.left + rect.width
      && c.y > rect.top && c.y < rect.top + rect.height)
    return { total: cands.length, inCanvas: inCanvas.length, pick: inCanvas[0] ?? null }
  })
  if (info.pick === null) {
    return { ok: false, why: `没找到画布内的按钮（Button 节点 ${info.total} 个，界内 ${info.inCanvas} 个）` }
  }
  if (HAS_TOUCH) {
    await page.touchscreen.tap(info.pick.x, info.pick.y)
  } else {
    await page.mouse.click(info.pick.x, info.pick.y)
  }
  return { ok: true, picked: info.pick }
}

// 第一次点击是"解锁音频"那一下：按设计它**不该**发声
const ready = await waitForGameRunning()
console.log(`[verify-audio] 等引擎跑起来：ready=${ready.ready} 帧数=${ready.frames} 等了 ${ready.waitedMs}ms`)
const framesBeforeTaps = await totalFrames()
// 2026-10-04 解卡点：**同文件 A/B**。最小探针 `tmp/probe-click-sanity.mjs` 怎么加都还是
// nodeTouch=30，而本探针恒为 0 ⇒ 差异在**本探针特有**的代码里。
// `AUDIO_SANITY_CLICK=1` 时跳过本探针自己的 `clickTile` 全套，改跑一段与最小探针**逐字相同**的点击
// （算一个 `/Button$/` 的中心 → `mouse.click` → 读同一个计数器）。
// ⇒ 若此时 `nodeTouch` 变正，差异就钉死在 `clickTile`（焦点 / 视口判定 / 缩放兜底那一套）。
const SANITY_CLICK = process.env.AUDIO_SANITY_CLICK === '1'
if (SANITY_CLICK) {
  const t = await page.evaluate(() => {
    const cc = window.cc
    const scene = cc.director.getScene()
    const camera = scene.getComponentInChildren('cc.Camera')
    const rect = document.querySelector('canvas').getBoundingClientRect()
    const pixel = cc.view.getVisibleSizeInPixel()
    let pick = null
    const walk = (x) => {
      if (pick === null && x.activeInHierarchy === true && /Button$/.test(x.name)) {
        const ui = x.getComponent('cc.UITransform')
        const s = camera.worldToScreen(ui.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
        const px = rect.left + (s.x / pixel.width) * rect.width
        const py = rect.top + rect.height - (s.y / pixel.height) * rect.height
        if (px > rect.left && px < rect.left + rect.width && py > rect.top && py < rect.top + rect.height) {
          pick = { name: x.name, x: Math.round(px), y: Math.round(py) }
        }
      }
      for (const c of x.children) walk(c)
    }
    walk(scene)
    return pick
  })
  console.log(`[verify-audio] A/B sanity 式点击目标=${JSON.stringify(t)}`)
  if (t !== null) {
    await page.mouse.click(t.x, t.y)
    await page.waitForTimeout(700)
  }
} else {
  await hideGuideAndPopup()
  await clickNodeCenter()
  console.log(`[verify-audio] 首点目标=${JSON.stringify(await clickNodeCenter())}`)
}
await page.waitForTimeout(400)
const afterFirst = await page.evaluate(() => globalThis.__bufferSources)
// 之后连点五次（间隔 > 节流窗口），应当真的排出声音
for (let i = 0; i < 5; i++) {
  if (process.env.AUDIO_NO_HIDE !== '1') await hideGuideAndPopup() // 2026-10-04：这行 removeFromParent 本身可能拆掉 UI 树，用 AUDIO_NO_HIDE=1 可单独关掉对照
  await clickNodeCenter() // 同上：每一发都点**真实节点中心**，让"点在哪"不再是变量
  await page.waitForTimeout(180)
}
// 2026-10-04：桌面 Web 上「音效永不解锁」的对照实验。
// 嫌疑：`AudioService.bindGlobalTouch` 挂在 `input.on(Input.EventType.TOUCH_START)` 上，
// 而 `Input.EventType.TOUCH_START` 对应 DOM 的 `touchstart` —— 桌面无触屏时鼠标点它不响。
// **但节点级触摸是能被鼠标点出来的**（本会话 city-multi-types / city-phone 都靠鼠标点中了格子），
// 而两者走的不是同一条路 ⇒ 不能靠"节点能点"反推"全局 input.on 会响"。
// 这里同页挂**两个计数器**，同一发 `page.mouse.click` 打过去：
//   节点级 node.on('touch-start') 有数 + 全局 input.on('touch-start') 无数 ⇒ 坐实。
const touchPaths = await page.evaluate(() => {
  const out = { nodeTouch: 0, globalTouch: 0, hasCcInput: false, hasCcNode: false, note: '' }
  const scene = window.cc?.director?.getScene?.()
  if (scene === null || scene === undefined) { out.note = 'no-scene'; return out }
  let host = null
  const find = (n) => {
    if (host === null && n.getComponent != null) host = n
    for (const c of n.children) find(c)
  }
  find(scene)
  if (host !== null && typeof host.on === 'function') {
    out.hasCcNode = true
    // 2026-10-04 修正：原先只挂在 DFS 到的**第一个**节点（多半是根/空节点，pointer 落不到它身上）
    // ⇒ `nodeTouch:0` 是个**无效对照**。现在挂到**每个**带 UITransform 且 active 的节点上，
    // 这样"节点级通不通"才是真的被量到。
    out.nodeTouch = 0
    out.nodeBound = 0
    const bindAll = (n) => {
      const ui = n.getComponent && n.getComponent('cc.UITransform')
      if (ui !== null && ui !== undefined && n.activeInHierarchy === true && typeof n.on === 'function') {
        out.nodeBound += 1
        n.on('touch-start', () => { out.nodeTouch += 1 })
      }
      for (const c of n.children) bindAll(c)
    }
    bindAll(scene)
  }
  // Cocos 的 input 模块是否挂在 window.cc 上（不同版本挂法不同，挂不上就如实记下来）
  const inp = window.cc?.input
  if (inp != null && typeof inp.on === 'function') {
    out.hasCcInput = true
    inp.on('touch-start', () => { out.globalTouch += 1 })
  } else {
    out.note = out.note === '' ? 'window.cc.input 不可直接取' : out.note
  }
  globalThis.__touchPaths = out
  return out
})
await page.waitForTimeout(300)

const afterMore = await page.evaluate(() => globalThis.__bufferSources)
const pointerEvents = await page.evaluate(() => globalThis.__pointerEvents)
// 2026-10-04：读 AudioService 自己那份**只读**诊断快照（AudioService.ts 里 `audioDiagnostics()`，
// 由 installAudio 挂到 globalThis.__ironoathAudioDiagnostics）。
// 它把「连点五次一次都没发声」剩下的三个候选一次分开：
// armed=false（首次手势之前）／ muted=true ／ 服务侧 clips Map 未就绪（loadingKeys 非空、clipKeys 缺键）。
const audioDiag = await page.evaluate(() => {
  const fn = globalThis.__ironoathAudioDiagnostics
  return typeof fn === 'function' ? fn() : { missing: true }
})
// 2026-10-04：读 `input.on` 监听自身的注册/派发读数。
// `calls` 是那一列：0 ⇒ 监听从没被派发（注册时机/输入实例问题）；>0 ⇒ 监听活着、问题在 handler 内。
const bindDiag = await page.evaluate(() => {
  const fn = globalThis.__ironoathAudioBind
  return typeof fn === 'function' ? fn() : { missing: true }
})
const touchRead = await page.evaluate(() => globalThis.__touchPaths)
// 2026-10-04 解卡点续：A/B 已证明**页面与点击完全相同**（目标同为 CollectAllButton@1260,72、
// nodeBound 同为 159），但本探针 nodeTouch=0、最小探针=5。
// 「帧在推进（实测 94→254）但输入不派发」正是 **director/game 被 pause** 的特征 ⇒ 直接读它。
const pauseState = await page.evaluate(() => {
  const d = window.cc?.director
  const g = window.cc?.game
  return {
    directorPaused: d?.isPaused ?? null,
    gamePaused: g?.isPaused ?? null,
    frameRate: g?.frameRate ?? null,
    totalFrames: typeof d?.getTotalFrames === 'function' ? d.getTotalFrames() : null,
  }
})
console.log(`[verify-audio] pause 读数：${JSON.stringify(pauseState)}`)
const framesAfterTaps = await totalFrames()

checks.taps = { beforeTaps, afterFirst, afterMore }
checks.pointerEvents = pointerEvents
checks.audioDiagnostics = audioDiag
checks.audioBind = bindDiag
checks.touchPaths = touchRead
checks.engineFrames = { beforeTaps: framesBeforeTaps, afterTaps: framesAfterTaps }
console.log(`[verify-audio] 发声计数 ${beforeTaps} →(首点，设计上不响) ${afterFirst} →(再点五次) ${afterMore}`
  + `；期间 canvas 上的 pointer/touch 事件 = ${pointerEvents}`
  + '（为 0 ⇒ 点击没送达页面；>0 而发声 0 ⇒ 拦在 armed/muted/节流那一侧）')
console.log(`[verify-audio] AudioService 只读诊断：${JSON.stringify(audioDiag)}`)
console.log(`[verify-audio] 全局 input.on 监听读数：${JSON.stringify(bindDiag)}`
  + ' —— calls=0 ⇒ 监听从没被派发（注册时机/输入实例）；calls>0 ⇒ 监听活着、问题在 handler 内')
console.log(`[verify-audio] 触摸通路对照：${JSON.stringify(touchRead)}`
  + ' —— 节点级 touch-start 有数 + 全局 input.on(touch-start) 无数 ⇒ 桌面鼠标不解锁音效')
checks.audioWarnings = audioWarnings
checks.errors = errors
// 把失败请求也打出来：ERR_CONNECTION_REFUSED 没有 URL 时无法判读，有 URL 就能
checks.failedRequests = failedRequests
for (const line of failedRequests.slice(0, 6)) {
  console.log(`[verify-audio][请求失败] ${line}`)
}

const failures = []
if (!checks.serviceNode.found) {
  failures.push('场景里没有 AudioService 节点 ⇒ installAudio 没被调到')
}
if (checks.serviceNode.found && !checks.serviceNode.hasSource) {
  failures.push('AudioService 节点上没有 cc.AudioSource ⇒ playOneShot 一声不出且不报错')
}
for (const [clip, loaded] of Object.entries(checks.clips)) {
  if (!loaded) {
    failures.push(`clip 没加载进来：${clip}（分包里没有这张，或路径写错）`)
  }
}
if (audioWarnings.length > 0) {
  failures.push(`音频层自己告警了 ${audioWarnings.length} 条：${audioWarnings[0]}`)
}
if (afterFirst !== beforeTaps) {
  failures.push(`第一次点击就发声了（${beforeTaps} → ${afterFirst}）：解锁那一下不该响`)
}
if (afterMore <= afterFirst) {
  failures.push(`连点五次一次都没发声（停在 ${afterMore}）：节流判错、或静音状态被写进了本机`)
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}

console.log(JSON.stringify(checks, null, 2))
if (failures.length > 0) {
  for (const f of failures) {
    console.log(`[verify-audio][FAIL] ${f}`)
  }
  console.log(`[verify-audio] 共 ${failures.length} 条判据失败`)
  await browser.close()
  await preview.close()
  process.exit(1)
}
console.log(`[verify-audio] OK：四张 clip 全部加载，连点排出 ${afterMore - afterFirst} 次发声调用`)
await browser.close()
await preview.close()
