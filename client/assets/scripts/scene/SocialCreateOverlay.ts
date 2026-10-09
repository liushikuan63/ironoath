/**
 * 职责：创建小队/联盟那一屏（B26 S2，`POST /squad/create` 与 `POST /alliance/create` 的唯一入口）。
 * 依赖：cc（渲染）、game/social/SocialCreate（数据组装，已单测）。
 *
 * <p>铁律：本文件只画编排层递过来的结论，点击只喊一声。能不能确认、行上写什么、差多少钱、
 * 门槛是哪句人话，全在 `SocialCreate.ts` 判，而那些数全来自 `/social/createPolicy`。
 *
 * <p>**打字时不重建 EditBox**：输入态存在编排层（它自己记一份就会出现"关掉再开还留着上次的字"），
 * 但每敲一个字就把整屏拆掉重画会连带把输入框与它的焦点一起拆了 —— 症状是"只能打一个字"。
 * 所以结构（哪几个字段）不变时只改那几行会变的字与按钮状态。
 */
import { _decorator, Color, Component, EditBox, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { CreateForm } from '../game/social/SocialCreate'
import { applyIronButton, applySlicedSprite } from './ArtCatalog'
import { applySystemUiFont } from './UiFont'
import { DIALOG_SCRIM, finishLegacyDialog } from './DialogStyle'

const { ccclass } = _decorator

const COLOR_SCRIM = DIALOG_SCRIM
/** 卡片背板（比面板底色略浅一点，压在 scrim 上才分得清"这是弹层，不是页面"）。 */
const COLOR_CARD = new Color(30, 25, 22, 255)
const COLOR_FIELD = new Color(40, 33, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)

const CARD_WIDTH = 520
const FIELD_WIDTH = CARD_WIDTH - 40
const FIELD_HEIGHT = 40
/** 输入区与按钮带之间的空气（#296 那条"公式恰好抵消"的教训，别再来一次）。 */
const BUTTON_CLEAR = 32
const BUTTON_HEIGHT = 36
const BUTTON_WIDTH = 120
const SAFE_MARGIN = 40
/**
 * 纵向排布一格一格走下来的步长（标题→小标签→输入框→…→消耗→提示→按钮）。
 * 卡片高度由这些数**加出来**，不是另写一个公式：两边各算一份就会对不上，
 * 而症状正是截图抓到的那样 —— 标题被第一个输入框压住半截。
 */
const TITLE_STEP = 26
const FIELD_STEP = 26
const GROUP_STEP = FIELD_HEIGHT / 2 + 22
const COST_STEP = FIELD_HEIGHT / 2 + 26
const HINT_STEP = 24
/**
 * 输入框的字符上限。服务端对名字与标签**没有**长度规则（只判非空与内容安全），
 * 所以这里不设一道假门槛；给一个宽上限只为了关掉引擎默认的 20 字静默截断 ——
 * 那条更糟：玩家打到第 21 个字，界面一个字都不说。
 */
const INPUT_MAX_LENGTH = 64

@ccclass('SocialCreateOverlay')
export class SocialCreateOverlay extends Component {
  private readonly nodes: Node[] = []
  private form: CreateForm | null = null
  private nameBox: EditBox | null = null
  private tagBox: EditBox | null = null
  private titleLabel: Label | null = null
  private nameLabelCaption: Label | null = null
  private tagLabelCaption: Label | null = null
  private costLabel: Label | null = null
  private hintLabel: Label | null = null
  private submitNode: Node | null = null
  private submitCaption: Label | null = null
  /** 上一次按什么结构建的（字段个数变了才要重建）。 */
  private builtHasTag = false
  private built = false
  private builtHeight = 0

  /** 打字（不是意图动作：只改编排层那份输入态，一条请求都不发）。 */
  onType: ((field: 'name' | 'tag', value: string) => void) | null = null
  /** 确认：编排层按服务端那几条再算一次，能发才发。 */
  onSubmit: (() => void) | null = null
  /** 取消：丢掉这次输入。 */
  onCancel: (() => void) | null = null

  render(form: CreateForm): void {
    this.node.active = true
    if (this.built && this.builtHasTag === (form.tagLabel !== null)) {
      this.form = form
      this.syncTexts()
      return
    }
    this.form = form
    this.rebuild()
  }

  override update(): void {
    if (this.built && this.node.active && this.builtHeight !== view.getVisibleSize().height) this.rebuild()
  }

  hide(): void {
    this.node.active = false
    this.built = false
    this.nameBox = null
    this.tagBox = null
    this.submitNode = null
    this.clearNodes()
  }

  /** 结构不变时只改字：输入框与它的焦点留着，玩家才打得进第二个字。 */
  private syncTexts(): void {
    const form = this.form
    if (form === null) {
      return
    }
    if (this.titleLabel !== null) {
      this.titleLabel.string = form.titleText
    }
    if (this.nameLabelCaption !== null) {
      this.nameLabelCaption.string = form.nameLabel
    }
    if (this.tagLabelCaption !== null) {
      this.tagLabelCaption.string = form.tagLabel ?? ''
    }
    if (this.costLabel !== null) {
      this.costLabel.string = form.costText
    }
    if (this.hintLabel !== null) {
      this.hintLabel.string = form.hint
      this.hintLabel.color = form.canSubmit ? COLOR_TEXT_DIM : COLOR_WARNING
    }
    if (this.nameBox !== null && this.nameBox.string !== form.name) {
      this.nameBox.string = form.name
    }
    if (this.tagBox !== null && this.tagBox.string !== form.tag) {
      this.tagBox.string = form.tag
    }
    if (this.submitNode !== null && this.submitCaption !== null) {
      applyIronButton(this.submitNode, form.canSubmit ? 'normal' : 'disabled',
        BUTTON_WIDTH, BUTTON_HEIGHT)
      this.submitCaption.color = form.canSubmit ? COLOR_TEXT : COLOR_TEXT_DIM
      this.submitNode.off('touch-start')
      if (form.canSubmit) {
        this.submitNode.on('touch-start', () => { this.onSubmit?.() }, this)
      }
    }
  }

  private rebuild(): void {
    const form = this.form
    this.clearNodes()
    if (form === null) {
      this.node.active = false
      return
    }
    const height = Math.min(this.node.getComponent(UITransform)?.height ?? 720,
      view.getVisibleSize().height)
    const hasTag = form.tagLabel !== null
    const span = TITLE_STEP + 2 * FIELD_STEP + (hasTag ? GROUP_STEP : 0)
      + COST_STEP + HINT_STEP + BUTTON_CLEAR + BUTTON_HEIGHT
    // 内容比可视高度还高时按可视高度收（上下各留 SAFE_MARGIN），按钮不会被切到屏外
    const top = height / 2 - Math.min(span, height - SAFE_MARGIN * 2) / 2
    // 每一格的 y 先全部算完再动手画：背板必须在内容之前画（后画的盖在上面），
    // 而它的高度又取决于最后一格 —— 边算边画就会把背板画到字上面去
    const titleY = top
    const nameCapY = titleY - TITLE_STEP
    const nameY = nameCapY - FIELD_STEP
    const tagCapY = hasTag ? nameY - GROUP_STEP : 0
    const tagY = hasTag ? tagCapY - FIELD_STEP : 0
    const costY = (hasTag ? tagY : nameY) - COST_STEP
    const hintY = costY - HINT_STEP
    const buttonY = hintY - BUTTON_CLEAR - BUTTON_HEIGHT / 2

    this.drawScrim()
    this.drawCard(titleY + 26, buttonY - BUTTON_HEIGHT / 2 - 18)
    // 联盟语义的顶饰（规格 §一 第 4 条 + 映射表"联盟 → 旗帜纹章"）：贴在卡片上沿之上、居中，
    // 与标题错开 —— 标题在 titleY±12，顶饰中心在 titleY + 56、高 58 ⇒ 下沿 titleY+27，不相压。
    const crest = new Node('crest')
    crest.layer = this.node.layer
    crest.addComponent(UITransform).setContentSize(new Size(40, 58))
    crest.setPosition(new Vec3(0, titleY + 56, 0))
    applySlicedSprite(crest, 'ui.crest.league', 40, 58)
    this.node.addChild(crest)
    this.nodes.push(crest)
    this.titleLabel = this.addLabel('title', form.titleText, 0, titleY, COLOR_COPPER_GOLD, 20)
    this.nameBox = this.addField(form.nameLabel, 'name-field', nameY, nameCapY, 'name')
    if (hasTag) {
      this.tagBox = this.addField(form.tagLabel ?? '', 'tag-field', tagY, tagCapY, 'tag')
    } else {
      this.tagLabelCaption = null
    }
    this.costLabel = this.addLabel('cost', form.costText, 0, costY, COLOR_TEXT_DIM, 14)
    this.hintLabel = this.addLabel('hint', form.hint, 0, hintY,
      form.canSubmit ? COLOR_TEXT_DIM : COLOR_WARNING, 14)
    this.addButton('cancel', '取消', -BUTTON_WIDTH / 2 - 8, buttonY, false)
    this.addButton('submit', '确认', BUTTON_WIDTH / 2 + 8, buttonY, !form.canSubmit)
    finishLegacyDialog(this.node, this.nodes, 'ui.panel.warning')
    this.built = true
    this.builtHeight = view.getVisibleSize().height
    this.builtHasTag = hasTag
  }

  /** 不透明背板：内容浮在面板之上，没有它就是"字压在别人的行上"。 */
  private drawCard(topY: number, bottomY: number): void {
    const node = new Node('card')
    node.layer = this.node.layer
    this.node.addChild(node)
    this.nodes.push(node)
    const height = topY - bottomY
    node.setPosition(new Vec3(0, (topY + bottomY) / 2, 0))
    node.addComponent(UITransform).setContentSize(new Size(CARD_WIDTH, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_CARD
    graphics.roundRect(-CARD_WIDTH / 2, -height / 2, CARD_WIDTH, height, 8)
    graphics.fill()
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-CARD_WIDTH / 2, -height / 2, CARD_WIDTH, height, 8)
    graphics.stroke()
  }

  private drawScrim(): void {
    const scrim = new Node('scrim')
    scrim.layer = this.node.layer
    this.node.addChild(scrim)
    this.nodes.push(scrim)
    const size = view.getVisibleSize()
    scrim.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    const graphics = scrim.addComponent(Graphics)
    graphics.fillColor = COLOR_SCRIM
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
  }

  /** 一行：小标签在上、输入框在下。返回那个 EditBox（重画时要按它回显在打的字）。 */
  private addField(label: string, nodeName: string, fieldY: number, captionY: number,
    kind: 'name' | 'tag'): EditBox {
    const caption = this.addCaption(`${nodeName}-caption`, label, captionY)
    if (kind === 'name') {
      this.nameLabelCaption = caption
    } else {
      this.tagLabelCaption = caption
    }
    const field = new Node(nodeName)
    field.layer = this.node.layer
    this.node.addChild(field)
    this.nodes.push(field)
    field.setPosition(new Vec3(0, fieldY, 0))
    field.addComponent(UITransform).setContentSize(new Size(FIELD_WIDTH, FIELD_HEIGHT))
    const graphics = field.addComponent(Graphics)
    graphics.fillColor = COLOR_FIELD
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-FIELD_WIDTH / 2, -FIELD_HEIGHT / 2, FIELD_WIDTH, FIELD_HEIGHT, 5)
    graphics.fill()
    graphics.stroke()
    const box = field.addComponent(EditBox)
    box.placeholder = ''
    box.inputMode = EditBox.InputMode.SINGLE_LINE
    box.maxLength = INPUT_MAX_LENGTH
    const form = this.form
    box.string = form === null ? '' : (kind === 'name' ? form.name : form.tag)
    if (box.textLabel !== null) {
      applySystemUiFont(box.textLabel)
      box.textLabel.fontSize = 16
      box.textLabel.color = COLOR_TEXT
      box.textLabel.overflow = Label.Overflow.SHRINK
    }
    box.node.on(EditBox.EventType.TEXT_CHANGED, () => {
      this.onType?.(kind, box.string)
    }, this)
    return box
  }

  private addButton(name: string, text: string, x: number, y: number, dim: boolean): void {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    this.nodes.push(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(BUTTON_WIDTH, BUTTON_HEIGHT))
    if (!applyIronButton(node, dim ? 'disabled' : 'normal', BUTTON_WIDTH, BUTTON_HEIGHT)) {
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_FIELD
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-BUTTON_WIDTH / 2, -BUTTON_HEIGHT / 2, BUTTON_WIDTH, BUTTON_HEIGHT, 5)
      graphics.fill()
      graphics.stroke()
    }
    const caption = new Node('Caption')
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform).setContentSize(new Size(BUTTON_WIDTH - 12, BUTTON_HEIGHT))
    const label = caption.addComponent(Label)
    applySystemUiFont(label)
    label.fontSize = 16
    label.color = dim ? COLOR_TEXT_DIM : COLOR_TEXT
    label.string = text
    if (name === 'submit') {
      this.submitNode = node
      this.submitCaption = label
      if (dim) {
        // 灰着的确认不挂触摸：与抽卡"钱不够就不发"同一条，点了只会被自己的界面挡
        return
      }
      node.on('touch-start', () => { this.onSubmit?.() }, this)
      return
    }
    node.on('touch-start', () => { this.onCancel?.() }, this)
  }

  /**
   * 小标签（左对齐）。锚点必须显式设成 (0, .5)：默认是中心，
   * 于是"贴左"的写法会把整条文字盒再往左推半个宽度 —— 截图上就是标签跑到屏幕外。
   */
  private addCaption(name: string, text: string, y: number): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    this.nodes.push(node)
    const transform = node.addComponent(UITransform)
    transform.setContentSize(new Size(FIELD_WIDTH, 22))
    transform.setAnchorPoint(0, 0.5)
    node.setPosition(new Vec3(-FIELD_WIDTH / 2, y, 0))
    const label = node.addComponent(Label)
    applySystemUiFont(label)
    label.fontSize = 13
    label.color = COLOR_TEXT_DIM
    label.string = text
    label.horizontalAlign = Label.HorizontalAlign.LEFT
    label.overflow = Label.Overflow.SHRINK
    return label
  }

  private addLabel(name: string, text: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    this.nodes.push(node)
    node.addComponent(UITransform).setContentSize(new Size(FIELD_WIDTH, fontSize + 10))
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    applySystemUiFont(label)
    label.fontSize = fontSize
    label.color = color
    label.string = text
    return label
  }

  private clearNodes(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
    this.titleLabel = null
    this.costLabel = null
    this.hintLabel = null
    this.nameBox = null
    this.tagBox = null
    this.submitNode = null
    this.submitCaption = null
  }
}
