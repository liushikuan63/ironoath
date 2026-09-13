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
