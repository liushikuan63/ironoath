/** Executes real scene/event methods. Stubs do not prove native drawing or touch. */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { CITY_GRID_FALLBACK_SIZE, buildCityGrid, buildCityPanel } from '../assets/scripts/game/city/CityPanel'
import type { BuildingRow, CityGrid, CityPanelView as CityData } from '../assets/scripts/game/city/CityPanel'
import { buildBuildChoices } from '../assets/scripts/game/session/Choices'
import { GuideDriver } from '../assets/scripts/game/guide/GuideDriver'
import * as anchors from '../assets/scripts/game/city/CitySceneAnchors'
import type { CityHarvestLearningResp, CityListResp } from '../assets/scripts/net/generated/CityProtocol'
import type { GuideStepView } from '../assets/scripts/net/generated/GuideProtocol'
import { PANEL_FRAME_BAND, buildingArtFootRatio } from '../assets/scripts/game/art/ArtFamilies'
import { guideBubbleLayout } from '../assets/scripts/game/ui/GuideLayout'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0; anchorX = .5; anchorY = .5
  setContentSize(size: Size | number, height?: number): void { this.width = typeof size === 'number' ? size : size.width; this.height = typeof size === 'number' ? height! : size.height }
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
}
class Label {
  static Overflow = { NONE: 0, SHRINK: 2 }; static HorizontalAlign = { CENTER: 1, LEFT: 0 }; static VerticalAlign = { CENTER: 1 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 40; color: unknown; enableWrapText = true
  overflow = 0; horizontalAlign = 0; verticalAlign = 0
}
class Graphics {
  fillColor: unknown; strokeColor: unknown; lineWidth = 0
  clear(): void {} rect(..._a: number[]): void {} roundRect(..._a: number[]): void {} fill(): void {} stroke(): void {}
}
class Sprite {}
class Node {
  active = true; layer = 1; parent: Node | null = null; position = new Vec3(); readonly children: Node[] = []
  private readonly components = new Map<unknown, unknown>(); private readonly events = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  setPosition(value: Vec3): void { this.position = value }
  addComponent<T extends object>(type: new () => T): T { const value = new type(); Object.assign(value, { node: this }); this.components.set(type, value); return value }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(node => node.name === name) ?? null }
  on(type: string, handler: () => void): void { this.events.set(type, handler) }
  off(type: string): void { this.events.delete(type) }
  hasEventListener(type: string): boolean { return this.events.has(type) }
  emit(type: string): void { this.events.get(type)?.() }
}
class Component { node = new Node('city'); isValid = true }
class Color { constructor(..._values: number[]) {} }
class NodePool { destroy(): void {} }
class Picker {
  options: ReturnType<typeof buildBuildChoices> = []; active = false
  private pick: ((id: string) => void) | null = null; private closed: (() => void) | null = null
  show(options: ReturnType<typeof buildBuildChoices>, pick: (id: string) => void, closed: () => void): void { this.options = options; this.pick = pick; this.closed = closed; this.active = true }
  hide(): void { const closed = this.closed; this.closed = null; this.pick = null; this.active = false; closed?.() }
  choose(id: string): void { const pick = this.pick; this.hide(); pick?.(id) } // actual ChoiceOverlay also hides before picking
}
interface CityHarness {
  node: Node; card: Node; panel: CityData; resp: CityListResp; selectedId: string | null; buildMode: boolean
  contentWidth: number; contentHeight: number; safeInsets: { left: number; right: number; top: number; bottom: number }
  viewAdjusted: boolean; gridTiles: Array<{ node: Node; icon: Node; plate: anchors.ProjectedPlate }>
  buildPicker: Picker; selectionBar: Node; selectedTitle: Label
  onUpgrade: ((id: string, x?: number, y?: number) => void) | null
  onCollect: ((id: string | null) => void) | null
  onStamina: (() => void) | null
  attachHarvestLearning(resp: CityHarvestLearningResp): void
  openResourceTutorial(): void
  buildActionBar(parent: Node): void; renderSelection(row: BuildingRow | null): void
  buildCard(): void; buildGrid(): void
  renderGrid(grid: CityGrid): void; render(): void; paintTile(): void; syncPreviews(): void
  mainCityFraming(footY: number, preferred: number, footX?: number): { size: number; stageY: number }
}
const repo = existsSync(path.resolve(process.cwd(), 'client/assets')) ? process.cwd() : path.resolve(process.cwd(), '..')
const scene = path.join(repo, 'client/assets/scripts/scene')
function exportsFor(file: string, values: Record<string, unknown>): Record<string, unknown> {
  const source = readFileSync(path.join(scene, file + '.ts'), 'utf8'), ast = ts.createSourceFile(file + '.ts', source, ts.ScriptTarget.ES2020, true)
  const stripped = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast, ast.statements.filter(s => !ts.isImportDeclaration(s))))
  const code = ts.transpileModule(stripped, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code + (file === 'CityPanelView' ? '\nexports.buildingIconSize = buildingIconSize;' : ''), { exports, _decorator: { ccclass: () => (klass: unknown) => klass }, Node, Component, Color, Vec3, Size, UITransform, Label, Graphics, Sprite, NodePool, ...values })
  return exports
}
function response(): CityListResp {
  return { buildings: [{ id: 'bld_main_city', configId: 'main_city', name: '主城', level: 3, gridX: 3, gridY: 3, status: 'IDLE',
    finishAt: null, remainingSeconds: null, progress: 0, startedAt: 0, totalSeconds: 0, helpCount: 0 }],
    buildOptions: [{ configId: 'barracks', name: '兵营', type: 'MILITARY', requireMainLevel: 2, requireBuilding: null, requireBuildingName: null }],
    queues: { used: 0, available: 2, max: 3 }, resources: {} as CityListResp['resources'],
    // 网格边长由服务端下发（city_rule.city_rule_grid_size）⇒ 夹具必须带它。
    gridSize: 6, serverNow: 1000 }
}
function setup(height = 443.6018957345971, themed = true): { city: CityHarness; commands: string[]; view: { getVisibleSize(): { width: number; height: number } }; buildingSize(id: string, width: number, height: number): number } {
  const commands: string[] = [], view = { getVisibleSize: () => ({ width: 960, height }) }
  const font = exportsFor('UiFont', {})
  const applyCommandButton = (node: Node): boolean => { commands.push(node.name); return themed }
  const sceneExports = exportsFor('CityPanelView', { ...anchors, PANEL_FRAME_BAND, buildingArtFootRatio, CITY_GRID_FALLBACK_SIZE, buildCityGrid, buildCityPanel, buildBuildChoices, guideBubbleLayout, ...font, view, sys: { now: () => 1000 }, applyCommandButton })
  const City = sceneExports['CityPanelView'] as new () => CityHarness
  const city = new City(); city.contentWidth = 960; city.contentHeight = height; city.card = city.node
  // R8 actual logical viewport/capsule/insets, rather than a zero-inset screen.
  city.safeInsets = { left: 53.4597156398104, right: 53.4597156398104, top: 58.0473933649289, bottom: 23.8862559241706 }
  city.resp = response(); city.panel = buildCityPanel(city.resp, 0, 1000); city.selectedId = 'bld_main_city'; city.viewAdjusted = true
  // Asset painting is outside this event regression; real render/grid/selection are still invoked.
  city.paintTile = () => {}; city.syncPreviews = () => {}
  city.gridTiles = anchors.projectSceneLayout(960, height, 0).plates.map(plate => { const node = new Node('Grid-' + (plate.gridY * 6 + plate.gridX)), icon = new Node('BuildingIcon'); icon.addComponent(UITransform).setContentSize(new Size(48, 48)); return { node, icon, plate } })
  city.buildGrid = () => {}
  city.buildPicker = new Picker(); city.buildCard(); city.render()
  return { city, commands, view, buildingSize: sceneExports.buildingIconSize as (id: string, width: number, height: number) => number }
}
function action(city: CityHarness, name: string): Node { const node = city.selectionBar.getChildByName(name); assert.ok(node); return node }
test('收割入口在无权威回执或未解锁时只打开资源教学，服务端开启后才发送批量请求', () => {
  const { city } = setup()
  let tutorials = 0
  const requests: Array<string | null> = []
  city.openResourceTutorial = () => { tutorials++ }
  city.onCollect = id => requests.push(id)
  const button = city.node.getChildByName('CollectAllButton')!
  assert.ok(button)
  button.emit('touch-start'); assert.equal(tutorials, 1); assert.deepEqual(requests, [])
  city.attachHarvestLearning({ resources: [], collectAllUnlocked: false,
    unlockReason: '请先逐种手动采集木材、石料、铁矿、粮草。', requiredMainLevel: 5, serverNow: 1000 })
  assert.equal(button.getChildByName('Caption')!.getComponent(Label)!.string, '资源教学')
  button.emit('touch-start'); assert.equal(tutorials, 2); assert.deepEqual(requests, [])
  // 用不同阈值反证客户端没有硬编码等级：只消费服务端最终结论。
  city.attachHarvestLearning({ resources: [], collectAllUnlocked: true,
    unlockReason: '资源教学完成，一键收割已开启。', requiredMainLevel: 99, serverNow: 1000 })
  assert.equal(button.getChildByName('Caption')!.getComponent(Label)!.string, '一键收割')
  button.emit('touch-start'); assert.deepEqual(requests, [null]); assert.equal(tutorials, 2)
})

test('建筑已空闲时也根据服务端的待确认产出显示手动收割键，不以本地计时冒充学习状态', () => {
  const { city } = setup()
  city.attachHarvestLearning({ resources: [{ type: 'WOOD', name: '木材', description: '测试服务端说明',
    manualRequired: true, manuallyCollected: false, collectableBuildingId: 'bld_main_city' }],
    collectAllUnlocked: false, unlockReason: '手动教学未完成', requiredMainLevel: 5, serverNow: 1000 })
  assert.equal(action(city, 'DetailCollectButton').active, true)
  const sent: Array<string | null> = []; city.onCollect = id => sent.push(id)
  action(city, 'DetailCollectButton').emit('touch-start'); assert.deepEqual(sent, ['bld_main_city'])
  city.attachHarvestLearning({ resources: [], collectAllUnlocked: false,
    unlockReason: '暂无可确认产出', requiredMainLevel: 5, serverNow: 1000 })
  assert.equal(action(city, 'DetailCollectButton').active, false)
})
function main(city: CityHarness): BuildingRow { const row = city.panel.rows.find(row => row.id === 'bld_main_city'); assert.ok(row); return row }
function empty(city: CityHarness): Node { const node = city.gridTiles.find(tile => tile.plate.gridX === 1 && tile.plate.gridY === 2)?.node; assert.ok(node); return node }
function own(node: Node): { left: number; right: number; bottom: number; top: number } { const ui = node.getComponent(UITransform)!; let x = node.position.x, y = node.position.y; for (let p = node.parent; p; p = p.parent) { x += p.position.x; y += p.position.y } return { left: x - ui.anchorX * ui.width, right: x + (1 - ui.anchorX) * ui.width, bottom: y - ui.anchorY * ui.height, top: y + (1 - ui.anchorY) * ui.height } }
function overlaps(a: ReturnType<typeof own>, b: ReturnType<typeof own>): boolean { return a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom }
function productionOpenCity(nav: { show(key: string): void }): () => void {
  const source = readFileSync(path.join(scene, 'GameBootstrap.ts'), 'utf8'), ast = ts.createSourceFile('GameBootstrap.ts', source, ts.ScriptTarget.ES2020, true)
  let callback: ts.Expression | undefined
  const scan = (node: ts.Node): void => { if (ts.isBinaryExpression(node) && ts.isPropertyAccessExpression(node.left) && node.left.expression.getText(ast) === 'army' && node.left.name.text === 'onOpenCity') callback = node.right; ts.forEachChild(node, scan) }
  scan(ast); assert.ok(callback, 'production army.onOpenCity is required')
  const code = ts.transpileModule('exports.callback = (function () { return (' + callback.getText(ast) + ') }).call(host)', { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, host: { nav } }); return exports.callback as () => void
}
test('Guide1主城3遗留选中仍有真实建造事件，原升级继续可用', () => {
  const { city } = setup(); assert.equal(city.selectedId, 'bld_main_city'); assert.equal(main(city).level, 3)
  assert.equal(action(city, 'DetailBuildButton').active, true); assert.equal(action(city, 'DetailBuildButton').hasEventListener('touch-start'), true)
  const upgrades: string[] = []; city.onUpgrade = id => upgrades.push(id); action(city, 'DetailUpgradeButton').emit('touch-start'); assert.deepEqual(upgrades, ['main_city'])
})
test('真实Army生产导航回城保留主3，Army服务端步骤自然隐藏且建造可达', () => {
  const { city, view } = setup(), font = exportsFor('UiFont', {})
  const Army = exportsFor('ArmyPanelView', { ...font, view, sys: { isBrowser: false }, applyCommandButton: () => true })['ArmyPanelView'] as new () => { node: Node; onLoad(): void; onOpenCity: (() => void) | null }
  const army = new Army(); army.onLoad()
  const table = JSON.parse(readFileSync(path.join(repo, 'contract/config/guide.json'), 'utf8')) as { version: number; rows: GuideStepView[] }, step = table.rows.find(row => row.panelKey === 'army'); assert.ok(step)
  const driver = GuideDriver.from({ version: String(table.version), applies: true, nextStepIndex: step.stepIndex, steps: [step], serverNow: 0 }); assert.ok(driver)
  let current = 'army'; army.onOpenCity = productionOpenCity({ show: key => { current = key; city.render() } })
  army.node.getChildByName('OpenCityButton')!.emit('touch-start')
  assert.equal(current, 'city'); assert.equal(driver.frameFor(current), null); assert.equal(driver.finished, false)
  assert.equal(city.selectedId, 'bld_main_city'); assert.equal(action(city, 'DetailBuildButton').active, true)
})
test('真实buildMode挂空地事件，服务端兵营候选只走既有placement回调', () => {
  const { city } = setup(), calls: Array<{ id: string; x: number | undefined; y: number | undefined }> = []; city.onUpgrade = (id, x, y) => calls.push({ id, x, y })
  action(city, 'DetailBuildButton').emit('touch-start'); assert.equal(city.buildMode, true)
  assert.equal(empty(city).hasEventListener('touch-start'), true); empty(city).emit('touch-start')
  assert.equal(city.selectionBar.active, false); assert.equal(city.buildPicker.active, true); assert.equal(city.buildPicker.options[0]?.label, '兵营')
  city.buildPicker.choose(city.buildPicker.options[0]!.id)
  assert.deepEqual(calls, [{ id: 'barracks', x: 1, y: 2 }]); assert.equal(city.buildMode, false)
})
test('选择器取消恢复原主3/升级/建造，退出后的空地不继续建造', () => {
  const { city } = setup(); let writes = 0; city.onUpgrade = () => writes++
  action(city, 'DetailBuildButton').emit('touch-start'); empty(city).emit('touch-start'); city.buildPicker.hide()
  assert.equal(city.buildMode, false); assert.equal(city.selectedId, 'bld_main_city'); assert.equal(city.selectedTitle.string, '主城 Lv3')
  assert.equal(action(city, 'DetailUpgradeButton').active, true); assert.equal(action(city, 'DetailBuildButton').active, true)
  assert.equal(empty(city).hasEventListener('touch-start'), false); empty(city).emit('touch-start'); assert.equal(writes, 0)
})
test('选地可取消恢复原选择，秒级render不丢目标或发请求', () => {
  const { city } = setup(); let writes = 0; city.onUpgrade = () => writes++
  action(city, 'DetailBuildButton').emit('touch-start'); city.render(); city.render()
  assert.equal(action(city, 'DetailBuildButton').getChildByName('Caption')?.getComponent(Label)?.string, '取消建造')
  action(city, 'DetailBuildButton').emit('touch-start'); assert.equal(city.buildMode, false); assert.equal(city.selectedId, 'bld_main_city'); assert.equal(writes, 0)
})
test('无服务端buildOptions不造候选，存量升级仍可用', () => {
  const { city } = setup(); city.resp.buildOptions = []; city.panel = buildCityPanel(city.resp, 0, 1000); city.render()
  assert.equal(action(city, 'DetailBuildButton').active, false); assert.equal(action(city, 'DetailBuildButton').hasEventListener('touch-start'), false)
  assert.equal(action(city, 'DetailUpgradeButton').active, true); assert.equal(city.selectedId, 'bld_main_city')
})
test('短屏/常规屏入口自身盒在净区并避开研究/升级，主题child文字完整容纳', () => {
  for (const height of [443.6018957345971, 600, 640]) {
    const { city, commands } = setup(height), build = action(city, 'DetailBuildButton'), box = own(build), tech = city.node.getChildByName('TechOpenButton')!
    assert.ok(commands.includes('DetailBuildButton'))
    assert.ok(box.left >= -480 + city.safeInsets.left && box.right <= 480 - city.safeInsets.right && box.bottom >= -height / 2 + city.safeInsets.bottom + 76 && box.top <= height / 2 - city.safeInsets.top)
    assert.equal(overlaps(box, own(tech)), false); assert.equal(overlaps(box, own(action(city, 'DetailUpgradeButton'))), false)
    const caption = build.getChildByName('Caption')!, label = caption.getComponent(Label)!, text = own(caption)
    assert.equal(caption.parent, build); assert.equal(label.string, '建造'); assert.equal(label.enableWrapText, false); assert.ok(label.lineHeight < 40)
    assert.ok(text.left >= box.left && text.right <= box.right && text.bottom >= box.bottom && text.top <= box.top)
  }
})
test('缺主题仍有Graphics同入口，选中升级状态也不丢建造/取消', () => {
  const { city } = setup(443.6018957345971, false), button = action(city, 'DetailBuildButton'); assert.ok(button.getComponent(Graphics))
  const upgrading = { ...main(city), upgrading: true, countdownText: '01:00', progressText: '0%' }; city.panel = { ...city.panel, rows: [upgrading] }; city.selectedId = upgrading.id; city.render()
  assert.equal(button.active, true); button.emit('touch-start'); assert.equal(city.buildMode, true); button.emit('touch-start'); assert.equal(city.selectedId, upgrading.id)
})
test('真实City标签不继承40行高：限宽标题/状态/队列/资源保持字号，caption自身盒在按钮内', () => {
  const { city } = setup(), labels: Label[] = []
  const scan = (node: Node): void => { const label = node.getComponent(Label); if (label !== null) labels.push(label); node.children.forEach(scan) }; scan(city.node)
  for (const label of labels) {
    const box = label.node.getComponent(UITransform)!
    assert.equal(label.lineHeight, label.fontSize + 6, label.node.name + ' inherits the engine default 40')
    assert.ok(box.height >= label.lineHeight, label.node.name + ' box must hold its configured line height')
    if (label.overflow === Label.Overflow.SHRINK) {
      const scale = 2.6375, raw = Math.floor(label.fontSize * scale * Math.min(1, box.height / label.lineHeight))
      assert.equal(raw, Math.floor(label.fontSize * scale), 'Synthetic line-height-only SHRINK model must preserve target size; actual native fonts remain separate')
    }
    if (label.node.name === 'Caption') {
      const button = label.node.parent!; assert.equal(label.enableWrapText, false)
      assert.ok(box.width > 0 && box.width <= button.getComponent(UITransform)!.width)
      assert.ok(box.height <= button.getComponent(UITransform)!.height)
    }
  }
})
test('字号恢复后normal-short Header/Queue/资源自身盒分开，消息避研究/建造并完整在净区', () => {
  for (const height of [443.6018957345971, 600, 640]) {
    const { city } = setup(height), header = city.node.getChildByName('Header')!, queue = city.node.getChildByName('Queue')!, collect = city.node.getChildByName('CollectAllButton')!
    assert.equal(overlaps(own(header), own(queue)), false); assert.equal(overlaps(own(header), own(collect)), false); assert.equal(overlaps(own(queue), own(collect)), false)
    const plate = own(city.node.getChildByName('ResourcePlate')!), first = city.node.getChildByName('Resource-0-0')!
    assert.ok(own(queue).bottom - own(first).top >= 4)
    for (let column = 0; column < 2; column++) for (let row = 0; row < 3; row++) {
      const node = city.node.getChildByName('Resource-' + row + '-' + column)!, box = own(node)
      assert.ok(box.left >= plate.left && box.right <= plate.right && box.top <= plate.top && box.bottom >= plate.bottom)
      if (row > 0) assert.equal(overlaps(box, own(city.node.getChildByName('Resource-' + (row - 1) + '-' + column)!)), false)
    }
    const message = own(city.node.getChildByName('Message')!)
    assert.equal(overlaps(message, own(action(city, 'DetailBuildButton'))), false); assert.equal(overlaps(message, own(city.node.getChildByName('TechOpenButton')!)), false)
    assert.ok(message.left >= -480 + city.safeInsets.left && message.right <= 480 - city.safeInsets.right && message.bottom >= -height / 2 + city.safeInsets.bottom + 76 && message.top <= height / 2 - city.safeInsets.top)
    assert.equal(overlaps(own(city.node.getChildByName('ResourcePlate')!), own(action(city, 'DetailBuildButton'))), false)
    assert.equal(overlaps(own(city.node.getChildByName('ResourcePlate')!), own(city.node.getChildByName('TechOpenButton')!)), false)
    const title = own(city.selectionBar.getChildByName('SelectedTitle')!), status = own(city.selectionBar.getChildByName('SelectedStatus')!), bar = own(city.selectionBar)
    assert.ok(title.bottom - status.top >= 4, 'Selected title and status require an own-box gap')
    assert.ok(title.top <= bar.top && status.bottom >= bar.bottom)
  }
})

test('首次默认Guide真实placeBubble不覆盖完整City Header/Queue及资源教学入口自身盒', () => {
  for (const height of [443.6018957345971, 600, 640]) {
    const { city } = setup(height)
    const Guide = exportsFor('GuideView', { guideBubbleLayout, applySlicedSprite: () => false, DIALOG_SCRIM: new Color() })['GuideView'] as new () => {
      node: Node; bubble: Node; openPanelKey: () => string
      placeBubble(width: number, height: number, hole: { x: number; y: number; width: number; height: number }): void
    }
    const guide = new Guide(); guide.bubble = new Node('GuideBubble'); guide.bubble.addComponent(UITransform); guide.bubble.addComponent(Graphics); guide.node.addChild(guide.bubble); guide.openPanelKey = () => 'city'
    const plate = new Node('GuideBubblePlate'); plate.addComponent(UITransform); guide.bubble.addChild(plate)
    const safe = city.safeInsets
    guide.placeBubble(960, height, { x: -480 + safe.left, y: -height / 2 + safe.bottom + 60,
      width: 960 - safe.left - safe.right, height: height - safe.top - safe.bottom - 60 })
    for (const name of ['Header', 'Queue', 'CollectAllButton']) assert.equal(overlaps(own(city.node.getChildByName(name)!), own(guide.bubble)), false, name + ' full own-box is hidden by the first Guide')
    const collect = own(city.node.getChildByName('CollectAllButton')!)
    assert.ok(own(guide.bubble).bottom - collect.top >= 8 - 1e-7, 'Resource teaching needs an actual gap below the Guide')
    assert.ok(collect.bottom >= -height / 2 + safe.bottom + 60, 'Resource teaching must stay above navigation')
    for (const node of [city.node.getChildByName('ResourcePlate')!, city.node.getChildByName('Message')!, city.node.getChildByName('TechOpenButton')!, action(city, 'DetailBuildButton')]) {
      assert.equal(overlaps(collect, own(node)), false, 'Resource teaching overlaps ' + node.name + ' ' + JSON.stringify({ height, collect, other: own(node) }))
    }
  }
})

test('HUD重排后的真实default mainCityFraming保铭牌4px净区，各档主堡与Guide二维相隔12px', () => {
  for (const height of [443.6018957345971, 600, 640]) {
    const { city, buildingSize } = setup(height), row = city.resp.buildings[0]!, tile = city.gridTiles.find(tile => tile.plate.gridX === row.gridX && tile.plate.gridY === row.gridY)!
    city.panel = { ...city.panel, resourceLines: Array.from({ length: 6 }, (_, i) => ({ text: 'unit display line ' + i, type: 'WOOD' as const })) }; city.render()
    city.node.getChildByName('Message')!.getComponent(Label)!.string = 'unit action hint'
    const zoom = 1.5, framing = city.mainCityFraming(tile.plate.y, buildingSize('main_city', tile.plate.width, height), tile.plate.x), ratio = buildingArtFootRatio('main_city')
    // Production paintTile first sizes the icon, then renderGrid fits focus with that actual icon height.
    const focusFraming = city.mainCityFraming(tile.plate.y, framing.size, tile.plate.x)
    assert.ok(Math.abs(focusFraming.size - framing.size) < 1e-7 && Math.abs(focusFraming.stageY - framing.stageY) < 1e-7,
      'Real sizing/focus passes must produce the same default geometry')
    const foot = tile.plate.y * zoom + framing.stageY
    const castle = { bottom: foot - framing.size * ratio * zoom, top: foot + framing.size * (1 - ratio) * zoom }
    const caption = { bottom: foot + (-16 - 27 / 2) * zoom, top: foot + (-16 + 27 / 2) * zoom }
    const lower = own(city.selectionBar).top, upper = own(city.node.getChildByName('Queue')!).bottom
    assert.ok(Math.min(castle.bottom, caption.bottom) >= lower + 4 - 1e-7)
    assert.ok(Math.max(castle.top, caption.top) <= upper - 4 + 1e-7)
    const maxFocus = 960 * (zoom - 1) / (2 * zoom), focus = Math.max(-maxFocus, Math.min(maxFocus, tile.plate.x))
    const castleRight = tile.plate.x * zoom - focus * zoom + framing.size * zoom / 2
    const safe = city.safeInsets, bottom = -height / 2 + safe.bottom + 60
    const bubble = guideBubbleLayout({ x: -480 + safe.left, y: bottom, width: 960 - safe.left - safe.right,
      height: height / 2 - safe.top - bottom }, 960, height, 'city', { width: 420, height: 150, margin: 24, buttonWidth: 150 })
    const gap = bubble.x - bubble.width / 2 - castleRight
    const castleLeft = castleRight - framing.size * zoom
    const gap2d = Math.max(gap, castleLeft - (bubble.x + bubble.width / 2), bubble.y - bubble.height / 2 - castle.top, castle.bottom - (bubble.y + bubble.height / 2))
    if (height < 600) assert.ok(gap >= 12, 'Actual short-screen framing must retain the castle/Guide gap')
    console.log(JSON.stringify({ scope: 'pure production geometry, not native draw', height, framing, nameplateGap: caption.bottom - lower, castleGuideHorizontalGap: gap, castleGuideSeparatingAxisGap: gap2d,
      castleGuideOwnOverlap: castleRight > bubble.x - bubble.width / 2 && castle.top > bubble.y - bubble.height / 2 && castle.bottom < bubble.y + bubble.height / 2 }))
    assert.ok(gap2d >= 12 - 1e-7, 'Default castle must leave a 12px gap along a separating axis of the actual Guide rectangle')
    const bodyBox = { left: castleLeft, right: castleRight, ...castle }, centerX = (castleLeft + castleRight) / 2
    const captionBox = { left: centerX - 96 * zoom / 2, right: centerX + 96 * zoom / 2, ...caption }
    const hud = [city.node.getChildByName('ResourcePlate')!, city.node.getChildByName('Header')!, city.node.getChildByName('Queue')!,
      city.node.getChildByName('Message')!, city.node.getChildByName('CollectAllButton')!, city.node.getChildByName('TechOpenButton')!, action(city, 'DetailBuildButton'),
      ...city.node.children.filter(node => node.name.startsWith('Resource-') && node.getComponent(Label)?.string !== '')]
    for (const node of hud) {
      assert.equal(overlaps(bodyBox, own(node)), false, 'Default main body overlaps ' + node.name)
      assert.equal(overlaps(captionBox, own(node)), false, 'Default main nameplate overlaps ' + node.name)
    }
  }
})

test('服务端展示行超过现有六槽时原提示仍完整显示，自己的盒不占消息/详情/建造/研究/缩放键', () => {
  for (const height of [443.6018957345971, 600, 640]) {
    const { city } = setup(height)
    city.panel = { ...city.panel, resourceLines: Array.from({ length: 7 }, (_, i) => ({ text: 'unit display line ' + i, type: 'WOOD' as const })) }; city.render()
    const warning = city.node.getChildByName('ResourceOverflow')!, box = own(warning)
    assert.equal(warning.getComponent(Label)?.string, '另有 1 项资源未显示')
    const avoid = [action(city, 'DetailBuildButton'), action(city, 'DetailUpgradeButton'), city.node.getChildByName('TechOpenButton')!,
      city.node.getChildByName('Message')!, city.node.getChildByName('ZoomInButton')!, city.node.getChildByName('ZoomOutButton')!,
      city.selectionBar.getChildByName('SelectedTitle')!, city.selectionBar.getChildByName('SelectedStatus')!]
    for (const node of avoid) assert.equal(overlaps(box, own(node)), false, 'ResourceOverflow own box overlaps ' + node.name)
  }
})

// ---------- 资源条按「种类」认，不按中文名前缀（#2207） ----------

test('体力那一行按资源种类（STAMINA）认，不按中文名前缀：中文名改了也不影响可点性', () => {
  const { city } = setup()
  let opened = 0
  city.onStamina = () => { opened++ }
  // 故意把显示文本换成**不是中文名**的内容：界面若还按前缀认「体力」，
  // 这一行就点不动 —— 而那正是旧实现的形状（`line.startsWith('体力')`）。
  city.panel = {
    ...city.panel,
    resourceLines: [
      { text: '任意一行显示文本 1/2', type: 'WOOD' },
      { text: '任意一行显示文本 3/4', type: 'STAMINA' },
    ],
  }
  city.render()

  const staminaRow = city.node.children.find(node =>
    node.name.startsWith('Resource-') && node.getComponent(Label)?.string === '任意一行显示文本 3/4')
  assert.ok(staminaRow, '第二行应当被画出来')
  assert.ok(staminaRow!.hasEventListener('touch-start'), 'STAMINA 那一行必须可点')
  staminaRow!.emit('touch-start')
  assert.equal(opened, 1, '点 STAMINA 那一行必须打开体力详情')
})

test('非 STAMINA 的行不可点：按种类认，不会把别的资源误当成体力', () => {
  const { city } = setup()
  let opened = 0
  city.onStamina = () => { opened++ }
  city.panel = {
    ...city.panel,
    resourceLines: [
      { text: '木材 12000/12000', type: 'WOOD' },
      { text: '金币 300/12000', type: 'GOLD' },
    ],
  }
  city.render()

  for (const node of city.node.children.filter(node => node.name.startsWith('Resource-'))) {
    assert.equal(node.hasEventListener('touch-start'), false,
      '非 STAMINA 的资源行不应绑体力入口：' + node.name)
  }
  assert.equal(opened, 0)
})
