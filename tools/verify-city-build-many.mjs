/**
 * 职责：**多建筑叠加**的运行期验收 —— 走真实建造流程建一栋，看"未建不画、建了才叠正稿、主城不叠图"是否成立。
 * 依赖：`client/build/web-mobile` 产物 + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么必须专门验：方案 A 的核心是"未建不画、建了才叠正稿"，而主城恰恰是**不叠图**的那一个
 * （底图上已经画着城堡），所以主城跑通并不证明其余 14 类也对 —— 这一步才是真正的验收。
 *
 * <p>只建不拆、不加速：建造会消耗本地 dev 后端的资源（内存存储，重启即清）。
 *
 * <p><b>建的是"选择器第 0 行"那栋（今天 = 伐木场），第二栋走第 1 行</b>（今天会被服务端以
 * "主城等级不足"拒绝 —— 那本身是一条负向用例）。断言按"主城 + 伐木场各一栋"写死，
 * **改流程要连这段注释一起改**。
 *
 * 退出码：0 全绿；1 判据失败（含"一格表现都没有"）；2 前置不满足（产物缺失）。
 *
 * <p>2026-09-21 从 `tmp/` 迁进 `tools/`（复检那一轮的一次性探针）。迁移时修掉两处硬伤：
 * ① 等待判据原先写的是 `texts.includes('收割')` —— 它命中常驻的「一键收割」按钮，
 *    于是第一次 5 秒检查就 break，拍出来的 `09-two-buildings-done.png` 其实是**建造中**的帧；
 *    现在改成"**重载后**读队列回到 0/N"（重载是因为队列计数来自 `/city/list` 快照，本地不重算）。
 * ② 原先没有任何退出码语义（跑完就算过），现在按仓库惯例补上 0/1/2。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = 'client/build/art-verify/cc-audit'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CITY_MANY_PORT ?? 8212)
mkdirSync(OUT, { recursive: true })
if (!existsSync(path.resolve(process.cwd(), 'client/build/web-mobile/index.html'))) {
  console.error('[build-many][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `buildmany-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))
await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

const GUIDE_COPY = /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/
const clearGuide = () => page.evaluate((src) => {
  const re = new RegExp(src)
  const scene = window.cc.director.getScene()
  const killed = []
  const visit = (n) => {
    const label = n.getComponent('cc.Label')
    if (/Guide/i.test(n.name) || (label !== null && re.test(label.string ?? ''))) {
      const v = n.getComponent('GuideView'); if (v !== null) v.enabled = false
      n.removeFromParent(); killed.push(n.name); return
    }
    for (const c of n.children) visit(c)
  }
  visit(scene)
  return killed.length
}, GUIDE_COPY.source)

await page.waitForTimeout(3500)
console.log('[build-many] 清引导层：', await clearGuide())
await page.waitForTimeout(500)

const toPage = (name) => page.evaluate((n) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let t = null
  const visit = (x) => { if (x.name === n) t = x; for (const c of x.children) visit(c) }
  visit(scene)
  if (t === null) return null
  const ui = t.getComponent('cc.UITransform')
  if (ui === null) return null
  const s = scene.getComponentInChildren('cc.Camera').worldToScreen(ui.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const px = cc.view.getVisibleSizeInPixel()
  const rect = document.querySelector('canvas').getBoundingClientRect()
  return { x: rect.left + (s.x / px.width) * rect.width,
           y: rect.top + rect.height - (s.y / px.height) * rect.height }
}, name)

const texts = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (n, shown) => {
    const vis = shown && n.active !== false
    const l = n.getComponent('cc.Label')
    if (vis && l !== null && l.string !== '') out.push(l.string)
    for (const c of n.children) visit(c, vis)
  }
  visit(scene.getChildByName('Canvas'), true)
  return out
})

/** 建一栋：点「建造」→ 点一块空地 → 在选择器里选第 optionIndex 行。 */
async function buildOne(optionIndex, emptyIndex) {
  await clearGuide()
  const buildBtn = await toPage('DetailBuildButton')
  if (buildBtn === null) { console.error('[判据失败] 找不到「建造」按钮'); return false }
  await page.mouse.click(buildBtn.x, buildBtn.y)
  await page.waitForTimeout(700)

  const emptyTile = await page.evaluate((idx) => {
    const scene = window.cc.director.getScene()
    let grid = null
    const visit = (n) => { if (n.name === 'CityGrid') grid = n; for (const c of n.children) visit(c) }
    visit(scene)
    if (grid === null) return null
    const empty = grid.children.filter((c) => /^Grid-\d+$/.test(c.name)).filter((c) => {
      const lv = c.getChildByName('Level')?.getComponent('cc.Label')
      return lv !== null && lv.string === ''
    })
    return empty.length <= idx ? null : empty[idx].name
  }, emptyIndex)
  if (emptyTile === null) { console.error('[判据失败] 没有足够的空地'); return false }
  const pt = await toPage(emptyTile)
  await page.mouse.click(pt.x, pt.y)
  await page.waitForTimeout(900)

  const row = await toPage(`Choice-${optionIndex}`)
  if (row === null) { console.error('[判据失败] 选择器里没有第', optionIndex, '行'); return false }
  const label = await page.evaluate((i) => {
    const scene = window.cc.director.getScene()
    let t = null
    const visit = (n) => { if (n.name === `Choice-${i}`) t = n; for (const c of n.children) visit(c) }
    visit(scene)
    if (t === null) return null
    const labels = t.getComponentsInChildren('cc.Label')
    return labels.length > 0 ? labels[0].string : null
  }, optionIndex)
  await page.mouse.click(row.x, row.y)
  await page.waitForTimeout(1200)
  console.log(`[build-many] 在 ${emptyTile} 发起建造「${label}」`)
  return true
}

await buildOne(0, 2)   // 伐木场
await buildOne(1, 8)   // 采石场

await page.screenshot({ path: path.join(OUT, '08-two-buildings-queued.png') })
console.log('[build-many] 建造中屏上文本：', (await texts()).filter((t) => /伐木场|采石场|升级|建造/.test(t)).join(' | '))

/**
 * 等两栋建完（timeBaseSec=20 秒，队列 2 条并行），每 5 秒看一次队列。
 *
 * <p>**判据修过（2026-09-21）**：原来写的是 `if (t.some((x) => x.includes('收割'))) break`，
 * 而屏上常驻着「一键收割」按钮 —— 于是**第一次检查（第 5 秒）就 break**，
 * 拍出来的 `09-two-buildings-done.png` 其实是**建造中**的帧（伐木场 Lv0、队列 1/2）。
 * 现在的判据是「队列回到 0/N」；等满 60 秒也不成立就如实报"没等到"，不假装建完。
 */
let queueZero = false
for (let i = 0; i < 8; i++) {
  await page.waitForTimeout(5000)
  // **每轮重载一次再读**：队列计数来自 `/city/list` 的快照，本地每秒刷新的是倒计时、不是它 ——
  // 不重载就会一直读到"1/2"（12 轮全是 1/2 的那次实测，见审计 §10.3 的说明），判据也就永远走不到。
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
  await page.waitForTimeout(2500)
  await clearGuide()
  const t = await texts()
  const queue = t.find((x) => x.includes('建造队列')) ?? ''
  const matched = /建造队列\s*(\d+)\s*\/\s*(\d+)/.exec(queue)
  const queued = matched === null ? Number.NaN : Number(matched[1])
  console.log(`[build-many] 第 ${i + 1} 次检查（重载后）：${queue}`)
  if (Number.isFinite(queued) && queued === 0) { queueZero = true; break }
}
console.log(`[build-many] 队列归零：${queueZero ? '是' : '否（等满 60 秒）'}`)

/**
 * 最后一张读数**重新载入面板再取**：倒计时是本地每帧算的，而等级、队列、可收割提示
 * 都来自上一次 `/city/list` 的快照 —— 不重载就读不到"建完了"这件事本身。
 */
await page.reload({ waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(3500)
await clearGuide()
await page.waitForTimeout(500)
await page.screenshot({ path: path.join(OUT, '09-two-buildings-done.png') })

const final = (await texts()).filter((t) => !/^(内城|军队|武将|招募|背包|关卡|战报|任务|战令|邮件|社交|战力|商店|外观|搜索|地图|设置)$/.test(t))
console.log('[build-many] 最终屏上文本：', final.join(' | '))

/** 逐格读：哪些格上有建筑图标、哪些是空的。 */
const tiles = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let grid = null
  const visit = (n) => { if (n.name === 'CityGrid') grid = n; for (const c of n.children) visit(c) }
  visit(scene)
  if (grid === null) return []
  return grid.children.filter((c) => /^Grid-\d+$/.test(c.name)).map((c) => {
    const lv = c.getChildByName('Level')?.getComponent('cc.Label')
    const name = c.getChildByName('Name')?.getComponent('cc.Label')
    const icon = c.getChildByName('BuildingIcon')
    const sprite = icon?.getComponent('cc.Sprite') ?? null
    return { tile: c.name, level: lv?.string ?? '', name: name?.string ?? '',
      iconActive: icon?.active !== false,
      hasSprite: sprite !== null && sprite.spriteFrame !== null }
  }).filter((t) => t.level !== '' || t.iconActive)
})
console.log('[build-many] 有建筑表现的格：')
tiles.forEach((t) => console.log(`   ${t.tile}  ${t.name} ${t.level}  iconActive=${t.iconActive} hasSprite=${t.hasSprite}`))
writeFileSync(path.join(OUT, 'multi-building-report.json'),
  JSON.stringify({ queueZero, tiles, texts: final, pageErrors: errors }, null, 2))
console.log(`[build-many] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)
await browser.close()
await preview.close()

/**
 * 判据（都能失败）：
 * ① 伐木场那格必须画着**正稿**（`iconActive && hasSprite`）—— 这就是"建了才叠正稿"；
 * ② 它的等级必须是 `Lv1` —— 建造真完成、面板真刷新过（防的正是本文件头注释里那个假绿）；
 * ③ 主城**一格都不许叠**（底图已有城堡，叠了就是重影）；
 * ④ 除这两栋外不许有第三个格子有建筑表现 —— "未建不画"；
 * ⑤ 队列必须回到 `0/N`；⑥ 一格表现都没有 ⇒ 判据走不到，算红（反空转）。
 */
const failures = []
const lumber = tiles.find((t) => t.name === '伐木场')
const mainCity = tiles.find((t) => t.name === '主城')
const others = tiles.filter((t) => t.name !== '伐木场' && t.name !== '主城')
if (tiles.length === 0) {
  failures.push('一格建筑表现都没有 —— 判据走不到，不许当绿')
}
if (lumber === undefined) {
  failures.push('没看到伐木场的建筑表现 —— "建了才叠正稿"没成立')
} else {
  if (!lumber.iconActive || !lumber.hasSprite) {
    failures.push(`伐木场那格没有正稿：${JSON.stringify(lumber)}`)
  }
  if (lumber.level !== 'Lv1') {
    failures.push(`伐木场等级不是 Lv1（建造没完成或面板没刷新）：${JSON.stringify(lumber)}`)
  }
}
if (mainCity !== undefined && (mainCity.iconActive || mainCity.hasSprite)) {
  failures.push(`主城不该叠正稿（底图已有城堡）：${JSON.stringify(mainCity)}`)
}
if (others.length > 0) {
  failures.push(`未建位置出现了建筑表现：${others.map((t) => `${t.tile} ${t.name}`).join('、')}`)
}
if (!queueZero) {
  failures.push('建造队列没有回到 0/N —— 这是本文件头注释里那个"5 秒就 break"的假绿要防的事')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[build-many] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[build-many] 全绿：建了才叠正稿、主城不叠图、未建不画、队列归零')

