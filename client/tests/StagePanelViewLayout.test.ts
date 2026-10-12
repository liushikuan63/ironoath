/** 运行真实StagePanelView/DialogStyle/NodePool；节点桩不代替Cocos实画或原生触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as paging from '../assets/scripts/game/ui/PanelPaging'
import * as stages from '../assets/scripts/game/stage/StagePanel'
import { buildStaminaBoard } from '../assets/scripts/game/stage/StaminaBoard'
import { dialogLayout, dialogBodyFlow } from '../assets/scripts/game/ui/DialogLayout'
import { PANEL_IRON_INSET } from '../assets/scripts/game/art/ArtFamilies'
import * as uiTokens from '../assets/scripts/game/ui/UiTokens'
import type { StageListResp } from '../assets/scripts/net/generated/StageProtocol'

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
  clear(): void {} rect(): void {} roundRect(): void {} fill(): void {} stroke(): void {}
}
class Label {
  static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }; static VerticalAlign = { TOP: 0, CENTER: 1 }
  static Overflow = { NONE: 0, SHRINK: 2, RESIZE_HEIGHT: 3 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown
  horizontalAlign = 0; verticalAlign = 0; overflow = 0; enableWrapText = true
  updateRenderData(): void {
    const box = this.node.getComponent(UITransform)!
    if (this.overflow === Label.Overflow.RESIZE_HEIGHT) {
      const lines = this.string.split('\n').reduce((n, line) => n + Math.max(1, Math.ceil(line.length * this.fontSize / Math.max(1, box.width))), 0)
      box.setContentSize(box.width, lines * this.lineHeight)
    }
  }
}
class Sprite { enabled = true; spriteFrame: { name: string } | null = null }
class Mask { static Type = { GRAPHICS_RECT: 0 }; type = 0 }
class Node {
  parent: Node | null = null; position = new Vec3(); layer = 1; active = true
  readonly children: Node[] = []; private components = new Map<unknown, unknown>()
  private listeners = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(n: Node): void { n.removeFromParent(); n.parent = this; this.children.push(n) }
  addComponent<T extends object>(type: new () => T): T { const c = new type(); Object.assign(c, { node: this }); this.components.set(type, c); return c }
  getComponent<T>(type: new () => T | string): T | null { return this.components.get(type) as T ?? null }
  getChildByName(name: string): Node | null { return this.children.find(n => n.name === name) ?? null }
  setPosition(p: Vec3): void { this.position = p }
  setSiblingIndex(index: number): void { const rows = this.parent?.children; if (rows) { rows.splice(rows.indexOf(this), 1); rows.splice(index, 0, this) } }
  on(event: string, fn: () => void): void { this.listeners.set(event, fn) }
  off(event: string): void { this.listeners.delete(event) }
  hasEventListener(event: string): boolean { return this.listeners.has(event) }
  emit(event: string): void { this.listeners.get(event)?.() }
  removeFromParent(): void { const rows = this.parent?.children; if (rows) rows.splice(rows.indexOf(this), 1); this.parent = null }
  destroy(): void { this.removeFromParent() }
}
class ScrollView {
  node!: Node; content: Node | null = null; horizontal = false; vertical = true
  inertia = false; elastic = false; cancelInnerEvents = false; offset = new Vec2()
  scrollToTop(): void { this.scrollToOffset(new Vec2()) }
  scrollToOffset(offset: Vec2): void { this.offset = offset; this.content?.setPosition(new Vec3(0, this.node.getComponent(UITransform)!.height / 2 + offset.y, 0)) }
  getScrollOffset(): Vec2 { return this.offset }
  scrollToBottom(): void { this.scrollToOffset(new Vec2(0, this.content!.getComponent(UITransform)!.height - this.node.getComponent(UITransform)!.height)) }
}
class Component { node = new Node('stage') }
class Color { constructor(..._values: number[]) {} }
function exportsFor(name: string, bindings: Record<string, unknown>): Record<string, unknown> {
  const source = fs.readFileSync(path.join(process.cwd(), 'assets/scripts/scene', name + '.ts'), 'utf8')
  const ast = ts.createSourceFile(name + '.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Node, Component, Color, Graphics, Label, UITransform, Vec2, Vec3, Size, Sprite, Mask, ScrollView, ...bindings })
  return exports
}
interface Harness { node: Node; onLoad(): void; attach(data: StageListResp): void; showBlocked(text: string): void }
function setup(height: number): Harness {
  const view = { getVisibleSize: () => ({ width: 960, height }) }
  const art = (n: Node, _key: string, w: number, h: number): boolean => { n.getComponent(UITransform)!.setContentSize(w, h); return false }
  const dialog = exportsFor('DialogStyle', { view, PANEL_IRON_INSET, ...uiTokens, dialogLayout, dialogBodyFlow,
    applySlicedSprite: art, applyIronButton: art, applyCommandButton: art })
  const pool = exportsFor('NodePool', {})
  const out = exportsFor('StagePanelView', { view, ...paging, ...stages, ...dialog, NodePool: pool.NodePool,
    ChoiceOverlay: class { hide(): void {} }, applySystemUiFont: (l: Label) => l,
    oneLineFloorHeight: () => 26, buildStaminaBoard })
  const stage = new (out.StagePanelView as new () => Harness)(); stage.onLoad()
  stage.attach({ stamina: 110, serverNow: 1, stages: Array.from({ length: 10 }, (_, i) => ({
    stageId: 'stage_' + i, chapterId: 'chapter_01', stageNo: i + 1, name: '边境哨站' + i,
    staminaCost: 1, roundLimit: 6, unitRestriction: 'NONE', bossMechanic: 'NONE', unlocked: true,
    lockedReason: null, attempted: false, progress: { stageId: 'stage_' + i, stars: 0, bestRounds: 0, clearedAt: 0, sweepCount: 0 },
  })) })
  return stage
}
function find(node: Node, name: string): Node | undefined {
  if (node.name === name) return node
  for (const child of node.children) { const found = find(child, name); if (found) return found }
  return undefined
}
for (const height of [360, 443.6018957345971, 640]) {
  test(`结算${height}高：关卡行不叠摘要，正文可滚动，返回保留页码`, () => {
    const stage = setup(height), next = find(stage.node, 'NextPageButton')!
    next.emit('touch-start')
    const pageBefore = stage.node.children.filter(n => n.getComponent(Label)?.string.includes('页')).map(n => n.getComponent(Label)!.string).join(',')
    const text = Array.from({ length: 20 }, (_, i) => `结算第${i + 1}行：实际获得金币与损失兵力`).join('\n')
    stage.showBlocked(text)
    assert.equal(stage.node.children.filter(n => n.name === 'StageRow' && n.active).length, 0, '关卡行不能覆盖结算正文')
    assert.equal(next.active, false, '翻页动作不能覆盖结算')
    const close = find(stage.node, 'SummaryCloseButton')!
    assert.ok(close?.hasEventListener('touch-start'), '结算必须有正常返回动作')
    const mask = find(stage.node, 'DialogContent')!, scroll = mask.getComponent(ScrollView)!
    assert.ok(scroll.content!.getComponent(UITransform)!.height > mask.getComponent(UITransform)!.height)
    assert.equal(find(stage.node, 'SummaryText')!.getComponent(Label)!.string, text)
    const footerY = close.position.y; scroll.scrollToBottom(); assert.equal(close.position.y, footerY)
    close.emit('touch-start')
    assert.equal(find(stage.node, 'SummaryPanel')!.active, false)
    assert.ok(stage.node.children.some(n => n.name === 'StageRow' && n.active))
    assert.equal(stage.node.children.filter(n => n.getComponent(Label)?.string.includes('页')).map(n => n.getComponent(Label)!.string).join(','), pageBefore)
  })
}
