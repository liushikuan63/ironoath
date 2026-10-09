import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { dialogLayout, DIALOG_COMPACT_INSET } from '../assets/scripts/game/ui/DialogLayout'

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

test('内容的宽高与80·72铜边分别计账，空态小窗也不会把字塞入边带', () => {
  const area = { x: -480, y: -260, width: 960, height: 580 }
  const layout = dialogLayout(area, 560, 152)
  assert.equal(layout.width, 720)
  assert.equal(layout.height, 296)
  assert.equal(layout.innerTop - layout.innerBottom, 152)
  assert.equal(layout.viewportHeight, 100)
  assert.ok(144 / layout.height < 0.6)
})

test('窄净区限制外框宽度，内宽由同一份切分边厚扣除', () => {
  const layout = dialogLayout({ x: -360, y: -220, width: 720, height: 440 }, 760, 1200)
  assert.equal(layout.width, 696)
  assert.equal(layout.innerWidth, 536)
  assert.equal(layout.height, 424)
})
