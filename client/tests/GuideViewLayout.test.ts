/** 执行真实场景方法的几何和接线回归；节点桩不证明Cocos实画或原生触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { GuideDriver } from '../assets/scripts/game/guide/GuideDriver'
import { guideBubbleLayout } from '../assets/scripts/game/ui/GuideLayout'
import { buildQuestList, candidateLabel, chosenClaimReq } from '../assets/scripts/game/quest/QuestPanel'
import type { HeroChoicePrompt } from '../assets/scripts/game/quest/QuestPanel'
import type { GuideScriptResp } from '../assets/scripts/net/generated/GuideProtocol'
import type { QuestListResp } from '../assets/scripts/net/generated/QuestProtocol'
import * as paging from '../assets/scripts/game/ui/PanelPaging'

class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0
  setContentSize(size: Size | number, height?: number): void {
    this.width = typeof size === 'number' ? size : size.width
    this.height = typeof size === 'number' ? height! : size.height
  }
}
class Graphics {
  enabled = true; fillColor: unknown; strokeColor: unknown; lineWidth = 0
  readonly rects: number[][] = []
  clear(): void { this.rects.length = 0 }
  rect(...rect: number[]): void { this.rects.push(rect) }
  roundRect(...rect: number[]): void { this.rects.push(rect) }
  fill(): void {} stroke(): void {}
}
class Label {
  static Overflow = { SHRINK: 2 }; static HorizontalAlign = { LEFT: 0, CENTER: 1 }
  static VerticalAlign = { CENTER: 1 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown
  overflow = 0; horizontalAlign = 0; verticalAlign = 0; enableWrapText = true
}
class Node {
  layer = 1; active = true; parent: Node | null = null; position = new Vec3()
  readonly children: Node[] = []
  private readonly components = new Map<Function, unknown>()
  private readonly events = new Map<string, () => void>()
  constructor(readonly name: string) {}
  addChild(node: Node): void { node.parent = this; this.children.push(node) }
  setSiblingIndex(index: number): void {
    if (this.parent === null) return
    const siblings = this.parent.children
    siblings.splice(siblings.indexOf(this), 1)
    siblings.splice(Math.min(index, siblings.length), 0, this)
  }
  addComponent<T extends object>(type: new () => T): T {
    const component = new type(); Object.assign(component, { node: this }); this.components.set(type, component)
    return component
  }
  getComponent<T>(type: new () => T): T | null { return this.components.get(type) as T | undefined ?? null }
  getChildByName(name: string): Node | null { return this.children.find(child => child.name === name) ?? null }
  setPosition(position: Vec3): void { this.position = position }
  on(type: string, listener: () => void): void { this.events.set(type, listener) }
  emit(type: string): void { this.events.get(type)?.() }
  removeFromParent(): void {
    this.parent?.children.splice(this.parent.children.indexOf(this), 1); this.parent = null
  }
  destroy(): void { this.removeFromParent() }
}
class Component { node = new Node('host'); isValid = true }
class Color { constructor(..._values: number[]) {} }
interface Rect { x: number; y: number; width: number; height: number }
const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets')) ? path.resolve(process.cwd(), 'client') : process.cwd()
function sceneSource(name: string): string {
  const sourceDir = process.env.IRONOATH_GUIDE_TEST_SOURCE_DIR ?? path.join(clientDir, 'assets/scripts/scene')
  return readFileSync(path.join(sourceDir, name + '.ts'), 'utf8')
}
function sceneClass<T>(name: string, bindings: Record<string, unknown>): new () => T {
  const ast = ts.createSourceFile(name + '.ts', sceneSource(name), ts.ScriptTarget.ES2020, true)
  const source = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast,
    ast.statements.filter(statement => !ts.isImportDeclaration(statement))))
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Component, Color, Node, Label, Size, Vec3, UITransform, Graphics, ...bindings })
  return exports[name] as new () => T
}
interface GuideHarness {
  node: Node; isValid: boolean; bubble: Node | null; blockerNodes: Node[]; openPanelKey: () => string | null
  contentRectFor: () => Rect; attach(script: GuideScriptResp): void; repaint(): void; update(): void
  setBubbleSuppressed(panel: string, suppressed: boolean): void
  applyProgress(index: number | null, advanced?: boolean): void
}
interface QuestHarness {
  node: Node; promptPanel: Node | null; onPromptVisibility: ((visible: boolean) => void) | null
  onClaim: ((quest: string, hero: string | null) => void) | null; openPrompt: HeroChoicePrompt | null
  buildPrompt(): void; showPrompt(prompt: HeroChoicePrompt): void; hidePrompt(): void; onDestroy(): void
  attach(resp: QuestListResp): void; switchTab(key: string): void; rowPool: object | null
}
const nativeSize = { width: 960, height: 443.6018957345971 }
const nativeArea = { x: -426.5402843601896, y: -137.914691943128, width: 853.0805687203791, height: 359.7156398104265 }
function setup(panel = 'city', skippable = false, material = true): {
  guide: GuideHarness; setPanel(key: string): void; size: { width: number, height: number }; area: Rect
  restoreHeld(): void
} {
  const size = { ...nativeSize }, area = { ...nativeArea }
  const heldTimers: Array<() => void> = []
  let current = panel
  const art = (node: Node, _key: string, width: number, height: number): boolean => {
    node.getComponent(UITransform)?.setContentSize(width, height); return material
  }
  const GuideView = sceneClass<GuideHarness>('GuideView', { view: { getVisibleSize: () => size }, GuideDriver,
    applySystemUiFont: (label: Label) => label, DIALOG_SCRIM: {}, observeDialogSize: () => {},
    applySlicedSprite: art, applyCommandButton: art, guideBubbleLayout,
    setTimeout: (callback: () => void) => { heldTimers.push(callback); return 1 }, clearTimeout: () => {} })
  const guide = new GuideView()
  guide.openPanelKey = () => current; guide.contentRectFor = () => area
  guide.attach({ version: 'server-version', nextStepIndex: 1, applies: true, serverNow: 1,
    steps: [{ id: 'server-current', name: '服务端当前帧', stepIndex: 1, panelKey: panel,
      highlightPath: panel, maskArea: 'full', text: '服务端下发正文', trigger: 'PANEL_OPEN', skippable }] })
  return { guide, setPanel: key => { current = key }, size, area,
    restoreHeld: () => { heldTimers.pop()?.() } }
}
function dimensions(node: Node): UITransform { return node.getComponent(UITransform)! }
function bubbleRect(guide: GuideHarness, size = nativeSize): Rect {
  const node = guide.bubble!, ui = dimensions(node)
  return { x: node.position.x + size.width / 2 - ui.width / 2,
    y: node.position.y + size.height / 2 - ui.height / 2, width: ui.width, height: ui.height }
}
function intersects(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
}
test('原生短横屏city气泡真实排位与主堡间隔至少12，正文62高不变', () => {
  const { guide } = setup(), rect = bubbleRect(guide)
  assert.equal(rect.width, 324)
  assert.ok(rect.x > 556.7293712350496 + 12)
  assert.ok(Math.abs(rect.x + rect.width - 894.5402843601896) < 0.00001)
  const text = dimensions(guide.bubble!.getChildByName('GuideStepText')!)
  assert.equal(text.width, 300); assert.equal(text.height, 62)
  assert.equal(dimensions(guide.bubble!.getChildByName('GuideBubblePlate')!).width, rect.width)
})
test('city单双按钮实际盒同宽重排，两键净宽300且单键居中', () => {
  for (const skippable of [false, true]) {
    const { guide } = setup('city', skippable)
    const row = guide.bubble!.getChildByName('GuideButtons')!, next = row.getChildByName('GuideNext')!
    const skip = row.getChildByName('GuideSkip')!
    assert.equal(dimensions(row).width, 300); assert.equal(dimensions(next).width, 144)
    assert.equal(dimensions(next.getChildByName('caption')!).width, 132)
    assert.equal(skip.active, skippable)
    if (skippable) {
      assert.equal(next.position.x, -78); assert.equal(skip.position.x, 78)
      assert.equal(skip.position.x - 72 - (next.position.x + 72), 12)
    } else assert.equal(next.position.x, 0)
  }
})
test('同页600/640与短屏切换重排同一气泡和素材，不缩正常city', () => {
  const { guide, size, area } = setup('city', true), bubble = guide.bubble!
  for (const height of [600, 640, nativeSize.height, 640]) {
    size.height = height; Object.assign(area, height === nativeSize.height ? nativeArea
      : { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 })
    guide.repaint()
    assert.equal(guide.bubble, bubble)
    const compact = height < 600, width = compact ? 324 : 420
    assert.equal(dimensions(bubble).width, width)
    assert.equal(dimensions(bubble.getChildByName('GuideBubblePlate')!).width, width)
    assert.equal(dimensions(bubble.getChildByName('GuideStepText')!).width, compact ? 300 : 388)
    assert.equal(dimensions(bubble.getChildByName('GuideButtons')!.getChildByName('GuideNext')!).width, compact ? 144 : 150)
  }
})
test('短屏Quest保持420正文宽并避开真实领取键，旧右角必须交叠', () => {
  const { guide } = setup('quest'), rect = bubbleRect(guide)
  const claim = { x: 720, y: 295.4018957345971, width: 76, height: 50.4 }
  assert.equal(rect.width, 420); assert.equal(dimensions(guide.bubble!.getChildByName('GuideStepText')!).width, 388)
  assert.equal(intersects(rect, claim), false)
  assert.equal(intersects({ x: 474.5402843601896, y: 281.6018957345971, width: 420, height: 150 }, claim), true)
})
test('安全区变化而尺寸不变时update仍更新气泡净区位置', () => {
  const { guide, area } = setup('city'), before = bubbleRect(guide)
  area.x += 17; area.width -= 31; guide.update()
  const after = bubbleRect(guide)
  assert.ok(Math.abs(after.x - (before.x - 14)) < 0.00001)
})
test('导航重建后尺寸不变也恢复Guide根层在最后，保住遮罩输入顺序', () => {
  const { guide } = setup(), host = new Node('Canvas')
  host.addChild(guide.node); host.addChild(new Node('NavBar'))
  guide.update()
  assert.equal(host.children.at(-1), guide.node)
  assert.ok(guide.blockerNodes.length > 0)
})
test('重绘在新增导航和更多抽屉之上，不重建既有气泡', () => {
  const { guide } = setup(), host = new Node('Canvas'), bubble = guide.bubble
  host.addChild(guide.node); host.addChild(new Node('NavBar')); host.addChild(new Node('NavMoreLayer'))
  guide.repaint()
  assert.equal(host.children.at(-1), guide.node)
  assert.equal(guide.bubble, bubble)
})
test('选择奖励收起气泡时Guide根层仍挡住洞外新导航', () => {
  const { guide } = setup('quest'), host = new Node('Canvas')
  host.addChild(guide.node); guide.setBubbleSuppressed('quest', true)
  host.addChild(new Node('NavBar')); guide.update()
  assert.equal(host.children.at(-1), guide.node)
  assert.equal(guide.bubble!.active, false)
  assert.ok(guide.blockerNodes.length > 0)
})
test('引导已结束后不抢新导航层级', () => {
  const { guide } = setup(), host = new Node('Canvas'), nav = new Node('NavBar')
  host.addChild(guide.node); guide.applyProgress(null); host.addChild(nav); guide.update()
  assert.equal(host.children.at(-1), nav)
  assert.equal(guide.node.active, false)
})
test('非city长文保原420宽；非对称安全区两角均在真实净区内', () => {
  const { guide } = setup('army')
  assert.equal(dimensions(guide.bubble!).width, 420)
  const preferred = { width: 420, height: 150, margin: 24, buttonWidth: 150 }
  const area = { x: -413, y: -120, width: 817, height: 331 }
  for (const key of ['city', 'quest', 'army']) {
    const layout = guideBubbleLayout(area, 960, 443, key, preferred)
    assert.ok(layout.x - layout.width / 2 >= area.x + 12)
    assert.ok(layout.x + layout.width / 2 <= area.x + area.width - 12)
  }
})
function wireQuest(guide: GuideHarness, quest: QuestHarness): void {
  const source = sceneSource('GameBootstrap'), ast = ts.createSourceFile('GameBootstrap.ts', source, ts.ScriptTarget.ES2020, true)
  const klass = ast.statements.find(node => ts.isClassDeclaration(node) && node.name?.text === 'GameBootstrap')!
  assert.ok(ts.isClassDeclaration(klass))
  const targets = klass.members.find(node => ts.isMethodDeclaration(node) && node.name.getText(ast) === 'targets')!
  assert.ok(ts.isMethodDeclaration(targets) && targets.body)
  const statement = targets.body.statements.find(node => ts.isIfStatement(node) && node.expression.getText(ast) === 'quest !== null')!
  const script = ts.transpileModule('function wire(quest: any) { const out: any = {}; ' + statement.getText(ast) + ' }',
    { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText
  const context = { wire: undefined as unknown as (quest: QuestHarness) => void }
  runInNewContext(script, context); context.wire.call({ guide, root: null }, quest)
}
function questWithGuide(guide: GuideHarness): QuestHarness {
  const QuestPanel = sceneClass<QuestHarness>('QuestPanelView', { view: { getVisibleSize: () => nativeSize },
    applySystemUiFont: (label: Label) => label, buildQuestList, candidateLabel, chosenClaimReq, ...paging })
  const quest = new QuestPanel(); quest.buildPrompt(); wireQuest(guide, quest); return quest
}
const prompt: HeroChoicePrompt = { questId: 'server-quest', title: '服务器奖励',
  candidates: [{ heroId: 'hero-from-server', name: '服务器武将' }] }
test('真实Quest打开/选择方法经Bootstrap回调收气泡保遮罩，关闭立即恢复', () => {
  const { guide } = setup('quest'), quest = questWithGuide(guide), mask = guide.blockerNodes[0]
  const claims: Array<[string, string | null]> = []; quest.onClaim = (id, hero) => claims.push([id, hero])
  assert.equal(typeof quest.onPromptVisibility, 'function')
  quest.showPrompt(prompt)
  assert.equal(guide.bubble!.active, false); assert.equal(guide.node.active, true)
  assert.equal(guide.blockerNodes[0], mask); assert.ok(guide.blockerNodes.length > 0)
  quest.promptPanel!.getChildByName('HeroOption_hero-from-server')!.emit('touch-start')
  assert.equal(guide.bubble!.active, true); assert.equal(quest.promptPanel!.active, false)
  assert.deepEqual(claims, [['server-quest', 'hero-from-server']])
})
test('尚未关闭三选一时离页再回任务不丢抑制；真实关闭和销毁恢复', () => {
  const { guide, setPanel } = setup('quest'), quest = questWithGuide(guide)
  quest.showPrompt(prompt); setPanel('city'); guide.repaint(); assert.equal(guide.bubble, null)
  setPanel('quest'); guide.repaint(); assert.equal(guide.bubble!.active, false)
  quest.hidePrompt(); assert.equal(guide.bubble!.active, true)
  quest.showPrompt(prompt); quest.onDestroy(); assert.equal(guide.bubble!.active, true)
  assert.equal(quest.onPromptVisibility, null)
})
test('抑制期间进度回执和同页resize重绘都不复活气泡；已结束不再画层', () => {
  const { guide, size } = setup('quest'), quest = questWithGuide(guide)
  quest.showPrompt(prompt); guide.repaint(); guide.applyProgress(1)
  assert.equal(guide.bubble!.active, false); assert.ok(guide.blockerNodes.length > 0)
  size.height = 640; guide.repaint(); assert.equal(guide.bubble!.active, false)
  guide.applyProgress(null); assert.equal(guide.bubble, null); assert.equal(guide.node.active, false)
  quest.hidePrompt(); assert.equal(guide.bubble, null)
})
test('任务权威attach与切页签都通过真实hidePrompt恢复；重复关闭不写业务', () => {
  const { guide } = setup('quest'), quest = questWithGuide(guide)
  let claims = 0; quest.onClaim = () => { claims++ }
  quest.rowPool = { releaseAll: () => {} }; quest.showPrompt(prompt)
  quest.attach({ quests: [], claimableCount: 0, serverNow: 1 })
  assert.equal(guide.bubble!.active, true); assert.equal(quest.openPrompt, null)
  quest.showPrompt(prompt); quest.switchTab('activity')
  assert.equal(guide.bubble!.active, true); assert.equal(quest.promptPanel!.active, false)
  quest.hidePrompt(); quest.hidePrompt(); assert.equal(claims, 0)
})
test('没有真实选择窗不发送打开状态；Guide已销毁后回调不复活节点', () => {
  const { guide } = setup('quest'), quest = questWithGuide(guide)
  const panel = quest.promptPanel; quest.promptPanel = null; quest.showPrompt(prompt)
  assert.equal(guide.bubble!.active, true)
  quest.promptPanel = panel; quest.showPrompt(prompt); assert.equal(guide.bubble!.active, false)
  guide.isValid = false; quest.hidePrompt(); assert.equal(guide.bubble!.active, false)
})
test('素材缺失时真实Graphics气泡和按钮也随短屏/普通视口重画', () => {
  const { guide, size, area } = setup('city', true, false)
  const bubble = guide.bubble!, graphics = bubble.getComponent(Graphics)!
  assert.equal(graphics.enabled, true); assert.equal(graphics.rects[0]![2], 324)
  const next = bubble.getChildByName('GuideButtons')!.getChildByName('GuideNext')!
  assert.equal(next.getComponent(Graphics)!.rects[0]![2], 144)
  size.height = 640; Object.assign(area, { x: -480, y: -260, width: 960, height: 580 }); guide.repaint()
  assert.equal(graphics.rects[0]![2], 420)
  assert.equal(next.getComponent(Graphics)!.rects[0]![2], 150)
})
test('真实未达成回执只改短提示，步数保持固定单行盒且计时重绘不复活被抑制气泡', () => {
  const { guide, restoreHeld } = setup('city'), bubble = guide.bubble!
  guide.applyProgress(1, false)
  const position = bubble.getChildByName('GuideStepPosition')!, label = position.getComponent(Label)!
  assert.equal(label.string, '尚未完成，请先完成当前操作')
  assert.equal(label.overflow, Label.Overflow.SHRINK); assert.equal(label.enableWrapText, false)
  assert.equal(label.horizontalAlign, Label.HorizontalAlign.CENTER)
  assert.equal(label.verticalAlign, Label.VerticalAlign.CENTER)
  assert.equal(dimensions(position).width, 300); assert.equal(dimensions(position).height, 28)
  assert.equal(label.lineHeight, 22)
  const text = bubble.getChildByName('GuideStepText')!
  assert.ok(position.position.y - 14 >= text.position.y + dimensions(text).height / 2)
  guide.setBubbleSuppressed('city', true); restoreHeld()
  assert.equal(label.string, '第 1 / 1 步'); assert.equal(bubble.active, false)
})
test('150高气泡的header正文与按钮自身盒互不相交，caption固定单行盒保22目标字号', () => {
  for (const panel of ['city', 'quest']) {
    const { guide, size, area } = setup(panel, true)
    for (const height of [nativeSize.height, 600, 640, nativeSize.height]) {
      size.height = height; Object.assign(area, height === nativeSize.height ? nativeArea
        : { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 }); guide.repaint()
      const bubble = guide.bubble!, header = bubble.getChildByName('GuideStepPosition')!
      const body = bubble.getChildByName('GuideStepText')!, buttons = bubble.getChildByName('GuideButtons')!
      const bounds = (node: Node): { top: number, bottom: number } => ({
        top: node.position.y + dimensions(node).height / 2,
        bottom: node.position.y - dimensions(node).height / 2,
      })
      assert.equal(dimensions(bubble).height, 150)
      assert.ok(bounds(header).bottom >= bounds(body).top, 'header与正文自身盒不交')
      assert.ok(bounds(body).bottom >= bounds(buttons).top, '正文与动作行自身盒不交')
      assert.equal(dimensions(header).height, 28); assert.equal(header.position.y, 53)
      assert.equal(dimensions(body).height, 62); assert.equal(body.position.y, 8)
      assert.equal(dimensions(buttons).height, 44); assert.equal(buttons.position.y, -45)
      assert.equal(bounds(header).top, 67); assert.equal(bounds(buttons).bottom, -67)
      const headerLabel = header.getComponent(Label)!
      assert.equal(headerLabel.fontSize, 20); assert.equal(headerLabel.lineHeight, 22)
      assert.equal(body.getComponent(Label)!.lineHeight, 26)
      for (const button of buttons.children) {
        const caption = button.getChildByName('caption')!, label = caption.getComponent(Label)!
        assert.equal(dimensions(caption).height, 44); assert.equal(caption.position.y, 0)
        assert.equal(label.fontSize, 22); assert.equal(label.lineHeight, 28)
        assert.equal(label.overflow, Label.Overflow.SHRINK); assert.equal(label.enableWrapText, false)
        assert.equal(label.horizontalAlign, Label.HorizontalAlign.CENTER)
        assert.equal(label.verticalAlign, Label.VerticalAlign.CENTER)
      }
    }
  }
})
test('单双动作Caption使用固定44高单行SHRINK，禁用NONE的55.44高自身盒扩张', () => {
  for (const optional of [false, true]) {
    const { guide } = setup('city', optional)
    const row = guide.bubble!.getChildByName('GuideButtons')!
    for (const button of row.children) {
      const caption = button.getChildByName('caption')!, label = caption.getComponent(Label)!
      assert.equal(label.overflow, Label.Overflow.SHRINK)
      assert.equal(label.enableWrapText, false); assert.equal(label.verticalAlign, Label.VerticalAlign.CENTER)
      assert.equal(dimensions(caption).height, 44); assert.equal(label.fontSize, 22); assert.equal(label.lineHeight, 28)
    }
  }
})
