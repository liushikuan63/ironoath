/**
 * 职责：战力明细面板与目标搜索列表的展示数据组装（B08 §1 / §8）。
 * 依赖：生成的协议类型 + 客户端 FixedPoint（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何战力校验</b>（B08 禁止项：不要在客户端做战力校验）。
 * 它不判断「这个目标能不能打」，也不过滤服务端发来的目标列表 ——
 * 服务端已经用 PowerBandGuard 筛过一遍了，客户端再筛一遍只有两种结果：
 * 口径一致时是纯粹的浪费，口径不一致时玩家会遇到「列表里有、点了却说打不了」。
 * 后者是 B08 明确要防的失败模式，而它只可能由客户端的第二次判定引入。
 * 所以这里的目标列表是<b>一行不改地照搬</b>，只做文本化。
 *
 * <p><b>明细面板的意义在于「可自查」而不是「好看」</b>。B08 §1 说玩家对
 * 「我为什么是这个战力」极度敏感，所以面板要把 matchPower 的两个输入
 * （currentMatchPower 与 peakMemoryFloor）都摊开，并在峰值记忆生效时明确说出来。
 * 只给一个总数的话，一个刚打完大仗的玩家会看到匹配战力高于自己的部队实际战力，
 * 而他没有任何线索知道那是防压分的托底 —— 他会理解成「这游戏在骗我」。
 *
 * <p><b>数字格式化全程整数运算</b>：战力以「万 / 亿」为单位缩写时用整除与取余，
 * 倍率用 FixedPoint.format。用 double 除法的后果是 1.5 倍显示成 1.4999 倍，
 * 而玩家会把它读成「差一点就能打了」。
 */

import * as FixedPoint from '../../core/FixedPoint'
import type { PowerDetailResp } from '../../net/generated/Protocol'
import type { DistanceBand, ResourceHint, SearchTargetsResp, TargetBrief } from '../../net/generated/WorldProtocol'

/** 明细面板的一行。 */
export interface PowerLine {
  /** 中文行标签。五行固定，顺序即面板顺序（从大到小的稳定顺序，不按数值排） */
  readonly label: string
  readonly value: number
  readonly text: string
}

/** 战力明细面板的完整展示数据。 */
export interface PowerPanelView {
  readonly lines: readonly PowerLine[]
  /** 五行之和的文本。必须等于 displayPower —— 服务端已用构造器强制，这里只是照实显示 */
  readonly totalText: string
  readonly displayPowerText: string
  readonly matchPowerText: string
  /**
   * 峰值记忆此刻是否在托底（matchPower > currentMatchPower）。
   *
   * 为 true 时面板必须显示 peakMemoryHint：不解释的话，玩家看到的是
   * 「我兵都卸了，匹配战力怎么还这么高」，而正确答案（防压分）对他是有利的。
   */
  readonly peakMemoryActive: boolean
  readonly peakMemoryHint: string
  readonly peakPowerText: string
}

/** 目标列表的一行。 */
export interface TargetRow {
  readonly id: string
  readonly name: string
  readonly ratioText: string
  readonly distanceText: string
  readonly resourceText: string
  /** 暴虐档位标签；服务端对平民档下发 null，这里就保持 null（不显示无意义的标签） */
  readonly tyrannyText: string | null
  readonly shielded: boolean
  readonly coordText: string
}

/** 明细行的标签与取值顺序。改这里就是改面板顺序，所以集中在一处。 */
const LINE_KEYS: readonly { readonly label: string; readonly key: keyof PowerDetailResp['breakdown'] }[] = [
  { label: '建筑', key: 'building' },
  { label: '部队', key: 'troops' },
  { label: '武将', key: 'heroes' },
  { label: '科技', key: 'tech' },
  { label: '装备', key: 'equipment' },
]

const DISTANCE_LABELS: Readonly<Record<DistanceBand, string>> = {
  NEAR: '近',
  MID: '中',
  FAR: '远',
}

const RESOURCE_LABELS: Readonly<Record<ResourceHint, string>> = {
  RICH: '富庶',
  NORMAL: '一般',
  POOR: '贫瘠',
}

/**
 * 暴虐档位的标签。与协议里的 TyrannyLevel 一致（B08 §4 的表格用词）。
 *
 * 「公敌」这一档刻意用带号召性的措辞：它不是给施暴者的羞辱标记，
 * 而是给其他人的行动理由 —— 围剿有加成、击溃有全服公告，
 * 标签本身就该让人看出「打他有额外好处」。
 */
const TYRANNY_LABELS: Readonly<Record<string, string>> = {
  TYRANT: '强横',
  BRUTE: '暴虐（围剿 +15%）',
  PUBLIC_ENEMY: '公敌（全服围剿）',
}

/** 组装战力明细面板。 */
export function buildPowerPanel(resp: PowerDetailResp): PowerPanelView {
  const lines: PowerLine[] = LINE_KEYS.map((entry) => {
    const value = resp.breakdown[entry.key]
    return { label: entry.label, value, text: formatPower(value) }
  })
  const sum = lines.reduce((acc, line) => acc + line.value, 0)
  const peakMemoryActive = resp.power.matchPower > resp.currentMatchPower
  return {
    lines,
    totalText: formatPower(sum),
    displayPowerText: formatPower(resp.power.displayPower),
    matchPowerText: formatPower(resp.power.matchPower),
    peakMemoryActive,
    peakMemoryHint: peakMemoryActive
      ? `匹配战力被历史峰值托底到 ${formatPower(resp.peakMemoryFloor)}，防止战前卸兵压分。`
        + '峰值每日衰减，长期不出战会自然回落。'
      : '',
    peakPowerText: formatPower(resp.power.peakPower),
  }
}

/**
 * 把服务端的目标列表原样文本化。
 *
 * <p><b>不排序、不过滤、不去重</b>：顺序是服务端按四项权重算出来的，
 * 客户端重排就等于用自己的一套权重覆盖 B08 §8 的口径。
 * 返回的行数与 resp.targets 严格一一对应，这一点由单测钉住。
 */
export function buildTargetRows(resp: SearchTargetsResp): readonly TargetRow[] {
  return resp.targets.map((target: TargetBrief): TargetRow => ({
    id: target.id,
    name: target.name,
    ratioText: formatRatio(target.powerRatio),
    distanceText: DISTANCE_LABELS[target.distanceBand],
    resourceText: RESOURCE_LABELS[target.resourceHint],
    tyrannyText: target.tyrannyLevel === null
      ? null
      : TYRANNY_LABELS[target.tyrannyLevel] ?? target.tyrannyLevel,
    shielded: target.isShielded,
    coordText: `(${target.coord.x}, ${target.coord.y})`,
  }))
}

/**
 * 目标搜索那一格该对玩家说什么 —— 只有「搜过了、且一个目标都没有」才给那句话。
 *
 * <p>为什么 `searched` 要单独传进来、不看 `total === 0` 就完事：`rows` 的初值就是空数组，
 * 光看行数分不出「面板还没搜过」与「搜过、确实一个没有」，而只有后者需要一句话解释。
 * 少了这个状态，要么搜索前就先印上「没有目标」（玩家以为自己在瞎搜），
 * 要么零结果时整块空白（他以为搜索坏了）—— 两句都错在同一个地方。
 *
 * <p>句式照房规 `MarchPanelView:77`「暂无在外的队伍」。返回空串时调用方直接画，不用再判一次。
 */
export function targetSearchNotice(searched: boolean, total: number): string {
  return searched && total === 0 ? '这一带没有可打的目标' : ''
}

/**
 * 目标搜索那两颗翻页键该不该露着 —— **没搜过就必须收着**，即使传进来的页数大于 1。
 *
 * <p>为什么单列一条免引擎判据（B00 铁律 2：判据要能脱离 Cocos 跑单测）：视图里 `paintPageButtons`
 * 只在 `render()` 里被调，而 `render()` 在 `response === null` 时第一行就 return ⇒
 * **首次搜索之前那两颗键从来没被判过**。它们建出来就是 `active`、`touch-start` 也挂着，
 * 点下去 `changePage` 又因 `response === null` 直接 return ⇒ 玩家看到一颗按了没反应的按钮。
 * #345 对这一族定的口径正是"点了没反应的按钮不该露着"（两颗半径键同一条理由已经收了，
 * 见 `TargetSearchView` 那句"建完就判一次"）。
 */
export function pagerKeysVisible(searched: boolean, pages: number): boolean {
  return searched && pages > 1
}

/**
 * 大数缩写：1234567 ⇒ "123.4万"，123456789 ⇒ "1.2亿"，9999 以下原样。
 *
 * <p>全程整数运算。用 {@code (v / 10000).toFixed(1)} 会在 19999 上得到 "2.0万"
 * （因为 1.9999 四舍五入），而玩家看到的战力从 1.9万 跳到 2.0万 却查不到那 1 点来源。
 * 截断而不是四舍五入：显示值永远不大于真实值，玩家不会以为自己能打一个实际打不过的对手。
 */
export function formatPower(value: number): string {
  if (!Number.isInteger(value)) {
    throw new Error(`战力必须是整数，实际=${value}`)
  }
  if (value < 0) {
    throw new Error(`战力不得为负，实际=${value}`)
  }
  const YI = 100_000_000
  const WAN = 10_000
  if (value >= YI) {
    return `${truncateToOneDecimal(value, YI)}亿`
  }
  if (value >= WAN) {
    return `${truncateToOneDecimal(value, WAN)}万`
  }
  return `${value}`
}

/** 定点倍率 → 文本：15000 ⇒ "1.5×"。 */
export function formatRatio(ratioFixed: number): string {
  return `${FixedPoint.format(ratioFixed)}×`
}

/**
 * 保留一位小数并<b>截断</b>（不四舍五入）。
 *
 * @param value 被缩写的整数
 * @param unit  单位（万 = 10000，亿 = 100000000）
 */
function truncateToOneDecimal(value: number, unit: number): string {
  const scaled = Math.trunc((value * 10) / unit)
  const whole = Math.trunc(scaled / 10)
  const decimal = scaled % 10
  return decimal === 0 ? `${whole}` : `${whole}.${decimal}`
}
