/**
 * 职责：等级奖励面板的纯逻辑层（收口清单 #829 裁决③「新建可见入口」的表现层数据组装）。
 * 依赖：只依赖生成的协议类型（`net/generated/LevelRewardProtocol`），不依赖 cc。
 *
 * <p><b>本文件一个数值都不自己算</b>：每级给多少、领没领过、能不能领、主城几级，
 * 全部来自服务端那一份响应（客户端不抄配置表）。这里只做三件事：
 * 把行文案排好、按实测屏高算该画哪一屏、把点击意图整理成领取请求体。
 *
 * <p><b>奖励文案只用服务端下发的 `name`，绝不用 `id`</b>：`WOOD` / `GOLD` 这类内部码印到屏上
 * 是本仓反复出过的事故（台账「屏上不出现裸 id」那一维，运行时量具 `verify-level-reward-runtime.mjs` 专门钉它）。
 */

import type {
  LevelRewardClaimResp,
  LevelRewardItem,
  LevelRewardListResp,
  LevelRewardRow,
} from '../../net/generated/LevelRewardProtocol'

/** 编排层递给面板的那一块：原始响应 + 上一次操作的结果行（面板只画一次）。 */
export type LevelRewardPanelData = {
  readonly source: LevelRewardListResp
  readonly notice?: string | null
}

export type LevelRewardRowView = {
  readonly level: number
  /** 行名（服务端 `name`，如「主城 31 级奖励」）。 */
  readonly nameText: string
  /** 「木材×97,440 · 石料×97,440 · 金币×800」——每一项都取服务端 `name`。 */
  readonly rewardText: string
  /** 三态各自的一句话，玩家据此知道下一步该做什么。 */
  readonly stateText: string
  readonly claimable: boolean
  readonly claimed: boolean
  readonly locked: boolean
}

export type LevelRewardView = {
  readonly rows: readonly LevelRewardRowView[]
  /** 表头：`等级奖励 · 第 25–32 级 / 共 40 级`；一行都没有时不带范围段。 */
  readonly headerText: string
  /** `主城 14 级 · 可领 3 级`。 */
  readonly summaryText: string
  /** 空表时给玩家的那句话（正常不会走到，表被清空时是唯一可见线索）。 */
  readonly noticeText: string
  readonly totalRows: number
  readonly windowStart: number
}

/** 千分位：6 位以上的奖励数（31 级起就上万）没有分隔符时玩家要逐位数。 */
function groupThousands(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 一个奖励条目 → 「木材×50」。`name` 缺失时宁可整项不画，也不回退成 `id`。 */
function itemText(item: LevelRewardItem): string | null {
  if (item.name === undefined || item.name === '') {
    return null
  }
  return `${item.name}×${groupThousands(item.count)}`
}

/**
 * 一行的奖励串。
 *
 * <p>空段不进串（房规同 `ArmyPanelView:413`、`ShopPanelView:262`）：无条件拼分隔符会印出
 * 「木材×50 · 」这种带尾巴的空分隔符（#349 目视抓到过同一形状）。
 */
function rewardTextOf(row: LevelRewardRow): string {
  const parts = row.rewards.map(itemText).filter((text): text is string => text !== null)
  return parts.join(' · ')
}

/**
 * 三态的措辞。
 *
 * <p><b>locked 那一态必须报出「要到几级」**：只说「不可领」玩家不知道该干什么，
 * 而等级差是他唯一能自己推进的事（服务端已经把 mainLevel 与每级 level 都下发了，不需要猜）。
 */
function stateTextOf(row: LevelRewardRow, mainLevel: number): string {
  if (row.claimed) {
    return '已领取'
  }
  if (row.locked) {
    return `主城 ${mainLevel} 级，还差 ${row.level - mainLevel} 级`
  }
  return '待领取'
}

/**
 * 窗口起点：先落在「第一个还没领的等级」，再往前回看 {@link CLAIM_LOOKBACK} 级。
 *
 * <p><b>为什么要回看</b>：纯按"下一个该领"起屏的话，刚领完的那一行立刻被推出屏幕 ——
 * 玩家点完「领取」看到键变灰，却找不到是哪一级领过了；而「已领/待领两态可辨」这条验收
 * 在只画待领的那一屏上根本无法成立（运行时量具 C 相就是查这一条，量出来的会是"屏上只有一种态"）。
 * 回看两级之后同一屏上既有刚领掉的、也有接下来要领的。
 */
const CLAIM_LOOKBACK = 2

export function windowStartOf(resp: LevelRewardListResp): number {
  const rows = resp.rows
  for (let i = 0; i < rows.length; i += 1) {
    if (rows[i]!.claimable) {
      return i
    }
  }
  for (let i = 0; i < rows.length; i += 1) {
    if (rows[i]!.locked) {
      return i
    }
  }
  return 0
}

/**
 * 组装这一屏。
 *
 * @param maxRows 视图按实测可视高度算出来的行数（写死行数会在矮窗口把最后一行压进导航条，
 *                与战令/商店/军队同一条纪律 —— 台账 #811 的净区高判据就是为这一族立的）
 */
export function buildLevelRewardPanel(resp: LevelRewardListResp, maxRows: number): LevelRewardView {
  const all = resp.rows
  const anchor = windowStartOf(resp)
  const capacity = Math.max(0, all.length - Math.max(1, maxRows))
  const start = Math.min(Math.max(0, anchor - CLAIM_LOOKBACK), capacity)
  const window = all.slice(start, start + Math.max(1, maxRows))
  const last = window.length === 0 ? start : start + window.length - 1
  const rows: LevelRewardRowView[] = window.map((row) => ({
    level: row.level,
    nameText: row.name,
    rewardText: rewardTextOf(row),
    stateText: stateTextOf(row, resp.mainLevel),
    claimable: row.claimable,
    claimed: row.claimed,
    locked: row.locked,
  }))
  // 范围段在"一行都没有"时是空串，直接拼会留下「等级奖励 · 」（#349 那一族的同一个形状）
  const rangeText = all.length === 0
    ? ''
    : `第 ${all[start]?.level ?? 0}–${all[last]?.level ?? 0} 级 / 共 ${all.length} 级`
  return {
    rows,
    headerText: rangeText === '' ? '等级奖励' : `等级奖励 · ${rangeText}`,
    summaryText: `主城 ${resp.mainLevel} 级 · 可领 ${resp.claimableCount} 级`,
    noticeText: all.length === 0 ? '暂时没有任何等级奖励' : '',
    totalRows: all.length,
    windowStart: start,
  }
}

/**
 * 领取请求体。不可领的那一行返回 null —— 上层据此**不发请求**（服务端会拒，
 * 而一次注定被拒的写入还会吃掉一个幂等键）。
 */
export function claimBodyOf(row: LevelRewardRowView): { level: number } | null {
  return row.claimable ? { level: row.level } : null
}

/** 领取回执上的那一句飘字：只报服务端「真发出去的那份」（溢出截断后不是表面值）。 */
export function claimResultText(resp: LevelRewardClaimResp): string {
  const parts = resp.rewards
    .map(itemText)
    .filter((text): text is string => text !== null)
  const body = parts.join(' · ')
  // 明细为空 = 仓库存不下（发放器把装不下的部分转了邮件），此时不能说「获得 ：」，
  // 也不能说「已入账」—— 玩家打开资源条看不见变化，那两句都会让他以为领了个寂寞
  return body === ''
    ? `主城 ${resp.level} 级已领取，装不下的部分请查看邮件`
    : `已领取 ${body}`
}
