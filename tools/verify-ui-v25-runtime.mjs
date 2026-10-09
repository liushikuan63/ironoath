/**
 * 职责：V25 素材的运行时验收 —— 证明包内素材**能加载、九宫格几何与 .png.meta 自洽**，
 *       并且**沿真实玩家路径画到屏上**（点体力 → 详情弹层用的就是 `ui.panel.iron`），留一张肉眼可判的截图。
 * 依赖：playwright、`tools/lib/preview-server.mjs`、独立构建产物（`outputName=ui-v25`）、一台活后端。
 *
 * 为什么不只跑 shot-panel-sweep：那 17 页量的是导航条面板，而本批改的是三个弹窗底板
 * （礼包 / 选择弹层 / 体力详情）—— 它们不在导航条上，新号登录也不一定有可弹的礼包。
 * "构建 missing=0" 只证明资源进了包，不证明**渲染正确**，这一份补的就是那一截。
 *
 * 为什么不在页面里手工 new Node + addComponent(UITransform)：release 产物的 `window.cc` 里
 * 拿不到 `UITransform` 这个类，`addComponent(undefined)` 抛 Cocos 错误 3804（实测两次）
 * ⇒ 改成走真实调用链：找带 `onStamina` 的组件调一次，弹层由生产代码自己建。
 *
 * 跑法：
 *   BACKEND_ORIGIN=http://localhost:8311 SWEEP_ROOT=client/build/ui-v25 \
 *   SWEEP_PORT=8298 node tools/verify-ui-v25-runtime.mjs
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = process.env.SWEEP_ROOT ?? 'client/build/ui-v25'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.SWEEP_PORT ?? 8298)
const OUT = process.env.UI25_OUT ?? 'tmp/ui-v25-shots'

// 期望值来自 art-src/弹窗面板美术风格_VibeCoding规格.md §二 三档契约 + §4.7 实测尺寸。
// ⚠ w/h 填的是**交付尺寸（未裁边）**：Cocos 导入会 trim 掉 1~2px 透明边，meta 的 width/height 是裁后的，
//    而运行时 frame 报的是原图尺寸 ⇒ 照 meta 抄会每条差 1~2px 全红（本轮实测撞过一次）。
const EXPECT = [
  // 统一 v3 五种底板采用同一张 512×326 薄框母版；角帽约16–18px，四边24保角并留缓冲。
  // 本表与 .png.meta 不一致时，本探针报的正是"改了图没改布局常量"那一族。
  { key: 'ui.panel.kingdom', res: 'ui/generated/ui/panel-kingdom-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.iron', res: 'ui/generated/ui/panel-iron-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.parchment', res: 'ui/generated/ui/panel-parchment-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.warning', res: 'ui/generated/ui/panel-warning-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.gilt', res: 'ui/generated/ui/panel-gilt-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.button.iron', res: 'ui/generated/ui/button-iron-v1', w: 256, h: 65, inset: [6, 6, 4, 4] },
  { key: 'ui.chip.close', res: 'ui/generated/ui/chip-close-v1', w: 52, h: 52, inset: [6, 6, 4, 4] },
  { key: 'ui.plate.band', res: 'ui/generated/ui/plate-band-v1', w: 512, h: 52, inset: [12, 12, 8, 8] },
  { key: 'ui.plate.tooltip', res: 'ui/generated/ui/plate-tooltip-v1', w: 256, h: 61, inset: [6, 6, 4, 4] },
  // 装饰件（顶饰 / 火漆 / 分隔线）inset 全 0：它们按原比例整幅缩放、永不拉伸。
  { key: 'ui.crest.league', res: 'ui/generated/ui/crest-league-v1', w: 178, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.crest.nation', res: 'ui/generated/ui/crest-nation-v1', w: 246, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.crest.battle', res: 'ui/generated/ui/crest-battle-v1', w: 223, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.seal.wax', res: 'ui/generated/ui/seal-wax-v1', w: 256, h: 256, inset: [0, 0, 0, 0] },
]
// 零消费的礼包旗帜、奖章与绳线已退回草稿区；余下条目继续逐件核对加载与几何。

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[ui-v25][前置] 产物不存在：${ROOT}（先构建 outputName=ui-v25）`)
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const post = async (url, body) => (await fetch(`${BACKEND}${url}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})).json()
const init = await post('/player/init', {
  requestId: `ui25-init-${Date.now()}`, deviceId: `ui25-${Date.now()}`,
  nickName: '美术验收', clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[ui-v25][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => { localStorage.setItem('ironoath.deviceId', v) }, `ui25-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))
await page.goto(preview.origin)
await page.waitForFunction(() => window.cc?.director?.getScene() != null, null, { timeout: 60000 })
await page.waitForTimeout(3000)

const results = []
const push = (name, pass, detail) => results.push({ name, pass, detail })

// 一、逐件加载并回读运行时 inset —— 手写 .png.meta 最容易错的就是尺寸/顶点/border 三者不自洽。
const loaded = await page.evaluate((specs) => new Promise((resolve) => {
  const { resources, SpriteFrame } = window.cc
  const out = []
  let done = 0
  specs.forEach((s) => {
    resources.load(`${s.res}/spriteFrame`, SpriteFrame, (err, frame) => {
      if (err || !frame) {
        out.push({ res: s.res, ok: false, why: `加载失败：${err?.message || 'frame 为 null'}` })
      } else {
        out.push({
          res: s.res, ok: true,
          px: [frame.width, frame.height],
          inset: [frame.insetLeft, frame.insetRight, frame.insetTop, frame.insetBottom],
        })
      }
      if (++done === specs.length) resolve(out)
    })
  })
}), EXPECT)

for (const spec of EXPECT) {
  const got = loaded.find((l) => l.res === spec.res)
  if (!got || !got.ok) {
    push(`${spec.key} 可加载`, false, got?.why || '没有回读结果')
    continue
  }
  push(`${spec.key} 可加载`, true, `运行时像素 ${got.px.join('x')}`)
  push(`${spec.key} 尺寸与 meta 一致`, got.px[0] === spec.w && got.px[1] === spec.h,
    `运行时 ${got.px.join('x')} / 期望 ${spec.w}x${spec.h}`)
  push(`${spec.key} inset 与 meta 的 border 一致`, got.inset.join(',') === spec.inset.join(','),
    `运行时 L/R/T/B=${got.inset.join('/')} / meta=${spec.inset.join('/')}`)
}

// 二、真实玩家路径：点体力 → CityPanelView.onStamina → AppRoot.openStaminaDetail()
//     → 服务端 staminaView → StaminaDetailOverlay.render() —— 底板正是 ui.panel.iron。
const clicked = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let called = 0
  const names = []
  const visit = (n) => {
    for (const c of (n.components || [])) {
      if (typeof c.onStamina === 'function') {
        names.push(`${n.name}/${c.constructor?.name || '?'}`)
        try { c.onStamina(); called++ } catch (e) { /* 计数已加，异常留给 pageerror 兜 */ }
      }
    }
    ;(n.children || []).forEach(visit)
  }
  visit(scene)
  return { called, names }
})
push('找到并调用 onStamina（真实触发链）', clicked.called > 0,
  `命中 ${clicked.called} 处：${clicked.names.slice(0, 2).join(', ') || '无'}`)
await page.waitForTimeout(2500)

// 三、回读屏上是否真有一块 SLICED 的图在画着，并核它两条：没退化、边框也没吃掉可读区
//     （与 verify-art-runtime 同一条判据；V25-e 把它同时接进这一份，因为**只有这一份会画到 panel-iron**）。
const rendered = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const found = []
  const visit = (n) => {
    const sp = (n.components || []).find((c) => c.constructor?.name === 'cc.Sprite' || c.spriteFrame !== undefined)
    if (sp && sp.spriteFrame) {
      const f = sp.spriteFrame
      // release 产物会压缩类名 ⇒ 组件必须按**注册名**取（本仓既有配方）；
      // 直接读 n.uiTransform 在压缩后拿不到，会把盒子读成 0x0 并伪装成"退化"（实测踩过）。
      const ut = n.getComponent('cc.UITransform')
      const name = String(f.name || '')
      if (/panel-iron|plate-band|button-iron/.test(name)) {
        found.push({
          node: n.name, frame: name, type: sp.type,
          box: ut ? [ut.width, ut.height] : null,
          inset: [f.insetLeft, f.insetRight, f.insetTop, f.insetBottom],
          degenerate: !!ut && (ut.width < f.insetLeft + f.insetRight || ut.height < f.insetTop + f.insetBottom),
        })
      }
    }
    ;(n.children || []).forEach(visit)
  }
  visit(scene)
  return found
})
push('屏上真有 V25 素材在画（玩家路径渲染）', rendered.length > 0,
  rendered.length ? rendered.map((r) => `${r.node}:${r.frame}@${r.box ? r.box.join('x') : '?'}`).join(' | ') : '一张都没找到')
for (const r of rendered) {
  push(`未退化：${r.node} (${r.frame})`, !!r.box && !r.degenerate,
    r.box ? `消费 ${r.box.join('x')} vs inset 和 L+R=${r.inset[0] + r.inset[1]} T+B=${r.inset[2] + r.inset[3]}，type=${r.type}`
      : '读不到 cc.UITransform —— 量具问题，不是退化')
  // V25-e：不退化只是"引擎没把整张图缩小"，还要"边框没吃掉可读区"（规格 §二 的 0.6）。
  // 这条在这一份里比在 verify-art-runtime 里更值钱：只有这一份沿真实链路画出 panel-iron/button-iron。
  const ratio = r.box && r.box[0] > 0 && r.box[1] > 0
    ? Math.max((r.inset[0] + r.inset[1]) / r.box[0], (r.inset[2] + r.inset[3]) / r.box[1])
    : null
  push(`边厚不吃可读区：${r.node} (${r.frame})`, ratio !== null && ratio <= 0.6,
    ratio === null ? '读不到消费尺寸 —— 量具问题，不是通过'
      : `边框占可读区 ${ratio.toFixed(2)}（阈 0.6），消费 ${r.box.join('x')}`
        + ` / 边框和 ${r.inset[0] + r.inset[1]}x${r.inset[2] + r.inset[3]}`)
}

// 三之二、置灰：运行时驱动这一支**未执行** —— 遍历场景找不到带 render 的弹层组件实例
// （实测 `overlay=false buy=true`：节点在、组件句柄拿不到，release 产物里 getter 不可靠）。
// 不拿"跑不到的分支"凑绿，改成两条能失败的静态判据：贴图与兜底**两条路径都必须各自置灰**。
// 删掉任一条分支，下面这条就红 —— 它防的是"换贴图时把置灰弄丢"这个具体回归。
import { readFileSync } from 'node:fs'
const overlaySrc = readFileSync(path.resolve(process.cwd(),
  'client/assets/scripts/scene/StaminaDetailOverlay.ts'), 'utf8')
push('置灰：贴图路径在（灰态压暗 tint）',
  /buySprite\.color\s*=\s*[\s\S]{0,80}COLOR_TINT_ON[\s\S]{0,40}COLOR_TINT_OFF/.test(overlaySrc),
  'render() 里必须有 `buySprite.color = 可点 ? TINT_ON : TINT_OFF`')
push('置灰：兜底路径未被删（Graphics 换色）',
  /buyBackground\.fillColor\s*=\s*[\s\S]{0,60}COLOR_BUY\s*:\s*COLOR_BUY_OFF/.test(overlaySrc),
  '素材加载失败那条路仍要能置灰，否则贴图缺失时灰态消失')
push('置灰：协议要求"画着但不响应"（active 不被置 false）',
  !/buy\.active\s*=\s*false/.test(overlaySrc) && /buyEnabled/.test(overlaySrc),
  '协议明写置灰而不是隐藏；这里断言没有把按钮 active 关掉')
// 显式登记未执行项：它既不算通过也不算失败 —— 计入失败会淹没真红，静默跳过等于隐瞒。
const SKIPPED = ['运行时驱动灰态读 tint（release 产物里拿不到弹层组件实例句柄，实测 overlay=false）'
  + ' ⇒ 未验证；要补就走 tools/lib/panel-clicks.mjs 那套按节点名取壳的写法']

// 三之三、MarchComposeOverlay 的遮罩不能被材质换图顺手删掉。
// 风险形状：该弹层原先把「整屏遮罩 rect」与「底板 roundRect」画在**同一个 Graphics** 上，
// 而 applySlicedSprite 会清空所挂节点的整张画布 ⇒ 若把底板换回原节点，遮罩会一起消失，
// 底下搜索面板的字与标题叠在一起（该文件注释里记着 `compose-mode-*.png` 那次两张都拍到的事故）。
// 打开这个弹层要造部队 + 目标（成本高、且会占集结名额），所以这里钉**结构**而不是钉像素：
// 遮罩必须还画在 background 上，而贴图/兜底底板必须在**另一个节点**里。
const composeSrc = readFileSync(path.resolve(process.cwd(),
  'client/assets/scripts/scene/MarchComposeOverlay.ts'), 'utf8')
const scrimStillOnBackground = /background\.rect\(\s*-screen\.width/.test(composeSrc)
const plateOnOwnNode = /new Node\('plate'\)[\s\S]{0,400}applySlicedSprite\(plate,\s*'ui\.panel\.iron'/.test(composeSrc)
const plateNotDrawnOnBackground = !/background\.roundRect\(\s*-width\s*\/\s*2/.test(composeSrc)
push('集结弹层：整屏遮罩仍画在 background 上', scrimStillOnBackground,
  '断言 `background.rect(-screen.width…)` 还在 —— 少了它弹层打开时底下面板照常亮')
push('集结弹层：底板在独立 plate 节点（贴图路径）', plateOnOwnNode,
  "断言有 `new Node('plate')` 且对它调 applySlicedSprite('ui.panel.iron')")
push('集结弹层：底板不再画回 background（否则遮罩会被一起清空）', plateNotDrawnOnBackground,
  '兜底 roundRect 必须画在 plate 自己的 Graphics 上，不能回到 background')

const shot = path.join(OUT, 'ui-v25-stamina-on-screen.png')
await page.screenshot({ path: shot })

const failed = results.filter((r) => !r.pass)
results.forEach((r) => console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  —  ${r.detail}`))
console.log(`\n[ui-v25] ${results.length} 条判据 / 失败 ${failed.length}`)
if (SKIPPED.length) {
  console.log(`[ui-v25] 未执行 ${SKIPPED.length} 条（不算通过、也不算失败）：`)
  SKIPPED.forEach((s) => console.log(`  SKIP  ${s}`))
}
console.log(`[ui-v25] 截图：${shot}`)
if (errors.length) console.log(`[ui-v25] pageerror ${errors.length} 条：${errors.slice(0, 2).join(' | ')}`)
await browser.close()
await preview.close()
process.exitCode = failed.length || errors.length ? 1 : 0
