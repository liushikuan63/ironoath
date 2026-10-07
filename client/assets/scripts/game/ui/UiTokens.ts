/**
 * 职责：弹窗与面板的视觉常量唯一真源 —— 遮罩、底板兜底色、号色、圆角、标题字号。纯数据，不碰引擎。
 * 依赖：无。
 *
 * <p><b>为什么本文件不许 import 引擎的 `Color`</b>：`game/` 这一层有 81 个模块、除本文件外
 * **零引擎依赖**（现跑 `grep -rl "^import .* from 'cc'" client/assets/scripts/game` 命中 0），
 * 为的是 `node --test` 能直接跑这层的用例。一旦这里 import 引擎，
 * `client/tests/UiTokens.test.ts` 会在 `import` 阶段就崩，
 * 而这些常量恰恰最需要被用例钉住。⇒ 颜色以 RGBA 元组下发，`new Color(...MASK_SCRIM)` 由 `scene/` 侧做。
 *
 * <p><b>为什么现在要有这个文件</b>：同一件事原先散在 333 处局部常量里 —— `COLOR_PANEL` 有
 * **17 处各自定义、5 个不同取值**（`(40,33,27)` 12 处是主流，另有 `(36,30,25,250)` / `(38,30,22,245)` /
 * `(38,31,26)` / `(43,36,29)` 四个离群值），遮罩有 8 处定义、alpha 6 种，关闭键两形态并存。
 * 这些不是设计差异，是没人收口的结果；而 V25 要把方块换成「铁誓」材质贴图时，
 * 素材加载失败那条兜底路径必须只有一个样子（#794）。
 *
 * <p><b>本文件刻意不做两件事</b>：
 * ① 不收九宫格框带厚 —— 它的唯一真源是 `ui/generated/ui/panel-kingdom-v1.png.meta` 的 border 四值，
 *    代码侧的镜像常量是 `game/art/ArtFamilies.ts` 的 `PANEL_FRAME_BAND`，两者由
 *    `client/tests/ArtFamilies.test.ts` 对账。这里再放一份就是留一个将来必然分叉的口径（#213 的成因）。
 * ② 不填没有出处的数 —— 羊皮纸面与暗红号色是 V25 新引入的材质语言，出厂值要等 V25-b 出图后目视定案
 *    （浅底配金字会掉对比度，规格 §七 Q2），所以它们现在是 `null` 而不是一个看着合理的颜色。
 *
 * <p>接线节奏（规格 §六）：本文件先只收色、不替换各视图的局部常量；
 * 局部 `COLOR_*` 的删除时机 = 该文件截图验收通过之后，逐文件删，不留半接线状态。
 */

/** RGBA 四元组。用 `as const` 的只读元组，消费点 `new Color(...TOKEN)` 直接展开。 */
export type Rgba = readonly [number, number, number, number]

/**
 * 全屏遮罩。现跑 8 处定义、alpha 只有 6 种（160 / 168 / 190 / 200 / 232 / 238，RGB 也分
 * `0,0,0`、`12,10,9`、`8,6,5` 三家），取最接近中位（195）的**既有值** 190 —— 不发明新数。
 */
export const MASK_SCRIM: Rgba = [8, 6, 5, 190]

/**
 * 素材未加载时面板底板的兜底色。取 17 处定义里的主流值（12 处），
 * 这样"贴图没上来"的那一刻与今天的观感一致，不会多一次视觉跳变。
 */
export const PANEL_FALLBACK: Rgba = [40, 33, 27, 255]

/** 铁面：底板素材的主体色，也是内嵌条行的底色。出处 `scene/BagPanelView.ts:32-35` 的现跑基线。 */
export const IRON_SURFACE: Rgba = [22, 18, 16, 255]

/**
 * 铜金：交互与王权的号色（描边、匾额、主按钮高光）。
 * 与内城规格 §1.1「铜金与暗红集中给王权/交互」同一条，出处 `scene/BagPanelView.ts:36`。
 */
export const BRONZE_GOLD: Rgba = [184, 134, 11, 255]

/** 暗红号色（警示绶带、危险态）。**待 V25-b 目视定案**，不给感觉值。 */
export const OXBLOOD_ACCENT: Rgba | null = null

/** 羊皮纸面（长文内衬）。**待 V25-b 目视定案**：亮度只提一档还是深字 tokens 一起改，见规格 §七 Q2。 */
export const PARCHMENT_FACE: Rgba | null = null

/** 圆角半径。现跑 `roundRect` 的 r 参数分布是 6(39) / 5(33) / 4(21) / 8(11) / 10(8)，取主流值。 */
export const CORNER_RADIUS = 6

/** 标题字号。现跑两处主流是 22（`BagPanelView.ts:291`、`NationPanelView.ts:262`），26 是礼包那一处的离群值。 */
export const TITLE_FONT_SIZE = 22
