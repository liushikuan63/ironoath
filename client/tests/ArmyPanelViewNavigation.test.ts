/** Executes actual Army scene methods and bootstrap callback. Node stubs do not prove native drawing/touch. */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { GuideDriver } from '../assets/scripts/game/guide/GuideDriver'
import { guideBubbleLayout } from '../assets/scripts/game/ui/GuideLayout'
import type { GuideStepView } from '../assets/scripts/net/generated/GuideProtocol'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = 0.5; anchorY = 0.5
  setContentSize(size: Size): void { this.width = size.width; this.height = size.height }
}
class Label {
  static Overflow = { NONE: 0 }; static HorizontalAlign = { CENTER: 1 }; static VerticalAlign = { CENTER: 1 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown; overflow = 0
  horizontalAlign = 0; verticalAlign = 0; enableWrapText = true
}
class Graphics { fillColor: unknown; strokeColor: unknown; lineWidth = 0; rect(): void {} roundRect(): void {} fill(): void {} stroke(): void {} }
class Node {
  layer = 1; active = true; parent: Node | null = null; position = new Vec3(); readonly children: Node[] = []
  private readonly components = new Map<Function, unknown>(); private readonly events = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  setPosition(position: Vec3): void { this.position = position }
  addComponent<T extends object>(type: new () => T): T { const value = new type(); Object.assign(value, { node: this }); this.components.set(type, value); return value }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(child => child.name === name) ?? null }
  on(type: string, handler: () => void): void { this.events.set(type, handler) }
  emit(type: string): void { this.events.get(type)?.() }
}
class Component { node = new Node('army') }
class Color { constructor(..._values: number[]) {} }
class NodePool { destroy(): void {} }
interface ArmyHarness { node: Node; onLoad(): void; onDestroy(): void; update(): void; onOpenCity: (() => void) | null }
const repoRoot = existsSync(path.resolve(process.cwd(), 'client/assets')) ? process.cwd() : path.resolve(process.cwd(), '..')
const sourceDir = path.resolve(repoRoot, 'client/assets/scripts/scene')
function army(height = 443.6018957345971, themed = true): { view: ArmyHarness; commands: string[]; resize(height: number): void } {
  const source = readFileSync(path.join(sourceDir, 'ArmyPanelView.ts'), 'utf8')
  const ast = ts.createSourceFile('ArmyPanelView.ts', source, ts.ScriptTarget.ES2020, true)
  const body = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(statement => !ts.isImportDeclaration(statement))))
  const code = ts.transpileModule(body, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}, commands: string[] = []
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass }, Component, Color, Node, Label, Size, Vec3, UITransform, Graphics, NodePool,
    view: { getVisibleSize: () => ({ width: 960, height }) },
    applySystemUiFont: (label: Label) => label,
    keepOneLine: (label: Label, font: number) => { label.lineHeight = font + 6; label.enableWrapText = false; return label },
    capWidth: (label: Label, width: number) => { label.node.getComponent(UITransform)?.setContentSize(new Size(width, 27)); return label },
    applyCommandButton: (node: Node) => { commands.push(node.name); return themed },
  })
  const View = exports.ArmyPanelView as new () => ArmyHarness, view = new View(); view.onLoad()
  return { view, commands, resize: value => { height = value } }
}
function cityButton(view: ArmyHarness): Node { const button = view.node.getChildByName('OpenCityButton'); assert.ok(button, 'Army页自身必须有实际内城建设按钮'); return button }
function productionOpenCity(nav: { show(key: string): void }): () => void {
  const source = readFileSync(path.join(sourceDir, 'GameBootstrap.ts'), 'utf8')
  const ast = ts.createSourceFile('GameBootstrap.ts', source, ts.ScriptTarget.ES2020, true)
  let callback: ts.Expression | null = null
  const scan = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken && ts.isPropertyAccessExpression(node.left)
      && node.left.expression.getText(ast) === 'army' && node.left.name.text === 'onOpenCity') callback = node.right
    ts.forEachChild(node, scan)
  }
  scan(ast); assert.ok(callback !== null, 'GameBootstrap必须真正装配army.onOpenCity生产回调')
  const code = ts.transpileModule(`exports.callback = (function () { return (${(callback as ts.Expression).getText(ast)}) }).call(host)`, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
  const exports: Record<string, unknown> = {}; runInNewContext(code, { exports, host: { nav } }); return exports.callback as () => void
}

test('Army未收到列表、兵营未解锁时，内城建设入口仍有文字且可点', () => {
  const { view, commands } = army(); let navigation = 0; view.onOpenCity = () => { navigation++ }
  const button = cityButton(view); assert.equal(button.active, true); assert.ok(commands.includes(button.name))
  assert.equal(button.getChildByName('Caption')?.getComponent(Label)?.string, '内城建设')
  button.emit('touch-start'); assert.equal(navigation, 1)
})
test('真实Army点击执行生产bootstrap导航，PANEL_OPEN离开自然隐藏、返回仍是原服务端步骤', () => {
  const { view } = army(), table = JSON.parse(readFileSync(path.resolve(repoRoot, 'contract/config/guide.json'), 'utf8')) as { version: number; rows: GuideStepView[] }
  const step = table.rows.find(row => row.panelKey === 'army'); assert.ok(step)
  const driver = GuideDriver.from({ version: String(table.version), applies: true, nextStepIndex: step.stepIndex, steps: [step], serverNow: 0 }); assert.ok(driver)
  let current = 'army'; const routes: string[] = []
  view.onOpenCity = productionOpenCity({ show: key => { routes.push(key); current = key } })
  assert.equal(driver.frameFor(current)?.step.id, step.id); cityButton(view).emit('touch-start')
  assert.deepEqual(routes, ['city']); assert.equal(driver.frameFor(current), null)
  current = 'army'; assert.equal(driver.frameFor(current)?.step.id, step.id); assert.equal(driver.finished, false)
})
for (const height of [443.6018957345971, 600, 640]) test(`Army实际header在960×${height}入口自身盒位于Guide洞内且避开气泡/自动续训/治疗`, () => {
  const { view } = army(height), button = cityButton(view), box = button.getComponent(UITransform)!;
  const rect = { x: button.position.x - box.width / 2, y: button.position.y - box.height / 2, width: box.width, height: box.height }
  const area = { x: -426.5402843601896, y: -height / 2 + 83.88625592417056, width: 853.0805687203791, height: height - 83.88625592417056 }
  assert.ok(rect.x >= area.x && rect.y >= area.y && rect.x + rect.width <= area.x + area.width && rect.y + rect.height <= area.y + area.height)
  const bubble = guideBubbleLayout(area, 960, height, 'army', { width: 420, height: 150, margin: 24, buttonWidth: 150 })
  assert.ok(rect.x + rect.width < bubble.x - bubble.width / 2 || rect.x > bubble.x + bubble.width / 2
    || rect.y + rect.height < bubble.y - bubble.height / 2 || rect.y > bubble.y + bubble.height / 2)
  for (const name of ['AutoTrainButton', 'TreatButton']) {
    const other = view.node.getChildByName(name)!; const own = other.getComponent(UITransform)!;
    assert.equal(button.position.y, other.position.y)
    assert.ok(Math.abs(button.position.x - other.position.x) > (box.width + own.width) / 2)
  }
})
test('入口Caption是真按钮child，自身文字盒保持14/20单行并完整容纳', () => {
  const { view } = army(), button = cityButton(view), caption = button.getChildByName('Caption')!;
  const label = caption.getComponent(Label)!; const own = caption.getComponent(UITransform)!, target = button.getComponent(UITransform)!;
  assert.equal(caption.parent, button); assert.equal(label.fontSize, 14); assert.equal(label.lineHeight, 20); assert.equal(label.enableWrapText, false)
  assert.ok(own.width > 0 && own.width <= target.width && own.height > 0 && own.height <= target.height)
  assert.equal(caption.position.x, 0); assert.equal(caption.position.y, 0)
})
test('命令贴图不可用仍建实际Graphics入口和同一导航事件', () => {
  const { view } = army(443.6018957345971, false), button = cityButton(view); let calls = 0; view.onOpenCity = () => { calls++ }
  assert.ok(button.getComponent(Graphics)); button.emit('touch-start'); assert.equal(calls, 1)
})
test('销毁Army后残余按钮事件不能再次调用旧导航回调', () => {
  const { view } = army(), button = cityButton(view); let calls = 0; view.onOpenCity = () => { calls++ }
  view.onDestroy(); button.emit('touch-start'); assert.equal(calls, 0); assert.equal(view.onOpenCity, null)
})
test('600创建后short→600→640原位跟随高度，列表未到不render/request且点击仍可导航', () => {
  const { view, resize } = army(600), button = cityButton(view), own = button.getComponent(UITransform)!;
  let navigation = 0; view.onOpenCity = () => { navigation++ }
  const original = button.setPosition.bind(button); let positions = 0
  button.setPosition = position => { positions++; original(position) }
  for (const height of [443.6018957345971, 600, 640]) {
    resize(height); view.update(); view.update()
    assert.equal(view.node.getChildByName('OpenCityButton'), button)
    assert.ok(button.position.y - own.height / 2 >= -height / 2 + 83.88625592417056)
    assert.ok(button.position.y + own.height / 2 <= height / 2)
    const area = { x: -426.5402843601896, y: -height / 2 + 83.88625592417056, width: 853.0805687203791, height: height - 83.88625592417056 }
    const bubble = guideBubbleLayout(area, 960, height, 'army', { width: 420, height: 150, margin: 24, buttonWidth: 150 })
    assert.ok(button.position.x + own.width / 2 < bubble.x - bubble.width / 2
      || button.position.x - own.width / 2 > bubble.x + bubble.width / 2
      || button.position.y + own.height / 2 < bubble.y - bubble.height / 2
      || button.position.y - own.height / 2 > bubble.y + bubble.height / 2)
    button.emit('touch-start')
  }
  assert.equal(positions, 3, '同高度第二帧不得重复重排'); assert.equal(navigation, 3)
})
