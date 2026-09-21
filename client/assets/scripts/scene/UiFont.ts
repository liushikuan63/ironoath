/**
 * 职责：统一客户端系统字体策略。
 * 依赖：cc（Label）。
 *
 * <p>不打包中文字库是有意选择：Noto CJK 全量字体超过首包预算，裁成静态子集又会漏掉
 * 玩家昵称、联盟名和后续配置文案。这里显式接受系统字体，同时给出跨平台回退栈，
 * 避免各 Label 落到引擎默认 Arial 后由浏览器各自决定中文替代字体。
 */

import { Label, Size, UITransform } from 'cc'

/**
 * Windows / macOS / Android 常见中文字体依次回退。
 * 微信小游戏不支持某个字体时，平台会继续尝试后面的字体或系统默认字体。
 */
export const UI_FONT_FAMILY =
  '"Microsoft YaHei","PingFang SC","Noto Sans CJK SC","Noto Sans SC",sans-serif'

/** 所有运行期创建的文本都走这里，保证字体策略只有一个入口。 */
export function applySystemUiFont(label: Label): Label {
  label.useSystemFont = true
  label.fontFamily = UI_FONT_FAMILY
  return label
}

/**
 * 把一行文本钉成「永远一行」：关掉换行，行高按字号给。
 *
 * <p>**别用 `overflow = SHRINK` + 猜出来的盒高去限行**：SHRINK 是拿"缩放字形"去服从盒子的，
 * 战令表头按 `字号 + 6` 钉住时页内实测落地字号 17/13/10/10/9（设定 20/17/15/15/14，最小的那行
 * 只剩 64%）。台账 #366 量出的迁移曲线是线性的：盒高 30 才等于设定值，且这个点**不随 14~20 号字
 * 走**（因为 `lineHeight` 没设过，引擎在用默认的 40 参与计算），所以没有任何"字号 + 常数"能钉准。
 *
 * <p>关掉换行才是"一行"这个意图的直接表达：不换行 ⇒ 盒子不会撑成两行压住下一行（#363/#364 要防的
 * 就是这个），且完全不碰字形尺寸。
 *
 * <p>代价说清：文案长过面板时不再自动缩字，而是横向顶出去 —— 交给量具的宽度判据兜（#221 同族）：
 * 顶出去就红，红了改文案或改布局，而不是让字号背锅。
 */
export function keepOneLine(label: Label, fontSize: number): Label {
  label.overflow = Label.Overflow.NONE
  label.enableWrapText = false
  label.lineHeight = fontSize + 6
  return label
}

/**
 * 一行字不被缩放所需的最小盒高：给那些**必须待在固定宽度槽位里**（旁边就是按钮、
 * 或者卡片宽度写死）的行用 —— 那种地方只能 `overflow = SHRINK`（裁成 CLAMP 会把数字读成
 * 另一个数），而 SHRINK 的盒高就是字形缩放系数。
 *
 * <p>下限是量出来的（台账 #366 的迁移曲线，系统字体）：14~20 号字都要盒高 **30** 才等于设定
 * 字号，且这个 30 不随字号走（`lineHeight` 没设时引擎拿默认 40 参与）；更大字号按比例走，
 * 26 号实测要 40。所以取 `max(字号 + 14, 30)`。
 *
 * <p>低于这条就是常态性把每一行压小（实测 `字号 + 6` 与 `字号 × 1.6` 都不够：战令五行落地
 * 17/13/10/10/9 对设定 20/17/15/15/14）；高于这条则会**反向放大**（20 号字给 36 高 → 落地 24）。
 * 用它的地方把盒高写死成这个返回值，别自己再乘一个系数。
 */
export function oneLineFloorHeight(fontSize: number): number {
  return Math.max(fontSize + 14, 30)
}

/**
 * 限宽不限字：给"必须待在固定宽度槽位里"的行用（旁边就是按钮、或卡片宽度写死，
 * 用 CLAMP 裁字会把数值读成另一个数）。
 *
 * <p>盒高取 `oneLineFloorHeight`，于是 SHRINK 只在文案真的宽过槽位时才缩 ——
 * 而不是像 `字号 + 6`、`字号 × 1.6` 那样把每一行常态性压小（台账 #366/#367 的迁移曲线）。
 *
 * <p>同时把字形钉在节点位置上（`verticalAlign = CENTER`）：盒子抬高了十几个像素，
 * 默认的上沿对齐会跟着把整行字往上挪 —— 那是"改了盒子就顺带挪了版式"，本函数不承诺这个副作用。
 */
export function capWidth(label: Label, width: number): Label {
  const transform = label.node.getComponent(UITransform)
  transform?.setContentSize(new Size(width, oneLineFloorHeight(label.fontSize)))
  label.verticalAlign = Label.VerticalAlign.CENTER
  label.overflow = Label.Overflow.SHRINK
  return label
}
