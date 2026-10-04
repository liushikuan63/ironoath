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
const preview = await startPreviewServer({ root: ROOT, backend: 'http://localhost:8080', port: PORT })

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

checks.taps = { beforeTaps, afterFirst, afterMore }
checks.audioWarnings = audioWarnings
checks.errors = errors

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
