#!/usr/bin/env node
/**
 * 职责：用**截图上的字形墨迹高度**量出横扫那 6 颗"疑似被盒子放大"的标签**实际画成几号**，
 * 把它们从"猜"变成"有数字"。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`、`./lib/ink-height.mjs`。
 *
 * <p>用法（端口绝不能是后端的 8199，撞了是 `EADDRINUSE` 而不是判据红）：
 * `LABELFIT_BACKEND=http://localhost:8199 LABELFIT_PORT=8193 node tools/verify-ink-height-runtime.mjs`
 *
 * <p><b>为什么需要这一份</b>：横扫只能报"盒高 > 下限 且 文本宽度用不满盒"（`verify-label-fit-runtime.mjs`
 * 的 `stretched` 那一支），从没量过落地字号。而 SHRINK 在 Cocos 里盒比字大时**会不会**把字放大，
 * 只有像素能作证 —— 读引擎内部的"实际字号"字段不算证据（`actualFontSize` 在 release 产物里不是落地值）。
 *
 * <p><b>目标不写死文案</b>：这一份用横扫**同一条谓词**（`overflow===2` 且 `盒高 > 27+8` 且单行且估宽用不满盒）
 * 现场派生目标，所以横扫那张清单变了这里自动跟着变。第一版按文案前缀匹配就漏了两颗
 * （内城的格子要等存档回来才画、`流亡迁城` 那一相根本没点上）—— 写死清单会把"没量到"演成"没问题"。
 *
 * <p><b>墨迹高 ≠ 字号**：汉字墨迹通常只有 em 的 0.8~1.0 倍（数字/拉丁更低），所以倍数必须拿
 * **同字号、同文字、`overflow=NONE` 的植入标签**当零点标定 —— 那就是尺子的 1.0 在哪。
 *
 * <p><b>判红范围</b>：只对"这把尺自己有没有坏"判红（四颗植入 + 一颗已知被压小的反向对照 + 矩形对位）。
 * 疑似放大那几颗**只报数不判红** —— 要不要升级成判据是待用户拍板的口径（见台账与 `.qoder-work-queue.md` ③），
 * 也不许为了让读数"看起来正常"去动任何盒高 / 字号。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideBoard } from './lib/guide-overlay.mjs'
import { decodePng } from './lib/png-diff.mjs'
import { measureInkBand, measureInkDiff, worldBoxToPixelRect, pixelScaleOf, designHeightOf } from './lib/ink-height.mjs'

const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[ink-height] 缺 LABELFIT_BACKEND：不给就退回别的后端，读数错得像产品缺陷')
  process.exit(2)
})()
const PORT = Number(process.env.LABELFIT_PORT ?? 8193)
const OUT = path.resolve(process.cwd(), 'client/build/ink-height-verify')
mkdirSync(OUT, { recursive: true })

const PANELS = ['city', 'world']
/**
 * 探针：拿 `world/放大` 那颗**屏上真的看得见**的标签，一次只改一个变量（字号 / overflow / 盒高），
 * 量完原样还原并复量一次自证还原。
 *
 * <p>**为什么不在页内植新标签**：第一版克隆一颗 Label 挂到面板根，四颗探针读到的墨迹都是同一个 15px
 * 且对位偏 35~55px —— 植进去的字压根没画出来，量到的是rect里别人的字。克隆一颗不可见的节点做标定
 * 等于拿猜标定猜。改地原地旋钮则没有这个问题：那颗字本来就在屏上。
 */
const PROBE = { panelKey: 'world', text: '放大' }
const PROBE_CASES = [
  { name: '基线(设定20/SHRINK/盒76)', fontSize: 20, overflow: 2, boxH: 76 },
  { name: '字12/SHRINK/盒100', fontSize: 12, overflow: 2, boxH: 100 },
  { name: '字12/NONE/盒100', fontSize: 12, overflow: 0, boxH: 100 },
  { name: '字20/NONE/盒100', fontSize: 20, overflow: 0, boxH: 100 },
  { name: '字12/SHRINK/盒14', fontSize: 12, overflow: 2, boxH: 14 },
  // #366 记的原始场景：20 号字给盒高 36 当年记成"落地 24"。这一档直接把它量一遍。
  { name: '字20/SHRINK/盒36(#366场景)', fontSize: 20, overflow: 2, boxH: 36 },
  // 放大方向的极端档：盒/字 = 16.7 倍，要 SHRINK 真会放大就该在这里放大。
  // center:true —— 那颗按钮贴着屏幕上沿，盒 200 会伸出屏外（不挪就量不准，见 inkRow 的 clipped）
  { name: '字12/SHRINK/盒200', fontSize: 12, overflow: 2, boxH: 200, center: true },
]

let failures = 0
const checks = []
const check = (msg, evidence, pass) => {
  if (!pass) failures += 1
  checks.push({ name: msg, evidence, pass })
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${msg} —— ${evidence}`)
}
const fmt = (v, d = 1) => (Number.isFinite(v) ? Number(v).toFixed(d) : String(v))

/**
 * 页内取目标：只序列化这一份函数的源码，故它必须自带全部实现、不引用模块作用域。
 * 分桶谓词与 `verify-label-fit-runtime.mjs` 的 WALK 逐条对齐（同一把尺的两端，口径不能分叉）。
 */
function collectInkTargets(arg) {
  const FLOOR = 27
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === arg.panelKey)
  if (panel === undefined || !panel.activeInHierarchy) return null
  const vis = window.cc.view.getVisibleSize()
  const can = window.cc.view.getCanvasSize()
  const stretched = []
  const shrunk = []
  let seen = 0
  const info = (n, lb, str) => {
    const ut = n.getComponent('cc.UITransform')
    const bb = ut.getBoundingBoxToWorld()
    // 字号是**本地**单位，盒子被面板缩放过 ⇒ 折到世界口径必须乘缩放比，否则倍数是错的
    const fontScale = ut.height > 0 ? bb.height / ut.height : 1
    let units = 0
    for (const ch of str) units += ch.charCodeAt(0) < 128 ? 0.55 : 1
    const est = Math.min(units * lb.fontSize * fontScale, bb.width)
    const align = lb.horizontalAlign
    const gx0 = align === 0 ? bb.x : (align === 2 ? bb.x + bb.width - est : bb.x + (bb.width - est) / 2)
    const chain = []
    for (let p = n; p !== null && chain.length < 3; p = p.parent) chain.push(p.name)
    return {
      text: str,
      path: chain.join('<'),
      fontSize: lb.fontSize,
      overflow: lb.overflow,
      fontScale,
      localBox: { w: Math.round(ut.width), h: Math.round(ut.height) },
      world: { x: bb.x, y: bb.y, width: bb.width, height: bb.height },
      glyph: { x: gx0, width: est },
    }
  }
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    const lb = n.getComponent('cc.Label')
    const str = lb ? (lb.string ?? '') : ''
    if (str.length > 0) {
      seen += 1
      if (lb.overflow === 2) {
        const h = Math.round(n.getComponent('cc.UITransform').height)
        if (h < FLOOR) shrunk.push(info(n, lb, str))
        else if (h > FLOOR + 8 && str.indexOf('\n') < 0 && lb.enableWrapText !== false) {
          // 横扫那条"估宽用不满盒"原式（0.95 系数也一并沿用）
          if (Math.round(str.length * lb.fontSize * 0.95) < n.getComponent('cc.UITransform').width) {
            stretched.push(info(n, lb, str))
          }
        }
      }
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return {
    vis: { width: vis.width, height: vis.height },
    can: { width: can.width, height: can.height },
    seen,
    stretched,
    shrunk,
  }
}

/**
 * 页内改探针：按文本找到那颗**看得见**的 Label，存下原样，再按 case 改一个变量。
 * 只序列化这一份函数的源码，故必须自带全部实现、不引用模块作用域。
 */
function probeInkLabel(arg) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === arg.panelKey)
  if (panel === undefined || !panel.activeInHierarchy) return { error: '面板不可见' }
  let node = null
  const find = (n) => {
    if (node !== null) return
    if (!n.activeInHierarchy) return
    const lb = n.getComponent('cc.Label')
    if (lb !== null && lb.string === arg.text) node = n
    for (const c of n.children) find(c)
  }
  find(panel)
  if (node === null) return { error: `这一屏没有文本恰为「${arg.text}」的可见 Label` }
  const lb = node.getComponent('cc.Label')
  const ut = node.getComponent('cc.UITransform')
  if (window.__inkProbe === undefined || window.__inkProbe === null) {
    const p = node.position
    window.__inkProbe = { node, fontSize: lb.fontSize, overflow: lb.overflow, enabled: lb.enabled, w: ut.width, h: ut.height, x: p.x, y: p.y, z: p.z }
  }
  if (arg.readOnly === true) return { saved: true }
  if (arg.center === true) {
    // 极端档要给到盒高 200：那颗按钮贴着屏幕上沿，盒子会伸出屏幕外，矩形被裁掉就量不准（实测偏移 104px）。
    // 挪到屏幕中央再量 —— 这是合成档，不是产品状态，还原时连位置一起写回。
    const visSize = window.cc.view.getVisibleSize()
    const put = node.parent.getComponent('cc.UITransform')
    const local = put.convertToNodeSpaceAR(new window.cc.Vec3(visSize.width / 2, visSize.height / 2, 0))
    node.setPosition(local)
  }
  lb.fontSize = arg.fontSize
  lb.overflow = arg.overflow
  // 盒宽保持原样（只给盒高这个旋钮），否则一次改两个变量就说不清是谁的影响
  ut.setContentSize(new window.cc.Size(arg.boxW > 0 ? arg.boxW : window.__inkProbe.w, arg.boxH))
  // 先关掉这颗字再截图当"改前"：不这么做的话差分会把**被擦掉的旧字形**并进来
  // （实测 12 号那档差出 30px = 原来那颗 20 号的脚印，虚高）
  lb.enabled = arg.enable !== false
  const bb = ut.getBoundingBoxToWorld()
  const vis = window.cc.view.getVisibleSize()
  const fontScale = ut.height > 0 ? bb.height / ut.height : 1
  const glyphW = arg.text.length * arg.fontSize * fontScale
  return {
    text: arg.text, fontSize: arg.fontSize, overflow: arg.overflow, probed: true,
    fontScale,
    localBox: { w: Math.round(ut.width), h: Math.round(ut.height) },
    world: { x: bb.x, y: bb.y, width: bb.width, height: bb.height },
    glyph: { x: bb.x + (bb.width - glyphW) / 2, width: glyphW },
    vis: { width: vis.width, height: vis.height },
    saved: (() => {
      const s = window.__inkProbe
      return { fontSize: s.fontSize, overflow: s.overflow, w: Math.round(s.w), h: Math.round(s.h) }
    })(),
  }
}

/** 页内还原：把存下来的原样写回去（含位置），返回还原后的真实属性（供复量核对）。 */
function restoreInkProbe() {
  const s = window.__inkProbe
  if (s === undefined || s === null) return { error: '没有探针可还原' }
  const lb = s.node.getComponent('cc.Label')
  const ut = s.node.getComponent('cc.UITransform')
  lb.fontSize = s.fontSize
  lb.overflow = s.overflow
  lb.enabled = s.enabled
  ut.setContentSize(new window.cc.Size(s.w, s.h))
  s.node.setPosition(new window.cc.Vec3(s.x, s.y, s.z))
  // 保留拥有的真实引用，后绘复量完成后才清理；恢复与复量均不能再依赖stretched桶。
  return { fontSize: lb.fontSize, overflow: lb.overflow, enabled: lb.enabled,
    w: ut.width, h: ut.height, position: { x: s.node.position.x, y: s.node.position.y, z: s.node.position.z } }
}

/** 页内把探针那颗字的显隐翻一下（差分法的"改前/改后"就是这一翻，别的都不动）。 */
function setProbeLabelVisible(v) {
  const s = window.__inkProbe
  if (s === undefined || s === null) return { error: '没有探针' }
  const lb = s.node.getComponent('cc.Label')
  lb.enabled = v === true
  return { enabled: lb.enabled }
}

/** 两个固定完成绘制帧里直接读取同一真实Label，不force update、不按成功提前停止。 */
function readInkProbeAfterDraw() {
  const director = window.cc?.director
  const event = window.cc?.Director?.EVENT_AFTER_DRAW
  const samples = []
  const timeoutMs = 5000
  const evidence = stable => ({ event: event ?? null, requiredFrames: 2, timeoutMs,
    frameIds: samples.map(sample => sample.frameId), stable, samples })
  if (!director || !event || typeof director.on !== 'function' || typeof director.off !== 'function'
    || typeof director.getTotalFrames !== 'function') return Promise.resolve({ error: '墨迹后绘事件或帧计数缺失', drawEvidence: evidence(false) })
  const capture = () => {
    const saved = window.__inkProbe
    const node = saved?.node
    const label = node?.getComponent('cc.Label')
    const ui = node?.getComponent('cc.UITransform')
    const scene = director.getScene()
    if (!node?.activeInHierarchy || !label || !ui) return { error: '墨迹真实Label引用缺失' }
    const box = ui.getBoundingBoxToWorld()
    const cameras = scene.getComponentsInChildren('cc.Camera')
    const renderer = node._uiProps.uiComp === label
    const cameraVisible = cameras.some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & node.layer) !== 0)
    if (!renderer || !cameraVisible) return { error: '墨迹真实Label不是首渲染组件或相机不可绘' }
    const str = label.string
    const fontScale = ui.height > 0 ? box.height / ui.height : 1
    let units = 0
    for (const ch of str) units += ch.charCodeAt(0) < 128 ? 0.55 : 1
    const est = Math.min(units * label.fontSize * fontScale, box.width)
    const align = label.horizontalAlign
    const gx0 = align === 0 ? box.x : align === 2 ? box.x + box.width - est : box.x + (box.width - est) / 2
    const target = { text: str, fontSize: label.fontSize, actualFontSize: label.actualFontSize,
      overflow: label.overflow, enabled: label.enabled, renderer, cameraVisible, fontScale,
      localBox: { w: ui.width, h: ui.height },
      position: { x: node.position.x, y: node.position.y, z: node.position.z },
      world: { x: box.x, y: box.y, width: box.width, height: box.height }, glyph: { x: gx0, width: est } }
    const vis = window.cc.view.getVisibleSize()
    return { target, vis: { width: vis.width, height: vis.height } }
  }
  return new Promise(resolve => {
    let timer = null, attached = false, completed = false
    const finish = error => {
      if (completed) return
      completed = true
      if (attached) director.off(event, onAfterDraw)
      clearTimeout(timer)
      resolve({ ...(samples.at(-1)?.snapshot ?? {}), ...(error ? { error } : {}), drawEvidence: evidence(!error) })
    }
    const onAfterDraw = () => {
      if (completed) return
      let snapshot, frameId = null
      try { snapshot = capture(); frameId = director.getTotalFrames() }
      catch (error) { snapshot = { error: `墨迹后绘读取失败：${error.message}` } }
      samples.push({ frameId, snapshot })
      if (samples.length !== 2) return
      const [a, b] = samples
      finish(a.snapshot.error ?? b.snapshot.error
        ?? (!Number.isSafeInteger(a.frameId) || a.frameId < 0 || b.frameId !== a.frameId + 1
          ? '墨迹不是连续两个完成绘制帧'
          : JSON.stringify(a.snapshot) !== JSON.stringify(b.snapshot) ? '墨迹真实属性或自身盒在两后绘帧间不稳定' : null))
    }
    timer = setTimeout(() => finish(`墨迹后绘超时：${samples.length}/2帧`), timeoutMs)
    try { attached = true; director.on(event, onAfterDraw) }
    catch (error) { finish(`墨迹后绘注册失败：${error.message}`) }
  })
}

/**
 * 一颗目标 → 一行读数。primary 只框字形横向范围（整盒当污染探针一起报）；
 * 墨迹在两个阈值（20 / 60）各量一次，报出抗锯齿带来的带宽。
 *
 * <p>给了 `baseImg`（旋钮档的"改前"那张）就走**差分法**：区域里叠着别人的字时，
 * 中位数法会把别人的字也算进带（实测极端放大档报出 11 条带、对位偏 50px），
 * 差分只认"变了的那部分"，也就是被旋钮动过的那颗字自己。
 */
function inkRow(img, vis, t, baseImg = null) {
  const { sx, sy } = pixelScaleOf(img, vis)
  const want = worldBoxToPixelRect(
    { x: t.glyph.x, y: t.world.y, width: t.glyph.width, height: t.world.height }, vis, img, 3)
  const strict = measureInkBand(img, want, { threshold: 60 })
  const loose = measureInkBand(img, want, { threshold: 20 })
  const diff = baseImg === null ? null : measureInkDiff(baseImg, img, want, { threshold: 20 })
  const picked = diff === null ? loose : diff
  const band = picked.band
  const fontWorld = t.fontSize * t.fontScale
  // 矩形被屏幕边界裁掉 ⇒ 这一档不可信（盒子伸出屏幕外时量到的可能是别人的字，实测偏移 104px）
  const clipped = picked.used.x !== want.x || picked.used.y !== want.y
    || picked.used.w !== want.w || picked.used.h !== want.h
  const method = diff === null ? 'median' : 'diff'
  return {
    text: t.text, path: t.path, overflow: t.overflow, fontSize: t.fontSize, fontScale: t.fontScale,
    method, fontWorld, localH: t.localBox.h, worldH: t.world.height,
    inkH: picked.inkH, inkStrictH: strict.inkH,
    inkMedianH: loose.inkH,
    inkWorld: designHeightOf(picked.inkH, sy), inkWorldStrict: designHeightOf(strict.inkH, sy),
    ratio: fontWorld === 0 ? NaN : designHeightOf(picked.inkH, sy) / fontWorld,
    ratioStrict: fontWorld === 0 ? NaN : designHeightOf(strict.inkH, sy) / fontWorld,
    boxRatio: fontWorld === 0 ? NaN : designHeightOf(
      measureInkBand(img, worldBoxToPixelRect(t.world, vis, img, 3), { threshold: 20 }).inkH, sy) / fontWorld,
    bands: picked.bands.length, peakCols: picked.peakCols, share: picked.share,
    ok: picked.ok, clipped, cols: picked.cols,
    untrust: clipped ? '裁边' : (diff === null && loose.share > 0.4 ? '占比' : undefined),
    bandTop: band === null ? null : band.top, bandBottom: band === null ? null : band.bottom,
    rect: picked.used, sx, sy,
  }
}

/** 植的是一颗几何已知的标签 ⇒ 墨迹中心必须落在预测中心附近，否则"倍数"是量错地方量出来的。 */
function alignOf(r, world, glyph) {
  if (r.bandTop === null || r.cols === null) return { dx: NaN, dy: NaN }
  const predCenterY = (r.vis.height - (world.y + world.height / 2)) * r.sy
  const inkCenterY = r.rect.y + (r.bandTop + r.bandBottom + 1) / 2
  return {
    dx: Math.abs((r.cols.x0 + r.cols.x1) / 2 - (glyph.x + glyph.width / 2) * r.sx),
    dy: Math.abs(inkCenterY - predCenterY),
  }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 墨迹高度尺：产物经 ${preview.origin}，后端 ${BACKEND}（只量不改、疑似放大那些只报不判红）===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `labelfit-${Date.now()}`)

/** 等到"这一屏真的画完了"：连续两次取样的颗数与分桶都不变才算稳；不稳也要拿到最后一次并印出取样序列。 */
async function settle(page, key) {
  const sigs = []
  let read = null
  let prev = null
  for (let i = 0; i < 8; i += 1) {
    const next = await page.evaluate(collectInkTargets, { panelKey: key })
    if (next === null) {
      sigs.push('null')
      await page.waitForTimeout(500)
      continue
    }
    read = next
    const sig = `${next.seen}|${next.stretched.length}|${next.shrunk.length}`
    sigs.push(sig)
    if (prev === sig) return { read, settled: true, sigs }
    prev = sig
    await page.waitForTimeout(500)
  }
  return read === null ? null : { read, settled: false, sigs }
}

const suspectRows = []
const controlRows = []
/** 面板名 → { page, vis }：探针要在"量过的那些页"上做，不能另开一页重新等它画出来。 */
const open = new Map()

for (const key of PANELS) {
  const page = await context.newPage()
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', key)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.evaluate(hideGuideBoard)
  const settled = await settle(page, key)
  if (settled === null) {
    console.log(`  SKIP  ${key}：这一格没画出来（深链没生效或面板名不是节点名）`)
    await page.close()
    continue
  }
  if (!settled.settled) {
    console.log(`  WARN  ${key}：取样序列 ${settled.sigs.join(' → ')} 没出现连续两次一致，`
      + '按最后一次读数继续（有倒计时之类的文字在变，分桶会跟着翻）')
  }
  const read = settled.read
  const img = decodePng(await page.screenshot())
  await page.screenshot({ path: path.join(OUT, `${key}.png`) })
  const sc = pixelScaleOf(img, read.vis)
  console.log(`  VIS   ${key}: 设计面 ${read.vis.width}x${read.vis.height}、画布 ${read.can.width}x${read.can.height}、`
    + `截图 ${img.width}x${img.height} → sx ${fmt(sc.sx, 3)} sy ${fmt(sc.sy, 3)}；Label ${read.seen} 颗，`
    + `疑似放大 ${read.stretched.length} 颗，被压小 ${read.shrunk.length} 颗`)
  for (const t of read.stretched) {
    const r = inkRow(img, read.vis, t)
    r.key = key
    suspectRows.push(r)
    console.log(`  INK   ${key}/${r.text.replace(/\n/g, '\\n')}  设定${r.fontSize}×缩放${fmt(r.fontScale, 2)}=${fmt(r.fontWorld)}`
      + ` 盒${r.localH}(世界${fmt(r.worldH)})  墨迹${r.inkH}px@60=${r.inkStrictH}px`
      + `  倍数${fmt(r.ratio, 2)}[严${fmt(r.ratioStrict, 2)}]  [整盒口径 ${fmt(r.boxRatio, 2)}]  ${r.path}`)
  }
  for (const t of read.shrunk) {
    const r = inkRow(img, read.vis, t)
    r.key = key
    controlRows.push(r)
    console.log(`  CTRL  ${key}/${r.text.replace(/\n/g, '\\n')}  设定${r.fontSize}×缩放${fmt(r.fontScale, 2)}=${fmt(r.fontWorld)}`
      + ` 盒${r.localH}  墨迹${r.inkH}px  倍数${fmt(r.ratio, 2)}  ${r.path}`)
  }
  open.set(key, { page, vis: read.vis })
}

/* -------------------------------------- 尺子自检：原地旋钮探针（含零点标定与还原自证） */
console.log('--- 尺子自检（这几条判红，疑似放大那些不判）---')
const probeRows = []
let probeRestored = { back: null, restored: null, diffShare: NaN, original: null, propertiesMatch: false, originalSnapshot: null, restoredSnapshot: null }
let syntheticReverse = null
const drawReads = []
const host = open.get(PROBE.panelKey)
const captureProbe = async () => {
  const read = await host.page.evaluate(readInkProbeAfterDraw)
  drawReads.push(read)
  if (read.error) throw new Error(read.error)
  return read
}
const measureCase = async (p, screenshotName) => {
  const changed = await host.page.evaluate(probeInkLabel,
    { panelKey: PROBE.panelKey, text: PROBE.text, fontSize: p.fontSize, overflow: p.overflow,
      boxH: p.boxH, center: true, enable: false })
  if (!changed?.probed || changed.error) throw new Error(`旋钮失败：${JSON.stringify(changed)}`)
  await captureProbe()
  const off = decodePng(await host.page.screenshot())
  const flip = await host.page.evaluate(setProbeLabelVisible, true)
  const painted = await captureProbe()
  const got = painted.target
  const img = decodePng(await host.page.screenshot({ path: path.join(OUT, screenshotName) }))
  const r = inkRow(img, painted.vis, got, off)
  const a = alignOf({ ...r, vis: painted.vis }, got.world, got.glyph)
  return { ...r, name: p.name, dx: a.dx, dy: a.dy, flipped: flip?.enabled === true, drawEvidence: painted.drawEvidence }
}
if (host === undefined) {
  check(`探针要挂在画出来的那一屏（${PROBE.panelKey}）上`, '这一格没画出来', false)
} else {
  let originalPaint = null, originalImg = null, originalRow = null, originalSnapshot = null
  try {
    const saved = await host.page.evaluate(probeInkLabel, { panelKey: PROBE.panelKey, text: PROBE.text, readOnly: true })
    if (!saved?.saved) throw new Error(`真实原样前提失败：${JSON.stringify(saved)}`)
    const original = await captureProbe()
    originalPaint = original.target
    originalSnapshot = { target: original.target, vis: original.vis }
    if (!originalPaint.enabled) throw new Error('墨迹原样Label未启用')
    originalImg = decodePng(await host.page.screenshot({ path: path.join(OUT, 'probe-original-before.png') }))
    originalRow = inkRow(originalImg, original.vis, originalPaint)
    for (const p of PROBE_CASES) {
      try {
        const r = await measureCase(p, `probe-${p.name}.png`)
        probeRows.push(r)
        console.log(`  PROBE ${p.name}: 设定${r.fontSize}×缩放${fmt(r.fontScale, 2)}=${fmt(r.fontWorld)}`
          + ` 盒${r.localH}  墨迹${r.inkH}px(${r.method}) = ${fmt(r.inkWorld)}号`
          + ` 倍数${fmt(r.ratio, 3)} 对位Δx${fmt(r.dx)} Δy${fmt(r.dy)}`
          + ` [${r.rect.x},${r.rect.y} ${r.rect.w}x${r.rect.h}]`
          + (r.untrust === undefined ? '' : ` 不可信(${r.untrust})`))
      } catch (error) { check(`旋钮「${p.name}」拧得动`, String(error), false) }
    }
    // 独立完成一次固定小盒真实画字，不依赖已不存在的产品Lv文案或shrunk桶。
    syntheticReverse = await measureCase({ name: '独立反向对照(12/SHRINK/14)', fontSize: 12, overflow: 2, boxH: 14 }, 'probe-independent-reverse.png')
  } catch (error) {
    check('真实墨迹原样、标定与独立反向前提完整', String(error), false)
  } finally {
    try {
      const back = await host.page.evaluate(restoreInkProbe)
      if (back?.error) throw new Error(back.error)
      const painted = await captureProbe()
      const img = decodePng(await host.page.screenshot({ path: path.join(OUT, 'probe-original-restored.png') }))
      const restored = inkRow(img, painted.vis, painted.target)
      const diffShare = originalImg && originalRow ? measureInkDiff(originalImg, img, originalRow.rect, { threshold: 20 }).share : NaN
      const restoredSnapshot = { target: painted.target, vis: painted.vis }
      const propertiesMatch = originalSnapshot !== null && JSON.stringify(originalSnapshot) === JSON.stringify(restoredSnapshot)
      probeRestored = { back, restored, diffShare, original: originalRow, propertiesMatch, originalSnapshot, restoredSnapshot }
      console.log(`  RESTORE 写回 ${JSON.stringify(back)}；原样自身区域改变 ${fmt(diffShare * 100, 2)}% 像元；属性一致=${propertiesMatch}`)
    } catch (error) { check('旋钮finally真实恢复与复量完成', String(error), false) }
    finally {
      // 幂等恢复再清理本量具拥有的引用；复量抛错也不能留下临时字号/位置/禁用状态。
      try { await host.page.evaluate(() => {
        const s = window.__inkProbe
        if (s) {
          const lb = s.node.getComponent('cc.Label'), ut = s.node.getComponent('cc.UITransform')
          lb.fontSize = s.fontSize; lb.overflow = s.overflow; lb.enabled = s.enabled
          ut.setContentSize(new window.cc.Size(s.w, s.h)); s.node.setPosition(new window.cc.Vec3(s.x, s.y, s.z))
          delete window.__inkProbe
        }
      }) } catch (error) { check('旋钮finally二次恢复与owned引用清理成功', String(error), false) }
    }
  }
}

const byName = (n) => probeRows.find((r) => r.name === n)
const base = byName('基线(设定20/SHRINK/盒76)')
const shrinkBig = byName('字12/SHRINK/盒100')
const noneBig = byName('字12/NONE/盒100')
const noneBig20 = byName('字20/NONE/盒100')
const shrinkSmall = byName('字12/SHRINK/盒14')
const box36 = byName('字20/SHRINK/盒36(#366场景)')
const shrinkHuge = byName('字12/SHRINK/盒200')

/** 只有"量得到 + 没被裁边 + 背景可信"的档才配进判据；不可信的档必须显式标注，不许静默当读数。 */
const okRow = (r) => r !== undefined && r.inkH > 0 && r.untrust === undefined

check('七档旋钮都拧得动、显隐翻得动且差分量得到自己的字（任何一环失灵＝这一节全部不作数）',
  probeRows.map((r) => `${r.name}:${r.inkH}px${r.flipped ? '' : '(没翻亮)'}${r.untrust === undefined ? '' : `(${r.untrust})`}`).join(' ') || '一个都没有',
  probeRows.length === PROBE_CASES.length
    && probeRows.every((r) => r.inkH > 0 && r.flipped === true))
check('可信档的对位都在 6px 内（原点/翻转/缩放任一处算错，这里先红，倍数就不必看了）',
  probeRows.map((r) => `${r.name.slice(0, 2)} Δx${fmt(r.dx)}/Δy${fmt(r.dy)}${r.untrust === undefined ? '' : '!'}`).join(' '),
  probeRows.length === PROBE_CASES.length
    && probeRows.filter((r) => r.untrust === undefined).length >= 5
    && probeRows.every((r) => r.untrust !== undefined || (r.dx <= 6 && r.dy <= 6)))
// 尺子灵敏度：同字号同文字，盒 14 装不下的那一档必须明显小于 NONE 那一档。
// 这条**不依赖"SHRINK 会不会放大"这个待查前提**，所以它是尺子自身的活体检查（拿放大当活体检查会把自己钉死）。
if (okRow(noneBig) && okRow(shrinkSmall)) {
  check('尺子看得见落地尺寸的变化：盒14 那档要不大于 NONE 档的 2/3（看不见 2 倍差就是死尺）',
    `盒14 ${shrinkSmall.inkH}px vs NONE盒100 ${noneBig.inkH}px = ${fmt(shrinkSmall.inkH / noneBig.inkH, 2)} 倍`,
    shrinkSmall.inkH <= noneBig.inkH * 0.75)
} else {
  check('尺子灵敏度两档都可信（有一档被裁边/占比污染 → 这一节不作数）',
    `盒14 ${shrinkSmall === undefined ? '无' : `${shrinkSmall.inkH}px/${shrinkSmall.untrust ?? 'ok'}`} `
    + `NONE ${noneBig === undefined ? '无' : `${noneBig.inkH}px/${noneBig.untrust ?? 'ok'}`}`, false)
}
if (okRow(noneBig) && okRow(noneBig20)) {
  check('零点标定：overflow=NONE 的墨迹要等于设定字号（±25% 内，超出就是尺子或换算错了）',
    `字12/NONE ${fmt(noneBig.inkWorld)} 号 vs 设定 ${fmt(noneBig.fontWorld)}；字20/NONE ${fmt(noneBig20.inkWorld)} 号 vs 设定 ${fmt(noneBig20.fontWorld)}`,
    Math.abs(noneBig.inkWorld - noneBig.fontWorld) <= Math.max(3, noneBig.fontWorld * 0.25)
      && Math.abs(noneBig20.inkWorld - noneBig20.fontWorld) <= Math.max(3, noneBig20.fontWorld * 0.25))
} else {
  check('零点标定两档都可信（NONE 档被污染 → 这一节不作数）',
    `字12/NONE ${noneBig === undefined ? '无' : `${noneBig.inkH}px/${noneBig.untrust ?? 'ok'}`} `
    + `字20/NONE ${noneBig20 === undefined ? '无' : `${noneBig20.inkH}px/${noneBig20.untrust ?? 'ok'}`}`, false)
}
if (okRow(shrinkBig) && okRow(shrinkHuge)) {
  // 单调性：盒子从 100 给到 200，落地墨迹不可能变小；量出变小就是尺子在说谎
  check('单调性：盒 100→200（同字号 SHRINK）墨迹不许变短（量具方向反了立刻红）',
    `盒100 ${shrinkBig.inkH}px → 盒200 ${shrinkHuge.inkH}px`,
    shrinkHuge.inkH >= shrinkBig.inkH)
}
if (okRow(shrinkBig) && okRow(noneBig) && okRow(box36) && okRow(noneBig20)) {
  const g12 = shrinkBig.inkH - noneBig.inkH
  const g20 = box36.inkH - noneBig20.inkH
  // 放大这一维只报数：它是产品问题不是尺子问题，判不判红等用户拍板（口径见文件头）
  console.log(`  NOTE  放大实测：12 号给盒 100 → ${shrinkBig.inkH}px vs NONE ${noneBig.inkH}px（差 ${g12}px = ${fmt(g12 / noneBig.inkH * 100)}%）；`
    + `20 号给盒 36（#366 场景）→ ${box36.inkH}px vs NONE ${noneBig20.inkH}px（差 ${g20}px = ${fmt(g20 / noneBig20.inkH * 100)}%）；`
    + `12 号给盒 200 → ${shrinkHuge === undefined ? '?' : `${shrinkHuge.inkH}px`}`
    + ` = ${shrinkHuge === undefined ? '?' : fmt(shrinkHuge.inkWorld)} 号（${shrinkHuge !== undefined && okRow(shrinkHuge) ? `设定 12 的 ${fmt(shrinkHuge.inkWorld / 12, 2)} 倍` : `档不可信:${shrinkHuge === undefined ? '无' : shrinkHuge.untrust}`}）`)
}
check('独立小盒反向对照：同颗真实字的墨迹要小于设定字号',
  syntheticReverse === null ? '独立12/SHRINK/14真实反向档拿不到'
    : `实测 ${fmt(syntheticReverse.inkWorld)} 号 vs 设定 ${fmt(syntheticReverse.fontWorld)}；对位Δx${fmt(syntheticReverse.dx)}/Δy${fmt(syntheticReverse.dy)}`,
  syntheticReverse !== null && okRow(syntheticReverse) && syntheticReverse.flipped
    && syntheticReverse.dx <= 6 && syntheticReverse.dy <= 6 && syntheticReverse.inkWorld < syntheticReverse.fontWorld)

const dirty = [...suspectRows, ...controlRows].filter((r) => r.untrust !== undefined)
check('被量的那些产品标签都要干净（裁边或占比高就是量具没框住那颗字，读数不作数）',
  dirty.length === 0 ? `${suspectRows.length + controlRows.length} 颗全部未裁边且占比 < 0.4`
    : dirty.map((r) => `${r.text}:${r.untrust}`).join(' '),
  dirty.length === 0)
check('旋钮还原后必须回到改前的像素状态（复量读数一样不算数，要像素一字不变）',
  JSON.stringify(probeRestored),
  probeRestored.original !== null && probeRestored.restored !== null
    && probeRestored.restored.inkH === probeRestored.original.inkH
    && probeRestored.diffShare < 0.005 && probeRestored.propertiesMatch)

/* ------------------------------------------------------- 标定后的落地字号与报数表 */
const cover12 = noneBig === undefined ? NaN : noneBig.inkWorld / noneBig.fontWorld
const cover20 = noneBig20 === undefined ? NaN : noneBig20.inkWorld / noneBig20.fontWorld
console.log('--- 零点标定（同一颗字改 overflow=NONE：汉字墨迹对 em 的占比）---')
console.log(`  12 号 NONE：墨迹 ${noneBig === undefined ? '?' : fmt(noneBig.inkWorld)} 号 → 占比 ${fmt(cover12, 3)}`)
console.log(`  20 号 NONE：墨迹 ${noneBig20 === undefined ? '?' : fmt(noneBig20.inkWorld)} 号 → 占比 ${fmt(cover20, 3)}`)
const coverageOf = (fontSize) => (fontSize <= 12 ? cover12 : cover20)

console.log('--- 疑似被盒子放大的实测（只报不判红，口径待拍板）---')
console.log('| 那颗字 | 设定字号 | 盒高 | 墨迹折回设定单位 | 标定后落地字号 | 落地/设定 | 严阈值墨迹 | 路径 |')
for (const r of suspectRows) {
  const cov = coverageOf(r.fontSize)
  const landed = Number.isFinite(cov) && cov > 0 ? r.inkWorld / cov : NaN
  r.landed = landed
  r.enlarge = Number.isFinite(landed) && r.fontWorld > 0 ? landed / r.fontWorld : NaN
  console.log(`| ${r.key}/${r.text.replace(/\n/g, '\\n')} | ${r.fontSize} | ${r.localH} | ${fmt(r.inkWorld)}`
    + ` | ${fmt(landed)} | ${fmt(r.enlarge, 2)} | ${fmt(r.inkWorldStrict)} | ${r.path} |`)
}
console.log(`=== 墨迹读数：疑似放大 ${suspectRows.length} 颗、反向对照 ${controlRows.length} 颗、旋钮 ${probeRows.length} 档；`
  + `尺子自检 ${failures === 0 ? '全绿' : `${failures} 条红`} ===`)

writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ checks, failures, suspectRows, controlRows, probeRows, syntheticReverse, probeRestored, drawReads }, null, 2))
await browser.close()
preview.assertRewritten()
await preview.close()
process.exit(failures === 0 ? 0 : 1)
