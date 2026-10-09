/** 铁誓弹窗共用的材质与净区：内容按需滚动，操作固定，遮罩仍铺满。 */
import { _decorator, Color, Component, Graphics, Label, Mask, Node, ScrollView, Size, Sprite, UITransform, Vec2, Vec3, view } from 'cc'
import { PANEL_IRON_INSET } from '../game/art/ArtFamilies'
import { BRONZE_GOLD, CORNER_RADIUS, IRON_SURFACE, MASK_SCRIM, PANEL_FALLBACK } from '../game/ui/UiTokens'
import { dialogLayout } from '../game/ui/DialogLayout'
import { applyCommandButton, applyIronButton, applySlicedSprite } from './ArtCatalog'
import type { PanelNav } from './PanelNav'

export const DIALOG_SCRIM = new Color(...MASK_SCRIM)
const FRAME_SIDE = PANEL_IRON_INSET.left + PANEL_IRON_INSET.right
const FRAME_VERTICAL = PANEL_IRON_INSET.top + PANEL_IRON_INSET.bottom

type DialogKey = 'ui.panel.iron' | 'ui.panel.warning' | 'ui.panel.parchment' | 'ui.panel.gilt'

@_decorator.ccclass('DialogSizeObserver')
class DialogSizeObserver extends Component {
  onResize: (() => void) | null = null
  private sizeKey = ''
  setCallback(callback: () => void): void {
    this.onResize = callback
    const size = view.getVisibleSize()
    this.sizeKey = `${size.width}:${size.height}`
  }
  override update(): void {
    const size = view.getVisibleSize()
    const key = `${size.width}:${size.height}`
    if (key === this.sizeKey) return
    this.sizeKey = key
    this.onResize?.()
  }
}

export function observeDialogSize(node: Node, callback: () => void): void {
  const observer = node.getComponent(DialogSizeObserver) ?? node.addComponent(DialogSizeObserver)
  observer.setCallback(callback)
}

function resizeScrims(parent: Node): void {
  const size = view.getVisibleSize()
  for (const node of parent.children) {
    if (!['scrim', 'CreditsMask', 'mask', 'Backdrop', 'ChoiceScrim'].includes(node.name)) continue
    node.getComponent(UITransform)?.setContentSize(size.width, size.height)
    node.setPosition(new Vec3(0, -parent.position.y, 0))
    const graphics = node.getComponent(Graphics)
    if (graphics === null) continue
    graphics.clear()
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
  }
  if (parent.name === 'MarchCompose') {
    parent.getComponent(UITransform)?.setContentSize(size.width, size.height)
    const graphics = parent.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
      graphics.fill()
    }
  }
}

function paintFrame(node: Node, key: DialogKey, width: number, height: number): void {
  node.getComponent(UITransform)!.setContentSize(width, height)
  if (applySlicedSprite(node, key, width, height)) return
  const graphics = node.getComponent(Graphics) ?? node.addComponent(Graphics)
  graphics.enabled = true
  graphics.clear()
  graphics.fillColor = new Color(...PANEL_FALLBACK)
  graphics.roundRect(-width / 2, -height / 2, width, height, 6)
  graphics.fill()
}

/** 净区不足以容纳 A 档铜边时画同色薄边；三档位图仍各守自己的消费尺寸。 */
export function paintCompactDialogFrame(node: Node, width: number, height: number): void {
  node.getComponent(UITransform)!.setContentSize(width, height)
  const sprite = node.getComponent(Sprite)
  if (sprite !== null) sprite.enabled = false
  const graphics = node.getComponent(Graphics) ?? node.addComponent(Graphics)
  graphics.enabled = true
  graphics.clear()
  graphics.fillColor = new Color(...IRON_SURFACE)
  graphics.strokeColor = new Color(...BRONZE_GOLD)
  graphics.lineWidth = 2
  graphics.roundRect(-width / 2 + 1, -height / 2 + 1, width - 2, height - 2, CORNER_RADIUS)
  graphics.fill()
  graphics.stroke()
}

/** 从实际导航读取可用矩形；覆盖层位于同一个 Game 局部坐标系。 */
export function dialogContentRect(node: Node): { x: number; y: number; width: number; height: number } {
  for (let parent: Node | null = node; parent !== null; parent = parent.parent) {
    // type-only 导入避免 PanelNav → 视图 → 本助手的运行期环。
    const nav = parent.getComponent('PanelNav') as PanelNav | null
    const area = nav?.contentRectFor(nav.current()) ?? null
    if (area !== null) return area
  }
  const size = view.getVisibleSize()
  return { x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height }
}

/** C 档主按钮与小 chip 分工；disabled 只改变材质，能否点击仍由业务结论决定。 */
export function applyDialogButton(node: Node, enabled: boolean, width: number, height: number, selected = false): void {
  const state = enabled ? (selected ? 'hover' : 'normal') : 'disabled'
  if (width >= 100) applyIronButton(node, state, width, height)
  else applyCommandButton(node, state, width, height)
}

export function createDialogScroll(parent: Node, name: string, width: number, height: number,
                                   x: number, top: number): { node: Node; content: Node; scroll: ScrollView } {
  const viewport = new Node(name)
  viewport.layer = parent.layer
  parent.addChild(viewport)
  viewport.addComponent(UITransform).setContentSize(new Size(width, Math.max(1, height)))
  viewport.setPosition(new Vec3(x, top - Math.max(1, height) / 2, 0))
  viewport.addComponent(Mask).type = Mask.Type.GRAPHICS_RECT
  const content = new Node(`${name}Content`)
  content.layer = parent.layer
  viewport.addChild(content)
  content.addComponent(UITransform).setAnchorPoint(0.5, 1)
  content.getComponent(UITransform)!.setContentSize(new Size(width, Math.max(1, height)))
  content.setPosition(new Vec3(0, height / 2, 0))
  const scroll = viewport.addComponent(ScrollView)
  scroll.content = content
  scroll.horizontal = false
  scroll.vertical = true
  scroll.inertia = true
  scroll.elastic = false
  // 滚动内容的动作挂 touch-end：超过引擎拖动阈值会收到取消，放开时不会选择或写入。
  scroll.cancelInnerEvents = true
  return { node: viewport, content, scroll }
}

/** 把旧选择窗的既有内容放进净区，保留节点名/回调；确认取消在下沿固定。 */
export function finishLegacyDialog(parent: Node, nodes: Node[],
                                   key: 'ui.panel.iron' | 'ui.panel.warning' | 'ui.panel.parchment' = 'ui.panel.iron'): void {
  const card = nodes.find(node => node.name === 'card' || node.name === 'CreditsCard')
  const box = card?.getComponent(UITransform)
  if (card === undefined || box === null || box === undefined) return
  const scrim = nodes.find(node => node.name === 'scrim' || node.name === 'CreditsMask')
  scrim?.on('touch-start', () => {}, parent)
  card.on('touch-start', () => {}, parent)
  const width = box.width + FRAME_SIDE
  const height = box.height + FRAME_VERTICAL
  const footer = nodes.filter(node => ['cancel', 'confirm', 'submit', 'save', 'clearSlot', 'CreditsCloseButton'].includes(node.name))
  const footerNodes = nodes.filter(node => footer.includes(node)
    || (node.getComponent(Label) !== null && footer.some(button =>
      Math.abs(node.position.y - button.position.y) < 1 && Math.abs(node.position.x - button.position.x) < 1)))
  const body = nodes.filter(node => node !== card && !footerNodes.includes(node)
    && !['scrim', 'CreditsMask'].includes(node.name))
  for (const node of footer) {
    const size = node.getComponent(UITransform)!
    // 监听是生产门禁：灰键没有 touch-start，不能因为套皮重新挂上。
    const listener = node as Node & { hasEventListener(type: string): boolean }
    const enabled = listener.hasEventListener('touch-start') || listener.hasEventListener('touch-end')
    applyDialogButton(node, enabled, size.width, size.height)
  }
  const layout = fitExistingDialog(parent, card, body, footerNodes, key, width, height)
  nodes.push(parent.getChildByName('DialogContent')!)
  layout()
}

/** 已接 A 档的短弹窗沿用其外框大小；矮屏只缩内容窗口，操作仍在净区内。 */
export function fitExistingDialog(parent: Node, card: Node, body: readonly Node[], footer: readonly Node[],
                                  key: DialogKey, width: number, height: number): () => void {
  const originalY = body.map(node => node.position.y)
  const wrapped = createDialogScroll(parent, 'DialogContent', width - FRAME_SIDE, 1, 0, 0)
  for (const node of body) {
    node.removeFromParent()
    wrapped.content.addChild(node)
  }
  const applyLayout = (reset = true): void => {
    const offset = wrapped.scroll.getScrollOffset().y
    // RESIZE_HEIGHT 的文案在 attach/render 后才有实际高度，每次布局按当前字串量取。
    const bodyBounds = body.map((node, index) => {
      node.getComponent(Label)?.updateRenderData(true)
      const box = node.getComponent(UITransform)!
      return { top: originalY[index]! + box.height * (1 - box.anchorY),
        bottom: originalY[index]! - box.height * box.anchorY }
    })
    const naturalTop = Math.max(0, ...bodyBounds.map(box => box.top))
    const naturalBottom = Math.min(0, ...bodyBounds.map(box => box.bottom))
    body.forEach((node, index) => node.setPosition(new Vec3(node.position.x, originalY[index]! - naturalTop, 0)))
    const layout = dialogLayout(dialogContentRect(parent), width - FRAME_SIDE, height - FRAME_VERTICAL)
    const localCenter = card === parent ? layout.centerY : 0
    card.setPosition(new Vec3(0, layout.centerY, 0))
    if (layout.compact) paintCompactDialogFrame(card, layout.width, layout.height)
    else paintFrame(card, key, layout.width, layout.height)
    if (key === 'ui.panel.parchment') {
      const ink = layout.compact ? new Color(226, 214, 190, 255) : new Color(22, 18, 16, 255)
      for (const node of body) {
        const label = node.getComponent(Label)
        if (label !== null) label.color = ink
      }
    }
    resizeScrims(parent)
    wrapped.node.getComponent(UITransform)!.setContentSize(layout.innerWidth, layout.viewportHeight)
    wrapped.node.setPosition(new Vec3(0, layout.innerTop - localCenter - layout.viewportHeight / 2, 0))
    wrapped.content.getComponent(UITransform)!.setContentSize(layout.innerWidth,
      Math.max(layout.viewportHeight, naturalTop - naturalBottom))
    wrapped.content.setPosition(new Vec3(0, layout.viewportHeight / 2, 0))
    for (const node of footer) node.setPosition(new Vec3(node.position.x,
      layout.footerY - (node.parent === card ? layout.centerY : localCenter), 0))
    if (reset) wrapped.scroll.scrollToTop()
    else wrapped.scroll.scrollToOffset(new Vec2(0,
      Math.min(Math.max(0, offset), Math.max(0, naturalTop - naturalBottom - layout.viewportHeight))))
  }
  observeDialogSize(parent, () => applyLayout(false))
  return applyLayout
}
