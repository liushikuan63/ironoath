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
 * 洞 = 面板可用区域（不含底部导航条，由 {@code PanelNav.contentRectFor} 给出）∩ 服务端给的矩形遮罩。
 *
 * <p><b>不参与 node:test</b>：本文件 import 'cc'，与 scene/ 下其它文件同一条边界。
 * 因此"遮罩真的挡住了导航条"这件事在编辑器/真机里看过才算验证过，这里只有类型与构建保证。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { GuideDriver } from '../game/guide/GuideDriver'
import type { GuideFrame } from '../game/guide/GuideDriver'
import type { GuideAction, GuideScriptResp } from '../net/generated/GuideProtocol'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用各面板的「铜金 + 暗红」。美术常量，不是游戏数值。 */
const COLOR_MASK = new Color(8, 6, 5, 168)
const COLOR_BUBBLE = new Color(38, 31, 25, 246)
const COLOR_BUBBLE_EDGE = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(232, 221, 200, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_BUTTON = new Color(52, 43, 35, 255)

const BUBBLE_HEIGHT = 150
const BUBBLE_MARGIN = 24
const BUTTON_HEIGHT = 44
const BUTTON_WIDTH = 150

/** 本地坐标下的矩形（左下角 + 宽高）—— 遮罩按它拼四块。 */
export interface GuideRect {
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

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
  private skipCaption: Label | null = null

  /** 收到一次脚本下发：换驱动器就等于重新开始读这一号的位置（服务端是唯一权威）。 */
  attach(resp: GuideScriptResp): void {
    this.driver = GuideDriver.from(resp)
    this.repaint()
  }

  /** 驱动实例（instrument 与用例读它，不复制判定）。 */
  currentDriver(): GuideDriver | null {
    return this.driver
  }

  /**
   * 重画当前帧。切面板、上报回执、登录完成都调它 —— 判定全在驱动器里，这里只跟着画。
   *
   * <p>没有帧（老号、已走完、或那一步的触发条件不在当前面板上）就把整层摘掉，
   * 于是遮罩不会留在屏幕上挡住正常操作。
   */
  repaint(): void {
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
    this.drawMask(area, hole)
    this.ensureBubble()
    this.placeBubble(size.height, hole)

    if (this.bubbleText !== null) {
      this.bubbleText.string = frame.step.text
    }
    if (this.bubblePosition !== null) {
      this.bubblePosition.string = '第 ' + frame.position.index + ' / ' + frame.position.total + ' 步'
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
    this.node.active = true
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
   * <p>它决定"引导期间还能点什么"：高亮的那个面板能用，导航条落在洞外被挡下 ——
   * 这正是验收 6 要的形状（挡住其他输入，但不挡住这一步本身要做的操作）。
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
    for (const piece of pieces) {
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
    const bubbleWidth = Math.min(size.width - BUBBLE_MARGIN * 2, 760)

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
    // 气泡自己也吃触摸：否则点气泡空白处会穿到下层面板里，做出"引导让你做、你自己又点了别的"那种事
    bubble.on('touch-start', (_event: EventTouch) => undefined)
    this.bubble = bubble

    const position = new Node('GuideStepPosition')
    position.layer = bubble.layer
    bubble.addChild(position)
    position.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, 24))
    position.setPosition(new Vec3(0, BUBBLE_HEIGHT / 2 - 26, 0))
    const positionLabel = applySystemUiFont(position.addComponent(Label))
    positionLabel.fontSize = 20
    positionLabel.lineHeight = 24
    positionLabel.color = COLOR_TEXT_DIM
    positionLabel.string = ''
    this.bubblePosition = positionLabel

    const text = new Node('GuideStepText')
    text.layer = bubble.layer
    bubble.addChild(text)
    text.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, 62))
    text.setPosition(new Vec3(0, 6, 0))
    const textLabel = applySystemUiFont(text.addComponent(Label))
    textLabel.fontSize = 24
    textLabel.lineHeight = 30
    textLabel.overflow = Label.Overflow.SHRINK
    textLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    textLabel.color = COLOR_TEXT
    textLabel.string = ''
    this.bubbleText = textLabel

    const row = new Node('GuideButtons')
    row.layer = bubble.layer
    bubble.addChild(row)
    row.addComponent(UITransform).setContentSize(new Size(bubbleWidth - 32, BUTTON_HEIGHT))
    row.setPosition(new Vec3(0, -BUBBLE_HEIGHT / 2 + BUTTON_HEIGHT / 2 + 12, 0))
    this.nextButton = this.button(row, 'GuideNext', BUTTON_WIDTH, '我完成了', COLOR_BUBBLE_EDGE)
    this.nextCaption = this.nextButton.getChildByName('caption')?.getComponent(Label) ?? null
    this.skipButton = this.button(row, 'GuideSkip', BUTTON_WIDTH, '跳过这一步', COLOR_TEXT_DIM)
    this.skipCaption = this.skipButton.getChildByName('caption')?.getComponent(Label) ?? null

    this.nextButton.on('touch-start', (_event: EventTouch) => this.complete(), this)
    this.skipButton.on('touch-start', (_event: EventTouch) => this.skip(), this)
  }

  private button(parent: Node, name: string, width: number, caption: string, edge: Color): Node {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, BUTTON_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 8)
    graphics.fill()
    graphics.lineWidth = 2
    graphics.strokeColor = edge
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 8)
    graphics.stroke()
    const label = new Node('caption')
    label.layer = node.layer
    node.addChild(label)
    label.addComponent(UITransform).setContentSize(new Size(width - 12, BUTTON_HEIGHT))
    const captionLabel = applySystemUiFont(label.addComponent(Label))
    captionLabel.fontSize = 22
    captionLabel.lineHeight = BUTTON_HEIGHT
    captionLabel.horizontalAlign = Label.HorizontalAlign.CENTER
    captionLabel.color = edge
    captionLabel.string = caption
    return node
  }

  /** 气泡贴着洞的下沿放；洞不在了（全屏面板）就贴屏幕底部。 */
  private placeBubble(height: number, hole: GuideRect): void {
    if (this.bubble === null) {
      return
    }
    const anchorBottom = hole.y > -height / 2 ? hole.y : -height / 2
    const y = anchorBottom + BUBBLE_HEIGHT / 2 + 12
    this.bubble.setPosition(new Vec3(0, Math.min(y, height / 2 - BUBBLE_HEIGHT / 2 - 8), 0))
    const row = this.bubble.getChildByName('GuideButtons')
    if (row !== null && this.skipButton !== null && this.nextButton !== null) {
      this.nextButton.setPosition(new Vec3(-(BUTTON_WIDTH + 12) / 2, 0, 0))
      this.skipButton.setPosition(new Vec3((BUTTON_WIDTH + 12) / 2, 0, 0))
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
   */
  applyProgress(nextStepIndex: number | null): void {
    this.driver?.applyProgress(nextStepIndex)
    this.repaint()
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
  }
}
