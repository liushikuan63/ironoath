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
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript(() => {
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

await page.goto(`${preview.origin}/`, { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(1500)

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
// 第一次点击是"解锁音频"那一下：按设计它**不该**发声
await page.mouse.click(720, 500)
await page.waitForTimeout(400)
const afterFirst = await page.evaluate(() => globalThis.__bufferSources)
// 之后连点五次（间隔 > 节流窗口），应当真的排出声音
for (let i = 0; i < 5; i++) {
  await page.mouse.click(720, 500)
  await page.waitForTimeout(180)
}
const afterMore = await page.evaluate(() => globalThis.__bufferSources)
const pointerEvents = await page.evaluate(() => globalThis.__pointerEvents)

checks.taps = { beforeTaps, afterFirst, afterMore }
checks.pointerEvents = pointerEvents
console.log(`[verify-audio] 发声计数 ${beforeTaps} →(首点，设计上不响) ${afterFirst} →(再点五次) ${afterMore}`
  + `；期间 canvas 上的 pointer/touch 事件 = ${pointerEvents}`
  + '（为 0 ⇒ 点击没送达页面；>0 而发声 0 ⇒ 拦在 armed/muted/节流那一侧）')
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
