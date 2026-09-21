/**
 * 职责：统一客户端系统字体策略。
 * 依赖：cc（Label）。
 *
 * <p>不打包中文字库是有意选择：Noto CJK 全量字体超过首包预算，裁成静态子集又会漏掉
 * 玩家昵称、联盟名和后续配置文案。这里显式接受系统字体，同时给出跨平台回退栈，
 * 避免各 Label 落到引擎默认 Arial 后由浏览器各自决定中文替代字体。
 */

import { Label } from 'cc'

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
