import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { dialogBodyFlow, dialogLayout, DIALOG_COMPACT_INSET } from '../assets/scripts/game/ui/DialogLayout'

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
