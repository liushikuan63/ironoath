import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { dialogBodyFlow, dialogLayout, DIALOG_COMPACT_INSET, safeAreaInsets } from '../assets/scripts/game/ui/DialogLayout'

test('普通与矮窗的长名单只增长滚动内容，框和固定操作仍在真实净区', () => {
  for (const height of [640, 540, 320]) {
    const area = { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 }
    const layout = dialogLayout(area, 560, 1600)
    assert.ok(layout.centerY - layout.height / 2 >= area.y)
    assert.ok(layout.centerY + layout.height / 2 <= area.y + area.height)
    assert.equal(layout.innerWidth, 560)
    assert.ok(layout.footerY - 18 >= layout.innerBottom)
    assert.ok(layout.footerY + 18 < layout.innerTop)
    assert.ok(layout.innerTop - layout.viewportHeight >= layout.footerY + 22)
    assert.ok(layout.viewportHeight > 30)
  }
})

test('240逻辑高用token薄边兜底，确认与正文不碰撞且不跨档拉伸提示位图', () => {
  const layout = dialogLayout({ x: -480, y: -60, width: 960, height: 180 }, 560, 1600)
  assert.equal(layout.compact, true)
  assert.equal(layout.height, 164)
  assert.ok(layout.viewportHeight >= 100)
  assert.ok(layout.footerY - 18 >= layout.innerBottom)
  assert.ok(layout.innerTop - layout.viewportHeight >= layout.footerY + 22)
  assert.deepEqual(DIALOG_COMPACT_INSET, { left: 6, right: 6, top: 6, bottom: 6 })
  const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets'))
    ? path.resolve(process.cwd(), 'client') : process.cwd()
  for (const file of ['DialogStyle.ts', 'NationPanelView.ts']) {
    const source = readFileSync(path.join(clientDir, 'assets/scripts/scene', file), 'utf8')
    assert.doesNotMatch(source, /ui\.plate\.tooltip/)
    assert.match(source, /paintCompactDialogFrame\(/)
  }
})

test('普通大净区中的短正文保留位图框，只有真实可用高不足才切矮屏兜底', () => {
  const roomy = dialogLayout({ x: -480, y: -420, width: 960, height: 840 }, 560, 80)
  assert.equal(roomy.compact, false)
  assert.ok(roomy.height < 240, '短正文自然框高已低于旧判档阈值，不能让正文长短选择框材质')
  assert.ok(roomy.viewportHeight > 0)
  assert.ok(roomy.footerY - 18 >= roomy.innerBottom)
  assert.ok(roomy.innerTop - roomy.viewportHeight >= roomy.footerY + 22)
  const cramped = dialogLayout({ x: -480, y: -60, width: 960, height: 180 }, 560, 80)
  assert.equal(cramped.compact, true)
  assert.ok(cramped.footerY - 18 >= cramped.innerBottom)
  assert.ok(cramped.innerTop - cramped.viewportHeight >= cramped.footerY + 22)
})

test('真实引擎包同时启用Mask与UI，生产滚动层直接消费Mask而非只在类型桩声明', () => {
  const clientDir = existsSync(path.resolve(process.cwd(), 'client/settings'))
    ? path.resolve(process.cwd(), 'client') : process.cwd()
  const config = JSON.parse(readFileSync(path.join(clientDir, 'settings/v2/packages/engine.json'), 'utf8'))
  const selected = config.modules.configs.defaultConfig
  assert.equal(selected.cache.mask._value, true)
  assert.equal(selected.cache.ui._value, true)
  assert.ok(selected.includeModules.includes('mask'))
  assert.ok(selected.includeModules.includes('ui'))
  const source = readFileSync(path.join(clientDir, 'assets/scripts/scene/DialogStyle.ts'), 'utf8')
  assert.match(source, /import \{[^}]*\bMask\b[^}]*\} from 'cc'/)
  assert.match(source, /viewport\.addComponent\(Mask\)\.type = Mask\.Type\.GRAPHICS_RECT/)
})

test('采用版四边24独立于正文计账，短窗完整保留152正文净高与100滚动高', () => {
  const area = { x: -480, y: -260, width: 960, height: 580 }
  const layout = dialogLayout(area, 560, 152)
  assert.equal(layout.width, 608)
  assert.equal(layout.height, 200)
  assert.equal(layout.innerTop - layout.innerBottom, 152)
  assert.equal(layout.viewportHeight, 100)
  assert.ok(48 / layout.height < 0.6)
  assert.equal(layout.compact, false)
})

test('窄净区限制外框宽度，内宽由同一份切分边厚扣除', () => {
  const layout = dialogLayout({ x: -360, y: -220, width: 720, height: 440 }, 760, 1200)
  assert.equal(layout.width, 696)
  assert.equal(layout.innerWidth, 648)
  assert.equal(layout.height, 424)
})

test('真实长标题与坐标后才排上锚条行，隐藏条目不占空间，末项说明保留', () => {
  const boxes = [
    { height: 92, anchorY: 0.5, visible: true },
    { height: 26.46, anchorY: 0.5, visible: true },
    { height: 44, anchorY: 1, visible: true },
    { height: 44, anchorY: 1, visible: false },
    { height: 52, anchorY: 0.5, visible: true },
  ]
  const flow = dialogBodyFlow(boxes)
  assert.equal(flow.positions[0], -46)
  assert.equal(flow.positions[2], -130.46)
  assert.equal(flow.height, 232.46)
  const visible = boxes.map((box, index) => ({ ...box, y: flow.positions[index]! })).filter(box => box.visible)
  for (let index = 1; index < visible.length; index++) {
    const above = visible[index - 1]!, below = visible[index]!
    assert.ok(below.y + below.height * (1 - below.anchorY) <= above.y - above.height * above.anchorY - 6 + 1e-6)
  }
  const compact = dialogLayout({ x: -480, y: -60, width: 960, height: 180 }, 460, flow.height)
  assert.ok(flow.height > compact.viewportHeight)
  assert.equal(dialogBodyFlow([]).height, 0)
})

test('微信原生UI安全矩形直接得到刘海与手势边距，不重复DPR或设计缩放', () => {
  const width = 960, height = 443.6018957345971
  const rect = { x: 53.459715639810426, y: 23.886255924170616,
    width: 853.0805687203791, height: 419.7156398104265 }
  const safe = safeAreaInsets(width, height, rect)
  assert.ok(Math.abs(safe.left - 53.459715639810426) < 1e-7)
  assert.ok(Math.abs(safe.right - 53.459715639810426) < 1e-7)
  assert.ok(Math.abs(safe.bottom - 23.886255924170616) < 1e-7)
  assert.ok(Math.abs(safe.top) < 1e-7)
  assert.ok(Math.abs(width - safe.left - safe.right - rect.width) < 1e-7)
  assert.ok(Math.abs(height - safe.top - safe.bottom - rect.height) < 1e-7)
})

test('非对称安全边缘不把左刘海复制到右侧或把底部手势区复制到顶部', () => {
  assert.deepEqual(safeAreaInsets(960, 640, { x: 40, y: 18, width: 910, height: 592 }),
    { left: 40, right: 10, top: 30, bottom: 18 })
})

test('Web和无安全矩形的600/640视口保持零边距，完整UI矩形同样为零', () => {
  for (const height of [600, 640]) {
    assert.deepEqual(safeAreaInsets(960, height, null), { left: 0, right: 0, top: 0, bottom: 0 })
    assert.deepEqual(safeAreaInsets(960, height, { x: 0, y: 0, width: 960, height }),
      { left: 0, right: 0, top: 0, bottom: 0 })
  }
})

test('安全矩形越出视口时只取真实交集，不生成负边距', () => {
  assert.deepEqual(safeAreaInsets(960, 640, { x: -20, y: -10, width: 1000, height: 670 }),
    { left: 0, right: 0, top: 0, bottom: 0 })
  assert.deepEqual(safeAreaInsets(960, 640, { x: -20, y: 15, width: 950, height: 610 }),
    { left: 0, right: 30, top: 15, bottom: 15 })
})

test('空、非有限或完全不可交的安全矩形回退全视口，不能把HUD挤成负净宽', () => {
  for (const rect of [
    { x: NaN, y: 0, width: 960, height: 640 },
    { x: 0, y: Infinity, width: 960, height: 640 },
    { x: 0, y: 0, width: 0, height: 640 },
    { x: 0, y: 0, width: 960, height: -1 },
    { x: 1000, y: 0, width: 960, height: 640 },
    { x: 0, y: -700, width: 960, height: 640 },
  ]) {
    assert.deepEqual(safeAreaInsets(960, 640, rect), { left: 0, right: 0, top: 0, bottom: 0 })
  }
  assert.deepEqual(safeAreaInsets(0, 640, null), { left: 0, right: 0, top: 0, bottom: 0 })
})

test('真实刘海负控：旧资源与选中标题坐标在安全区外，平移后整盒位于安全区', () => {
  const safe = safeAreaInsets(960, 443.6018957345971,
    { x: 53.459715639810426, y: 23.886255924170616, width: 853.0805687203791, height: 419.7156398104265 })
  const inside = (x: number, width: number) => x >= safe.left && x + width <= 960 - safe.right
  assert.equal(inside(22, 155), false, '旧资源首列确实被左刘海吞掉')
  assert.equal(inside(12, 300), false, '旧主城详情标题确实被左刘海吞掉')
  assert.equal(inside(safe.left + 22, 155), true)
  assert.equal(inside(safe.left + 12, 300), true)
})
