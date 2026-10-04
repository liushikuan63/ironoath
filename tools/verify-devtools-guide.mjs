#!/usr/bin/env node
/**
 * 职责：在真实渲染里验新手引导那一层「画出来了、并且遮罩挡在该挡的地方」（B18 验收 6 的界面面）。
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 GUIDE_PORT（默认 8094，同机并发时换一个）
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 *
 * <p><b>为什么需要它，而不是只看单测</b>：{@code GuideDriver} 的判定在 CI 里（441 条客户端用例的一部分），
 * 但「遮罩是不是一个全屏节点把玩家要点的按钮也一起挡了」只在真实场景图里才看得出来 ——
 * 遮罩是按四块矩形拼的（洞=面板可用区），这个形状一旦写错，症状是引导永远推不动（玩家点不到升级按钮），
 * 而所有纯逻辑用例照绿。
 *
 * <p><b>六条判据</b>（全部读真实节点，不读 DOM 文本）：
 * ① 引导层存在且处于激活态（新号首屏就该有）；② 气泡里的文案是<b>表里那七条之一</b>
 * （配合卡口 `check-guide-no-copy` 才成立：客户端没有第二份文案，画出来的必然是下发的）；
 * ③ 「第 N / M 步」里的 M 等于表里的行数；④ 底部导航条的中心<b>落在某块遮罩里</b>（挡住 = 不许切面板）；
 * ⑤ 洞的中心<b>不落在任何遮罩里</b>（放行 = 玩家能做这一步要做的操作）；
 * ⑥ 跳过按钮的可见性等于当前步的 `skippable`（第 1 步是强制步 ⇒ 不许出现）。
 *
 * <p><b>不验到的部分（如实标注）</b>：像素级排版与字号；真机触摸（这里走的是引擎自己的命中测试，
 * 与 UITransform 矩形同源，但不是手指）；以及"玩家从第 1 步一路走完七步"（主线前置链今天做不到，见收口清单）。
 *
 * <p>用法（与 `verify-devtools-panels.mjs` 同一套环境变量）：
 * <pre>
 *   BACKEND_ORIGIN=http://localhost:8161 GUIDE_OPS_UNUSED=1 node tools/verify-devtools-guide.mjs
 * </pre>
 */
import { mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[devtools-guide] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.GUIDE_PORT ?? 8094)
const TABLE = 'contract/config/guide.json'

const rows = JSON.parse(readFileSync(TABLE, 'utf8')).rows
const stepTexts = rows.map(r => r.text)
const stepByIndex = new Map(rows.map(r => [r.stepIndex, r]))
console.log(`[guide] 从 ${TABLE} 读到 ${rows.length} 步：${rows.map(r => r.id).join(' ')}`)

function verdict(ok, label, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${label}${detail === undefined ? '' : ' | ' + detail}`)
  return ok ? 0 : 1
}

/** 在页面里跑的取数函数：读引导层的真实节点几何。必须是真函数（字符串会被当表达式求值）。 */
function readGuideLayer({ texts }) {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  const out = { started: game !== null && game !== undefined }
  if (!out.started) {
    return out
  }
  const guide = game.getChildByName('Guide')
  out.found = guide !== null && guide !== undefined
  if (!out.found) {
    return out
  }
  out.active = guide.active === true
  const worldRect = (node) => node.getComponent('cc.UITransform')?.getBoundingBoxToWorld() ?? null
  const center = (rect) => new window.cc.Vec3(rect.x + rect.width / 2, rect.y + rect.height / 2, 0)
  const gameHeight = game.getComponent('cc.UITransform')?.height ?? 0
  const gameWidth = game.getComponent('cc.UITransform')?.width ?? 0

  const maskRects = guide.children
    .filter(node => node.name === 'GuideMask')
    .map(worldRect)
    .filter(rect => rect !== null)
  out.maskCount = maskRects.length

  const bar = game.getComponent('PanelNav')?.node?.getChildByName('NavBar') ?? null
  const barRect = bar === null ? null : worldRect(bar)
  out.barFound = barRect !== null
  out.barBlocked = barRect === null
    ? false
    : maskRects.some(rect => rect.contains(center(barRect)))

  const holeRect = barRect === null
    ? null
    : {
      x: barRect.x,
      y: barRect.y + barRect.height,
      width: barRect.width,
      height: Math.max(1, gameHeight / 2 - (barRect.y + barRect.height)),
    }
  out.holeFound = holeRect !== null
  out.holeBlocked = holeRect === null
    ? true
    : maskRects.some(rect => rect.contains(center(holeRect)))

  const labels = []
  const walk = (node) => {
    const label = node.active === false && node !== guide ? null : node.getComponent('cc.Label')
    if (label !== null && label !== undefined && (label.string ?? '').trim() !== '') {
      labels.push(label.string.trim())
    }
    for (const child of node.children ?? []) {
      walk(child)
    }
  }
  walk(guide)
  out.labels = labels
  out.textFromTable = labels.some(text => texts.includes(text))
  const counter = labels.find(text => /^第\s*\d+\s*\/\s*\d+\s*步$/.test(text)) ?? ''
  out.counter = counter
  out.skipVisible = guide.getChildByName('GuideBubble')
    ?.getChildByName('GuideButtons')?.getChildByName('GuideSkip')?.active ?? null

  // **气泡自己也是遮挡源**：它不透明，落在洞里面就把洞里的按钮盖住。第 1 步要玩家按「升级」，
  // 而气泡正好贴着可用区下沿画 —— 于是"引导让你按的那颗键被引导自己挡住"。
  // 上面那两条只量遮罩（mask），量不到这件事。
  const bubble = guide.getChildByName('GuideBubble')
  const bubbleRect = bubble === null || bubble === undefined ? null : worldRect(bubble)
  out.bubbleFound = bubbleRect !== null
  const findNamed = (name) => {
    let hit = null
    const walk2 = (n) => {
      if (n.name === name) hit = n
      for (const c of n.children ?? []) walk2(c)
    }
    walk2(game)
    return hit
  }
  const intersects = (a, b) => a !== null && b !== null
    && !(a.x + a.width <= b.x || b.x + b.width <= a.x
      || a.y + a.height <= b.y || b.y + b.height <= a.y)
  const target = findNamed('DetailUpgradeButton')
  const targetRect = target === null ? null : worldRect(target)
  out.targetFound = targetRect !== null
  out.barRect = barRect === null ? null
    : { x: barRect.x, y: barRect.y, w: barRect.width, h: barRect.height }
  out.bubbleRect = bubbleRect === null ? null
    : { x: bubbleRect.x, y: bubbleRect.y, w: bubbleRect.width, h: bubbleRect.height }
  out.targetRect = targetRect === null ? null
    : { x: targetRect.x, y: targetRect.y, w: targetRect.width, h: targetRect.height }
  out.screen = { w: gameWidth, h: gameHeight }
  out.bubbleCoversTarget = intersects(bubbleRect, targetRect)
  return out
}

async function main() {
  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()
  let failures = 0

  await page.goto(`${preview.origin}/`, { waitUntil: 'networkidle' })
  // 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
  // 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  // 引导层要等登录后那一次 /guide/script 才会画：等它出现而不是等固定毫秒
  const deadline = Date.now() + 45_000
  let read = null
  while (Date.now() < deadline) {
    read = await page.evaluate(readGuideLayer, { texts: stepTexts })
    if (read?.active === true) {
      break
    }
    await page.waitForTimeout(1000)
  }

  failures += verdict(read?.started === true, '场景已起来（Canvas/Game 找得到）')
  failures += verdict(read?.found === true, '引导层节点存在（Game/Guide）', JSON.stringify(read?.found))
  failures += verdict(read?.active === true, '验收：新号首屏引导层处于激活态', 'labels=' + (read?.labels ?? []).length)
  failures += verdict((read?.maskCount ?? 0) >= 1, '反空转：至少拼出一块遮罩（0 块就是遮罩没画）', 'maskCount=' + read?.maskCount)
  failures += verdict(read?.textFromTable === true, '验收 1：气泡里画的是表里某一步的原文（客户端没有第二份文案）',
    (read?.labels ?? []).slice(0, 2).join(' / ').slice(0, 60))

  const counter = read?.counter ?? ''
  const matched = /^第\s*(\d+)\s*\/\s*(\d+)\s*步$/.exec(counter)
  const shownIndex = matched === null ? NaN : Number(matched[1])
  const shownTotal = matched === null ? NaN : Number(matched[2])
  failures += verdict(shownTotal === rows.length, '步数来自下发的 steps 长度而不是客户端自己数', counter)
  failures += verdict(stepByIndex.has(shownIndex), '当前序号在表里对得上一步', counter)
  const step = stepByIndex.get(shownIndex)
  const skipVisible = read?.skipVisible
  failures += verdict(typeof skipVisible === 'boolean' && skipVisible === step?.skippable,
    '验收 8：跳过按钮的可见性 = 这一步的 skippable', '画出来=' + skipVisible + ' 表里=' + step?.skippable)
  failures += verdict(read?.barFound === true, '导航条找得到（否则下面两条无从判定）')
  failures += verdict(read?.barBlocked === true, '验收 6：导航条中心被遮罩盖住（引导期间切不走面板）')
  failures += verdict(read?.holeBlocked === false && (read?.maskCount ?? 0) >= 1,
    '验收 6：确实拼出了遮罩，而洞的中心不在任何遮罩里（这一步要点的按钮还点得到）', 'maskCount=' + read?.maskCount)
  failures += verdict(read?.bubbleFound === true, '反空转：气泡画出来了（量不到就无从判遮挡）')
  failures += verdict(read?.targetFound === true, '这一步要玩家按的那颗键找得到（内城「升级」）')
  failures += verdict(read?.bubbleCoversTarget === false,
    '验收 6 补：气泡不压住这一步要玩家按的那颗键（气泡不透明，压住就是挡住操作）',
    'bubble=' + JSON.stringify(read?.bubbleRect) + ' target=' + JSON.stringify(read?.targetRect)
    + ' nav=' + JSON.stringify(read?.barRect) + ' screen=' + JSON.stringify(read?.screen))

  const shots = 'client/build/guide-verify'
  mkdirSync(shots, { recursive: true })
  await page.screenshot({ path: path.join(shots, 'guide-step-1.png') })
  console.log('  截图：' + path.join(shots, 'guide-step-1.png'))

  await browser.close()
  await preview.close()
  console.log(`=== 引导层判定：${failures === 0 ? '全部通过' : failures + ' 条失败'} ===`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(async error => {
  console.error('[guide] 量具本身失败：' + error.message)
  process.exit(2)
})
