/** 运行真实科技View绘制和分页方法；节点桩不代替Cocos实图/原生触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as paging from '../assets/scripts/game/ui/PanelPaging'
import type { TechPanelView as TechData } from '../assets/scripts/game/tech/TechPanel'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0
  setAnchorPoint(): void {}
  setContentSize(size: Size | number, height?: number): void {
    this.width = typeof size === 'number' ? size : size.width
    this.height = typeof size === 'number' ? height! : size.height
  }
}
class Graphics { fillColor: unknown; rect(): void {} fill(): void {} }
class Label {
  static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }
  string = ''; color: unknown; fontSize = 0; lineHeight = 0; horizontalAlign = 0
}
class Node {
  parent: Node | null = null; active = true; position = new Vec3()
  readonly children: Node[] = []; private components = new Map<unknown, unknown>()
  private listeners = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(n: Node): void { n.parent = this; this.children.push(n) }
  addComponent<T extends object>(type: new () => T): T { const c = new type(); this.components.set(type, c); return c }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T ?? null }
  setPosition(p: Vec3): void { this.position = p }
  on(event: string, fn: () => void): void { this.listeners.set(event, fn) }
  emit(event: string): void { this.listeners.get(event)?.() }
  destroy(): void { const siblings = this.parent?.children; if (siblings) siblings.splice(siblings.indexOf(this), 1) }
}
class Component { node = new Node('techPanel') }
class Color { constructor(..._args: number[]) {} }
interface TechHarness { node: Node; render(data: TechData): void; hide(): void; onDestroy(): void }
function setup(height: number): { tech: TechHarness; size: { width: number; height: number } } {
  const source = readFileSync(path.join(process.cwd(), 'assets/scripts/scene/TechPanelView.ts'), 'utf8')
  const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, { compilerOptions: { target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}, size = { width: 960, height }
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Node, Component, Color, Graphics, Label, UITransform, Vec3, Size, ...paging,
    view: { getVisibleSize: () => size }, applySystemUiFont: (label: Label) => label })
  return { tech: new (exports.TechPanelView as new () => TechHarness)(), size }
}
function data(queueText: string | null = null): TechData {
  return { academyText: '学院 Lv1', queueText, noticeText: null, rows: Array.from({ length: 11 }, (_, i) => ({
    techId: 'tech_' + i, name: '研究项目' + i, schoolText: ['农政', '军事', '商贸', '城防'][Math.floor(i / 3)]!,
    levelText: '0 / 30 级', effectText: '木材产量 +4%/级', costText: '木材 600 · 石料 200',
    timeText: '13 分', canResearch: true, reasonText: null,
  })) }
}
function button(tech: TechHarness, name: string): Node | undefined { return tech.node.children.find(n => n.name === name) }
function inside(node: Node, height: number): void {
  const box = node.getComponent(UITransform)!
  assert.ok(node.position.y - box.height / 2 >= -height / 2, node.name + ' below viewport')
  assert.ok(node.position.y + box.height / 2 <= height / 2, node.name + ' above viewport')
}
for (const height of [443.6018957345971, 360, 640]) {
  test(`科技${height}高：关闭/翻页/研究可见且全部11项可达`, () => {
    const { tech } = setup(height), visited = new Set<string>(); tech.render(data())
    for (let page = 0; page < 20; page++) {
      inside(button(tech, 'close')!, height)
      for (const node of tech.node.children.filter(n => n.name.startsWith('research-'))) {
        inside(node, height); visited.add(node.name)
        assert.ok(node.position.y - node.getComponent(UITransform)!.height / 2 > button(tech, 'close')!.position.y + 18)
      }
      const next = button(tech, 'TechNextPageButton'); if (!next) break
      inside(next, height); next.emit('touch-start')
    }
    assert.equal(visited.size, 11, 'every server row must remain reachable')
    button(tech, 'close')!.emit('touch-start'); assert.equal(tech.node.active, false)
  })
}
test('科技队列占位后所有项目仍可翻到，缩短列表会夹回有效页', () => {
  const { tech } = setup(443.6018957345971); tech.render(data('正在研究 · 还剩 10 秒'))
  const visited = new Set<string>()
  for (let i = 0; i < 20; i++) {
    for (const node of tech.node.children.filter(n => n.name.startsWith('research-'))) visited.add(node.name)
    const next = button(tech, 'TechNextPageButton'); if (!next) break; next.emit('touch-start')
  }
  assert.equal(visited.size, 11)
  const shorter = data(); tech.render({ ...shorter, rows: shorter.rows.slice(0, 1) })
  assert.ok(button(tech, 'research-tech_0'))
  assert.equal(button(tech, 'TechNextPageButton'), undefined)
})
