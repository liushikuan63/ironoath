/**
 * 职责：战报面板的展示组装 + 回放参数装配（B12 §3 / 台账第 21 条）。
 * 依赖：只有协议类型与 `BattlePlayback` 的倍速解析（**解析只有一处**，不在这再写一遍）。
 *
 * <p><b>本模块不判胜负</b>（铁律 2）：`won` 是服务端算好下发的布尔，这里只把它说成「胜 / 败」。
 *
 * <p><b>为什么要 `playbackOptionsOf`</b>：`BattlePlaybackView.attach` 从写下第一天起就没有调用方，
 * 原因就记在它自己的 TODO 里 —— 播放时长参数没人给。现在表里那两个数随战报详情下发，
 * 表里没有的三段（开场/结算/技能）按参数自己的注释「与回合时长同量级」从 `roundMs` 推导：
 * **不新造数**，也不把它们抄进客户端常量（那会变成第二个家，改了表也不会跟着动）。
 */
import type { BattleReportListResp, BattlePlaybackParams } from '../../net/generated/BattleProtocol'
import type { ScoutListResp } from '../../net/generated/WorldProtocol'
import { parseSpeeds } from './BattlePlayback'
import type { PlaybackOptions } from './BattlePlayback'
import { resourceName } from '../ui/ResourceNames'

/** 一行的展示数据。 */
export interface ReportRow {
  readonly reportId: string
  /** 「打野 叛军斥候」这种：类型在前，对手在后（玩家扫列表时先要知道自己刚干了哪类事）。 */
  readonly title: string
  readonly outcomeText: string
  readonly won: boolean
  /** 战损摘要：我方损失 / 对方损失。数字来自服务端，这里只排版。 */
  readonly lossText: string
  readonly roundsText: string
  /** 「3 小时后过期」。战报是会自动消失的东西，不写清楚玩家会以为它一直在。 */
  readonly expiresIn: string
}

export interface ReportListView {
  readonly rows: readonly ReportRow[]
  readonly headerText: string
  /** 空列表与"没连上"要分得开，所以这一格由服务端有没有回数决定。 */
  readonly emptyText: string
}

const HOUR = 3_600_000
const DAY = 24 * HOUR

const TYPE_LABEL: Record<string, string> = {
  PVE: '打野',
  PVP_SOLO: '野战',
  PVP_RALLY: '集结',
  SIEGE: '攻城',
}

/**
 * 组装战报列表。
 *
 * @param now 服务端时刻（相对时间要它算，理由与邮件面板同一条）
 */
export function buildReportList(resp: BattleReportListResp, now: number): ReportListView {
  const rows = resp.reports.map(r => ({
    reportId: r.reportId,
    title: `${TYPE_LABEL[r.battleType] ?? r.battleType} ${r.opponentName}`,
    outcomeText: r.won ? '胜' : '败',
    won: r.won,
    lossText: `我方 ${r.attackerLoss} · 对方 ${r.defenderLoss}`,
    roundsText: `${r.totalRounds} 回合`,
    expiresIn: expiresInText(r.expiresAt - now),
  } satisfies ReportRow))
  const won = rows.filter(r => r.won).length
  return {
    rows,
    headerText: rows.length === 0 ? '' : `${rows.length} 场 · 胜 ${won} · 败 ${rows.length - won}`,
    emptyText: rows.length === 0 ? '还没有战报（打过一场就会出现在这里）' : '',
  }
}

/**
 * 把服务端下发的两个数装配成 `PlaybackOptions`。
 *
 * <p>开场与结算各取一个回合的量级、技能特效取半个回合 —— 依据是 `PlaybackOptions`
 * 自己那两行注释（「表现层约定，与回合时长同量级」）。倍速用 `parseSpeeds` 解析，
 * 它对不支持的档位会抛：**这里不吞**，参数表写错就该在装配的那一刻响，
 * 而不是让玩家点开后看到一屏不动的画。
 */
export function playbackOptionsOf(params: BattlePlaybackParams): PlaybackOptions {
  const roundMs = params.roundMs
  if (roundMs <= 0) {
    throw new Error(`BATTLE_ROUND_DISPLAY_MS 下发的是 ${roundMs}：一回合演 0 毫秒等于没有回放`)
  }
  return {
    roundMs,
    openingMs: roundMs,
    settlementMs: roundMs,
    skillMs: Math.max(1, Math.round(roundMs / 2)),
    speeds: parseSpeeds(params.speeds),
  }
}

/** 还剩多久过期。不到一小时也写「不到 1 小时」而不是 0，玩家看到 0 会以为已经没了。 */
function expiresInText(millis: number): string {
  if (millis <= 0) {
    return '已到期'
  }
  if (millis < HOUR) {
    return '不到 1 小时'
  }
  if (millis < DAY) {
    return `${Math.floor(millis / HOUR)} 小时后`
  }
  return `${Math.floor(millis / DAY)} 天后`
}

// ---------- 侦察情报（B26 S19：`GET /world/reports` 的读者）----------

/**
 * 侦察指标名 → 中文。**只放「不是资源」的那几条**。
 *
 * <p><b>为什么资源那几条不在这张表里</b>：资源名是 `resource.json` 的 `name` 列，
 * 客户端那份唯一来源在 `game/ui/ResourceNames`（`resourceName`）——
 * 而它的内容是**服务端随 /player/init 下发**的，不是客户端抄的表。
 * 这里原本**另抄了一份**，而且抄错了：
 * `iron: '铁'` —— 而唯一副本里是「铁矿」。⇒ 同一份资源名在客户端有两种译法，
 * 玩家在战报里看到「铁」、在资源条上看到「铁矿」，说的是同一样东西。
 *
 * <p><b>为什么资源那五条不在这张表里**（第二个理由，比第一个更硬）：
 * `ScoutMetric.name` 的合法取值是 `wood` / `stone` / `iron` / `grain` / `gold`（**小写**），
 * 而资源名映射的键是 `WOOD` / `STONE` / `IRON` / `GRAIN` / `GOLD`（**大写**）——
 * 协议用的是**指标键**，不是**资源类型枚举**，两个命名空间。
 * 所以这里必须显式做一次键名映射（`wood` → `WOOD`），
 * 想当然地直接 `resourceName(metric.name)` 会全部落空、屏幕上退回英文原名。
 */
const METRIC_LABEL: Record<string, string> = {
  power: '战力', totalUnits: '总兵力', infantry: '步兵', cavalry: '骑兵', archer: '弓手',
  siege: '攻城',
}

/**
 * 侦察指标键 → 资源类型枚举。
 *
 * <p>协议键是**小写的指标名**（`ScoutMetric.name` 的取值），
 * 资源类型枚举是**大写的资源 id**（`resource.json` 的 `rows[].id`）。
 * 两者不是同一个命名空间 ⇒ 必须显式映射。
 *
 * <p>⚠️ **只列服务端真会发的那几条**（`ScoutReport.STANDARD_METRICS` 里的资源项：
 * wood / stone / iron / grain / gold）。表里没有的键不在这里 ——
 * 那样它会走到「表里没有的名字原样透出」的回退分支（宁可生僻，也不猜一个错的）。
 */
const METRIC_RESOURCE_TYPE: Record<string, string> = {
  wood: 'WOOD', stone: 'STONE', iron: 'IRON', grain: 'GRAIN', gold: 'GOLD',
}

/** 一个侦察指标的中文名。资源类走唯一那份资源名映射，其余走本文件的显示词表。 */
function metricLabel(name: string): string {
  const resourceType = METRIC_RESOURCE_TYPE[name]
  if (resourceType !== undefined) {
    return resourceName(resourceType)
  }
  return METRIC_LABEL[name] ?? name
}

/** 一行侦察情报。`outcome` 放在最右边那一格：过期与否是"这份还能不能用"的第一读数。 */
export interface ScoutRow {
  readonly reportId: string
  readonly title: string
  readonly detail: string
  readonly outcome: string
  readonly expired: boolean
}

export interface ScoutListView {
  readonly rows: readonly ScoutRow[]
  readonly headerText: string
  readonly emptyText: string
}

/** 一万以上折成「1.2 万」：情报里的兵力动辄上万，一长串数字反而读不出量级。 */
function compactCount(value: number): string {
  return value >= 10_000 ? `${(Math.round(value / 100) / 100).toFixed(1)} 万` : `${value}`
}

/**
 * 把敌情报告列表摊成面板数据。
 *
 * <p><b>误差幅度必须和数字贴在一起</b>（B07 验收 9）：分开写玩家就会把「兵力 1.2 万」当成
 * 精确值来做决策，那比没有情报更糟。`errorFixed` 是定点（10000 = 100%），这里只除成百分数。
 *
 * <p><b>过期的那份不藏起来</b>：服务端连 `expired=true` 一起回，就是让玩家知道"我侦察过、
 * 但那份已经不作数了"，而不是让列表凭空少一条。
 */
export function buildScoutIntel(resp: ScoutListResp | null, now: number): ScoutListView {
  if (resp === null) {
    return { rows: [], headerText: '侦察情报', emptyText: '敌情读取中' }
  }
  const rows = resp.reports.map((report) => {
    const errorPercent = Math.round(report.errorFixed / 100)
    const metrics = report.metrics.slice(0, 3)
      .map(metric => `${metricLabel(metric.name)} ${compactCount(metric.value)}`)
      .join(' · ')
    const observed = metrics.length === 0 ? '没有观测项' : `${metrics} · ±${errorPercent}%`
    return {
      reportId: report.reportId,
      title: `侦察：${report.target.x}, ${report.target.y} · ${report.targetLevel} 级`,
      detail: report.expired ? `已过期，不能再拿来定打法 · ${observed}`
        : `${observed} · ${expiresInText(report.expiresAt - now)}过期`,
      outcome: report.expired ? '过期' : '有效',
      expired: report.expired,
    }
  })
  const live = rows.filter(row => !row.expired).length
  return {
    rows,
    headerText: rows.length === 0 ? '侦察情报' : `侦察情报 ${rows.length} 份（${live} 份还有效）`,
    emptyText: '还没有敌情：在出征编成里把命令切成「侦察」，派一队去看',
  }
}
