/** 运行真实EquipPanelView/DialogStyle方法；节点桩不证明Cocos实画或原生触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import type { EquipPanelView as EquipData, EquipRow } from '../assets/scripts/game/equip/EquipPanel'
import { dialogLayout, dialogBodyFlow } from '../assets/scripts/game/ui/DialogLayout'
import { PANEL_IRON_INSET } from '../assets/scripts/game/art/ArtFamilies'
import * as uiTokens from '../assets/scripts/game/ui/UiTokens'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Vec2 { constructor(public x = 0, public y = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = .5; anchorY = .5
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
  setContentSize(size: Size | number, height?: number): void {
    this.width = typeof size === 'number' ? size : size.width
    this.height = typeof size === 'number' ? height! : size.height
  }
}
class Graphics {
  enabled = true; fillColor: unknown; strokeColor: unknown; lineWidth = 0
  rects: number[][] = []
  clear(): void { this.rects.length = 0 }
  rect(...r: number[]): void { this.rects.push(r) }
  roundRect(...r: number[]): void { this.rects.push(r) }
  fill(): void {} stroke(): void {}
}
class Label {
  static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }
  static VerticalAlign = { CENTER: 1 }; static Overflow = { NONE: 0, SHRINK: 2 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown
  horizontalAlign = 0; verticalAlign = 0; overflow = 0; enableWrapText = true
  updateRenderData(): void {
    if (this.overflow !== Label.Overflow.NONE) return
    const box = this.node.getComponent(UITransform)!
    box.setContentSize(this.string.length * this.fontSize, this.lineHeight)
  }
}
class Sprite { enabled = true; spriteFrame: { name: string } | null = null }
class Mask { static Type = { GRAPHICS_RECT: 0 }; type = 0 }
class Node {
  parent: Node | null = null; position = new Vec3(); layer = 1; active = true
  readonly children: Node[] = []; private components = new Map<unknown, unknown>()
  private listeners = new Map<string, Array<(...args: unknown[]) => void>>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  addComponent<T extends object>(type: new () => T): T {
    const component = new type(); Object.assign(component, { node: this }); this.components.set(type, component); return component
  }
  getComponent<T>(type: new () => T | string): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(n => n.name === name) ?? null }
  setPosition(p: Vec3): void { this.position = p }
  setSiblingIndex(index: number): void {
    if (!this.parent) return
    const siblings = this.parent.children; siblings.splice(siblings.indexOf(this), 1); siblings.splice(Math.min(index, siblings.length), 0, this)
  }
  on(type: string, listener: (...args: unknown[]) => void): void { this.listeners.set(type, [...this.listeners.get(type) ?? [], listener]) }
  hasEventListener(type: string): boolean { return (this.listeners.get(type)?.length ?? 0) > 0 }
  emit(type: string): void { for (const listener of this.listeners.get(type) ?? []) listener() }
  removeFromParent(): void { this.parent?.children.splice(this.parent.children.indexOf(this), 1); this.parent = null }
  destroy(): void { this.removeFromParent() }
}
class Component { node = new Node('host') }
class Color { constructor(...readonlyValues: number[]) { this.values = readonlyValues } values: readonly number[] }
class ScrollView {
  node!: Node
  content: Node | null = null; horizontal = true; vertical = false; inertia = false; elastic = true; cancelInnerEvents = false
  offset = new Vec2()
  scrollToTop(): void { this.scrollToOffset(new Vec2()) }
  scrollToBottom(): void {
    this.scrollToOffset(new Vec2(0, Math.max(0,
      this.content!.getComponent(UITransform)!.height - this.node.getComponent(UITransform)!.height)))
  }
  scrollToOffset(offset: Vec2): void {
    this.offset = offset
    // Cocos的top-anchor正文以正y offset上移；这里只复现公开滚动几何，不冒充引擎输入。
    this.content?.setPosition(new Vec3(0, this.node.getComponent(UITransform)!.height / 2 + offset.y, 0))
  }
  getScrollOffset(): Vec2 { return this.offset }
}
interface EquipHarness {
  node: Node; onForge: ((uid: string) => void) | null
  onRowAction: ((uid: string, slot: string, takeOff: boolean) => void) | null
  render(data: EquipData): void; hide(): void; onDestroy(): void
}
interface Rect { x: number; y: number; width: number; height: number }
function exportsFor(name: string, bindings: Record<string, unknown>): Record<string, unknown> {
  const source = readFileSync(path.join(process.cwd(), 'assets/scripts/scene', name + '.ts'), 'utf8')
  const ast = ts.createSourceFile(name + '.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, { compilerOptions: { target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Node, Component, Color, UITransform, Graphics, Label, Sprite, Mask, ScrollView, Size, Vec2, Vec3, ...bindings })
  return exports
}
function setup(height = 443.6018957345971): {
  equip: EquipHarness; area: Rect; size: { width: number; height: number }; resize(): void
} {
  const size = { width: 960, height }, area = { x: -426.54, y: -137.915, width: 853.08, height: height - 83.885 }
  const art = (node: Node, key: string, width: number, tall: number): boolean => {
    node.getComponent(UITransform)!.setContentSize(width, tall)
    const sprite = node.getComponent(Sprite) ?? node.addComponent(Sprite); sprite.spriteFrame = { name: key }; return true
  }
  const styles = exportsFor('DialogStyle', { view: { getVisibleSize: () => size }, PANEL_IRON_INSET,
    ...uiTokens, dialogLayout, dialogBodyFlow, applySlicedSprite: art,
    applyIronButton: (node: Node, state: string, width: number, tall: number) => art(node, 'ui.button.iron:' + state, width, tall),
    applyCommandButton: (node: Node, state: string, width: number, tall: number) => art(node, 'ui.button.command:' + state, width, tall) })
  // Use the production fit/create/paint methods; only navigation area and resize clock are substituted.
  const Equip = exportsFor('EquipPanelView', { view: { getVisibleSize: () => size }, ...styles,
    applySystemUiFont: (label: Label) => label, PANEL_IRON_INSET }).EquipPanelView as new () => EquipHarness
  const equip = new Equip(); equip.node.layer = 1
  // DialogStyle's public traversal reads PanelNav by string, matching production.
  const originalGet = equip.node.getComponent.bind(equip.node)
  equip.node.getComponent = ((type: unknown) => type === 'PanelNav'
    ? { current: () => 'hero', contentRectFor: () => area } : originalGet(type as new () => unknown)) as typeof equip.node.getComponent
  return { equip, area, size, resize: () => {
    // The actual DialogSizeObserver is defined in the transpiled production module.
    const traverse = (node: Node): void => {
      const raw = node as unknown as { components: Map<unknown, { update?: () => void }> }
      for (const component of raw.components.values()) if (component.update) component.update()
      node.children.forEach(traverse)
    }
    traverse(equip.node)
  } }
}
function row(uid: string, canForge = true, actionText: string | null = '装备'): EquipRow {
  return { uid, name: '服务端装备名' + uid, slot: 'WEAPON', slotText: '武器', rarityText: 'SR', forgeText: '强化 +1 / 20',
    statsText: '武力 +12', costText: canForge ? '强化消耗 铁矿 120' : null, wornText: '未装备', worn: false,
    canForge, reasonText: canForge ? null : '铁矿不足', actionText }
}
function data(rows: readonly EquipRow[]): EquipData { return { rows, summaryText: '服务端汇总', targetText: '给 服务端武将 换装', noticeText: null } }
function find(root: Node, name: string): Node {
  const visit = (node: Node): Node | undefined => node.name === name ? node : node.children.map(visit).find(Boolean)
  const result = visit(root); assert.ok(result, 'Missing production node ' + name); return result
}
function own(node: Node): Rect {
  const box = node.getComponent(UITransform)!; let x = node.position.x, y = node.position.y
  for (let p = node.parent; p; p = p.parent) { x += p.position.x; y += p.position.y }
  return { x: x - box.width * box.anchorX, y: y - box.height * box.anchorY, width: box.width, height: box.height }
}
function inside(inner: Rect, outer: Rect): boolean {
  return inner.x >= outer.x - .001 && inner.y >= outer.y - .001 && inner.x + inner.width <= outer.x + outer.width + .001 && inner.y + inner.height <= outer.y + outer.height + .001
}
test('短横屏真实card自身框/主题/scroll/固定close在导航净区，遮罩铺满且阻背景', () => {
  const { equip, area, size } = setup(); equip.render(data([row('a'), row('b')]))
  const card = find(equip.node, 'card'), box = own(card)
  assert.equal(box.width, 620); assert.ok(box.height > 300 && box.height < 360); assert.ok(inside(box, area))
  assert.equal(find(card, 'DialogFrameArt').getComponent(Sprite)!.spriteFrame!.name, 'ui.panel.iron')
  const scrim = find(equip.node, 'scrim'); assert.equal(own(scrim).width, size.width); assert.equal(own(scrim).height, size.height)
  assert.equal(scrim.hasEventListener('touch-start'), true); assert.equal(card.hasEventListener('touch-start'), true)
  const body = find(equip.node, 'DialogContent'), close = find(equip.node, 'close')
  assert.ok(inside(own(body), box)); assert.ok(inside(own(close), box)); assert.equal(close.parent, equip.node)
  assert.ok(own(close).y + own(close).height <= own(body).y)
  assert.equal(body.getComponent(ScrollView)!.cancelInnerEvents, true)
  assert.equal(find(body, 'equip-a').parent, find(body, 'DialogContentContent'))
})
test('normal620×600与同页resize保持真实净区及滚动offset，caption由按钮独占local0,0', () => {
  const { equip, area, size, resize } = setup(760)
  equip.render(data([row('a'), ...Array.from({ length: 11 }, (_, i) => row('extra' + i))]))
  assert.equal(own(find(equip.node, 'card')).height, 600)
  const forge = find(equip.node, 'forge-a'), close = find(equip.node, 'close')
  for (const button of [forge, close]) {
    const caption = button.children.find(n => n.getComponent(Label))
    assert.ok(caption, 'Caption must be button-owned child'); assert.equal(caption.position.x, 0); assert.equal(caption.position.y, 0)
    assert.ok(inside(own(caption), own(button))); assert.equal(caption.getComponent(Label)!.overflow, Label.Overflow.SHRINK)
  }
  const body = find(equip.node, 'DialogContent'), scroll = body.getComponent(ScrollView)!
  scroll.scrollToOffset(new Vec2(0, 10)); size.height = 443.6; area.height = 359.715; resize()
  assert.ok(inside(own(find(equip.node, 'card')), area)); assert.ok(own(find(equip.node, 'card')).height < 360)
  assert.ok(inside(own(close), own(find(equip.node, 'card')))); assert.equal(equip.node.children.filter(n => n.name === 'DialogContent').length, 1)
  assert.equal(scroll.getScrollOffset().y, 10)
})
test('生产动作在touch-end提交，拖动cancel无强化/装备；灰键与无动作行零监听', () => {
  const { equip } = setup(); equip.render(data([row('enabled'), row('disabled', false, null)]))
  const calls: string[] = []; equip.onForge = uid => calls.push('forge:' + uid); equip.onRowAction = uid => calls.push('equip:' + uid)
  const forge = find(equip.node, 'forge-enabled'), action = find(equip.node, 'equip-enabled')
  forge.emit('touch-start'); action.emit('touch-start'); assert.deepEqual(calls, [])
  forge.emit('touch-cancel'); action.emit('touch-cancel'); assert.deepEqual(calls, [])
  forge.emit('touch-end'); action.emit('touch-end'); assert.deepEqual(calls, ['forge:enabled', 'equip:enabled'])
  for (const node of [find(equip.node, 'forge-disabled'), find(equip.node, 'equip-disabled')]) {
    for (const event of ['touch-start', 'touch-end', 'touch-cancel']) assert.equal(node.hasEventListener(event), false)
    node.emit('touch-end')
  }
  assert.deepEqual(calls, ['forge:enabled', 'equip:enabled'])
})
test('16件全部保留服务端行序/uid/名称/强化权威灰态，重画销毁旧容器，关闭只hide', () => {
  const { equip } = setup(); const rows = Array.from({ length: 16 }, (_, i) => row(String(i), i % 2 === 0))
  equip.render(data(rows)); const body = find(equip.node, 'DialogContentContent')
  const drawn = body.children.filter(n => n.name.startsWith('equip-')).map(n => n.name)
  assert.deepEqual(drawn, rows.map(r => 'equip-' + r.uid))
  for (const r of rows) assert.ok(body.children.some(n => n.getComponent(Label)?.string === `${r.name}  ${r.rarityText}`))
  assert.equal(body.children.some(n => n.getComponent(Label)?.string.includes('件未显示')), false)
  assert.equal(find(equip.node, 'forge-1').getChildByName('DialogButtonArt')?.getComponent(Sprite)?.spriteFrame?.name ?? find(equip.node, 'forge-1').getComponent(Sprite)?.spriteFrame?.name, 'ui.button.command:disabled')
  const scroll = find(equip.node, 'DialogContent').getComponent(ScrollView)!; scroll.scrollToBottom()
  assert.ok(inside(own(find(equip.node, 'equip-15')), own(find(equip.node, 'DialogContent'))))
  assert.ok(inside(own(find(equip.node, 'forge-15')), own(find(equip.node, 'DialogContent'))))
  equip.render(data([row('new')])); assert.equal(equip.node.children.filter(n => n.name === 'DialogContent').length, 1)
  find(equip.node, 'close').emit('touch-end'); assert.equal(equip.node.active, false)
})
test('40件实际正文bounds包含末行及尾提示，scroll到底后末行/强化键可达且保权威uid', () => {
  const { equip } = setup(); const rows = Array.from({ length: 40 }, (_, i) => row('uid' + i))
  equip.render({ ...data(rows), noticeText: '服务端返回的尾部提示' })
  const viewport = find(equip.node, 'DialogContent'), content = find(equip.node, 'DialogContentContent')
  assert.equal(content.children.filter(n => n.name.startsWith('equip-')).length, rows.length)
  const last = find(equip.node, 'equip-uid39'), forge = find(equip.node, 'forge-uid39')
  const notice = content.children.find(n => n.getComponent(Label)?.string === '服务端返回的尾部提示')!
  assert.ok(notice); assert.ok(own(notice).y + own(notice).height < own(last).y, 'Notice follows last complete row')
  assert.ok(inside(own(last), own(content))); assert.ok(inside(own(notice), own(content)))
  assert.ok(own(content).height >= 40 * 46)
  const scroll = viewport.getComponent(ScrollView)!; scroll.scrollToBottom()
  assert.equal(scroll.getScrollOffset().y, own(content).height - own(viewport).height)
  for (const node of [last, forge, notice]) assert.ok(inside(own(node), own(viewport)), node.name + ' must be reachable at bottom')
  const calls: string[] = []; equip.onForge = uid => calls.push('forge:' + uid); equip.onRowAction = uid => calls.push('equip:' + uid)
  assert.equal(scroll.cancelInnerEvents, true)
  for (const node of [last, forge]) { node.emit('touch-start'); node.emit('touch-cancel') }
  assert.deepEqual(calls, [])
  forge.emit('touch-end'); last.emit('touch-end'); assert.deepEqual(calls, ['forge:uid39', 'equip:uid39'])
})
test('40件底部normal→短屏→normal重排保持全部行并clamp真实scroll offset，close始终独立固定', () => {
  const { equip, area, size, resize } = setup(760)
  equip.render(data(Array.from({ length: 40 }, (_, i) => row('resize' + i))))
  const viewport = find(equip.node, 'DialogContent'), scroll = viewport.getComponent(ScrollView)!
  const content = find(equip.node, 'DialogContentContent'), last = find(equip.node, 'equip-resize39'), close = find(equip.node, 'close')
  assert.equal(content.children.filter(n => n.name.startsWith('equip-')).length, 40)
  scroll.scrollToBottom(); const normalOffset = scroll.getScrollOffset().y, normalCloseY = close.position.y
  assert.ok(inside(own(last), own(viewport)))
  size.height = 443.6; area.height = 359.715; resize()
  assert.equal(scroll.getScrollOffset().y, normalOffset); assert.ok(inside(own(close), own(find(equip.node, 'card'))))
  const shortCloseY = close.position.y; scroll.scrollToBottom()
  assert.ok(scroll.getScrollOffset().y > normalOffset); assert.ok(inside(own(last), own(viewport)))
  assert.equal(close.position.y, shortCloseY, 'Scrolling must not move the fixed footer')
  size.height = 760; area.height = 676.115; resize()
  assert.equal(scroll.getScrollOffset().y, normalOffset); assert.ok(inside(own(last), own(viewport)))
  assert.equal(close.position.y, normalCloseY); assert.equal(own(find(equip.node, 'card')).height, 600)
  assert.equal(equip.node.children.filter(n => n.name === 'DialogContent').length, 1)
})
test('行命中区与强化控制分离，卸下回调保uid/slot/takeOff，caption位于真实按钮盒', () => {
  const { equip } = setup(); equip.render(data([row('serverUid', true, '卸下')]))
  const action = find(equip.node, 'equip-serverUid'), forge = find(equip.node, 'forge-serverUid')
  const actionBox = own(action), forgeBox = own(forge)
  assert.ok(actionBox.x + actionBox.width < forgeBox.x)
  assert.equal(actionBox.y + actionBox.height / 2, forgeBox.y + forgeBox.height / 2)
  assert.ok(inside(forgeBox, own(find(equip.node, 'DialogContentContent'))))
  const calls: unknown[][] = []; equip.onRowAction = (...args) => calls.push(args)
  action.emit('touch-end'); assert.deepEqual(calls, [['serverUid', 'WEAPON', true]])
  const caption = forge.children.find(n => n.getComponent(Label))!
  assert.equal(caption.getComponent(Label)!.fontSize, 16); assert.equal(caption.getComponent(Label)!.enableWrapText, false)
})
