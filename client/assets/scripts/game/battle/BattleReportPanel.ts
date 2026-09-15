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
import { parseSpeeds } from './BattlePlayback'
import type { PlaybackOptions } from './BattlePlayback'

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
