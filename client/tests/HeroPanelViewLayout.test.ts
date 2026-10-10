/** 运行真实HeroPanelView/NodePool/DialogStyle方法；字体排版桩不代表Cocos或原生GPU验收。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { buildHeroPanel, buildHeroRow } from '../assets/scripts/game/hero/HeroPanel'
import type { HeroRow, LineupPanel } from '../assets/scripts/game/hero/HeroPanel'
import type { HeroListResp, HeroView } from '../assets/scripts/net/generated/HeroProtocol'
import { dialogLayout, dialogBodyFlow } from '../assets/scripts/game/ui/DialogLayout'
import { PANEL_IRON_INSET } from '../assets/scripts/game/art/ArtFamilies'
import * as tokens from '../assets/scripts/game/ui/UiTokens'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Vec2 { constructor(public x = 0, public y = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = .5; anchorY = .5
  setContentSize(s: Size | number, h?: number): void { this.width = typeof s === 'number' ? s : s.width; this.height = typeof s === 'number' ? h! : s.height }
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
}
class Label {
  static Overflow = { NONE: 0, CLAMP: 1, SHRINK: 2, RESIZE_HEIGHT: 3 }
  static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }; static VerticalAlign = { TOP: 0, CENTER: 1 }
  node!: Node; string = ''; fontSize = 40; lineHeight = 40; actualFontSize = 40
  color: unknown; overflow = 0; horizontalAlign = 0; verticalAlign = 0; enableWrapText = true
  parsedString: string[] = []
  updateRenderData(): void {
    const box = this.node.getComponent(UITransform)!
    // Conservative synthetic glyph metrics make long copy wrap. Actual font/Cocos AFTER_DRAW is a separate acceptance.
    const measure = (copy: string): number => [...copy].reduce((sum, ch) => sum + this.fontSize * (ch.charCodeAt(0) > 255 ? 1 : .6), 0)
    if (this.overflow === Label.Overflow.RESIZE_HEIGHT) {
      const lines: string[] = []; let row = ''
      for (const ch of this.string) { if (row.length > 0 && measure(row + ch) > box.width) { lines.push(row); row = '' } row += ch }
      lines.push(row); this.parsedString = lines; box.height = lines.length * this.lineHeight; this.actualFontSize = this.fontSize
    } else {
      this.parsedString = [this.string]
      this.actualFontSize = this.overflow === Label.Overflow.SHRINK
        ? Math.floor(this.fontSize * Math.min(1, box.height / this.lineHeight, box.width / Math.max(1, measure(this.string)))) : this.fontSize
    }
  }
}
class Graphics {
  enabled = true; fillColor: unknown; strokeColor: unknown; lineWidth = 0
  clear(): void {} rect(..._r: number[]): void {} roundRect(..._r: number[]): void {} fill(): void {} stroke(): void {}
}
class Sprite { enabled = true; spriteFrame: unknown = null }
class Mask { static Type = { GRAPHICS_RECT: 0 }; type = 0 }
class ScrollView {
  node!: Node; content: Node | null = null; horizontal = true; vertical = false; inertia = false; elastic = true; cancelInnerEvents = false
  private offset = new Vec2()
  getScrollOffset(): Vec2 { return this.offset }
  scrollToTop(): void { this.scrollToOffset(new Vec2()) }
  scrollToOffset(v: Vec2): void { this.offset = v; this.content?.setPosition(new Vec3(0, this.node.getComponent(UITransform)!.height / 2 + v.y, 0)) }
  scrollToBottom(): void { this.scrollToOffset(new Vec2(0, Math.max(0, this.content!.getComponent(UITransform)!.height - this.node.getComponent(UITransform)!.height))) }
}
class Node {
  parent: Node | null = null; layer = 1; active = true; position = new Vec3(); readonly children: Node[] = []
  readonly components = new Map<unknown, unknown>(); private readonly events = new Map<string, Array<(...args: unknown[]) => void>>()
  constructor(readonly name: string) {}
  addChild(n: Node): void { n.parent = this; this.children.push(n) }
  addComponent<T extends object>(type: new () => T): T { const c = new type(); Object.assign(c, { node: this }); this.components.set(type, c); return c }
  getComponent<T>(type: (new () => T) | string): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(n => n.name === name) ?? null }
  setPosition(v: Vec3): void { this.position = v }
  on(type: string, fn: (...args: unknown[]) => void): void { this.events.set(type, [...this.events.get(type) ?? [], fn]) }
  off(type: string): void { this.events.delete(type) }
  hasEventListener(type: string): boolean { return (this.events.get(type)?.length ?? 0) > 0 }
  emit(type: string): void { this.events.get(type)?.forEach(fn => fn()) }
  removeFromParent(): void { this.parent?.children.splice(this.parent.children.indexOf(this), 1); this.parent = null }
  destroy(): void { this.removeFromParent() }
}
class Component { node = new Node('host'); isValid = true }
class Color { constructor(..._v: number[]) {} }
function exportsFor(name: string, extra: Record<string, unknown>): Record<string, unknown> {
  const sourcePath = name === 'HeroPanelView' && process.env.IRONOATH_HERO_LAYOUT_SOURCE_FILE
    ? process.env.IRONOATH_HERO_LAYOUT_SOURCE_FILE : path.join(process.cwd(), 'assets/scripts/scene', name + '.ts')
  const source = readFileSync(sourcePath, 'utf8')
  const ast = ts.createSourceFile(name + '.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (k: unknown) => k }, Component, Color, Node, Size, Vec2, Vec3, UITransform, Label, Graphics, Sprite, Mask, ScrollView, ...extra })
  return exports
}
interface Draft { lines: string[]; titleColor: Color; iconKey: null; heroId: string | null; presetIndex: number | null }
interface HeroHarness {
  node: Node; drawnRows: Node[]; rowHeroes: Map<Node, string>; onHeroAction: ((id: string, action: string) => void) | null
  onLineupEdit: ((preset: number) => void) | null; onLoad(): void; onDestroy(): void; attach(resp: HeroListResp): void; switchTab(tab: string): void
  createRow(): Node; renderRow(node: Node, data: Draft, index: number): void; render(): void
  panel: { heroes: HeroRow[]; lineups: LineupPanel[]; troopCapText: string }
}
const COMMON = ['R 贺兰迟 · Lv1/100 · ★☆☆☆☆ · 觉醒 0/1', '兰迟突 Lv1/10｜顽抗 Lv1/10（仅副将位生效）',
  '武力 52→57 统率 55→60 智力 58→63 · 战力 180', '武器：空 护甲：空 坐骑：空 饰品：空 · 缘分：未知武将 · 经验 0/20']
function commonHero(id = 'owned'): HeroRow {
  return { heroId: id, name: '贺兰迟', rarity: 'R', levelText: 'Lv1/100', starText: '★☆☆☆☆', awakenText: '觉醒 0/1',
    mainSkillText: '兰迟突 Lv1/10', subSkillText: '顽抗 Lv1/10（仅副将位生效）', attrText: '武力 52→57 统率 55→60 智力 58→63', powerText: '战力 180',
    equipTexts: ['武器：空', '护甲：空', '坐骑：空', '饰品：空'], emptyEquipSlots: 4, bondText: '缘分：未知武将', expText: '经验 0/20' }
}
function setup(): { hero: HeroHarness; size: { width: number; height: number }; resize(): void } {
  const size = { width: 960, height: 640 }
  const pool = exportsFor('NodePool', {}).NodePool
  const styles = exportsFor('DialogStyle', { view: { getVisibleSize: () => size }, ...tokens, PANEL_IRON_INSET, dialogLayout, dialogBodyFlow })
  const Hero = exportsFor('HeroPanelView', { view: { getVisibleSize: () => size }, NodePool: pool, buildHeroPanel, ...styles,
    applySystemUiFont: (l: Label) => l, applyCommandButton: () => true, applyAnyIconSprite: () => false,
    ensureFamily: async () => 0, heroPortraitKey: () => null, rarityIconKey: () => null }).HeroPanelView as new () => HeroHarness
  const hero = new Hero()
  hero.node.components.set('PanelNav', { current: () => 'hero', contentRectFor: () => ({ x: -480, y: -size.height / 2 + 64, width: 960, height: size.height - 64 }) })
  return { hero, size, resize: () => { for (const c of hero.node.components.values()) (c as { update?: () => void }).update?.() } }
}
function row(hero: HeroHarness, lines = COMMON, id = 'owned'): Node {
  const node = hero.createRow(); hero.node.addChild(node); hero.renderRow(node, { lines, titleColor: new Color(), iconKey: null, heroId: id, presetIndex: null }, 0); return node
}
function own(node: Node): { left: number; right: number; top: number; bottom: number } {
  const ui = node.getComponent(UITransform)!; let x = node.position.x, y = node.position.y
  for (let p = node.parent; p; p = p.parent) { x += p.position.x; y += p.position.y }
  return { left: x - ui.anchorX * ui.width, right: x + (1 - ui.anchorX) * ui.width, bottom: y - ui.anchorY * ui.height, top: y + (1 - ui.anchorY) * ui.height }
}
function fit(node: Node): void {
  const labels = node.children.slice(0, 4).map(n => n.getComponent(Label)!)
  for (const [i, label] of labels.entries()) {
    assert.equal(label.string, COMMON[i]); assert.equal(label.overflow, Label.Overflow.RESIZE_HEIGHT)
    label.updateRenderData(); assert.equal(label.actualFontSize, label.fontSize); assert.equal(label.parsedString.join(''), label.string)
    assert.ok(label.lineHeight >= label.fontSize && label.lineHeight < 40)
    const box = own(label.node), parent = own(node); assert.ok(box.left >= parent.left && box.right <= parent.right && box.top <= parent.top && box.bottom >= parent.bottom)
  }
}
test('正文与54宽肖像保持至少12像素净隙，完整长文自身盒仍守住右侧16像素边距', () => {
  const { hero } = setup()
  for (const copy of [COMMON, COMMON.map(s => s + ' · ' + s)]) {
    const n = row(hero, copy), portrait = own(n.getChildByName('Icon')!)
    for (const child of n.children.slice(0, 4)) {
      const text = own(child)
      assert.ok(text.left - portrait.right >= 12, `${child.name} must leave readable space beside portrait`)
      assert.ok(own(n).right - text.right >= 16, `${child.name} must remain in the row's right padding`)
    }
  }
})
test('真实行按钮/页签/合成文字自身盒完全位于按钮内，原文完整且配置字号不缩小', () => {
  const { hero } = setup(); hero.onLoad()
  const n = row(hero), actions = n.children.filter(child => child.name.endsWith('Button'))
  const headerButtons = ['Tab_heroes', 'Tab_lineups', 'ComposeEntry'].map(name => hero.node.getChildByName(name)!)
  for (const button of [...actions, ...headerButtons]) {
    const label = button.getChildByName('Caption')!.getComponent(Label)!, ui = label.node.getComponent(UITransform)!
    label.updateRenderData(); const caption = own(label.node), control = own(button)
    assert.ok(caption.left >= control.left && caption.right <= control.right && caption.bottom >= control.bottom && caption.top <= control.top, `${button.name} Caption own box must fit button`)
    assert.equal(ui.anchorX, .5); assert.equal(ui.anchorY, .5)
    assert.equal(label.enableWrapText, false); assert.equal(label.overflow, Label.Overflow.CLAMP)
    assert.equal(label.actualFontSize, label.fontSize); assert.equal(label.parsedString.join(''), label.string)
    assert.equal(label.lineHeight, label.fontSize + 6); assert.ok(ui.height >= label.lineHeight)
    assert.ok([...label.string].reduce((width, ch) => width + label.fontSize * (ch.charCodeAt(0) > 255 ? 1 : .6), 0) <= ui.width)
    if (actions.includes(button)) { assert.equal(ui.width, 40); assert.equal(ui.height, 20); assert.equal(label.fontSize, 12) }
  }
  assert.deepEqual(actions.map(b => b.getChildByName('Caption')!.getComponent(Label)!.string), ['装备库', '升级', '升星', '觉醒', '技能'])
  assert.deepEqual(headerButtons.map(b => b.getChildByName('Caption')!.getComponent(Label)!.string), ['武将', '编队', '碎片合成'])
  hero.onDestroy()
})
test('独立养成按钮排全部保留行内16像素边距，末技能按钮框不会被新增Mask裁去右缘', () => {
  const { hero } = setup()
  for (const copy of [COMMON, COMMON.map(s => s + s + s)]) {
    const n = row(hero, copy), parent = own(n), actions = n.children.filter(c => c.name.endsWith('Button'))
    for (const button of actions) {
      assert.ok(own(button).left >= parent.left + 16 && own(button).right <= parent.right - 16, `${button.name} frame must stay inside row padding`)
    }
    for (let i = 1; i < actions.length; i++) assert.ok(own(actions[i]!).left - own(actions[i - 1]!).right >= 4)
  }
})
test('真实Hero四行显式行高并换行测高，存量中文全文保留配置字号，不继承40压到12/7/6', () => {
  const { hero } = setup(), n = row(hero); assert.deepEqual(n.children.slice(0, 4).map(x => x.name), ['Line1', 'Line2', 'Line3', 'Line4']); fit(n)
})
test('长装备/技能/属性全文自然增高，四行及按钮不重叠、不裁字，行图标仍在行内', () => {
  const { hero } = setup(); const long = COMMON.map((copy, i) => i === 0 ? copy : copy + ' · ' + copy + ' · ' + copy), n = row(hero, long)
  const labels = n.children.slice(0, 4).map(x => x.getComponent(Label)!)
  labels.forEach((label, i) => { assert.equal(label.string, long[i]); assert.equal(label.overflow, Label.Overflow.RESIZE_HEIGHT); assert.equal(label.actualFontSize, label.fontSize); assert.equal(label.parsedString.join(''), long[i]); assert.ok(own(label.node).right <= own(n).right) })
  for (let i = 0; i < 3; i++) assert.ok(own(labels[i]!.node).bottom > own(labels[i + 1]!.node).top)
  const buttons = n.children.filter(x => x.name.endsWith('Button')); assert.equal(buttons.length, 5)
  buttons.forEach(b => { assert.ok(own(b).top < own(labels[3]!.node).bottom); assert.ok(own(b).bottom >= own(n).bottom) })
  assert.ok(own(n).top - own(n).bottom > 96)
})
test('复用池化Hero/编队行按当前全文重算高度，按钮映射与编队点击对象保持正确且拖动不先写', () => {
  const { hero } = setup(), n = row(hero, COMMON, 'first'); const calls: string[] = []; hero.onHeroAction = (id, action) => calls.push(id + '/' + action)
  const armory = n.getChildByName('ArmoryButton')!; assert.equal(armory.hasEventListener('touch-start'), false); armory.emit('touch-start'); assert.equal(calls.length, 0); armory.emit('touch-end'); assert.deepEqual(calls, ['first/armory'])
  hero.renderRow(n, { lines: COMMON.map(s => s + s), titleColor: new Color(), iconKey: null, heroId: null, presetIndex: 2 }, 1)
  assert.ok(n.children.filter(c => c.name.endsWith('Button')).every(c => c.active === false)); const edits: number[] = []; hero.onLineupEdit = p => edits.push(p); n.emit('touch-start'); assert.equal(edits.length, 0); n.emit('touch-end'); assert.deepEqual(edits, [2])
  hero.renderRow(n, { lines: COMMON, titleColor: new Color(), iconKey: null, heroId: 'second', presetIndex: null }, 0); fit(n); armory.emit('touch-end'); assert.deepEqual(calls, ['first/armory', 'second/armory']); assert.equal(n.hasEventListener('touch-end'), false)
})
test('真实列表用Mask滚动承载增高行，短横屏末项按钮可达且resize保留/夹住offset', () => {
  const { hero, size, resize } = setup(); hero.onLoad()
  const sample = commonHero()
  hero.panel = { heroes: Array.from({ length: 5 }, (_, i) => ({ ...sample, heroId: 'h' + i })), lineups: [], troopCapText: '带兵上限 0（已用 0）' }; hero.render()
  const viewport = hero.node.getChildByName('HeroRows')!; assert.ok(viewport, 'Added scroll viewport must keep content out of navbar')
  const scroll = viewport.getComponent(ScrollView)!; assert.equal(scroll.cancelInnerEvents, true); assert.equal(scroll.vertical, true); assert.equal(viewport.getComponent(Mask)!.type, Mask.Type.GRAPHICS_RECT)
  const verifyBottom = (): void => { scroll.scrollToBottom(); const last = hero.drawnRows.at(-1)!, area = own(viewport); for (const button of last.children.filter(c => c.name.endsWith('Button'))) { const box = own(button); assert.ok(box.top <= area.top && box.bottom >= area.bottom && box.left >= area.left && box.right <= area.right, `${button.name} complete frame must fit viewport`) }; assert.ok(area.bottom >= -size.height / 2 + 64) }
  verifyBottom(); const before = scroll.getScrollOffset().y; size.height = 443.6; resize(); assert.ok(scroll.getScrollOffset().y >= 0); assert.ok(scroll.getScrollOffset().y <= scroll.content!.getComponent(UITransform)!.height - viewport.getComponent(UITransform)!.height); verifyBottom(); size.height = 640; resize(); assert.ok(scroll.getScrollOffset().y <= before + .01); verifyBottom(); hero.onDestroy()
})
test('当前配置真实最长武将技能和四槽装备名组合完整排版，数值夹具不冒充合法可达最大养成', () => {
  interface HeroCfg { id: string; name: string; rarity: HeroView['rarity']; mainSkill: string; subSkill: string; might: number; command: number; wisdom: number; maxLevel: number; awakenMax: number }
  interface EquipCfg { id: string; name: string; slot: HeroView['equips'][number]['slot']; forgeMax: number }
  const config = <T>(name: string): T[] => (JSON.parse(readFileSync(path.join(process.cwd(), '../contract/config', name + '.json'), 'utf8')) as { rows: T[] }).rows
  const skills = new Map(config<{ id: string; name: string }>('skill').map(c => [c.id, c.name]))
  const cfg = config<HeroCfg>('hero').sort((a, b) => (b.name.length + skills.get(b.mainSkill)!.length + skills.get(b.subSkill)!.length) - (a.name.length + skills.get(a.mainSkill)!.length + skills.get(a.subSkill)!.length))[0]!
  const equipCfg = config<EquipCfg>('equip')
  const equips = (['WEAPON', 'ARMOR', 'MOUNT', 'ACCESSORY'] as const).map(slot => equipCfg.filter(c => c.slot === slot).sort((a, b) => b.name.length - a.name.length)[0]!)
  const data: HeroView = { heroId: cfg.id, name: cfg.name, rarity: cfg.rarity, level: 1, exp: 0, expToNext: 20, maxLevel: cfg.maxLevel, star: 1, maxStar: 5,
    awaken: 0, maxAwaken: cfg.awakenMax, mainSkillId: cfg.mainSkill, mainSkillName: skills.get(cfg.mainSkill)!, mainSkillLevel: 1,
    subSkillId: cfg.subSkill, subSkillName: skills.get(cfg.subSkill)!, subSkillLevel: 1, maxSkillLevel: 10,
    equips: equips.map(c => ({ slot: c.slot, uid: 'fixture-' + c.id, equipId: c.id, name: c.name, forgeLevel: c.forgeMax })),
    baseAttrs: { might: cfg.might, command: cfg.command, wisdom: cfg.wisdom }, finalAttrs: { might: cfg.might, command: cfg.command, wisdom: cfg.wisdom }, power: 180, bondWith: cfg.id }
  const { hero } = setup(); hero.onLoad(); const built = buildHeroRow(data, new Map([[data.heroId, data.name]]))
  hero.panel = { heroes: [built], lineups: [], troopCapText: '夹具带兵显示' }; hero.render()
  const labels = hero.drawnRows[0]!.children.slice(0, 4).map(c => c.getComponent(Label)!)
  for (const l of labels) { assert.equal(l.actualFontSize, l.fontSize); assert.equal(l.parsedString.join(''), l.string); assert.ok(l.lineHeight < 40) }
  assert.ok(equips.every(c => labels[3]!.string.includes(c.name)), 'All real config equipment names remain visible copy')
  assert.ok(labels[1]!.string.includes(data.mainSkillName) && labels[1]!.string.includes(data.subSkillName))
  assert.ok(labels[3]!.string.includes('缘分：' + data.name) && labels[3]!.string.includes('经验 0/20'))
  const button = hero.drawnRows[0]!.getChildByName('ArmoryButton')!; assert.ok(own(button).top < own(labels[3]!.node).bottom)
})
for (const count of [16, 40]) test(`完整${count}行名册顺序与末行按钮可达，切编队保留第三套附注及池化映射`, () => {
  const { hero, size, resize } = setup(); hero.onLoad()
  const heroes = Array.from({ length: count }, (_, i) => commonHero('authority-' + i))
  const lineups: LineupPanel[] = Array.from({ length: 3 }, (_, i) => ({ presetIndex: i, presetText: '编队 ' + i, slotTexts: ['裴惊澜', '沈砚', '李承鄞'], emptySlots: 0,
    atkText: '攻击 +62%', defText: '防御 +41%', skillText: '技能 +15%', commandText: '带兵上限 240', capped: false, cappedHint: null,
    breakdownLines: ['[武将] 裴惊澜 Lv60 ★4 +62%', '[缘分] 与沈砚同队 +10%', '[装备套装] 铁骑套装4件 +25%'], bondTexts: ['沈砚'] }))
  hero.panel = { heroes, lineups, troopCapText: '同权威测试行序' }; hero.render()
  assert.equal(hero.drawnRows.length, count); assert.deepEqual(Array.from(hero.drawnRows, n => hero.rowHeroes.get(n)), heroes.map(h => h.heroId))
  const wrapped = hero.node.getChildByName('HeroRows')!, scroll = wrapped.getComponent(ScrollView)!
  size.height = 443.6; resize(); scroll.scrollToBottom(); const last = hero.drawnRows.at(-1)!, button = last.getChildByName('ArmoryButton')!, viewport = own(wrapped)
  assert.ok(own(button).bottom >= viewport.bottom && own(button).top <= viewport.top)
  const calls: string[] = []; hero.onHeroAction = id => calls.push(id); button.emit('touch-end'); assert.deepEqual(calls, [heroes.at(-1)!.heroId])
  hero.switchTab('lineups'); assert.equal(hero.drawnRows.length, 6, 'Three primary rows plus all three bond annotations survive old cap5')
  assert.ok(hero.drawnRows.at(-1)!.children[0]!.getComponent(Label)!.string.includes('已激活缘分：沈砚'))
  const edits: number[] = []; hero.onLineupEdit = p => edits.push(p); hero.drawnRows[4]!.emit('touch-end'); assert.deepEqual(edits, [2]); hero.drawnRows[5]!.emit('touch-end'); assert.deepEqual(edits, [2])
  assert.ok(hero.drawnRows.every(n => n.children.filter(c => c.name.endsWith('Button')).every(c => c.active === false)))
  hero.switchTab('heroes'); assert.equal(hero.drawnRows.length, count); assert.equal(scroll.getScrollOffset().y, 0); hero.onDestroy()
})
