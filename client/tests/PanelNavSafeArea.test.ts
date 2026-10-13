/** 导航安全区回归：执行生产布局方法，只用节点/尺寸桩记录几何，不模拟 Cocos 渲染。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'

interface Insets { left: number, right: number, top: number, bottom: number }
interface Rect { x: number, y: number, width: number, height: number }
class Size {
  constructor(public width: number, public height: number) {}
}
class Vec3 {
  constructor(public x = 0, public y = 0, public z = 0) {}
}
class UITransform {
  width = 0
  height = 0
  setContentSize(size: Size): void { this.width = size.width; this.height = size.height }
}
class Graphics {
  fillColor: unknown
  strokeColor: unknown
  lineWidth = 0
  roundRect(..._args: number[]): void {}
  fill(): void {}
  stroke(): void {}
}
class Node {
  layer = 0
  active = true
  destroyed = false
  position = new Vec3()
  parent: Node | null = null
  readonly children: Node[] = []
  private readonly components = new Map<Function, unknown>()
  private readonly handlers = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(child: Node): void { child.parent = this; this.children.push(child) }
  setPosition(value: Vec3): void { this.position = value }
  addComponent<T>(type: new () => T): T {
    const instance = new type()
    this.components.set(type, instance)
    return instance
  }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(node => node.name === name) ?? null }
  on(type: string, handler: () => void): void { this.handlers.set(type, handler) }
  emit(type: string): void { this.handlers.get(type)?.() }
  destroy(): void {
    this.destroyed = true
    if (this.parent !== null) this.parent.children.splice(this.parent.children.indexOf(this), 1)
  }
}
interface Harness {
  node: Node
  panelNodes: Map<string, Node>
  moreLayer: Node | null
  moreOpen: boolean
  buildBar(): void
  update(): void
  contentRectFor(key: string | null): Rect | null
}

const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets'))
  ? path.resolve(process.cwd(), 'client') : process.cwd()
const source = readFileSync(path.join(clientDir, 'assets/scripts/scene/PanelNav.ts'), 'utf8')
const ast = ts.createSourceFile('PanelNav.ts', source, ts.ScriptTarget.ES2020, true)
const panelClass = ast.statements.find(statement => ts.isClassDeclaration(statement)
  && statement.name?.text === 'PanelNav')
assert.ok(panelClass !== undefined && ts.isClassDeclaration(panelClass))
const methodNames = ['contentRectFor', 'buildBar', 'buildMoreLayer', 'update']
const methods = methodNames.map(name => {
  const method = panelClass.members.find(member => ts.isMethodDeclaration(member)
    && ts.isIdentifier(member.name) && member.name.text === name)
  assert.ok(method !== undefined, `找不到生产 ${name}，不能用测试中的另一套布局替代`)
  return method.getText(ast)
})
const constantNames = ['BAR_HEIGHT', 'MORE_COLUMNS', 'MORE_GAP', 'MORE_PADDING',
  'MORE_KEY', 'MORE_LABEL', 'MORE_KEYS', 'BAR_PANELS', 'MORE_PANELS']
const constants = constantNames.map(name => {
  const statement = ast.statements.find(item => ts.isVariableStatement(item)
    && item.declarationList.declarations.some(declaration => ts.isIdentifier(declaration.name)
      && declaration.name.text === name))
  assert.ok(statement !== undefined, `找不到生产导航常量 ${name}`)
  return statement.getText(ast)
})
const panels = /^const PANELS:[\s\S]*?= \[([\s\S]*?)^\]/m.exec(source)?.[1]
assert.ok(panels !== undefined)
const panelDefs = Array.from(panels.matchAll(/\{ key: '([^']+)', label: '([^']+)'/g),
  match => ({ key: match[1]!, label: match[2]! }))
const runtime = ts.transpileModule(`${constants.join('\n')}
  class NavHarness { ${methods.join('\n')} }; NavHarness;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.None },
}).outputText

function harness(width: number, height: number, initialInsets: Insets): {
  nav: Harness, setInsets: (value: Insets) => void, cells: Array<{ parent: Node, key: string, caption: string }>,
} {
  let insets = initialInsets
  const cells: Array<{ parent: Node, key: string, caption: string }> = []
  const Constructor = runInNewContext(runtime, {
    Node, Vec3, Size, UITransform, Graphics, PANELS: panelDefs,
    COLOR_BAR: 'bar', COLOR_IDLE: 'idle',
    view: { getVisibleSize: () => ({ width, height }) },
    viewportSafeInsets: () => insets,
    // 此夹具只验证导航/安全区几何；材质由共用助手故障回归和真实Cocos量具覆盖。
    applyPanelSurface: () => {},
  }) as new () => Harness
  const nav = new Constructor()
  const city = new Node('city')
  city.addComponent(UITransform)
  Object.assign(nav, {
    node: new Node('Game'), panelNodes: new Map([['city', city]]),
    visibleWidth: 0, visibleHeight: 0, safeInsets: zero, moreOpen: false, moreLayer: null,
    buttonNodes: new Map(), buttonLabels: new Map(), navDots: new Map(), cellWidths: new Map(),
    createCell: (parent: Node, key: string, caption: string) => cells.push({ parent, key, caption }),
    highlight: () => {}, refreshNavDots: () => {},
    setMoreOpen: (open: boolean) => { nav.moreOpen = open; if (nav.moreLayer !== null) nav.moreLayer.active = open },
  })
  nav.buildBar()
  return { nav, cells, setInsets: value => { insets = value } }
}
const zero = { left: 0, right: 0, top: 0, bottom: 0 }
const native = { left: 53.45971563981042, right: 53.45971563981042, top: 0, bottom: 23.886255924170617 }
const nativeHeight = 443.6018957345971
function bounds(node: Node): Rect {
  const box = node.getComponent(UITransform)
  assert.ok(box !== null)
  return { x: node.position.x - box.width / 2, y: node.position.y - box.height / 2,
    width: box.width, height: box.height }
}
function get(node: Node, name: string): Node {
  const child = node.getChildByName(name)
  assert.ok(child !== null, `缺少 ${name}`)
  return child
}
function near(actual: number, expected: number): void { assert.ok(Math.abs(actual - expected) < 1e-8, `${actual} != ${expected}`) }

test('Web 600/640 高且没有安全边距时，导航与抽屉保持原有几何', () => {
  for (const height of [600, 640]) {
    const { nav } = harness(960, height, zero)
    assert.deepEqual(bounds(get(nav.node, 'NavBar')), { x: -450, y: -height / 2 + 8, width: 900, height: 52 })
    assert.deepEqual(bounds(get(nav.moreLayer!, 'NavMoreTray')),
      { x: -450, y: -height / 2 + 68, width: 900, height: 168 })
    assert.deepEqual({ ...nav.contentRectFor('city')! },
      { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 })
  }
})

test('真实 844×390 原生安全区内的导航、抽屉、引导内容均避开刘海与底部', () => {
  const { nav } = harness(960, nativeHeight, native)
  const safeLeft = -480 + native.left, safeRight = 480 - native.right
  const safeBottom = -nativeHeight / 2 + native.bottom
  const bar = bounds(get(nav.node, 'NavBar'))
  const tray = bounds(get(nav.moreLayer!, 'NavMoreTray'))
  for (const box of [bar, tray]) {
    assert.ok(box.x >= safeLeft && box.x + box.width <= safeRight)
    assert.ok(box.y >= safeBottom && box.y + box.height <= nativeHeight / 2)
  }
  near(bar.y, safeBottom + 8)
  near(tray.y, bar.y + bar.height + 8)
  const content = nav.contentRectFor('city')!
  near(content.x, safeLeft)
  near(content.width, safeRight - safeLeft)
  near(content.y, bar.y + bar.height)
  near(content.y + content.height, nativeHeight / 2)
  near(bounds(get(nav.moreLayer!, 'NavMoreBackdrop')).y, bar.y + bar.height)
})

test('非对称安全边距让导航和抽屉一起移向净区中心，内容排除安全顶边', () => {
  const { nav } = harness(960, 640, { left: 80, right: 20, top: 10, bottom: 18 })
  const bar = get(nav.node, 'NavBar'), tray = get(nav.moreLayer!, 'NavMoreTray')
  assert.equal(bar.position.x, 30)
  assert.equal(tray.position.x, 30)
  assert.deepEqual({ ...nav.contentRectFor('city')! }, { x: -400, y: -242, width: 860, height: 552 })
})

test('尺寸不变但安全区变化时重建导航，保留抽屉开态与原有中文入口', () => {
  const { nav, setInsets, cells } = harness(960, nativeHeight, zero)
  const oldBar = get(nav.node, 'NavBar')
  const oldCells = cells.map(({ key, caption }) => ({ key, caption }))
  nav.moreOpen = true
  setInsets(native)
  nav.update()
  assert.equal(oldBar.destroyed, true)
  assert.equal(nav.moreOpen, true)
  assert.equal(nav.moreLayer?.active, true)
  assert.deepEqual(cells.slice(oldCells.length).map(({ key, caption }) => ({ key, caption })), oldCells)
  near(bounds(get(nav.node, 'NavBar')).y, -nativeHeight / 2 + native.bottom + 8)
  const backdrop = get(nav.moreLayer!, 'NavMoreBackdrop')
  backdrop.emit('touch-start')
  assert.equal(nav.moreOpen, false)
})

test('尺寸和安全区都不变时不重建；未知面板的内容区仍为空', () => {
  const { nav } = harness(960, 640, zero)
  const bar = get(nav.node, 'NavBar')
  nav.update()
  assert.equal(get(nav.node, 'NavBar'), bar)
  assert.equal(nav.contentRectFor(null), null)
  assert.equal(nav.contentRectFor('unknown'), null)
})
