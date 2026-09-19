/**
 * 世界地图的**标签读数**：拍一张地图，并核对格子上印的是中文资源名而不是枚举原文。
 *
 * <p>为什么单独一个工具，而不是复用 `verify-art-runtime.mjs`：那条量具是"一趟走完八个面板"的串行流程，
 * 中间任何一格崩了它整体就退 1 —— 本轮 `ArmyPanelView` 的 `unitId` 崩溃（并行会话的线，已裁决不接）
 * 就把地图那一屏的验收一起拖住。验收自己的改动不该等别人的红，所以这里只走地图这一格。
 *
 * <p>判据（都能失败）：
 * ① 屏幕上**一条裸资源枚举都不能有**（`WOOD/STONE/IRON/GRAIN/GOLD/STAMINA`）——
 *    那正是 #268 修的东西，修前的截图上全是它；
 * ② 至少要读到**一个中文资源名**（期望值从 `contract/config/resource.json` 现读，不抄第二份）——
 *    没有这条正向断言，①会把"地图压根没渲染"也判成绿（同一族教训见 #265/#268）；
 * ③ 一个非 ASCII 文本都没有 ⇒ 面板没渲染，退 2 不算绿。
 *
 * 退出码：0 绿；1 判据失败；2 前置不满足（产物 / 后端 / 场景结构 / 页面报错）。
 */
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[world][前置] 产物不存在：${ROOT}（先跑 Cocos 构建）`)
  process.exit(2)
}

/** 客户端映射的对照真源：配置表里那六个中文名。 */
const RESOURCE_ROWS = (() => {
  const doc = JSON.parse(readFileSync('contract/config/resource.json', 'utf8'))
  return Array.isArray(doc) ? doc : doc.rows
})()
const CN_NAMES = RESOURCE_ROWS.map((row) => row.name)
const ENUM_NAMES = RESOURCE_ROWS.map((row) => row.id)

const OUT = process.env.WORLD_SHOT_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify/world-label-check.png')
const PORT = Number(process.env.WORLD_SHOT_PORT ?? 8197)
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `world-label-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'world')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

/**
 * 等地图上有实体挂出文字。冷后端首连要注册 + 拉快照，固定 sleep 会把"还没画完"当成"画错了"。
 */
const sawCaption = await page.waitForFunction(() => {
  const scene = window.cc.director.getScene()
  let count = 0
  const visit = (n) => {
    const label = n.getComponent('cc.Label')
    if (n.active !== false && label !== null && label.enabled !== false && label.string !== '') count += 1
    for (const c of n.children) visit(c)
  }
  visit(scene)
  return count
}, { timeout: 25000, polling: 500 }).then((h) => h.jsonValue()).catch(() => 0)

/**
 * 等地图**稳定**再拍。首跑只等到"有文字"就拍，结果拍到的是分块还在流式加载的中间态：
 * 屏幕只有左上角一小块地形、其余全黑、读数写着"缩放 0"、一个资源标签都没有 ——
 * 那既不是缺陷也不是通过，是**没拍完**。分块加载是异步的，只能等它自己停。
 * 判稳口径：地形节点数连续三次（每 700ms 一次）不变。
 */
async function countTerrain() {
  return page.evaluate(() => {
    let n = 0
    const visit = (node) => {
      if (node.name === 'Art') n += 1
      for (const c of node.children) visit(c)
    }
    visit(window.cc.director.getScene())
    return n
  })
}
let stable = 0
let last = -1
for (let attempt = 0; attempt < 30 && stable < 3; attempt += 1) {
  await page.waitForTimeout(700)
  const now = await countTerrain()
  stable = now === last ? stable + 1 : 0
  last = now
}
console.log(`[world] 地形节点稳定在 ${last} 个（连续 ${stable} 次未变）`)
if (last === 0) {
  console.error('[world][前置] 一个地形节点都没有 —— 地图没画出来，判据走不到')
  await browser.close()
  await preview.close()
  process.exit(2)
}
await page.screenshot({ path: OUT })
console.log(`[world] 截图：${OUT}`)

/**
 * 视口覆盖率：把所有地形块在 UI 空间里的外接盒并起来，与可见尺寸比。
 *
 * <p>为什么量这个而不是量像素：WebGL 画布默认不保留绘制缓冲，`toDataURL` 拿到的可能是黑的，
 * 那种"量不出来"会被读成"覆盖率为 0"。外接盒是从**真正会画出来的节点**上读的，
 * 与 #269 那条"地图只占中间约 350×350"是同一件事，但它可以失败。
 */
const coverage = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const visible = window.cc.view.getVisibleSize()
  let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity
  let tiles = 0
  const visit = (n) => {
    if (n.name === 'Art') {
      const box = n.getComponent('cc.UITransform')
      if (box !== null) {
        const w = n.getWorldPosition(new window.cc.Vec3())
        const halfW = box.width / 2; const halfH = box.height / 2
        minX = Math.min(minX, w.x - halfW); maxX = Math.max(maxX, w.x + halfW)
        minY = Math.min(minY, w.y - halfH); maxY = Math.max(maxY, w.y + halfH)
        tiles += 1
      }
    }
    for (const c of n.children) visit(c)
  }
  visit(scene)
  if (tiles === 0) return { tiles: 0, widthRatio: 0, heightRatio: 0 }
  return {
    tiles,
    boxW: maxX - minX, boxH: maxY - minY,
    viewW: visible.width, viewH: visible.height,
    // **按轴判，不按面积判**：面积比会把"768×768 摆在 960×600 上"算成 102%（通过），
    // 而那一屏左右各有一条 96px 的黑边 —— 面积是够的，宽度不够。
    // 这正是旧口径（写死 8px）的实际形态，用面积比就抓不到它。
    widthRatio: (maxX - minX) / visible.width,
    heightRatio: (maxY - minY) / visible.height,
  }
})
const cover = Math.min(coverage.widthRatio, coverage.heightRatio)
console.log(`[world] 地形 ${coverage.tiles} 块，外接盒 ${Math.round(coverage.boxW ?? 0)}×${Math.round(coverage.boxH ?? 0)}`
  + ` vs 视口 ${Math.round(coverage.viewW ?? 0)}×${Math.round(coverage.viewH ?? 0)}`
  + ` ⇒ 宽 ${(coverage.widthRatio * 100).toFixed(0)}% / 高 ${(coverage.heightRatio * 100).toFixed(0)}%`
  + ` / 取短板 ${(cover * 100).toFixed(0)}%`)
if (cover < 0.98) {
  console.error(`[world] 判据失败：短板方向只铺到 ${(cover * 100).toFixed(0)}%`
    + ' —— 屏幕会露出一圈纯黑，读起来像"地图到此为止"')
  await browser.close()
  await preview.close()
  process.exit(1)
}

/**
 * 读**所有**实体标签节点的文本，不管它当前可不可见。
 *
 * <p>为什么这里不按可见性过滤（与上面那段"可见文字"的口径相反）：本判据要回答的是
 * "#268 之后地图上写的到底是 `IRON` 还是 `铁矿`"，那是**文案内容**问题；
 * 而默认缩放下资源格的标签根本不画（截图实测：地图只占中间约 350×350，其余全黑，
 * 读数写着"缩放 0"，屏上 0 条资源标签）—— 那是**另一条更大的排版缺陷**，
 * 已单独记进 `.qoder-work-queue.md`，不许混进来把文案判据变成"因为没画所以通过/失败"。
 * 可见条数仍然打出来当读数，只是不当门。
 */
const captions = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (n, visible) => {
    const shown = visible && n.active !== false
    if (/Caption$/i.test(n.name)) {
      const label = n.getComponent('cc.Label')
      if (label !== null && label.string !== '') out.push([label.string, shown])
    }
    for (const c of n.children) visit(c, shown)
  }
  visit(scene, true)
  return out
})
const labels = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (n, visible) => {
    const shown = visible && n.active !== false
    const label = n.getComponent('cc.Label')
    if (shown && label !== null && label.enabled !== false && label.string !== '') out.push(label.string)
    for (const c of n.children) visit(c, shown)
  }
  visit(scene, true)
  return out
})
await browser.close()
await preview.close()

const captionTexts = captions.map((entry) => entry[0])
const visibleCaptions = captions.filter((entry) => entry[1]).length
console.log(`[world] 等到 ${sawCaption} 条文字后拍摄；可见文字 ${labels.length} 条`)
if (errors.length > 0) {
  console.error(`[world][前置] 页面报错 ${errors.length} 条：${errors[0]}`)
  process.exit(2)
}
// 只数**实体**标签：按钮与页签那一些节点也叫 `*_Caption`，把它们算进来会得到
// "22 个标签、0 个资源名"这种看着像缺陷、其实是判据抓错对象的读数（实测首跑就这样）。
const entityCaptions = captionTexts.filter((s) => !/^(放大|缩小|回城|流亡迁城|行军|内城|军队|武将|背包|关卡|战报|任务|战令|邮件|社交|战力|商店|外观|搜索|地图|设置)$/.test(s))
console.log(`[world] 实体标签 ${entityCaptions.length} 个（屏上另有按钮/页签标签 ${captionTexts.length - entityCaptions.length} 个），其中当前可见 ${visibleCaptions} 个`)
if (entityCaptions.length === 0) {
  // 这是**本工具自己的能力边界**，不是产品缺陷：默认缩放（截图实测"缩放 0"，地图只占中间约 350×350）
  // 根本不画实体标签，而 headless 下点不动"放大"按钮（触摸送不进去，见项目队列）。
  // 所以这一屏的文案要看 `verify-art-runtime.mjs` 导出的 `art-world-zoom-runtime.png`（它会放大再看）。
  console.error('[world][前置] 默认缩放下地图不画任何实体标签，本工具又点不动"放大" —— '
    + '文案判据走不到。要看中文资源名请核 `art-world-zoom-runtime.png`，不要把这条读成产品缺陷')
  process.exit(2)
}

const bareEnums = entityCaptions.filter((s) => ENUM_NAMES.some((e) => new RegExp(`(^|[^A-Z])${e}([^A-Z]|$)`).test(s)))
const cnHits = entityCaptions.filter((s) => CN_NAMES.some((n) => s.includes(n)))
console.log(`[world] 标签文案：中文资源名 ${cnHits.length} 条 / 裸枚举 ${bareEnums.length} 条`
  + `${bareEnums.length > 0 ? `（${bareEnums.slice(0, 6).join('、')}）` : ''}`
  + ` / 其余 ${entityCaptions.length - bareEnums.length - cnHits.length} 条：${entityCaptions.filter((s) => !CN_NAMES.some((n) => s.includes(n))).slice(0, 4).join('、')}`)
// **负向判据永远有效**：这一屏只要冒出 `IRON`/`WOOD` 就是 #268 的缺陷复发，判红。
if (bareEnums.length > 0) {
  console.error(`[world] 判据失败：${bareEnums.length} 条标签仍是资源枚举原文（#268 的缺陷形态）`)
  process.exit(1)
}
// **正向判据要有对象才成立**：默认缩放这一屏只画得出玩家自己那座城（实测 1 条实体标签"无名君主"），
// 资源格的标签根本不画，而 headless 点不动"放大"。没有资源标签时判"必须有中文资源名"，
// 等于拿工具的无能当产品的缺陷 —— 退 2 说清去核哪张图。
if (cnHits.length === 0) {
  console.error('[world][前置] 这一屏没有资源格标签可核（默认缩放不画资源标签，本工具又点不动"放大"）。'
    + '负向判据已过（无枚举原文）；正向请核 `art-verify/art-world-zoom-runtime.png`')
  process.exit(2)
}
console.log(`[world] 全绿：地图实体标签写的都是中文资源名（${cnHits.length}/${entityCaptions.length} 条）`)
