#!/usr/bin/env node
/**
 * 职责：把「自上次登录以来」那一屏真的画得出来、点一条真的跳得过去，变成能失败的判据。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 OFFLINE_PORT（默认 8097，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8075 node tools/verify-offline-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`OfflineReportOverlay` 与纯逻辑模块在两个平台上都是同一份代码
 * （小游戏那边没有可编程点击通道）。
 *
 * <p><b>本探针怎么造出"那一屏"</b>（这一段是边界，写清楚免得被读强）：真服上要看到它，
 * 得让「距上次登录」越过阈值（表里现为 10 分钟）**且**至少有一条明细 —— 前者要求真的等，
 * 后者要求号里有产出/战报/事件。所以这里分两段验：
 * ① **默认不弹**：正常登录一次，场景里那一层必须是收起的（它不该不请自来）；
 * ② **注入一份生产形状的视图**（不是注入响应 —— 那一屏的组装与判定由
 *    `OfflineReport.test.ts` / `AppRoot.test.ts` 的用例按确定数字覆盖）：验它画得出来、标题口径对、
 *    点一条会经编排层跳到目标面板、截图留证。
 * 这两段合起来证明「渲染与接线是对的」，**不**证明「阈值在真服上真的拦得住」—— 那一句由纯逻辑用例负责。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const ROOT = path.resolve('client/build/web-mobile')
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，读数错得像产品缺陷
// （2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错 —— 台账 #371/#372）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-offline-runtime] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.OFFLINE_PORT ?? 8097)
const SHOT_DIR = path.resolve('client/build/offline-verify')
/** 探针注入的那一份视图：形状与 `game/offline/OfflineReport.ts` 的产物逐字段一致。 */
const SAMPLE_VIEW = {
  items: [
    { key: 'resources', text: '资源产出（约 3 小时）', detail: '木材 +600、铁矿 +300', jump: 'city' },
    { key: 'buildings', text: '2 座建筑已升级完成', detail: '回城里收割一下就能拿到产出', jump: 'city' },
    { key: 'battles:2:1', text: '2 场战斗', detail: '胜 1 · 败 1', jump: 'reports' },
    { key: 'social:3', text: '3 条社交动态', detail: '盟友 张三 正在被攻击', jump: 'social' },
  ],
}

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 读那一层：是否存在、是否激活、标题与行文本、以及当前导航格。 */
function readOverlay() {
  const out = { found: false, active: false, title: null, rows: [], currentKey: null }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  out.currentKey = game?.getComponent('PanelNav')?.currentKey ?? null
  const node = game?.getChildByName('OfflineReport')
  if (node === undefined || node === null) {
    return out
  }
  out.found = true
  out.active = node.activeInHierarchy !== false
  for (const child of node.children) {
    if (child.name === 'label') {
      const label = child.getComponent('cc.Label')
      if (label !== null && label !== undefined && child.position.y > 40) {
        out.title = label.string
      }
    }
    if (child.name.startsWith('offlineRow') && child.activeInHierarchy !== false) {
      const texts = []
      for (const grand of child.children) {
        const label = grand.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') {
          texts.push(label.string)
        }
      }
      out.rows.push(texts.join(' / '))
    }
  }
  return out
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try {
        boot = JSON.parse(text.slice('[boot] '.length))
      } catch {
        // 非结构化那一条不算数
      }
    }
  })

  await page.goto(`${preview.origin}/`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await hideGuideOverlay(page)
  const deadline = Date.now() + 45_000
  while (boot === null && Date.now() < deadline) {
    await page.waitForTimeout(500)
  }
  preview.assertRewritten()
  if (boot === null) {
    await browser.close()
    await preview.close()
    console.error('\n=== 判定中止：没捕获到 [boot] 自检行，读数会是假的 ===')
    process.exit(1)
  }
  verdict(boot.started === true, '启动跑通（started=true）', `platform=${boot.platform} bootMs=${boot.bootMs}`)

  // ① 默认不弹：新号没有「上一次登录」，这一层不该出现
  await page.waitForTimeout(1500)
  const idle = await page.evaluate(readOverlay)
  verdict(idle.found === true, '那一层已经在场景里挂好了（不是登录后才建）', `found=${idle.found}`)
  verdict(idle.active === false, '默认是收起的（不请自来的弹窗比不弹更糟）', `active=${idle.active}`)

  // ② 注入一份生产形状的视图，验渲染与跳转
  await page.evaluate((view) => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    // GameBootstrap 与 PanelNav 都挂在 Game 这个节点上（不是它的子节点）
    const bootstrap = game?.getComponent('GameBootstrap')
    if (bootstrap === undefined || bootstrap === null || bootstrap.offlineReport === null) {
      throw new Error('拿不到 GameBootstrap.offlineReport（场景装配变了？）')
    }
    bootstrap.offlineReport.render(view)
  }, SAMPLE_VIEW)
  await page.waitForTimeout(400)
  const shown = await page.evaluate(readOverlay)
  verdict(shown.active === true, '注入一份视图之后它弹出来了', `active=${shown.active}`)
  verdict(shown.title === '自上次登录以来', '标题口径是「自上次登录以来」（不是"离线收益"）',
    `标题="${shown.title}"`)
  verdict(shown.rows.length === SAMPLE_VIEW.items.length,
    '四条都画出来了（含资源那一行的"约"）', `${shown.rows.length} 行：${shown.rows.join(' | ')}`)
  verdict(shown.rows.some(r => r.includes('资源产出（约')), '资源那一行真的带"约"字（估算不当成流水账）',
    shown.rows.find(r => r.includes('资源产出')) ?? '—')

  const shot = path.join(SHOT_DIR, 'offline-report.png')
  await page.screenshot({ path: shot })
  lines.push(`SHOT  ${shot}`)

  // ③ 点第一条会跳到内城（那一条的 jump 是 city）：跳转要经编排层，且那一层要收起
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const node = game?.getChildByName('OfflineReport')
    for (const child of node?.children ?? []) {
      if (child.name === 'offlineRow2') {   // 第 3 行是战斗，jump=reports
        child.emit('touch-start')
        return
      }
    }
  })
  await page.waitForTimeout(800)
  const afterJump = await page.evaluate(readOverlay)
  verdict(afterJump.currentKey === 'reports',
    '点「2 场战斗」那一行：导航切到了战报页（跳转真的经编排层走通了）',
    `当前格=${afterJump.currentKey}`)
  verdict(errors.length === 0, '全程零页面异常', `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[offline] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[offline] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[offline] 全部判据通过。截图见上面那行 SHOT。')
}

main().catch((error) => {
  console.error('[offline] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
