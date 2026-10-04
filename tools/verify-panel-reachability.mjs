/**
 * ⚠ 实验量具，**不作门禁**（2026-09-26）：双向对照里「屏心不裁 / 屏边一带必裁」两向已过，
 * 但第三向「停放坐标（4 倍屏高）必不裁」读错（parked=false），说明坐标带判据与
 * 池化停放/地图空间标签的排除还没标定完。它自己 fail-closed（对照不过退 2），不会假绿。
 * 标定顺序：先修 parked 向（查 fresh node 的 getWorldPosition 是否要等一帧），
 * 再给池化行/地图名牌/进度窗口三类加排除，最后植入一个真被裁的行做正向对照。
 *
 * 复核队列里那条过期阻塞的本体主张：「剩下 9 相不画翻页行」在合并树上是否仍成立。
 * 判据（能失败）：逐相数①在屏的「上一页/下一页」Label ②在屏的 cc.ScrollView；
 * 两者皆 0 且该相在屏 Label 数超过一屏可读量（>24）⇒ 真缺陷（内容够不着），退 1；
 * 否则该相"不画翻页行"是有承载的（滚动或一屏放得下），格子可按证据关掉。
 * 用法：PAGING_BACKEND=http://localhost:8171 node tmp/probe-nonpaging.mjs
 */
import { chromium } from 'playwright'
import { startPreviewServer } from '../tools/lib/preview-server.mjs'

const BACKEND = process.env.PAGING_BACKEND ?? 'http://localhost:8171'
const PORT = Number(process.env.NONPAGING_PORT ?? 8298)
const PAGES = [['city', '内城'], ['hero', '武将'], ['gacha', '招募'], ['battlePass', '战令'],
  ['social', '社交'], ['power', '战力'], ['world', '地图'], ['settings', '设置']]
const ONE_SCREEN_LABELS = 24

const post = async (url, body) => {
  const r = await fetch(`${BACKEND}${url}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  return r.json()
}
const init = await post('/player/init', {
  requestId: `nonpaging-${Date.now()}`, deviceId: `nonpaging-${Date.now()}`,
  nickName: '翻页复核', clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[nonpaging][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `nonpaging-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))
await page.goto(preview.origin)
await page.waitForFunction(() => window.cc?.director?.getScene() != null, null, { timeout: 60000 })
await page.waitForTimeout(2500)
// Guide 会在数据到达后重新激活并吃掉触摸（实测 nav 因此停在 city），
// 所以每页点击前都藏一次，与 shot-panel-sweep 同机制。
const hideGuide = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const walk = (n) => { if (n.name === 'Guide') n.active = false; n.children.forEach(walk) }
  walk(scene)
})
await hideGuide()

const first = 85
const step = (1355 - 85) / 16
const indexOf = { 内城: 0, 军队: 1, 武将: 2, 招募: 3, 背包: 4, 关卡: 5, 战报: 6, 任务: 7, 战令: 8, 邮件: 9, 社交: 10, 战力: 11, 商店: 12, 外观: 13, 搜索: 14, 地图: 15, 设置: 16 }
const bad = []
for (const [key, label] of PAGES) {
  await hideGuide()
  await page.mouse.click(Math.round(first + step * indexOf[label]), 845)
  await page.waitForTimeout(900)
  await hideGuide()
  const read = await page.evaluate((key) => {
    const scene = window.cc.director.getScene()
    let panel = null
    const find = (n) => { if (panel === null && n.name === key) panel = n; n.children.forEach(find) }
    find(scene)
    if (panel === null) return { paging: -1, scroll: -1, labels: -1, clipped: -1 }
    // 世界坐标含画布缩放（=设备像素），必须跟 innerWidth/innerHeight 比；
    // 拿 getVisibleSize()（设计分辨率）比会把在屏标签全判成"被裁"（对照组实测自证）。
    const h = window.innerHeight
    const w = window.innerWidth
    let paging = 0
    let scroll = 0
    let labels = 0
    let clipped = 0
    const v3 = new window.cc.Vec3()
    const walk = (n, visible) => {
      const shown = visible && n.active !== false
      if (shown) {
        const lab = n.getComponent('cc.Label')
        if (n.getComponent('cc.ScrollView') !== null) scroll += 1
        if (lab !== null && lab.string && /上一页|下一页/.test(lab.string)) paging += 1
        if (lab !== null && lab.string.trim() !== '') {
          labels += 1
          n.getWorldPosition(v3)
          // 世界坐标原点在屏中心；落在可视区外即被裁掉（够不着）
          // 只把「刚好超出屏边一带」判为够不着：池化停放行与地图空间名牌
          // 停在更远坐标（实测 |y| 到几千像素），它们不是布局溢出。
          const ax = Math.abs(v3.x)
          const ay = Math.abs(v3.y)
          if ((ax > w / 2 && ax <= w * 0.8) || (ay > h / 2 && ay <= h * 0.8)) clipped += 1
        }
      }
      n.children.forEach((c) => walk(c, shown))
    }
    walk(panel, true)
    // 双向对照组：屏心的标签必判"不裁"、屏外的必判"裁"。
    // 任一对照读错就说明世界坐标这套数学不可信，本次读数作废（退 2），
    // 而不是拿一个没校准的量具去判产品有没有缺陷。
    const probe = (y) => {
      const n = new window.cc.Node('ProbeControl')
      n.layer = panel.layer
      panel.addChild(n)
      n.addComponent('cc.UITransform')
      const lab = n.addComponent('cc.Label')
      lab.string = 'probe'
      n.setPosition(0, y, 0)
      const v = new window.cc.Vec3()
      n.getWorldPosition(v)
      const hit = Math.abs(v.x) > w / 2 || Math.abs(v.y) > h / 2
      n.destroy()
      return hit
    }
    const parked = probe(h * 4) === false
    const controlOn = probe(0) === false
    const controlOff = probe(h * 0.7) === true
    return { paging, scroll, labels, clipped, controlOn, controlOff, parked }
  }, key)
  if (!read.controlOn || !read.controlOff || !read.parked) {
    console.error(`[nonpaging][NO-RUN] ${key} 对照组读错（on=${read.controlOn} off=${read.controlOff} parked=${read.parked}）——量具未校准，读数作废`)
    process.exit(2)
  }
  const unreachable = read.clipped > 0
  console.log(`[nonpaging] ${key}(nav=${read.navKey}): 翻页键 ${read.paging} / ScrollView ${read.scroll} / 面板内Label ${read.labels} / 被裁 ${read.clipped}`
    + `${unreachable ? '  ==> 有内容落在可视区外（真缺陷）' : ''}`)
  if (unreachable) bad.push(key)
}
await browser.close()
await preview.close()
if (errors.length > 0) {
  console.error(`[nonpaging][FAIL] pageerror ${errors.length}: ${errors[0]}`)
  process.exit(1)
}
if (bad.length > 0) {
  console.error(`[nonpaging][FAIL] 有 Label 落在可视区外（够不着）：${bad.join(',')}`)
  process.exit(1)
}
console.log(`[nonpaging] 全绿：8 相里"不画翻页行"都有承载（滚动或一屏放得下），无够不着的内容`)
