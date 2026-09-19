/**
 * 职责：排行榜面板的展示数据组装（B23 §一 3）。引擎无关，可脱离 Cocos 跑单测（B00 铁律 2）。
 * 依赖：生成的协议类型（`RankProtocol`）。
 *
 * <p><b>本模块不重算任何名次</b>（B23 禁止项：不要在客户端重算名次或本地拼榜）：
 * 名次、榜值、每页条数、有没有下一页全部原样取自服务端一次 `/rank/list` 响应。
 * 客户端算一遍的结局只有一种 —— 和服务端差一位，而玩家对名次比别人低一位极度敏感，
 * 何况「我第几名」还决定了发不发奖。
 *
 * <p><b>「我的名次恒在顶部」靠服务端下发的 `myRank`/`myValue`，不靠把我插进行里</b>：
 * 插行会让页面的名次列出现两个第 N 名（服务端那一页里可能也有我）。所以置顶那一行是
 * **独立的摘要行**，行里不写名字（名字要靠本地昵称拼，那正是"本地拼榜"的开端）。
 *
 * <p><b>页签的顺序是固定的四张榜</b>（战力 / 击杀 / 联盟 / 国家，§一 1 的顺序）：
 * 与 `RankType` 枚举同序，不按"当前哪个榜人最多"动态排 —— 玩家下一次打开时页签位置不变，
 * 肌肉记忆才有意义。
 */

import type { RankEntryView, RankListResp, RankSnapshotResp, RankType } from '../../net/generated/RankProtocol'

/** 页签的键：四张榜 + 原来那一页「战力明细」。 */
export type RankTabKey = RankType | 'DETAIL'

/**
 * 页签定义：顺序即面板顺序。
 *
 * <p><b>为什么明细也占一个页签（五个而不是裁决④字面上的四个）</b>：裁决④把入口写进「战力」页，
 * 而 B23 §一 3 要的是"四类榜都能看"。若严格按四个 tab 实现（战力/击杀/联盟/国家），
 * 第一个 tab 要么是明细（那 POWER 榜没地方放）、要么是 POWER 榜（那 B08 的战力明细被挤掉）。
 * 所以第一个页签叫「明细」，后面四个是四类榜 —— **能力一个不少，页签多一个**。
 */
export const RANK_TABS: readonly { readonly key: RankTabKey; readonly label: string }[] = [
  { key: 'DETAIL', label: '明细' },
  { key: 'POWER', label: '战力榜' },
  { key: 'KILL', label: '击杀榜' },
  { key: 'ALLIANCE', label: '联盟榜' },
  { key: 'NATION', label: '国家榜' },
]

/** 这个页签是不是一张榜（明细页不画榜）。 */
export function isBoardTab(key: RankTabKey): key is RankType {
  return key !== 'DETAIL'
}

/** 一个页签。 */
export interface RankTabView {
  readonly key: RankTabKey
  readonly label: string
  readonly active: boolean
}

/** 榜上的一行。 */
export interface RankRowView {
  readonly rank: number
  /** 「第 3 名」 */
  readonly rankText: string
  readonly name: string
  /** 联盟缩写；个人榜为空串（不显示一个 null） */
  readonly tag: string
  /** 榜值文本，带千分位 */
  readonly valueText: string
  /** 是不是我自己那一行（服务端下的 id 与我相等才为真，不做名字比对） */
  readonly mine: boolean
}

/** 置顶的「我的名次」摘要行；未上榜时为 null。 */
export interface RankMineView {
  readonly rankText: string
  readonly valueText: string
  /** 榜值这一格叫什么：战力榜是「匹配战力」，击杀榜是「累计击杀」…… 见 {@link valueLabelOf} */
  readonly valueLabel: string
}

/** 整个榜面板的展示数据。 */
export interface RankBoardView {
  readonly tabs: readonly RankTabView[]
  /** 当前是哪个页签：表现层靠它决定画明细还是画榜（明细页不画榜） */
  readonly activeKey: RankTabKey
  readonly rows: readonly RankRowView[]
  /** 我的名次（置顶）；未上榜为 null */
  readonly mine: RankMineView | null
  /** 未上榜时的说明；上榜时为 null */
  readonly notRankedText: string | null
  /** 「第 2 / 5 页」 */
  readonly pageText: string
  readonly canPrev: boolean
  readonly canNext: boolean
  /** 空榜时的说明（"这个榜还没有人"这类）；有人时为 null */
  readonly emptyText: string | null
  /** 拉榜失败时那一行提示（限流/断网等）；正常时为 null。文案由编排层给（服务端理由原样） */
  readonly noticeText: string | null
}

/** 每日快照（申诉时间线）那一块的展示数据。 */
export interface RankSnapshotView {
  readonly dayText: string
  readonly snapshotText: string
  readonly rankText: string
  readonly valueText: string
}

/** 榜值那一格的标签。四张榜量纲不同，同一个「值」字会让玩家读不出自己在看什么。 */
export function valueLabelOf(key: RankType): string {
  switch (key) {
    case 'POWER':
      return '匹配战力'
    case 'KILL':
      return '赛季击杀'
    case 'ALLIANCE':
      return '联盟赛季分'
    case 'NATION':
      return '国家赛季分'
    default:
      throw new Error(`不认识的榜类型：${key}`)
  }
}

/** 榜值的量纲说明（空榜时用来说明"这个榜记的是什么"）。 */
export function boardHintOf(key: RankType): string {
  switch (key) {
    case 'POWER':
      return '按匹配战力排名（含峰值记忆托底，与你面板上那个数一致）'
    case 'KILL':
      return '按本赛季累计击杀排名（与战力是两本账）'
    case 'ALLIANCE':
      return '按成员赛季分之合排名'
    case 'NATION':
      return '按成员赛季分之合排名'
    default:
      throw new Error(`不认识的榜类型：${key}`)
  }
}

/** 大数字加千分位（与活动/任务面板同一条口径：1200000 读起来是 1,200,000）。 */
function formatCount(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * 把一次 `/rank/list` 响应组装成界面要的数据。
 *
 * @param resp    服务端响应；调这个榜但还没拿到时为 null（面板先画页签与"加载中"）
 * @param active  当前页签（客户端只决定"看哪一张"，不决定榜的内容）
 * @param myPlayerId 我自己的 playerId。只用于**标记**哪一行是我（`mine` 高亮），
 *                   不用于任何比较或插入 —— 组织榜那一行是我的联盟，不是我的 id，
 *                   所以组织榜下不会有任何一行被标成 mine（这是对的：联盟榜标"我"是联盟行）
 */
export function buildRankBoard(resp: RankListResp | null, active: RankTabKey,
                               myPlayerId: string, notice: string | null = null): RankBoardView {
  const tabs: RankTabView[] = RANK_TABS.map(tab => ({
    key: tab.key,
    label: tab.label,
    active: tab.key === active,
  }))
  const blank = (emptyText: string | null): RankBoardView => ({
    tabs,
    activeKey: active,
    rows: [],
    mine: null,
    notRankedText: null,
    pageText: '第 1 页',
    canPrev: false,
    canNext: false,
    emptyText,
    noticeText: notice,
  })
  // 明细页不画榜：这一页的数字来自 /player/power，与榜无关（两张数据源不同，混着画会串台）
  if (active === 'DETAIL') {
    return blank(null)
  }
  // 响应与页签不是同一张榜时**按"还在载入"处理**：切页签的那一刻，手里那份响应属于上一张榜，
  // 画出来就是"KILL 页签下画着 POWER 的行"。这不是洁癖 —— 两张榜的行长得一模一样
  // （都是名次+名字+数字），玩家看不出自己看错了榜，而页面标题与高亮页签都在说 KILL。
  // 让它机械地不可能发生，比在每个调用点记得"切页签时清空"可靠。
  if (resp !== null && resp.type !== active) {
    resp = null
  }
  if (resp === null) {
    return blank('正在载入…')
  }
  const personal = resp.type === 'POWER' || resp.type === 'KILL'
  const rows: RankRowView[] = resp.entries.map(entry => toRow(entry, personal, myPlayerId))
  const ranked = resp.myRank !== null && resp.myValue !== null
  return {
    tabs,
    activeKey: resp.type,
    rows,
    mine: ranked
      ? {
        rankText: `第 ${resp.myRank} 名`,
        valueText: formatCount(resp.myValue as number),
        valueLabel: valueLabelOf(resp.type),
      }
      : null,
    // 未上榜不是错误：说明里要给出下一步（打一仗/发育），而不是一句"暂无数据"
    notRankedText: ranked
      ? null
      : personal
        ? '你还没有上榜：榜值来自你在本赛季的成绩，先发育或出战再回来看看'
        : '你所在的联盟/国家还没有分数：成员上报战力后这里会跟着变',
    pageText: `第 ${resp.page} 页`,
    canPrev: resp.page > 1,
    canNext: resp.hasMore,
    emptyText: rows.length === 0 ? `这个榜还没有人。${boardHintOf(resp.type)}` : null,
    noticeText: notice,
  }
}

/** 快照那一块：只展示服务端给的三个数，不解释"为什么那天是这样"。 */
export function buildRankSnapshotView(resp: RankSnapshotResp): RankSnapshotView {
  const day = resp.dayKey
  const dayText = day.length === 8 ? `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}` : day
  return {
    dayText,
    snapshotText: `快照时刻：${new Date(resp.snapshotAt).toISOString().replace('T', ' ').slice(0, 16)}（UTC）`,
    rankText: resp.myRank === null ? '那天你不在榜上' : `那天你排第 ${resp.myRank} 名`,
    valueText: resp.myValue === null ? '' : formatCount(resp.myValue),
  }
}

function toRow(entry: RankEntryView, personal: boolean, myPlayerId: string): RankRowView {
  return {
    rank: entry.rank,
    rankText: `第 ${entry.rank} 名`,
    name: entry.name,
    tag: entry.tag === null ? '' : entry.tag,
    valueText: formatCount(entry.value),
    // 只有个人榜才可能"这一行是我"：组织榜的行是联盟/国家，它们没有"我"这个概念
    mine: personal && entry.id === myPlayerId,
  }
}
