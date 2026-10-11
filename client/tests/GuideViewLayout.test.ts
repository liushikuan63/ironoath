/** 执行真实场景方法的几何和接线回归；节点桩不证明Cocos实画或原生触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { GuideDriver } from '../assets/scripts/game/guide/GuideDriver'
import { guideBubbleLayout } from '../assets/scripts/game/ui/GuideLayout'
import { buildQuestList, candidateLabel, candidatePreview, chosenClaimReq } from '../assets/scripts/game/quest/QuestPanel'
import type { HeroChoicePrompt } from '../assets/scripts/game/quest/QuestPanel'
import type { GuideScriptResp } from '../assets/scripts/net/generated/GuideProtocol'
import type { CityHarvestLearningResp } from '../assets/scripts/net/generated/CityProtocol'
import * as cityAnchors from '../assets/scripts/game/city/CitySceneAnchors'
import { PANEL_FRAME_BAND } from '../assets/scripts/game/art/ArtFamilies'
import type { QuestListResp, QuestView } from '../assets/scripts/net/generated/QuestProtocol'
import * as paging from '../assets/scripts/game/ui/PanelPaging'
import { dialogLayout, safeAreaInsets } from '../assets/scripts/game/ui/DialogLayout'
import { PANEL_IRON_INSET } from '../assets/scripts/game/art/ArtFamilies'
import * as uiTokens from '../assets/scripts/game/ui/UiTokens'
import { worldSceneLayout } from '../assets/scripts/game/world/WorldSceneLayout'

class Vec2 { constructor(public x = 0, public y = 0) {} }
class Mask { static Type = { GRAPHICS_RECT: 1 }; type = 0 }
class ScrollView {
  node!: Node; content: Node | null = null; horizontal=false; vertical=true; inertia=true; elastic=false; cancelInnerEvents=true
  offset = new Vec2(); getScrollOffset(): Vec2 { return this.offset }
  stopAutoScroll(): void {}
  scrollToOffset(offset: Vec2): void { this.offset = offset; if(this.content) this.content.position.y = this.node.getComponent(UITransform)!.height / 2 + offset.y }
}
class Vec3 { constructor(public x = 0, public y = 0, public z = 0) {} }
class Size { constructor(public width: number, public height: number) {} }
class UITransform {
  width = 0; height = 0
  anchorX = 0.5; anchorY = 0.5
  setAnchorPoint(x: number, y: number): void { this.anchorX = x; this.anchorY = y }
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
  static Overflow = { SHRINK: 2, RESIZE_HEIGHT: 3 }; static HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 }
  static VerticalAlign = { CENTER: 1 }
  node!: Node; string = ''; fontSize = 0; lineHeight = 0; color: unknown
  overflow = 0; horizontalAlign = 0; verticalAlign = 0; enableWrapText = true
  actualFontSize = 0; parsedString: string[] = []
  updateRenderData(): void {
    const box = this.node.getComponent(UITransform)!
    // Conservative synthetic glyph width only; actual Cocos normalized font and parsed pixels need GPU acceptance.
    const textWidth = [...this.string].reduce((width, ch) => width + this.fontSize * (ch.charCodeAt(0) > 255 ? 1 : .6), 0)
    this.actualFontSize = this.overflow === Label.Overflow.SHRINK
      ? Math.floor(this.fontSize * Math.min(1, box.width / Math.max(1, textWidth), box.height / this.lineHeight)) : this.fontSize
    if (this.overflow === Label.Overflow.RESIZE_HEIGHT) box.height = this.lineHeight * Math.max(1, Math.ceil(textWidth / Math.max(1, box.width)))
    this.parsedString = [this.string]
  }
}
class Sprite { enabled = true; spriteFrame: { name: string } | null = null }
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
  off(type: string): void { this.events.delete(type) }
  hasEventListener(type: string): boolean { return this.events.has(type) }
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
function sceneExports(name: string, bindings: Record<string, unknown>): Record<string, unknown> {
  const ast = ts.createSourceFile(name + '.ts', sceneSource(name), ts.ScriptTarget.ES2020, true)
  const source = ts.createPrinter().printFile(ts.factory.updateSourceFile(ast,
    ast.statements.filter(statement => !ts.isImportDeclaration(statement))))
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020, experimentalDecorators: true } }).outputText
  const exports: Record<string, unknown> = {}
  runInNewContext(code, { exports, _decorator: { ccclass: () => (klass: unknown) => klass },
    Component, Color, Node, Label, Size, Vec2, Vec3, Mask, ScrollView, UITransform, Graphics, Sprite, ...bindings })
  return exports
}
function sceneClass<T>(name: string, bindings: Record<string, unknown>): new () => T {
  return sceneExports(name, bindings)[name] as new () => T
}

const capsuleRect = { left: 746, top: 12, right: 834, bottom: 44, width: 88, height: 32 }
const notchRect = { x: 53.459715639810426, y: 23.886255924170616,
  width: 853.0805687203791, height: 419.7156398104265 }
function actualViewportInsets(runtimeSys: Record<string, unknown>, runtimeWx?: Record<string, unknown>, clock?: () => number):
  (width: number, height: number) => { left: number; right: number; top: number; bottom: number } {
  return sceneExports('ViewportSafeArea', { sys: runtimeSys, safeAreaInsets,
    view: { getFrameSize: () => ({ width: 844, height: 390 }) }, wx: runtimeWx,
    ...(clock === undefined ? {} : { Date: { now: clock } }),
  }).viewportSafeInsets as ReturnType<typeof actualViewportInsets>
}

test('真实微信胶囊与HELD自身框相交时，实际净区入口保留字号并让文字整行离开胶囊', () => {
  const insets = actualViewportInsets({ isBrowser: false, getSafeAreaRect: () => notchRect }, {
    getMenuButtonBoundingClientRect: () => capsuleRect,
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390, screenTop: 0 }),
  })(960, 443.6018957345971)
  assert.ok(insets.top > 58 && insets.top < 58.1)
  assert.ok(423.6018957345971 - insets.top < 393.5545023696683,
    '旧HELD顶部必须整体低于原生胶囊底边，不能只判自身盒相交')
  assert.equal(insets.left, notchRect.x)
  assert.equal(insets.bottom, notchRect.y)
})

test('没有原生safeArea API时也实际查询微信胶囊并避让', () => {
  const insets = actualViewportInsets({ isBrowser: false }, {
    getMenuButtonBoundingClientRect: () => capsuleRect,
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }),
  })(960, 443.6018957345971)
  assert.ok(insets.top > 58 && insets.top < 58.1)
})

test('原生safeArea抛错后胶囊仍有效；较大的原生顶边不会重复扣除', () => {
  const runtimeWx = { getMenuButtonBoundingClientRect: () => capsuleRect,
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }) }
  const throwing = actualViewportInsets({ isBrowser: false, getSafeAreaRect: () => { throw Error('unsupported') } }, runtimeWx)
  assert.ok(throwing(960, 443.6018957345971).top > 58)
  const existing = actualViewportInsets({ isBrowser: false,
    getSafeAreaRect: () => ({ x: 20, y: 10, width: 920, height: 350 }) }, runtimeWx)(960, 443.6018957345971)
  assert.equal(existing.top, 83.60189573459712)
  assert.equal(existing.left, 20)
  assert.equal(existing.bottom, 10)
})

test('旧基础库只提供getSystemInfoSync时实际净区同样避胶囊', () => {
  const insets = actualViewportInsets({ isBrowser: false, getSafeAreaRect: () => notchRect }, {
    getMenuButtonBoundingClientRect: () => capsuleRect,
    getSystemInfoSync: () => ({ windowWidth: 844, windowHeight: 390 }),
  })(960, 443.6018957345971)
  assert.ok(insets.top > 58)
})

test('窗口信息较新API暂不可用时兼容旧API，只有窗口原点的screenTop可参与换算', () => {
  const insets = actualViewportInsets({ isBrowser: false }, {
    getMenuButtonBoundingClientRect: () => ({ ...capsuleRect, top: 32, bottom: 64 }),
    getWindowInfo: () => { throw Error('not ready') },
    getSystemInfoSync: () => ({ windowWidth: 844, windowHeight: 390, screenTop: 20 }),
  })(960, 443.6018957345971)
  assert.ok(insets.top > 58 && insets.top < 58.1)
})

test('连续窗口变化重新读取胶囊与窗口尺寸，不复用首次净区或乘DPR', () => {
  let bottom = 44
  let windowHeight = 390
  const readInsets = actualViewportInsets({ isBrowser: false }, {
    getMenuButtonBoundingClientRect: () => ({ ...capsuleRect, bottom, height: bottom - 12 }),
    getWindowInfo: () => ({ windowWidth: 844, windowHeight, pixelRatio: 3 }),
  })
  assert.ok(readInsets(960, 443.6018957345971).top > 58)
  bottom = 60; windowHeight = 600
  assert.equal(readInsets(960, 640).top, 72)
})

test('稳定视口连续帧只读一次原生安全区、窗口和胶囊，返回值不能污染缓存', () => {
  let safeReads = 0; let windowReads = 0; let capsuleReads = 0
  const readInsets = actualViewportInsets({ isBrowser: false,
    getSafeAreaRect: () => { safeReads++; return notchRect } }, {
    getWindowInfo: () => { windowReads++; return { windowWidth: 844, windowHeight: 390 } },
    getMenuButtonBoundingClientRect: () => { capsuleReads++; return capsuleRect },
  })
  const expected = readInsets(960, 443.6018957345971)
  const changed = readInsets(960, 443.6018957345971); changed.top = 999
  for (let frame = 0; frame < 1000; frame++) assert.deepEqual(readInsets(960, 443.6018957345971), expected)
  assert.deepEqual([safeReads, windowReads, capsuleReads], [1, 1, 1], '真实update调用不得逐帧桥接微信API')
})

test('同尺寸窗口变化和回前台使安全区缓存失效，每类监听只注册一次', () => {
  let bottom = 44; let safeReads = 0; let resize: (() => void) | undefined; let show: (() => void) | undefined
  let resizeListeners = 0; let showListeners = 0
  const readInsets = actualViewportInsets({ isBrowser: false,
    getSafeAreaRect: () => { safeReads++; return notchRect } }, {
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }),
    getMenuButtonBoundingClientRect: () => ({ ...capsuleRect, bottom, height: bottom - 12 }),
    onWindowResize: (listener: () => void) => { resizeListeners++; resize = listener },
    onShow: (listener: () => void) => { showListeners++; show = listener },
  })
  const initial = readInsets(960, 443.6018957345971).top
  for (let frame = 0; frame < 100; frame++) readInsets(960, 443.6018957345971)
  assert.deepEqual([resizeListeners, showListeners], [1, 1])
  bottom = 60; resize!()
  const resized = readInsets(960, 443.6018957345971).top
  assert.ok(resized > initial)
  bottom = 70; show!()
  assert.ok(readInsets(960, 443.6018957345971).top > resized)
  assert.deepEqual([safeReads, resizeListeners, showListeners], [3, 1, 1])
})

test('启动时胶囊暂未就绪的回退最多每秒惰性重试，恢复后不永久保留零净区', () => {
  let now = 100; let ready = false; let reads = 0
  const readInsets = actualViewportInsets({ isBrowser: false }, {
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }),
    getMenuButtonBoundingClientRect: () => { reads++; return { ...capsuleRect, width: ready ? 88 : 0 } },
  }, () => now)
  assert.equal(readInsets(960, 443.6018957345971).top, 0)
  ready = true
  for (let frame = 0; frame < 100; frame++) assert.equal(readInsets(960, 443.6018957345971).top, 0)
  assert.equal(reads, 1)
  now += 1000
  assert.ok(readInsets(960, 443.6018957345971).top > 58)
  for (let frame = 0; frame < 100; frame++) readInsets(960, 443.6018957345971)
  assert.equal(reads, 2)
})

test('原生safeArea初始为零时不会永久缓存，胶囊避让与之后恢复的刘海边缘都保留', () => {
  let now = 0; let ready = false
  const readInsets = actualViewportInsets({ isBrowser: false,
    getSafeAreaRect: () => ready ? notchRect : { x: 0, y: 0, width: 0, height: 0 } }, {
    getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }),
    getMenuButtonBoundingClientRect: () => capsuleRect,
  }, () => now)
  const initial = readInsets(960, 443.6018957345971)
  assert.ok(initial.top > 58); assert.equal(initial.left, 0)
  ready = true; now = 1000
  const recovered = readInsets(960, 443.6018957345971)
  assert.equal(recovered.left, notchRect.x); assert.ok(recovered.top > 58)
})

test('浏览器跳过微信调用；原生没有或抛错的胶囊接口保留实测safeArea', () => {
  const unsupported = { getMenuButtonBoundingClientRect: () => { throw Error('unsupported') } }
  assert.equal(actualViewportInsets({ isBrowser: true }, unsupported)(960, 640).top, 0)
  for (const runtimeWx of [undefined, unsupported]) {
    const insets = actualViewportInsets({ isBrowser: false, getSafeAreaRect: () => notchRect }, runtimeWx)(960, 443.6018957345971)
    assert.equal(insets.top, 0)
    assert.equal(insets.bottom, notchRect.y)
  }
})

test('错误尺寸、未就绪零胶囊和窗口之外坐标不能吞掉内容净区', () => {
  const badRects = [{ ...capsuleRect, bottom: NaN }, { ...capsuleRect, width: 0 },
    { ...capsuleRect, top: -1 }, { ...capsuleRect, right: 900 },
    { ...capsuleRect, top: 350, bottom: 382 }]
  for (const rect of badRects) {
    const insets = actualViewportInsets({ isBrowser: false, getSafeAreaRect: () => notchRect }, {
      getMenuButtonBoundingClientRect: () => rect,
      getWindowInfo: () => ({ windowWidth: 844, windowHeight: 390 }),
    })(960, 443.6018957345971)
    assert.equal(insets.top, 0)
    assert.equal(insets.bottom, notchRect.y)
  }
})
interface GuideHarness {
  node: Node; isValid: boolean; bubble: Node | null; blockerNodes: Node[]; openPanelKey: () => string | null
  contentRectFor: () => Rect; attach(script: GuideScriptResp): void; repaint(): void; update(): void
  setOverlaySuppressed(key: string, suppressed: boolean): void
  setBubbleSuppressed(panel: string, suppressed: boolean): void
  applyProgress(index: number | null, advanced?: boolean): void
}
interface QuestHarness {
  node: Node; promptPanel: Node | null; onPromptVisibility: ((visible: boolean) => void) | null
  onClaim: ((quest: string, hero: string | null) => void) | null; openPrompt: HeroChoicePrompt | null
  buildPrompt(): void; buildTabs(height: number): void; showPrompt(prompt: HeroChoicePrompt): void; hidePrompt(): void; onDestroy(): void
  attach(resp: QuestListResp): void; switchTab(key: string): void; rowPool: object | null
  buildBackground(width: number, height: number): void; buildHeader(height: number): void
  createRow(): Node; render(): void; drawnRows: Node[]; tab: string; page: number
  update(): void
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

test('原生地图引导气泡避开缩放与行军工具栏，同时留在导航上方的安全内容区', () => {
  const { guide, size, area } = setup('world')
  const safe = { left: 53.459715639810426, right: 53.459715639810426,
    top: 58.047393364928894, bottom: 23.886255924170616 }
  const layout = worldSceneLayout(size.width, size.height, safe)
  area.x = -size.width / 2 + safe.left
  area.y = layout.mapBottom
  area.width = size.width - safe.left - safe.right
  area.height = size.height / 2 - safe.top - area.y
  guide.repaint()
  const bubble = guide.bubble!, box = dimensions(bubble)
  assert.ok(bubble.position.y + box.height / 2 <= layout.mapTop,
    '安全区修正后引导气泡仍不可覆盖世界工具栏或状态行')
  assert.ok(bubble.position.y - box.height / 2 >= area.y)
  assert.ok(bubble.position.x - box.width / 2 >= area.x)
  assert.ok(bubble.position.x + box.width / 2 <= area.x + area.width)
})
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
function questWithGuide(guide: GuideHarness, area: Rect = { ...nativeArea }, material = true,
                        size = { ...nativeSize }): QuestHarness {
  const art = (node: Node, key: string, width: number, height: number): boolean => {
    node.getComponent(UITransform)?.setContentSize(width, height)
    const sprite = node.getComponent(Sprite) ?? node.addComponent(Sprite)
    sprite.enabled = material; sprite.spriteFrame = material ? { name: key } : null
    return material
  }
  const styles = sceneExports('DialogStyle', { view: { getVisibleSize: () => size },
    PANEL_IRON_INSET, ...uiTokens, dialogLayout, applySlicedSprite: art,
    applyIronButton: (node: Node, _state: string, width: number, height: number) => art(node, 'ui.button.iron', width, height) })
  const QuestPanel = sceneClass<QuestHarness>('QuestPanelView', { view: { getVisibleSize: () => size },
    applySystemUiFont: (label: Label) => label, buildQuestList, candidateLabel, candidatePreview, chosenClaimReq, ...paging,
    applySlicedSprite: art, applyDialogButton: styles.applyDialogButton, createDialogScroll: styles.createDialogScroll,
    heroPortraitKey: (id: string) => id.startsWith("hero_sr_") ? "portrait" : null, applyAnyIconSprite: art,
    paintCompactDialogFrame: styles.paintCompactDialogFrame, dialogContentRect: () => area,
    PANEL_IRON_INSET, dialogLayout })
  const quest = new QuestPanel(); quest.node = new Node('quest')
  quest.node.addComponent(UITransform).setContentSize(size.width, size.height)
  const host = new Node('Game'); host.addChild(quest.node); host.addChild(guide.node)
  quest.buildBackground(size.width, size.height); quest.buildHeader(size.height)
  quest.buildTabs(size.height); quest.buildPrompt(); wireQuest(guide, quest); return quest
}
function ownRect(node: Node, offset = new Vec3()): Rect {
  const ui = dimensions(node)
  return { x: offset.x + node.position.x - ui.anchorX * ui.width,
    y: offset.y + node.position.y - ui.anchorY * ui.height, width: ui.width, height: ui.height }
}
function questRows(quest: QuestHarness, firstClaimable = false): void {
  const Pool = sceneExports('NodePool', {}).NodePool as new (parent: Node, factory: () => Node) => object
  quest.rowPool = new Pool(quest.node, () => quest.createRow())
  const quests: QuestView[] = Array.from({ length: 20 }, (_, index) => ({
    questId: `server-row-${index}`, name: `服务端任务${index}`, type: 'SIDE', goalType: 'GATHER_RESOURCE',
    goalTarget: 'WOOD', goalValue: 5000, current: firstClaimable && index === 0 ? 5000 : 2500,
    complete: firstClaimable && index === 0, claimed: false,
    claimable: firstClaimable && index === 0, locked: false, preQuestId: null, heroChoices: [],
  }))
  quest.attach({ quests, claimableCount: firstClaimable ? 1 : 0, serverNow: 1 })
}
test('真实三页签关闭选择后Guide在Tabs下沿留12净隙，任务页签可返回且领取右列仍净空', () => {
  const { guide, size, area } = setup('quest'), quest = questWithGuide(guide, area, true, size)
  questRows(quest, true)
  for (const height of [nativeSize.height, 600, 640]) {
    size.height = height; Object.assign(area, height === nativeSize.height ? nativeArea
      : { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 })
    quest.update(); guide.update()
    quest.showPrompt(threeChoices); quest.node.getChildByName('Tab-achievement')!.emit('touch-start')
    guide.update()
    assert.equal(guide.bubble!.active, true); assert.equal(quest.promptPanel!.active, false)
    const bubble = ownRect(guide.bubble!)
    for (const key of ['quest', 'activity', 'achievement']) {
      const tab = ownRect(quest.node.getChildByName(`Tab-${key}`)!)
      assert.ok(bubble.y + bubble.height <= tab.y - 12 + 0.00001, `${height}: ${key}须留真实净隙`)
      assert.equal(intersects(bubble, tab), false)
    }
    assert.equal(bubble.width, 420); assert.equal(bubble.height, 150)
    assert.equal(guide.bubble!.getChildByName('GuideStepText')!.getComponent(Label)!.fontSize, 20)
    quest.node.getChildByName('Tab-quest')!.emit('touch-start'); assert.equal(quest.tab, 'quest')
    const row = quest.drawnRows[0]!, claim = row.getChildByName('ClaimButton')!
    assert.equal(claim.active, true)
    assert.equal(intersects(bubble, ownRect(claim, row.position)), false, '真实renderRow生成的首颗领取按钮须净空')
  }
})
test('真实Tabs尺寸或同父位移变化触发Guide重排，只读自身盒不包含超大Caption子盒', () => {
  const { guide, size, area } = setup('quest'), quest = questWithGuide(guide, area, true, size)
  quest.node.setPosition(new Vec3(33, -11)); guide.node.setPosition(new Vec3(-27, 7)); guide.update()
  const tab = quest.node.getChildByName('Tab-activity')!, ui = dimensions(tab)
  const offset = new Vec3(quest.node.position.x - guide.node.position.x, quest.node.position.y - guide.node.position.y)
  const before = ownRect(guide.bubble!)
  assert.ok(before.y + before.height <= ownRect(tab, offset).y - 12 + 0.00001)
  dimensions(tab.getChildByName('Caption')!).setContentSize(4000, 4000)
  guide.update(); assert.equal(ownRect(guide.bubble!).y, before.y, '子节点自身盒不得扩大Tab矩形')
  ui.setContentSize(ui.width, ui.height + 20); guide.update()
  const afterSize = ownRect(guide.bubble!)
  assert.ok(Math.abs(afterSize.y - (before.y - 10)) < 0.00001)
  tab.setPosition(new Vec3(tab.position.x, tab.position.y - 9)); guide.update()
  assert.ok(Math.abs(ownRect(guide.bubble!).y - (afterSize.y - 9)) < 0.00001)
})
test('真实Quest从600创建后短屏再600/640重排自身Tabs背景行和翻页键，未开选择窗也响应', () => {
  const { guide, size, area } = setup('quest'); size.height = 600
  Object.assign(area, { x: -480, y: -240, width: 960, height: 540 })
  const quest = questWithGuide(guide, area, true, size); questRows(quest)
  for (const height of [nativeSize.height, 600, 640, nativeSize.height]) {
    size.height = height; Object.assign(area, height === nativeSize.height ? nativeArea
      : { x: -480, y: -height / 2 + 60, width: 960, height: height - 60 })
    quest.update()
    for (const key of ['quest', 'activity', 'achievement']) {
      const tab = ownRect(quest.node.getChildByName(`Tab-${key}`)!)
      assert.ok(tab.y + tab.height <= Math.min(height / 2, area.y + area.height), '真实Tab不得被短屏上沿裁掉')
      assert.ok(tab.y >= area.y)
    }
    const background = dimensions(quest.node.getChildByName('Background')!)
    assert.equal(background.width, size.width); assert.equal(background.height, height)
    assert.equal(dimensions(quest.node).height, height)
    assert.equal(quest.node.getChildByName('Header')!.position.y, Math.min(height / 2, area.y + area.height) - 36)
    assert.ok(quest.drawnRows.length > 0)
    assert.equal(quest.drawnRows[0]!.position.y, Math.min(height / 2, area.y + area.height) - 123)
    for (const name of ['PrevPageButton', 'NextPageButton']) {
      const pager = quest.node.getChildByName(name)!, rect = ownRect(pager)
      assert.equal(pager.active, true); assert.ok(rect.y >= area.y)
      assert.ok(rect.y + rect.height < ownRect(quest.drawnRows.at(-1)!).y)
    }
    assert.equal(quest.openPrompt, null)
  }
})
test('真实Quest安全净区改变只render一次，保留选择窗候选及页签状态并守住末层', () => {
  const { guide, size, area } = setup('quest'), quest = questWithGuide(guide, area, true, size)
  questRows(quest); quest.update(); quest.node.getChildByName('NextPageButton')!.emit('touch-start')
  assert.equal(quest.page, 1); quest.showPrompt(threeChoices)
  const panel = quest.promptPanel!, candidate = choiceCard(panel, 'server-hero-1')
  const original = quest.render.bind(quest); let renders = 0
  quest.render = () => { renders++; original() }
  const visible: boolean[] = []; const callback = quest.onPromptVisibility!
  quest.onPromptVisibility = value => { visible.push(value); callback(value) }
  area.x += 17; area.width -= 34; area.height -= 22; quest.update()
  assert.equal(renders, 1); quest.update(); assert.equal(renders, 1, '同几何后续帧不能重复render')
  assert.equal(quest.openPrompt?.questId, threeChoices.questId); assert.equal(panel.active, true)
  assert.equal(choiceCard(panel, 'server-hero-1'), candidate)
  assert.equal(quest.node.children.at(-1), panel); assert.equal(quest.tab, 'quest')
  assert.equal(quest.page, 1, '几何改变不得重置仍有效的当前页')
  assert.deepEqual(visible, []); assert.equal(guide.bubble!.active, false)
  const top = area.y + area.height
  assert.equal(quest.node.getChildByName('Header')!.position.y, top - 36)
  assert.equal(quest.drawnRows[0]!.position.x, area.x + area.width / 2)
  assert.equal(quest.drawnRows[0]!.position.y, top - 123)
})
const prompt: HeroChoicePrompt = { questId: 'server-quest', title: '服务器奖励',
  candidates: [{ heroId: 'hero-from-server', name: '服务器武将', rarity: null, might: null, command: null, wisdom: null, mainSkillName: null, mainSkillDescription: null, subSkillName: null, subSkillDescription: null, featureText: null }] }
const threeChoices: HeroChoicePrompt = { questId: 'server-three', title: '服务器下发的任务奖励标题',
  candidates: ['一', '二', '三'].map((suffix, index) => ({ heroId: `server-hero-${index}`, name: `服务端武将${suffix}`, rarity: null, might: null, command: null, wisdom: null, mainSkillName: null, mainSkillDescription: null, subSkillName: null, subSkillDescription: null, featureText: null })) }

// R8-083/085: the real capsule lowers the hole top by58.047; old nativeArea above omitted it.
const capturedCapsuleArea = { x: -426.5402843601896, y: -137.914691943128,
  width: 853.0805687203791, height: 301.66824644549763 }
const capturedChoices: HeroChoicePrompt = { questId: 'captured-main-wall', title: '主线 · 筑起第一堵墙',
  candidates: [{ heroId: 'hero_sr_01', name: '卫无咎', rarity: null, might: null, command: null, wisdom: null, mainSkillName: null, mainSkillDescription: null, subSkillName: null, subSkillDescription: null, featureText: null }, { heroId: 'hero_sr_02', name: '沈砚秋', rarity: null, might: null, command: null, wisdom: null, mainSkillName: null, mainSkillDescription: null, subSkillName: null, subSkillDescription: null, featureText: null }, { heroId: 'hero_sr_03', name: '崔明烛', rarity: null, might: null, command: null, wisdom: null, mainSkillName: null, mainSkillDescription: null, subSkillName: null, subSkillDescription: null, featureText: null }] }
function treeOwnRect(node: Node): Rect {
  let x = 0, y = 0
  for (let parent = node.parent; parent !== null; parent = parent.parent) { x += parent.position.x; y += parent.position.y }
  return ownRect(node, new Vec3(x, y))
}
function contained(child: Rect, parent: Rect): boolean {
  const epsilon = .00001
  return child.x >= parent.x - epsilon && child.y >= parent.y - epsilon
    && child.x + child.width <= parent.x + parent.width + epsilon
    && child.y + child.height <= parent.y + parent.height + epsilon
}
function choiceCard(panel: Node, id: string): Node {
  return panel.getChildByName('HeroChoiceViewport')!.getChildByName('HeroChoiceViewportContent')!.getChildByName('HeroOption_' + id)!
}
for (const material of [true, false]) test('完整选将资料在真实安全区滚动，固定确认取消；素材 ' + material, () => {
  const { guide, size, area } = setup('quest'); Object.assign(area, capturedCapsuleArea)
  const quest = questWithGuide(guide, area, material, size)
  const enriched: HeroChoicePrompt = { ...capturedChoices, candidates: capturedChoices.candidates.map(c => ({ ...c,
    rarity: 'SR', might: 73, command: 82, wisdom: 61, featureText: '提高部队防御并恢复兵力',
    mainSkillName: '守阵', mainSkillDescription: '战斗开始时提高本队防御，持续三回合。'.repeat(3),
    subSkillName: '援护', subSkillDescription: '每回合恢复本队兵力。' })) }
  quest.showPrompt(enriched)
  const panel = quest.promptPanel!, viewport = panel.getChildByName('HeroChoiceViewport')!, scroll=viewport.getComponent(ScrollView)!
  let count=0; quest.onClaim=()=>{count++}
  assert.ok(viewport.getComponent(Mask)); assert.equal(scroll.cancelInnerEvents, true)
  assert.equal(scroll.elastic, false); assert.equal(scroll.horizontal, false)
  assert.equal(guide.bubble!.active,false); assert.ok(guide.blockerNodes.length>0)
  for (const height of [640, 600, nativeSize.height]) {
    size.height=height; Object.assign(area,height===nativeSize.height?capturedCapsuleArea:{x:-480,y:-height/2+60,width:960,height:height-60})
    quest.update(); assert.ok(contained(treeOwnRect(panel),area))
    const confirm=panel.getChildByName('HeroChoiceConfirm')!, cancel=panel.getChildByName('HeroChoiceCancel')!
    assert.ok(contained(treeOwnRect(confirm),treeOwnRect(panel))); assert.ok(contained(treeOwnRect(cancel),treeOwnRect(panel)))
    assert.equal(intersects(treeOwnRect(viewport),treeOwnRect(confirm)),false)
    assert.equal(intersects(treeOwnRect(panel.getChildByName('PromptHint')!),treeOwnRect(viewport)),false)
    let lastBottom=0
    for(const c of enriched.candidates){
      const card=choiceCard(panel,c.heroId), box=dimensions(card)
      assert.ok(card.position.y+box.height/2<=lastBottom); lastBottom=card.position.y-box.height/2
      assert.equal(card.getComponent(Graphics)!.enabled,!material)
      for(const name of ['Name','Attributes','Feature','MainSkill','SubSkill']){
        const label=card.getChildByName(name)!.getComponent(Label)!;label.updateRenderData()
        assert.equal(label.actualFontSize,name==='Name'?20:16);assert.equal(label.overflow,Label.Overflow.RESIZE_HEIGHT)
        assert.ok(contained(treeOwnRect(label.node),treeOwnRect(card)))
      }
      assert.match(card.getChildByName('Attributes')!.getComponent(Label)!.string,/武力 73.*统率 82.*智力 61/)
      assert.equal(card.getChildByName('MainSkill')!.getComponent(Label)!.string,'主技 守阵：'+c.mainSkillDescription)
      assert.equal(card.getChildByName('Portrait')!.getChildByName('PortraitFallback')!.active,!material)
    }
  }
  const third=choiceCard(panel,enriched.candidates[2]!.heroId)
  scroll.scrollToOffset(new Vec2(0,-third.position.y-dimensions(third).height/2))
  assert.ok(Math.abs(treeOwnRect(third).y+dimensions(third).height-(treeOwnRect(viewport).y+dimensions(viewport).height))<.001)
  third.emit('touch-start');third.emit('touch-cancel'); assert.equal(count,0)
  third.emit('touch-end');assert.equal(count,0);assert.equal(panel.active,true)
  panel.getChildByName('HeroChoiceConfirm')!.emit('touch-end');assert.equal(count,1)
  panel.getChildByName('HeroChoiceConfirm')!.emit('touch-end');assert.equal(count,1)
  assert.equal(panel.active,false);assert.equal(guide.bubble!.active,true)
})
test('候选只选中不领奖，取消与空白不发请求；重开清除旧选择',()=>{
 const {guide}=setup('quest'),quest=questWithGuide(guide);let count=0;quest.onClaim=()=>{count++}
 quest.showPrompt(threeChoices);const panel=quest.promptPanel!,confirm=panel.getChildByName('HeroChoiceConfirm')!
 panel.emit('touch-start');confirm.emit('touch-end');assert.equal(count,0)
 choiceCard(panel,threeChoices.candidates[1]!.heroId).emit('touch-end');assert.equal(count,0)
 panel.getChildByName('HeroChoiceCancel')!.emit('touch-end');assert.equal(panel.active,false);assert.equal(guide.bubble!.active,true)
 quest.showPrompt(threeChoices);confirm.emit('touch-end');assert.equal(count,0)
 choiceCard(panel,threeChoices.candidates[2]!.heroId).emit('touch-end');confirm.emit('touch-end');assert.equal(count,1)
})
test('选择窗末层与重排保持同一资料节点和滚动位置，关闭恢复底层',()=>{
 const {guide,size,area}=setup('quest'),quest=questWithGuide(guide,area,true,size)
 quest.showPrompt(threeChoices);const panel=quest.promptPanel!,card=choiceCard(panel,threeChoices.candidates[1]!.heroId)
 card.emit('touch-end');const viewport=panel.getChildByName('HeroChoiceViewport')!,scroll=viewport.getComponent(ScrollView)!
 scroll.scrollToOffset(new Vec2(0,40));quest.node.addChild(new Node('QuestRow'))
 area.height-=30;quest.update();assert.equal(quest.node.children.at(-1),panel)
 assert.equal(choiceCard(panel,threeChoices.candidates[1]!.heroId),card);assert.equal(scroll.getScrollOffset().y,40)
 assert.match(panel.getChildByName('HeroChoiceConfirm')!.getChildByName('Caption')!.getComponent(Label)!.string,/服务端武将二/)
 quest.hidePrompt();const later=new Node('QuestRow');quest.node.addChild(later);quest.update();assert.equal(quest.node.children.at(-1),later)
})
test('旧回执无资料与未知头像均明示回退，不编造属性也不展示内部标识',()=>{
 const {guide}=setup('quest'),quest=questWithGuide(guide);quest.showPrompt(prompt)
 const card=choiceCard(quest.promptPanel!,prompt.candidates[0]!.heroId)
 assert.equal(card.getChildByName('Portrait')!.getChildByName('PortraitFallback')!.active,true)
 assert.match(card.getChildByName('Attributes')!.getComponent(Label)!.string,/武力 —.*统率 —.*智力 —/)
 assert.equal(card.getChildByName('Feature')!.getComponent(Label)!.string,'特点信息暂不可用')
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

interface ResourceTutorialHarness {
  node: Node; harvestLearning: CityHarvestLearningResp; resourceTutor: Node | null
  onOpenHarvestQuest: (() => void) | null; onResourceTutorialVisibility: ((visible: boolean) => void) | null
  openResourceTutorial(index?: number): void
}
function resourceTutorial(area: Rect): ResourceTutorialHarness {
  const art = () => false
  const size = { width: 960, height: area.height + 84 }
  const styles = sceneExports('DialogStyle', { view: { getVisibleSize: () => size }, PANEL_IRON_INSET,
    ...uiTokens, dialogLayout, applySlicedSprite: art, applyIronButton: art })
  const City = sceneClass<ResourceTutorialHarness>('CityPanelView', {
    ...cityAnchors, PANEL_FRAME_BAND, ...sceneExports('UiFont', {}), applySystemUiFont: (label: Label) => label,
    ...styles, dialogContentRect: () => area,
    dialogLayout, observeDialogSize: (_node: Node, _layout: () => void) => undefined,
  })
  const city = new City(); city.node = new Node('city'); city.node.addComponent(UITransform).setContentSize(960, size.height)
  city.harvestLearning = { resources: [
    { type: 'WOOD', name: '木材', description: '来自服务端的资源用途说明，供教学完整换行显示。', manualRequired: true, manuallyCollected: true, collectableBuildingId: null },
    { type: 'GOLD', name: '金币', description: '用途说明，不作为手动采集任务。', manualRequired: false, manuallyCollected: false, collectableBuildingId: null },
  ], collectAllUnlocked: false, unlockReason: '主城达到服务端要求等级后开启。', requiredMainLevel: 5, serverNow: 1000 }
  return city
}
function wireResourceQuest(city: ResourceTutorialHarness, nav: { show(key: string): void }): void {
  const source = sceneSource('GameBootstrap'), ast = ts.createSourceFile('GameBootstrap.ts', source, ts.ScriptTarget.ES2020, true)
  let callback: ts.Expression | undefined
  const scan = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && ts.isPropertyAccessExpression(node.left)
      && node.left.expression.getText(ast) === 'city' && node.left.name.text === 'onOpenHarvestQuest') callback = node.right
    ts.forEachChild(node, scan)
  }
  scan(ast); assert.ok(callback, 'actual production city-to-quest callback is required')
  const code = ts.transpileModule('exports.callback=(function(){return (' + callback.getText(ast) + ')}).call(host)',
    { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
  const exports: Record<string, unknown> = {}; runInNewContext(code, { exports, host: { nav } })
  city.onOpenHarvestQuest = exports.callback as () => void
}
test('资源教学固定五按钮在短屏净区且任务入口走真实导航，退出恢复气泡', () => {
  for (const height of [nativeArea.height, 516, 556]) {
    const area = { ...nativeArea, height }, city = resourceTutorial(area), visibility: boolean[] = []
    let current = 'city'; wireResourceQuest(city, { show: key => { current = key } })
    city.onResourceTutorialVisibility = visible => visibility.push(visible)
    city.openResourceTutorial()
    const dialog = city.resourceTutor!, names = ['ResourceTutorialPrev', 'ResourceTutorialCollect', 'ResourceTutorialNext', 'ResourceTutorialQuest', 'ResourceTutorialClose']
    const boxes = names.map(name => ownRect(dialog.getChildByName(name)!))
    for (let i = 0; i < boxes.length; i++) {
      const box = boxes[i]!, previous = boxes[i - 1]
      assert.ok(box.width >= 64 && box.height === 38)
      if (previous !== undefined) assert.ok(box.x >= previous.x + previous.width + 7)
      const label = dialog.getChildByName(names[i]!)!.getChildByName('Caption')!.getComponent(Label)!
      assert.equal(label.fontSize, 16); assert.equal(label.enableWrapText, false)
    }
    assert.equal(dialog.getChildByName('ResourceTutorialCollect')!.hasEventListener('touch-end'), false)
    dialog.getChildByName('ResourceTutorialQuest')!.emit('touch-end')
    assert.equal(current, 'quest'); assert.equal(city.resourceTutor, null); assert.deepEqual(visibility, [true, false])
    city.openResourceTutorial(1)
    city.resourceTutor!.getChildByName('ResourceTutorialClose')!.emit('touch-end')
    assert.equal(current, 'quest', 'return closes the dialog without sending a claim or changing panel')
    assert.equal(city.resourceTutor, null); assert.deepEqual(visibility, [true, false, true, false])
  }
})

test('全局礼包显示时收起引导气泡及遮罩，关闭后恢复服务端当前步骤而不跳步', () => {
  const { guide, restoreHeld } = setup()
  assert.equal(guide.node.active, true)
  guide.setOverlaySuppressed('giftPopup', true)
  assert.equal(guide.node.active, false)
  assert.equal(guide.bubble, null)
  assert.equal(guide.blockerNodes.length, 0)
  guide.repaint(); guide.applyProgress(1, false); restoreHeld()
  assert.equal(guide.node.active, false, '异步回执和HELD计时器不得抢回全局弹窗层级')
  guide.setOverlaySuppressed('giftPopup', false)
  assert.equal(guide.node.active, true)
  assert.equal(guide.bubble!.getChildByName('GuideStepPosition')!.getComponent(Label)!.string, '第 1 / 1 步')
  assert.equal(guide.bubble!.getChildByName('GuideStepText')!.getComponent(Label)!.string, '服务端下发正文')
  assert.ok(guide.blockerNodes.length > 0)
})

test('多个全局弹层按实际可见性恢复，面板选择窗的气泡抑制仍保留', () => {
  const { guide } = setup()
  guide.setBubbleSuppressed('city', true)
  guide.setOverlaySuppressed('giftPopup', true)
  guide.setOverlaySuppressed('otherOverlay', true)
  guide.setOverlaySuppressed('giftPopup', false)
  assert.equal(guide.node.active, false)
  guide.setOverlaySuppressed('otherOverlay', false)
  assert.equal(guide.node.active, true)
  assert.equal(guide.bubble!.active, false, '临时全局弹层关闭不能抹去资源教学可见性')
  guide.setBubbleSuppressed('city', false)
  assert.equal(guide.bubble!.active, true)
})

interface GiftVisibilityHarness {
  node: Node; ensureNodes(): void; attach(resp: { popup: boolean; productName?: string; productId?: string; offerExpireAt: null; serverNow: number }): void
  hide(): void; onVisibilityChanged: ((visible: boolean) => void) | null
}
test('实际礼包attach/hide接到生产引导可见性回调，服务端不弹与重复关闭都恢复原步', () => {
  const { guide } = setup()
  const Gift = sceneClass<GiftVisibilityHarness>('GiftPopupView', { DIALOG_SCRIM: {} })
  const gift = new Gift(); gift.ensureNodes = () => {}
  const ast = ts.createSourceFile('GameBootstrap.ts', sceneSource('GameBootstrap'), ts.ScriptTarget.ES2020, true)
  let callback: ts.Expression | undefined
  const scan = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && ts.isPropertyAccessExpression(node.left)
      && node.left.expression.getText(ast) === 'giftPopup' && node.left.name.text === 'onVisibilityChanged') callback = node.right
    ts.forEachChild(node, scan)
  }; scan(ast); assert.ok(callback, '礼包可见性必须接入生产targets')
  const code = ts.transpileModule('exports.callback=(function(){return (' + callback.getText(ast) + ')}).call(host)',
    { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
  const exports: Record<string, unknown> = {}; runInNewContext(code, { exports, host: { guide, destroyed: false } })
  gift.onVisibilityChanged = exports.callback as (visible: boolean) => void
  gift.attach({ popup: true, productName: '服务端礼包名', productId: 'server-gift', offerExpireAt: null, serverNow: 1 })
  assert.equal(gift.node.active, true); assert.equal(guide.node.active, false)
  guide.repaint(); assert.equal(guide.node.active, false)
  gift.attach({ popup: false, offerExpireAt: null, serverNow: 2 })
  assert.equal(gift.node.active, false); assert.equal(guide.node.active, true)
  gift.hide(); assert.equal(guide.node.active, true)
})

interface OfflineVisibilityHarness {
  node: Node; render(view: { items: Array<{ text: string; detail: string | null; jump: string }> }): void; hide(): void
  onVisibilityChanged: ((visible: boolean) => void) | null
}
test('实际离线汇总render/hide生产可见性回调恢复引导，空汇总与嵌套礼包不抢层', () => {
  const { guide } = setup()
  const Offline = sceneExports('OfflineReportOverlay', { DIALOG_SCRIM: {}, PANEL_IRON_INSET,
    applySlicedSprite: () => false, applySystemUiFont: (label: Label) => label,
    applyDialogButton: () => {}, fitExistingDialog: () => () => {},
  }).OfflineReportOverlay as new (parent: Node) => OfflineVisibilityHarness
  const offline = new Offline(new Node('Game'))
  const ast = ts.createSourceFile('GameBootstrap.ts', sceneSource('GameBootstrap'), ts.ScriptTarget.ES2020, true)
  let callback: ts.Expression | undefined
  const scan = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && ts.isPropertyAccessExpression(node.left)
      && node.left.expression.getText(ast) === 'this.offlineReport' && node.left.name.text === 'onVisibilityChanged') callback = node.right
    ts.forEachChild(node, scan)
  }; scan(ast); assert.ok(callback, '离线汇总可见性必须接入生产targets')
  const code = ts.transpileModule('exports.callback=(function(){return (' + callback.getText(ast) + ')}).call(host)',
    { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
  const exports: Record<string, unknown> = {}; runInNewContext(code, { exports, host: { guide, destroyed: false } })
  offline.onVisibilityChanged = exports.callback as (visible: boolean) => void
  const view = { items: [{ text: '来自服务端的汇总', detail: null, jump: 'city' }] }
  offline.render(view); assert.equal(offline.node.active, true); assert.equal(guide.node.active, false)
  guide.repaint(); assert.equal(guide.node.active, false)
  offline.hide(); assert.equal(offline.node.active, false); assert.equal(guide.node.active, true)
  guide.setOverlaySuppressed('giftPopup', true); offline.render(view); offline.render({ items: [] })
  assert.equal(offline.node.active, false); assert.equal(guide.node.active, false, '礼包仍显示时不能恢复引导遮罩')
  guide.setOverlaySuppressed('giftPopup', false); assert.equal(guide.node.active, true)
})
