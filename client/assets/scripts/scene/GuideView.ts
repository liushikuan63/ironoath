/**
 * 职责：把 {@link GuideDriver} 算出来的一帧画出来 —— 遮罩、高亮洞、气泡、两个按钮（B18 §一.3）。
 * 依赖：cc（渲染）、game/guide/GuideDriver（判定与状态，已单测）、scene/UiFont。
 *
 * <p><b>本文件一个字都不写"内容"</b>：文案、能不能跳、遮罩形状、第几步，全是从服务端下发的那一步里读的。
 * 它画的是"这一帧该长什么样"，而"该不该有这一帧"归 {@code GuideDriver} 管（那部分在 CI 里）。
 *
 * <p><b>遮罩按四块矩形拼，而不是一块全屏</b>：Cocos 的触摸命中是按节点矩形算的 ——
 * 一个全屏节点挂了触摸监听就会把**所有**触摸吃掉，高亮处也一起被挡住，玩家就没法做那一步了（引导会卡死）。
 * 四块拼出"除了高亮之外的区域"，于是：高亮内的操作照常（升级主城、领奖…），
 * 高亮外的点击被挡下并交给 {@link GuideDriver.outsideTap} 记一次埋点（验收 6）。
 * 洞 = 面板可用区域（由 {@code PanelNav.contentRectFor} 给出）∩ 服务端给的矩形遮罩。
 * 跨面板 STATE_REACHED 另保留导航条的真实自身盒，允许玩家去完成状态目标；其他暗区仍拦截。
 *
 * <p><b>不参与 node:test</b>：本文件 import 'cc'，与 scene/ 下其它文件同一条边界。
 * 因此"遮罩真的挡住了导航条"这件事在编辑器/真机里看过才算验证过，这里只有类型与构建保证。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { GuideDriver } from '../game/guide/GuideDriver'
import type { GuideFrame } from '../game/guide/GuideDriver'
import type { GuideAction, GuideScriptResp } from '../net/generated/GuideProtocol'
import { applyCommandButton, applySlicedSprite } from './ArtCatalog'
import { applySystemUiFont } from './UiFont'
import { DIALOG_SCRIM } from './DialogStyle'
import { guideBubbleLayout } from '../game/ui/GuideLayout'

const { ccclass } = _decorator

/** 配色沿用各面板的「铜金 + 暗红」。美术常量，不是游戏数值。 */
const COLOR_MASK = DIALOG_SCRIM
const COLOR_BUBBLE = new Color(38, 31, 25, 216)
const COLOR_BUBBLE_EDGE = new Color(184, 134, 11, 255)
/** 「这一步还没达成」那一句的暖红，与内城 `COLOR_WARNING` 同族。 */
const COLOR_HELD = new Color(214, 122, 92, 255)
/** 这句提示停留多久（秒）；之后 `repaint` 把步数标签还原。 */
const HELD_HINT_SECONDS = 4
const COLOR_TEXT = new Color(232, 221, 200, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_BUTTON = new Color(52, 43, 35, 255)

const BUBBLE_HEIGHT = 150
const BUBBLE_MARGIN = 24
const BUTTON_HEIGHT = 44
const BUTTON_WIDTH = 150
/** 两颗按钮并排时的间距；单颗时它不参与排位（那颗必须居中）。 */
const BUTTON_GAP = 12

/** 本地坐标下的矩形（左下角 + 宽高）—— 遮罩按它拼四块。 */
export interface GuideRect {
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

/**
 * 职责：新手引导这一层 —— 画引导步骤、气泡与遮罩，并**上报**引导埋点。
 * 依赖：引导脚本由服务端下发（步骤顺序在表里）；`setOverlaySuppressed` / `setBubbleSuppressed`
 * 用来在别的面板/覆盖层盖上来时把引导收掉 —— 引导的显示优先级只有这一处说了算。
 */

@ccclass('GuideView')
export class GuideView extends Component {
  private driver: GuideDriver | null = null
  /** 上报一步（做完 / 跳过）。由 GameBootstrap 接到 {@code AppRoot.guideProgress}。 */
  onReport: ((stepId: string, action: GuideAction) => void) | null = null
  /** 埋点出口（enter / complete / skip / outside_tap）。 */
  onTrack: ((action: string, stepId: string, version: string) => void) | null = null
  /** 当前打开的面板 key —— PANEL_OPEN 那一步要看它才决定弹不弹。 */
  openPanelKey: (() => string | null) | null = null
  /** 该面板的可用区域（挖洞的那一块）。null 表示这一号没有可挖的区域。 */
  contentRectFor: ((key: string | null) => GuideRect | null) | null = null

  private readonly blockerNodes: Node[] = []
  private bubble: Node | null = null
  private bubbleText: Label | null = null
  private bubblePosition: Label | null = null
  private nextButton: Node | null = null
  private nextCaption: Label | null = null
  private skipButton: Node | null = null
  /** 「还没达成」提示的还原计时器；`isValid` 守卫见 `showHeldHint`。 */
  private heldHintTimer: ReturnType<typeof setTimeout> | null = null
  private skipCaption: Label | null = null
  /** 面板真实选择窗暂收提示；离页不等于选择窗关闭，不能随clearLayer丢掉。 */
  private readonly suppressedPanels = new Set<string>()
  /** 全局弹窗自带遮罩；显示时整层让出，服务端步骤与面板气泡状态保留。 */
  private readonly suppressedOverlays = new Set<string>()
  private layoutKey = ''
  private buttonWidth = BUTTON_WIDTH

  setOverlaySuppressed(key: string, suppressed: boolean): void {
    if (suppressed === this.suppressedOverlays.has(key)) return
    if (suppressed) this.suppressedOverlays.add(key)
    else this.suppressedOverlays.delete(key)
    this.repaint()
  }

  /** 只收气泡，洞外遮罩继续有效；不读取任务或步骤id。 */
  setBubbleSuppressed(panelKey: string, suppressed: boolean): void {
    if (suppressed === this.suppressedPanels.has(panelKey)) {
      return
    }
    if (suppressed) this.suppressedPanels.add(panelKey)
    else this.suppressedPanels.delete(panelKey)
    if (this.bubble !== null) {
      this.bubble.active = !this.suppressedPanels.has(this.openPanelKey?.() ?? '')
    }
  }

  /** 收到一次脚本下发：换驱动器就等于重新开始读这一号的位置（服务端是唯一权威）。 */
  attach(resp: GuideScriptResp): void {
    this.driver = GuideDriver.from(resp)
    this.repaint()
  }

  /**
   * 重画当前帧。切面板、上报回执、登录完成都调它 —— 判定全在驱动器里，这里只跟着画。
   *
   * <p>没有帧（老号、已走完、或那一步的触发条件不在当前面板上）就把整层摘掉，
   * 于是遮罩不会留在屏幕上挡住正常操作。
   */
  repaint(): void {
    if (this.suppressedOverlays.size > 0) {
      this.clearLayer()
      return
    }
    const driver = this.driver
    const openKey = this.openPanelKey?.() ?? null
    const frame = driver === null ? null : driver.frameFor(openKey)
    if (driver === null || frame === null) {
      this.clearLayer()
      return
    }
    driver.markShown((action, stepId) => this.onTrack?.(action, stepId, driver.scriptVersion))
    this.draw(frame)
  }

  private draw(frame: GuideFrame): void {
    const size = view.getVisibleSize()
    const area = this.maskAreaOf(frame, size.width, size.height)
    const hole = this.holeOf(size.width, size.height)
    this.layoutKey = this.geometryKey(size.width, size.height, hole)
    this.drawMask(area, hole)
    this.ensureBubble()
    this.placeBubble(size.width, size.height, hole)

    if (this.bubbleText !== null) {
      this.bubbleText.string = frame.step.text
    }
    if (this.bubblePosition !== null) {
      this.bubblePosition.string = '第 ' + frame.position.index + ' / ' + frame.position.total + ' 步'
      // 颜色也要一起还原：`showHeldHint` 把它染成了暖红，只改文字不改颜色会留下一个红着的步数
      this.bubblePosition.color = COLOR_TEXT_DIM
    }
    if (this.nextCaption !== null) {
      this.nextCaption.string = '我完成了'
    }
    if (this.skipButton !== null) {
      this.skipButton.active = frame.showSkip
    }
    if (this.skipCaption !== null) {
      this.skipCaption.string = '跳过这一步'
    }
    this.layoutButtons()
    if (this.bubble !== null) {
      this.bubble.active = !this.suppressedPanels.has(this.openPanelKey?.() ?? '')
    }
    this.node.active = true
    this.keepAboveNavigation()
  }

  /** 原生安全区可在尺寸未变时改变，提示与导航洞必须同轮重排。 */
  override update(): void {
    if (this.driver === null || !this.node.active) return
    // PanelNav在resize/安全区改变时重新addChild导航；尺寸未变的后续帧也要保持输入层级。
    this.keepAboveNavigation()
    const size = view.getVisibleSize()
    const hole = this.holeOf(size.width, size.height)
    if (this.geometryKey(size.width, size.height, hole) !== this.layoutKey) this.repaint()
  }

  private keepAboveNavigation(): void {
    const parent = this.node.parent
    if (parent !== null && parent.children.indexOf(this.node) !== parent.children.length - 1) {
      this.node.setSiblingIndex(parent.children.length - 1)
    }
  }

  private geometryKey(width: number, height: number, hole: GuideRect): string {
    const tabs = this.questTabs().map(node => {
      const box = node.getComponent(UITransform)!
      return `${node.position.x}:${node.position.y}:${box.width}:${box.height}:${box.anchorX}:${box.anchorY}`
    }).join(';')
    const quest = this.node.parent?.getChildByName('quest')
    const nav = this.navigationRect()
    return `${width}:${height}:${this.openPanelKey?.() ?? ''}:${hole.x}:${hole.y}:${hole.width}:${hole.height}`
      + `:${this.node.position.x}:${this.node.position.y}:${quest?.position.x}:${quest?.position.y}:${tabs}`
      + `:${nav?.x}:${nav?.y}:${nav?.width}:${nav?.height}`
  }

  /** 状态目标允许跨面板操作；只放行导航自身盒，不扩大面板洞或绕过服务端完成判定。 */
  private navigationRect(): GuideRect | null {
    if (this.driver?.current()?.trigger !== 'STATE_REACHED') return null
    const nav = this.node.parent?.getChildByName('NavBar')
    const box = nav?.getComponent(UITransform)
    if (!nav?.active || box === null || box === undefined || box.width <= 0 || box.height <= 0) return null
    return { x: nav.position.x - box.anchorX * box.width - this.node.position.x,
      y: nav.position.y - box.anchorY * box.height - this.node.position.y,
      width: box.width, height: box.height }
  }

  private excludeNavigation(piece: GuideRect, nav: GuideRect | null): GuideRect[] {
    if (nav === null) return [piece]
    // 给自身盒留2px净隙：避免矩形边界的浮点舍入及触摸边缘仍落进遮罩。
    const left = Math.max(piece.x, nav.x - 2), right = Math.min(piece.x + piece.width, nav.x + nav.width + 2)
    const bottom = Math.max(piece.y, nav.y - 2), top = Math.min(piece.y + piece.height, nav.y + nav.height + 2)
    if (right <= left || top <= bottom) return [piece]
    return [
      { x: piece.x, y: piece.y, width: piece.width, height: bottom - piece.y },
      { x: piece.x, y: top, width: piece.width, height: piece.y + piece.height - top },
      { x: piece.x, y: bottom, width: left - piece.x, height: top - bottom },
      { x: right, y: bottom, width: piece.x + piece.width - right, height: top - bottom },
    ].filter(rect => rect.width > 0 && rect.height > 0)
  }

  private questTabs(): Node[] {
    if (this.openPanelKey?.() !== 'quest') return []
    const quest = this.node.parent?.getChildByName('quest')
    if (quest === null || quest === undefined || !quest.active) return []
    return ['Tab-quest', 'Tab-activity', 'Tab-achievement']
      .map(name => quest.getChildByName(name))
      .filter((node): node is Node => node !== null && node.active && node.getComponent(UITransform) !== null)
  }

  /** 遮罩洞仍覆盖任务操作区；提示单独避开页签的自身盒，给真实返回入口留下净隙。 */
  private bubbleArea(hole: GuideRect): GuideRect {
    if (this.openPanelKey?.() !== 'quest') return hole
    const area = { ...hole, x: hole.x - this.node.position.x, y: hole.y - this.node.position.y }
    const quest = this.node.parent?.getChildByName('quest')
    let top = area.y + area.height
    for (const tab of this.questTabs()) {
      const box = tab.getComponent(UITransform)!
      const bottom = (quest?.position.y ?? 0) + tab.position.y - box.anchorY * box.height - this.node.position.y
      top = Math.min(top, bottom)
    }
    return { ...area, height: Math.max(1, top - area.y) }
  }

  /**
   * 表里 {@code maskArea} 换成本地矩形。
   *
   * <p>{@code full} 是整屏；{@code x,y,w,h} 这一种<b>按"左上角像素"解释</b> —— 本表今天七步全是
   * {@code full}，所以这条换算没有任何一行配置验证过它，将来真要用矩形遮罩时，
   * 第一件事是拿一个已知位置对一次（届时才发现偏移，比上线后玩家看见错位要便宜）。
   */
  private maskAreaOf(frame: GuideFrame, width: number, height: number): GuideRect {
    if (frame.mask.full || frame.mask.rect === null) {
      return { x: -width / 2, y: -height / 2, width, height }
    }
    const [x, y, w, h] = frame.mask.rect
    return { x: -width / 2 + x, y: height / 2 - y - h, width: w, height: h }
  }

  /**
   * 洞：面板可用区域（已排除底部导航条）。
   *
   * <p>面板打开类步骤仍拦导航；跨面板状态目标由 navigationRect 单独放行导航自身盒。
   * 其他洞外输入继续拦截，不能让引导挡住它要求玩家完成目标的路径。
   */
  private holeOf(width: number, height: number): GuideRect {
    const content = this.contentRectFor?.(this.openPanelKey?.() ?? null) ?? null
    if (content !== null) {
      return content
    }
    return { x: -width / 2, y: -height / 2, width, height }
  }

  /** 四块矩形拼出"暗区里除了洞以外"的部分：上、下、左、右。每块都吃触摸。 */
  private drawMask(area: GuideRect, hole: GuideRect): void {
    this.releaseBlockers()
    const left = hole.x
    const right = hole.x + hole.width
    const bottom = hole.y
    const top = hole.y + hole.height
    const screenLeft = area.x
    const screenRight = area.x + area.width
    const screenBottom = area.y
    const screenTop = area.y + area.height
    const pieces: GuideRect[] = []
    if (top < screenTop) {
      pieces.push({ x: screenLeft, y: top, width: area.width, height: screenTop - top })
    }
    if (bottom > screenBottom) {
      pieces.push({ x: screenLeft, y: screenBottom, width: area.width, height: bottom - screenBottom })
    }
    if (left > screenLeft) {
      pieces.push({ x: screenLeft, y: bottom, width: left - screenLeft, height: hole.height })
    }
    if (right < screenRight) {
      pieces.push({ x: right, y: bottom, width: screenRight - right, height: hole.height })
    }
    const navigation = this.navigationRect()
    for (const piece of pieces.flatMap(rect => this.excludeNavigation(rect, navigation))) {
      this.blockerNodes.push(this.blocker(piece))
    }
  }

  private blocker(piece: GuideRect): Node {
    const node = new Node('GuideMask')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(piece.width, piece.height))
    node.setPosition(new Vec3(piece.x + piece.width / 2, piece.y + piece.height / 2, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_MASK
    graphics.rect(-piece.width / 2, -piece.height / 2, piece.width, piece.height)
    graphics.fill()
    // 挂了监听才会被命中测试吃掉；洞里没有这种节点，所以洞内的操作照常到达下层面板
    node.on('touch-start', (_event: EventTouch) => {
      this.driver?.outsideTap((action, stepId) =>
        this.onTrack?.(action, stepId, this.driver?.scriptVersion ?? ''))
    })
    return node
  }

  private ensureBubble(): void {
    if (this.bubble !== null) {
      return
    }
    const size = view.getVisibleSize()
    // 420 宽的 C 档提示气泡贴角，中央城堡与本步操作保留可见空间。
    const bubbleWidth = Math.min(size.width - BUBBLE_MARGIN * 2, 420)

    const bubble = new Node('GuideBubble')
    bubble.layer = this.node.layer
    this.node.addChild(bubble)
    bubble.addComponent(UITransform).setContentSize(new Size(bubbleWidth, BUBBLE_HEIGHT))
    const graphics = bubble.addComponent(Graphics)
    graphics.fillColor = COLOR_BUBBLE
    graphics.roundRect(-bubbleWidth / 2, -BUBBLE_HEIGHT / 2, bubbleWidth, BUBBLE_HEIGHT, 10)
    graphics.fill()
    graphics.lineWidth = 2
    graphics.strokeColor = COLOR_BUBBLE_EDGE
    graphics.roundRect(-bubbleWidth / 2, -BUBBLE_HEIGHT / 2, bubbleWidth, BUBBLE_HEIGHT, 10)
    graphics.stroke()
    // C 档只给真实浮动提示；Sprite独占子节点，不能与已登记的Graphics争同一UIRenderer。
    const plate = new Node('GuideBubblePlate')
    plate.layer = bubble.layer
    bubble.addChild(plate)
    plate.addComponent(UITransform).setContentSize(bubbleWidth, BUBBLE_HEIGHT)
    if (applySlicedSprite(plate, 'ui.plate.tooltip', bubbleWidth, BUBBLE_HEIGHT)) {
      graphics.clear()
      graphics.enabled = false
    }
    // 气泡自己也吃触摸：否则点气泡空白处会穿到下层面板里，做出"引导让你做、你自己又点了别的"那种事
    bubble.on('touch-start', (_event: EventTouch) => undefined)
    this.bubble = bubble

    const position = new Node('GuideStepPosition')
    position.layer = bubble.layer
    bubble.addChild(position)
    position.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, 28))
    position.setPosition(new Vec3(0, BUBBLE_HEIGHT / 2 - 22, 0))
    const positionLabel = applySystemUiFont(position.addComponent(Label))
    positionLabel.fontSize = 20
    positionLabel.lineHeight = 22
    positionLabel.overflow = Label.Overflow.SHRINK
    positionLabel.enableWrapText = false
    positionLabel.horizontalAlign = Label.HorizontalAlign.CENTER
    positionLabel.verticalAlign = Label.VerticalAlign.CENTER
    positionLabel.color = COLOR_TEXT_DIM
    positionLabel.string = ''
    this.bubblePosition = positionLabel

    const text = new Node('GuideStepText')
    text.layer = bubble.layer
    bubble.addChild(text)
    text.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, 62))
    text.setPosition(new Vec3(0, 8, 0))
    const textLabel = applySystemUiFont(text.addComponent(Label))
    textLabel.fontSize = 20
    textLabel.lineHeight = 26
    textLabel.overflow = Label.Overflow.SHRINK
    textLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    textLabel.color = COLOR_TEXT
    textLabel.string = ''
    this.bubbleText = textLabel

    const row = new Node('GuideButtons')
    row.layer = bubble.layer
    bubble.addChild(row)
    row.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, BUTTON_HEIGHT))
    row.setPosition(new Vec3(0, -BUBBLE_HEIGHT / 2 + BUTTON_HEIGHT / 2 + 8, 0))
    this.nextButton = this.button(row, 'GuideNext', BUTTON_WIDTH, '我完成了', false)
    this.nextCaption = this.nextButton.getChildByName('caption')?.getComponent(Label) ?? null
    this.skipButton = this.button(row, 'GuideSkip', BUTTON_WIDTH, '跳过这一步', true)
    this.skipCaption = this.skipButton.getChildByName('caption')?.getComponent(Label) ?? null

    this.nextButton.on('touch-start', (_event: EventTouch) => this.complete(), this)
    this.skipButton.on('touch-start', (_event: EventTouch) => this.skip(), this)
  }

  /**
   * 一颗按钮。走全游戏同一张暗金 chip 母版（{@code applyCommandButton}），
   * 取不到素材时才退回 Graphics 描边 —— 引导气泡不该是界面里唯一一套按钮皮。
   *
   * @param secondary 次要键（跳过）：同一张 chip，只用文字颜色降权，不再另画一套灰皮。
   */
  private button(parent: Node, name: string, width: number, caption: string, secondary: boolean): Node {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, BUTTON_HEIGHT))
    if (!applyCommandButton(node, 'normal', width, BUTTON_HEIGHT)) {
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_BUTTON
      graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 8)
      graphics.fill()
      graphics.lineWidth = 2
      graphics.strokeColor = COLOR_BUBBLE_EDGE
      graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 8)
      graphics.stroke()
    }
    const label = new Node('caption')
    label.layer = node.layer
    node.addChild(label)
    label.addComponent(UITransform).setContentSize(new Size(width - 12, BUTTON_HEIGHT))
    const captionLabel = applySystemUiFont(label.addComponent(Label))
    captionLabel.fontSize = 22
    // NONE会按(1+BASELINE_RATIO)扩自己的高度；固定盒单行SHRINK保住字号与动作行边界。
    captionLabel.lineHeight = 28
    captionLabel.overflow = Label.Overflow.SHRINK
    captionLabel.enableWrapText = false
    captionLabel.horizontalAlign = Label.HorizontalAlign.CENTER
    captionLabel.verticalAlign = Label.VerticalAlign.CENTER
    captionLabel.color = secondary ? COLOR_TEXT_DIM : COLOR_TEXT
    captionLabel.string = caption
    return node
  }

  /**
   * 按钮行的排位：**只有一颗时它必须居中**。
   *
   * <p>原来这里无条件按"两颗并排"排（`-(W+gap)/2` 与 `+(W+gap)/2`），而 `showSkip=false`
   * 的那些步里「跳过这一步」是 `active=false` 的 —— 于是屏上只剩「我完成了」一颗，
   * 却仍坐在左半边的位置上，看着像没对齐（用户 2026-09-26 指出的正是这个形状）。
   * 排位读的是 `skipButton.active`，所以 `draw` 里必须先设 active 再调本方法。
   */
  private layoutButtons(): void {
    if (this.nextButton === null || this.skipButton === null) {
      return
    }
    if (this.skipButton.active) {
      const half = (this.buttonWidth + BUTTON_GAP) / 2
      this.nextButton.setPosition(new Vec3(-half, 0, 0))
      this.skipButton.setPosition(new Vec3(half, 0, 0))
      return
    }
    this.nextButton.setPosition(new Vec3(0, 0, 0))
  }

  /**
   * 气泡贴着洞的**上沿**放；洞不在了（全屏面板）就贴屏幕顶部。
   *
   * <p>为什么不是下沿（原写法）：气泡不透明，落在洞里就是一块"看不见的遮挡"。本游戏的面板把
   * **可点的动作键排在卡片底部**（详情条：升级 / 加速 / 收取 / 取消），而引导每一步要玩家做的正是按那颗键 ——
   * 贴下沿就是"引导把它让你按的东西挡住"。世界矩形实测：气泡 y 72..222 把「升级」那颗（88..165）整个盖住。
   *
   * <p>为什么贴顶不是"换个地方挡"：屏幕 960×640、底部导航条实测只有 52 高（y 8..60）、卡片 608 高 ——
   * **没有任何一个位置能既放得下 150 高的气泡又不碰到卡片**（贴屏幕底部时气泡占 y 8..158，
   * 照样压进动作条 88..165 里）。所以只能选挡住什么：
   * 顶部那一条是标题 / 队列 / 资源读数（读一次就够，且引导文案本身就在说要做的事），
   * 底部那一条是**这一步要按的键**（按不到就推不下去）。取前者。
   */
  private placeBubble(width: number, height: number, hole: GuideRect): void {
    if (this.bubble === null) {
      return
    }
    const layout = guideBubbleLayout(this.bubbleArea(hole), width, height, this.openPanelKey?.() ?? null,
      { width: 420, height: BUBBLE_HEIGHT, margin: BUBBLE_MARGIN, buttonWidth: BUTTON_WIDTH })
    const bubble = this.bubble
    bubble.getComponent(UITransform)!.setContentSize(layout.width, layout.height)
    bubble.setPosition(new Vec3(layout.x, layout.y, 0))
    const plate = bubble.getChildByName('GuideBubblePlate')!
    plate.getComponent(UITransform)!.setContentSize(layout.width, layout.height)
    const graphics = bubble.getComponent(Graphics)!
    graphics.clear()
    const material = applySlicedSprite(plate, 'ui.plate.tooltip', layout.width, layout.height)
    graphics.enabled = !material
    if (!material) {
      graphics.roundRect(-layout.width / 2, -layout.height / 2, layout.width, layout.height, 10)
      graphics.fill()
      graphics.stroke()
    }
    // 对已有节点原位重排，不能只改外盒而留下旧Sprite、正文或触摸盒。
    this.bubblePosition?.node.getComponent(UITransform)?.setContentSize(layout.innerWidth, 28)
    this.bubbleText?.node.getComponent(UITransform)?.setContentSize(layout.innerWidth, 62)
    bubble.getChildByName('GuideButtons')?.getComponent(UITransform)?.setContentSize(layout.innerWidth, BUTTON_HEIGHT)
    this.buttonWidth = layout.buttonWidth
    for (const button of [this.nextButton, this.skipButton]) {
      if (button === null) continue
      button.getComponent(UITransform)!.setContentSize(layout.buttonWidth, BUTTON_HEIGHT)
      const buttonGraphics = button.getComponent(Graphics)
      buttonGraphics?.clear()
      const buttonMaterial = applyCommandButton(button, 'normal', layout.buttonWidth, BUTTON_HEIGHT)
      if (buttonGraphics !== null) {
        buttonGraphics.enabled = !buttonMaterial
        if (!buttonMaterial) {
          buttonGraphics.roundRect(-layout.buttonWidth / 2, -BUTTON_HEIGHT / 2, layout.buttonWidth, BUTTON_HEIGHT, 8)
          buttonGraphics.fill()
          buttonGraphics.stroke()
        }
      }
      button.getChildByName('caption')?.getComponent(UITransform)?.setContentSize(layout.buttonWidth - 12, BUTTON_HEIGHT)
    }
  }

  private complete(): void {
    const driver = this.driver
    if (driver === null) {
      return
    }
    const action = driver.completedAction((kind, stepId) =>
      this.onTrack?.(kind, stepId, driver.scriptVersion))
    if (action !== null) {
      this.onReport?.(action.stepId, 'COMPLETE')
    }
  }

  private skip(): void {
    const driver = this.driver
    if (driver === null) {
      return
    }
    const action = driver.skipAction((kind, stepId) =>
      this.onTrack?.(kind, stepId, driver.scriptVersion))
    // 不可跳的步拿不到 action：按钮本来就不显示，这里什么都不做而不是发一个注定被拒的请求
    if (action !== null) {
      this.onReport?.(action.stepId, 'SKIP')
    }
  }

  /**
   * 消费一次上报回执。**位置只跟着服务端走**，所以这里只是把新位置交给驱动器再重画。
   *
   * <p>`advanced=false` 时服务端给的回执仍指向当前步，重画等于什么都不变 —— 这正是"留在原步等他"。
   *
   * @param advanced 服务端是否真的推进了。为 false 时**必须给玩家一句话**：契约里这是正常返回
   *                 （不是错误），客户端原先什么都不做，于是玩家点了「我完成了」界面毫无反应，
   *                 既不知道没完成、也不知道该去干什么 —— 实测引导第 1 步正是这种情形。
   */
  applyProgress(nextStepIndex: number | null, advanced = true): void {
    this.driver?.applyProgress(nextStepIndex)
    this.repaint()
    if (!advanced) {
      this.showHeldHint()
    }
  }

  /**
   * 把"还没达成"写在步数那一行上（不动版式，几秒后由 `repaint` 还原）。
   *
   * <p>用 `setTimeout` 而不是 `Component.scheduleOnce`：本工程锁定的 Cocos 类型里没有后者
   * （类型检查会报 TS2551）。回调里补 `isValid` 守卫，节点销毁后不再碰它。
   */
  private showHeldHint(): void {
    if (this.bubblePosition === null) {
      return
    }
    this.bubblePosition.string = '尚未完成，请先完成当前操作'
    this.bubblePosition.color = COLOR_HELD
    if (this.heldHintTimer !== null) {
      clearTimeout(this.heldHintTimer)
    }
    this.heldHintTimer = setTimeout(() => {
      this.heldHintTimer = null
      if (this.isValid) {
        this.repaint()
      }
    }, HELD_HINT_SECONDS * 1000)
  }

  private releaseBlockers(): void {
    for (const node of this.blockerNodes) {
      node.destroy()
    }
    this.blockerNodes.length = 0
  }

  private clearLayer(): void {
    this.releaseBlockers()
    if (this.bubble !== null) {
      this.bubble.destroy()
      this.bubble = null
      this.bubbleText = null
      this.bubblePosition = null
      this.nextButton = null
      this.nextCaption = null
      this.skipButton = null
      this.skipCaption = null
    }
    this.node.active = false
  }

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    this.node.active = false
  }

  override onDestroy(): void {
    this.clearLayer()
    this.suppressedPanels.clear()
    this.suppressedOverlays.clear()
  }
}
