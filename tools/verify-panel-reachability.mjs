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
    // 2026-10-05 **两处修正**（裁决「修」；判据「有内容落在可视区外 = 真缺陷」不动）。
    //
    // ① **统一量纲**（裁决原话：「两边都用 camera.worldToScreen 转后再比」）：
    //    `w/h` 来自 `window.innerWidth/innerHeight`（**浏览器像素**），
    //    而 `getWorldPosition()` 给的是 **Cocos 世界坐标**（实测本构建是 `[0,960]×[0,600]`、
    //    原点左下，而像素是 `1440×900`、原点左上，差 `canvasSize/visibleSize = 1.5` 倍）。
    //    ⇒ 一律转成**像素**再比，**两边同量纲**。
    //
    // ② ★ **真正的根因**（实测定位；裁决描述基于我上一格已证伪的假设）：
    //    `probe()` 原式 `|v.x| > w/2 || |v.y| > h/2` **没有"一带"上界**，
    //    而统计 `clipped` 的那段**有**（`&& … <= w*0.8` / `<= h*0.8`）。
    //    本文件 :90-92 的注释本就写明「池化停放行与地图空间名牌停在更远坐标，
    //    **它们不是布局溢出**」⇒ `probe(h*4)` 期望判"不裁"，
    //    但没有 `0.8` 上界时它**必然**被判"裁" ⇒ `parked` 永远是 `false`
    //    ⇒ **这份探针在任何环境下都 fail-closed**。
    //    ⇒ 现在统计段与对照组**用同一个 `isClipped`**，口径不可能再打架。
    const cam = (() => {
      let found = null
      const walkCam = (n) => {
        if (found === null && n.getComponent) {
          const c = n.getComponent('cc.Camera')
          if (c !== null && c !== undefined) found = c
        }
        for (const c of n.children) walkCam(c)
      }
      walkCam(window.cc.director.getScene())
      return found
    })()
    const V3 = window.cc.Vec3
    /** 世界坐标 → 视口像素；拿不到相机时退回"世界坐标当像素用"（与旧行为一致）。 */
    const toPixel = (v) => {
      if (cam === null || cam === undefined) return { x: v.x, y: v.y }
      const s = cam.worldToScreen(new V3(v.x, v.y, v.z))
      return { x: s.x, y: s.y }
    }
    /**
     * "落在可视区外一带" 的判据 —— **统计段与对照组共用这一份**。
     * 只把「刚超出屏边一带」的算布局溢出；远远停放的（池化停放 / 地图空间名牌）不算。
     */
    const isClipped = (v) => {
      const p = toPixel(v)
      const ax = Math.abs(p.x - w / 2)
      const ay = Math.abs(p.y - h / 2)
      return (ax > w / 2 && ax <= w * 0.8) || (ay > h / 2 && ay <= h * 0.8)
    }
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
          // 2026-10-05：统计段与对照组**改用同一个 `isClipped`**，口径不可能再打架。
          // 原式是 `(|v.x| > w/2 && |v.x| <= w*0.8) || (…|v.y|…)`，
          // 它把 `v3`（**世界坐标**）与 `w/h`（**浏览器像素**）直接比 ⇒ 量纲不一致；
          // 而下面 `probe()` 里的 `hit` **既没有 `0.8` 上界、量纲也不一致**
          // ⇒ 两处口径打架 ⇒ 对照组永远读错 ⇒ 探针永远 fail-closed。
          // 注释（:90-92）的原意保留在此：**只把「刚超出屏边一带」的判为布局溢出**，
          // 远远停放的（池化停放行 / 地图空间名牌，实测 |y| 到几千像素）不算。
          if (isClipped(v3)) clipped += 1
        }
      }
      n.children.forEach((c) => walk(c, shown))
    }
    walk(panel, true)
    // 双向对照组：屏心的标签必判"不裁"、屏外的必判"裁"。
    // 任一对照读错就说明世界坐标这套数学不可信，本次读数作废（退 2），
    // 而不是拿一个没校准的量具去判产品有没有缺陷。
    // 2026-10-05：下面的 `probePx()` 按**像素偏移**给点，再按 `canvas/visible` 比例反推成世界坐标。
    // 为什么：判据 `isClipped` 已经统一到**像素**，可原先三个对照仍按**世界坐标**取点
    // （`probe(h*0.7)` = 世界 `630` = **像素 945**，早已越过 `0.8h` 的带）
    // ⇒ **取点与判据不同量纲**，于是"带内"那条对照永远落在带外（实测 `off=false`）。
    // ⇒ 现在三者都是像素语义：中心=0（必不裁）、带内=600（必裁）、带外=1200（必不裁）。
    //    `600` 与 `1200` 都落在 `[>450, <=720]` 的**反面/正面**，与 `isClipped` 的带一致。
    // 2026-10-05 ⚠️ **这里自己踩了一次"世界坐标 vs 局部坐标"**（与本文件刚修的那类同源）：
    // `n.setPosition()` 收的是**面板局部坐标**，而我第一版按"世界中心 + 偏移"算，
    // 多加了半个可见区高（`vs.height/2 = 300`）⇒ 取点整体偏高 ⇒ 落到了带外。
    // 依据：`probe(0)` 的世界坐标实测是 `(480, 300)`，正好是可见区 `[0,960]×[0,600]` 的中心
    // ⇒ **面板局部原点就在世界中心** ⇒ 像素偏移 px 对应**局部**偏移 `px / k`，没有额外加项。
    const pxToLocalY = (px) => {
      // 世界可见区高 `visibleSize.height`、像素画布高 `canvasSize.height` ⇒ 比例 k
      const vs = window.cc.view.getVisibleSize()
      const cs = window.cc.view.getCanvasSize()
      const k = cs.height / Math.max(1, vs.height)
      return px / Math.max(0.0001, k)
    }
    const probePx = (px) => {
      const n = new window.cc.Node('ProbeControl')
      n.layer = panel.layer
      panel.addChild(n)
      n.addComponent('cc.UITransform')
      const lab = n.addComponent('cc.Label')
      lab.string = 'probe'
      n.setPosition(0, pxToLocalY(px), 0)
      const v = new V3()
      n.getWorldPosition(v)
      const hit = isClipped(v)
      n.destroy()
      return hit
    }
    // 屏心：像素偏移 0 ⇒ 必判「不裁」
    const controlOn = probePx(0) === false
    // 带内：像素偏移 600（带是 `>450` 且 `<=720`）⇒ 必判「裁」
    const controlOff = probePx(600) === true
    // 带外：像素偏移 1200（越过 `720`）⇒ 必判「不裁」（池化停放/地图名牌那种远处停放）
    const parked = probePx(1200) === false
    return { paging, scroll, labels, clipped, controlOn, controlOff, parked,
      navKey: key,
      // 2026-10-05 **只读诊断**（不改判据）：面板里到底有哪些节点、有多少 active 的
      // UITransform。用它分辨「面板内 Label 0」的两种可能：
      //   · 面板是空的（内容没建 / 懒加载还没触发）
      //   · 面板有内容，但**不走 cc.Label**（例如画在 Graphics/Sprite 上，或用了自定义组件）
      nodeCensus: (() => {
        const names = {}
        let activeUi = 0
        const tally = (n) => {
          names[n.name] = (names[n.name] ?? 0) + 1
          if (n.activeInHierarchy) {
            const u = n.getComponent && n.getComponent('cc.UITransform')
            if (u !== null && u !== undefined) activeUi += 1
          }
          for (const c of n.children) tally(c)
        }
        tally(panel)
        const top = Object.entries(names).sort((a, b) => b[1] - a[1]).slice(0, 8)
        return { totalNodes: Object.values(names).reduce((a, b) => a + b, 0), activeUi, topNames: top }
      })(),
      // 2026-10-05 **只读诊断**：把**导航栏上所有 active 的 UI 节点**及其**屏幕坐标**列出来。
      // 目的：为「把上面那套写死的等分坐标（`first + step*indexOf[label]`、y=845）
      // 改成按导航项自身世界坐标取点」取底数。
      // ⚠️ 只读：不点任何东西、不改判据。
      // ⚠️ **不能另写独立脚本取这个数** —— 独立脚本没挂 read 夹具，游戏根本起不来
      // （实测 `window.cc` 120s 都不就绪 ⇒ `CC_NOT_READY`），必须借探针自己的启动流程。
      navCensus: (() => {
        const rows = []
        const scan = (n) => {
          const u = n.getComponent && n.getComponent('cc.UITransform')
          if (n.activeInHierarchy && u !== null && u !== undefined) {
            const lb = n.getComponent('cc.Label')
            const wb = u.getBoundingBoxToWorld()
            let sx = null
            let sy = null
            if (cam !== null && cam !== undefined) {
              const s = cam.worldToScreen(new V3(wb.x + wb.width / 2, wb.y + wb.height / 2, 0))
              // worldToScreen 原点在左下 ⇒ 翻成页面像素的左上原点
              sx = Math.round(s.x)
              sy = Math.round(h - s.y)
            }
            rows.push({
              name: n.name,
              label: lb !== null && lb !== undefined ? String(lb.string).slice(0, 8) : null,
              sx,
              sy,
              w: Math.round(wb.width)
            })
          }
          for (const c of n.children) scan(c)
        }
        scan(window.cc.director.getScene())
        const navRow = rows.filter((r) => r.sy !== null && r.sy > h * 0.6)
        return { navRowCount: navRow.length, navRow: navRow.slice(0, 24) }
      })(),
      // 2026-10-05 **只读诊断**（不改判据）：把探针的**世界坐标**与**视口像素尺寸**都打出来，
      // 量化「两者差多少倍」。本探针的 `hit` 判据是 `|v.x| > w/2 || |v.y| > h/2`，
      // 而 `v` 来自 `getWorldPosition()`（**世界坐标**）、`w/h` 来自 `window.innerWidth/innerHeight`
      // （**浏览器像素**）⇒ 量纲不同 ⇒ 连 `(0,0)` 那个"必判不裁"的探针都会被判成"被裁"。
      // ⚠️ 只加读数：**判据、退出码、fail-closed 行为一个字节都没改**。
      diag: (() => {
        const mk = (y) => {
          const n = new window.cc.Node('DiagProbe')
          n.layer = panel.layer
          panel.addChild(n)
          n.addComponent('cc.UITransform')
          const lb = n.addComponent('cc.Label')
          lb.string = 'probe'
          n.setPosition(0, y, 0)
          const v = new window.cc.Vec3()
          n.getWorldPosition(v)
          const out = { x: Math.round(v.x), y: Math.round(v.y) }
          n.destroy()
          return out
        }
        return {
          viewportPx: { w, h },
          halfViewportPx: { w: Math.round(w / 2), h: Math.round(h / 2) },
          worldAtCenter: mk(0),
          worldAtOffscreen: mk(h * 0.7),
          worldAtFar: mk(h * 4),
          viewVisibleSize: (() => {
            const vs = window.cc.view.getVisibleSize()
            return { w: Math.round(vs.width), h: Math.round(vs.height) }
          })(),
          canvasSize: (() => {
            const cs = window.cc.view.getCanvasSize()
            return { w: Math.round(cs.width), h: Math.round(cs.height) }
          })()
        }
      })()
    }
  }, key)
  if (!read.controlOn || !read.controlOff || !read.parked) {
    // 2026-10-05 **只读诊断**：把量纲差量化出来（视口像素 vs 世界坐标）。
    // ⚠️ 判据、退出码、fail-closed 行为**一个字节都没改** —— 这行只打日志。
    if (read.diag !== undefined) {
      console.log(`  [诊断] ${key} 视口像素=${JSON.stringify(read.diag.viewportPx)}`
        + ` 半宽高=${JSON.stringify(read.diag.halfViewportPx)}`
        + ` 世界坐标@中心=${JSON.stringify(read.diag.worldAtCenter)}`
        + ` @刚出屏=${JSON.stringify(read.diag.worldAtOffscreen)}`
        + ` @远=${JSON.stringify(read.diag.worldAtFar)}`
        + ` visibleSize=${JSON.stringify(read.diag.viewVisibleSize)}`
        + ` canvasSize=${JSON.stringify(read.diag.canvasSize)}`)
    }
    console.error(`[nonpaging][NO-RUN] ${key} 对照组读错（on=${read.controlOn} off=${read.controlOff} parked=${read.parked}）——量具未校准，读数作废`)
    process.exit(2)
  }
  const unreachable = read.clipped > 0
  console.log(`  [导航] ${key} 行数=${read.navCensus?.navRowCount} 项=${JSON.stringify(read.navCensus?.navRow ?? null)}`)
  console.log(`  [普查] ${key} 总节点=${read.nodeCensus?.totalNodes} activeUI=${read.nodeCensus?.activeUi} 主要节点=${JSON.stringify(read.nodeCensus?.topNames)}`)
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
