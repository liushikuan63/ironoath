/** Player confirmation only; target and budget come from AppRoot's successful-input model. */
import { Color, EventTouch, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import type { AutoRefillConfirmationView } from '../game/army/AutoTrain'
import { applyDialogButton, DIALOG_SCRIM, fitExistingDialog } from './DialogStyle'
import { applySystemUiFont } from './UiFont'
import { Graphics } from 'cc'

const TEXT = new Color(226, 214, 190, 255)
const GOLD = new Color(184, 134, 11, 255)

export class AutoRefillOverlay {
  private readonly node: Node
  private readonly summary: Label
  private readonly notice: Label
  private readonly confirm: Node
  private readonly cancel: Node
  private readonly confirmCaption: Label
  private readonly layout: (reset?: boolean) => void
  private current: AutoRefillConfirmationView | null = null
  private access: HTMLDivElement | null = null
  private accessSummary: HTMLParagraphElement | null = null
  private accessNotice: HTMLParagraphElement | null = null
  private accessConfirm: HTMLButtonElement | null = null
  private accessCancel: HTMLButtonElement | null = null
  private returnFocus: HTMLElement | null = null
  onConfirm: (() => void) | null = null
  onCancel: (() => void) | null = null
  onVisibilityChanged: ((visible: boolean) => void) | null = null

  constructor(parent: Node) {
    this.node = new Node('AutoRefillOverlay')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    const screen = view.getVisibleSize()
    this.node.addComponent(UITransform).setContentSize(screen.width, screen.height)
    const scrim = this.child('scrim', 0, 0, screen.width, screen.height)
    const mask = scrim.addComponent(Graphics)
    mask.fillColor = DIALOG_SCRIM
    mask.rect(-screen.width / 2, -screen.height / 2, screen.width, screen.height)
    mask.fill()
    scrim.on('touch-start', (_event: EventTouch) => {}, this)
    const card = this.child('card', 0, 0, 520, 320)
    card.on('touch-start', (_event: EventTouch) => {}, this)
    const title = this.label('Title', 100, 22, GOLD)
    title.string = '确认目标补兵'
    this.summary = this.label('Summary', 40, 18, TEXT)
    const help = this.label('Help', -42, 16, TEXT)
    help.string = '只补此兵种；替换现有自动策略。\n不会取消已经在训的部队。'
    this.notice = this.label('Notice', -95, 16, TEXT)
    this.cancel = this.button('cancel', '取消', -115, () => {
      if (this.current !== null && !this.current.submitting) this.onCancel?.()
    }).node
    const confirm = this.button('confirm', '确认补兵', 115, () => {
      if (this.current !== null && !this.current.submitting) this.onConfirm?.()
    })
    this.confirm = confirm.node
    this.confirmCaption = confirm.caption
    this.layout = fitExistingDialog(this.node, card,
      [title.node, this.summary.node, help.node, this.notice.node], [this.cancel, this.confirm],
      'ui.panel.iron', 600, 430, { flow: true })
    this.node.active = false
    if (sys.isBrowser && typeof document !== 'undefined') this.createAccess()
  }

  render(data: AutoRefillConfirmationView | null): void {
    const wasOpen = this.current !== null
    this.current = data
    this.node.active = data !== null
    if (data === null) {
      if (this.access !== null) {
        this.access.hidden = true
        this.access.style.display = 'none'
      }
      if (wasOpen) {
        if (typeof document !== 'undefined') {
          document.removeEventListener('keydown', this.onKey, true)
          document.removeEventListener('focusin', this.keepFocus, true)
          document.removeEventListener('focusout', this.afterBlur, true)
        }
        if (this.returnFocus?.isConnected) this.returnFocus.focus({ preventScroll: true })
        this.returnFocus = null
        this.onVisibilityChanged?.(false)
      }
      return
    }
    this.node.setSiblingIndex(this.node.parent!.children.length - 1)
    const summary = `${data.unitName}\n可用 ${data.availableCount} · 在训 ${data.trainingCount}\n`
      + `补到 ${data.targetCount} · 单批最多 ${data.batchCount} · 预算 ${data.batchBudget} 批`
    this.summary.string = summary
    this.notice.string = data.notice ?? (data.submitting ? '正在提交，请稍候…' : '')
    this.confirmCaption.string = data.submitting ? '提交中…' : '确认补兵'
    applyDialogButton(this.confirm, !data.submitting, 190, 40)
    applyDialogButton(this.cancel, !data.submitting, 190, 40)
    this.layout(!wasOpen)
    if (this.access !== null) {
      this.access.hidden = false
      this.access.style.display = 'block'
      this.accessSummary!.textContent = summary + '\n只补此兵种；替换现有自动策略，不会取消已经在训的部队。'
      this.accessNotice!.textContent = this.notice.string
      this.accessConfirm!.disabled = data.submitting
      this.accessCancel!.disabled = data.submitting
      this.access.setAttribute('aria-busy', String(data.submitting))
      if (!wasOpen) {
        this.returnFocus = document.activeElement instanceof HTMLButtonElement ? document.activeElement
          : document.querySelector<HTMLButtonElement>('[data-ironoath-action="auto-refill"]')
        document.addEventListener('keydown', this.onKey, true)
        document.addEventListener('focusin', this.keepFocus, true)
        document.addEventListener('focusout', this.afterBlur, true)
        this.accessConfirm!.focus({ preventScroll: true })
      } else if (data.submitting) this.access.focus({ preventScroll: true })
    }
    if (!wasOpen) this.onVisibilityChanged?.(true)
  }

  destroy(): void {
    this.render(null)
    if (typeof document !== 'undefined') {
      document.removeEventListener('keydown', this.onKey, true)
      document.removeEventListener('focusin', this.keepFocus, true)
      document.removeEventListener('focusout', this.afterBlur, true)
    }
    this.access?.remove()
    this.access = null
    this.onConfirm = this.onCancel = this.onVisibilityChanged = null
    this.node.destroy()
  }

  private readonly onKey = (event: KeyboardEvent): void => {
    if (this.current === null) return
    // Keep game/background shortcuts from receiving modal keystrokes; native Enter still clicks a button.
    event.stopImmediatePropagation()
    if (event.key === 'Escape') {
      event.preventDefault()
      if (!this.current.submitting) this.onCancel?.()
    } else if (event.key === 'Tab') {
      event.preventDefault()
      if (this.current.submitting) this.access?.focus({ preventScroll: true })
      else (document.activeElement === this.accessConfirm ? this.accessCancel : this.accessConfirm)
        ?.focus({ preventScroll: true })
    }
  }

  private readonly keepFocus = (): void => {
    if (this.current === null || this.access === null || this.access.contains(document.activeElement)) return
    if (this.current.submitting) this.access.focus({ preventScroll: true })
    else this.accessConfirm?.focus({ preventScroll: true })
  }

  private readonly afterBlur = (): void => {
    // Cocos may blur its canvas without a following focusin; repair after the current pointer event.
    queueMicrotask(this.keepFocus)
  }

  private createAccess(): void {
    const access = document.createElement('div')
    access.setAttribute('role', 'dialog')
    access.setAttribute('aria-modal', 'true')
    access.setAttribute('aria-label', '确认目标补兵')
    access.tabIndex = -1
    access.hidden = true
    access.style.cssText = 'display:none;position:fixed;left:-10000px;top:0;width:1px;height:1px;overflow:hidden'
    this.accessSummary = document.createElement('p')
    this.accessNotice = document.createElement('p')
    this.accessNotice.setAttribute('role', 'status')
    this.accessCancel = document.createElement('button')
    this.accessConfirm = document.createElement('button')
    for (const button of [this.accessCancel, this.accessConfirm]) button.type = 'button'
    this.accessCancel.textContent = '取消'
    this.accessConfirm.textContent = '确认补兵'
    const focus = (node: Node, active: boolean): void => {
      const caption = node.getChildByName('Caption')?.getComponent(Label)
      if (caption !== null && caption !== undefined) caption.color = active ? GOLD : TEXT
    }
    this.accessConfirm.addEventListener('focus', () => focus(this.confirm, true))
    this.accessConfirm.addEventListener('blur', () => focus(this.confirm, false))
    this.accessCancel.addEventListener('focus', () => focus(this.cancel, true))
    this.accessCancel.addEventListener('blur', () => focus(this.cancel, false))
    this.accessCancel.addEventListener('click', () => {
      if (this.current !== null && !this.current.submitting) this.onCancel?.()
    })
    this.accessConfirm.addEventListener('click', () => {
      if (this.current !== null && !this.current.submitting) this.onConfirm?.()
    })
    access.append(this.accessSummary, this.accessNotice, this.accessCancel, this.accessConfirm)
    document.body.appendChild(access)
    this.access = access
  }

  private child(name: string, x: number, y: number, width: number, height: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    return node
  }

  private label(name: string, y: number, fontSize: number, color: Color): Label {
    const node = this.child(name, 0, y, 520, fontSize * 3.5)
    const label = node.addComponent(Label)
    applySystemUiFont(label)
    label.fontSize = fontSize
    label.lineHeight = fontSize + 6
    label.color = color
    label.enableWrapText = true
    return label
  }

  private button(name: string, text: string, x: number, onClick: () => void): { node: Node; caption: Label } {
    const node = this.child(name, x, -150, 190, 40)
    const labelNode = new Node('Caption')
    labelNode.layer = this.node.layer
    node.addChild(labelNode)
    labelNode.addComponent(UITransform).setContentSize(180, 32)
    const caption = labelNode.addComponent(Label)
    applySystemUiFont(caption)
    caption.fontSize = 18
    caption.color = TEXT
    caption.string = text
    node.on('touch-start', (_event: EventTouch) => onClick(), this)
    return { node, caption }
  }
}
