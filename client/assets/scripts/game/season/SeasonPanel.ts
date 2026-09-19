/**
 * 职责：赛季页的展示数据组装（V04-S1）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能打人、王城开没开、是不是只读期，
 * 全部是服务端下发的那三个布尔（`allowsPvp` / `allowsCapitalWar` / `readOnly`）——
 * 客户端只把它们翻成玩家看得懂的句子，不看图标颜色反推权限（V02 任务卡的原话）。
 *
 * <p><b>未启用赛季时整块收起</b>：`phase` / `seasonStartAt` / `dayIndex` / `phaseEndAt` 一律为 null
 * （服务端的口径，不是 0）。显示「赛季第 0 天」比不显示更糟 —— 玩家会以为赛季坏了。
 *
 * <p><b>倒计时用服务端两时刻相减</b>（`phaseEndAt - serverNow`，铁律 5）：客户端时钟可以改，
 * 用它算会让"还剩几天"随玩家改系统时间跳变。
 *
 * <p><b>保留项照抄 B14，不自己发明</b>：B14 §一 第 1 条与 §二「玩家主存档只保留：荣耀等级、历史最高段位、
 * 赛季徽章」，归档保留 3 季（申诉需要），段位按规则降 1~2 段（保留部分进度，降低挫败感）。
 */

import type {
  SeasonGloryView, SeasonPhase, SeasonStatusResp, SeasonTier,
} from '../../net/generated/SeasonProtocol'

/** 赛季阶段名（玩家语言）。表里没有的取值退回枚举名，不显示空白。 */
export function phaseLabel(phase: SeasonPhase | null): string {
  switch (phase) {
    case 'PREPARE':
      return '开垦期'
    case 'EXPAND':
      return '立盟期'
    case 'CAPITAL_WAR':
      return '问鼎期'
    case 'SETTLE':
      return '结算期'
    case 'REST':
      return '休赛期'
    default:
      return phase === null ? '' : String(phase)
  }
}

/**
 * 段位名（B14 §二 的原词：青铜 / 白银 / 黄金 / 铂金 / 钻石 / 王者）。
 *
 * <p>**不许把枚举名直接印到面板上**：`GOLD` 是给代码看的，玩家互相报的是"黄金"。
 * 表里没有的取值退回枚举名而不是空白 —— 与 {@link phaseLabel} 同一条纪律：
 * 新档位上线时玩家看到的是一个陌生的英文词，而不是一个空的行。
 */
export function tierLabel(tier: SeasonTier | null): string {
  switch (tier) {
    case 'BRONZE':
      return '青铜'
    case 'SILVER':
      return '白银'
    case 'GOLD':
      return '黄金'
    case 'PLATINUM':
      return '铂金'
    case 'DIAMOND':
      return '钻石'
    case 'KING':
      return '王者'
    default:
      return tier === null ? '' : String(tier)
  }
}

/** 一条闸门的措辞：`allowed=false` 的那句要写清"什么时候/什么条件下能用"，不是一句"不可用"。 */
export interface SeasonGate {
  readonly text: string
  readonly allowed: boolean
}

/** 剩余时间（服务端两时刻相减，铁律 5）。已过点给「即将切换」，不给负数。 */
export function remainTextOf(phaseEndAt: number | null, serverNow: number): string {
  if (phaseEndAt === null) {
    return ''
  }
  const remainMs = phaseEndAt - serverNow
  if (remainMs <= 0) {
    return '即将切换阶段'
  }
  const days = Math.floor(remainMs / 86_400_000)
  if (days >= 1) {
    return `还剩 ${days} 天`
  }
  const hours = Math.max(1, Math.floor(remainMs / 3_600_000))
  return `还剩 ${hours} 小时`
}

/** 保留项说明（口径来自 B14，逐句对得上；改这里要同时改 B14）。 */
export const SEASON_KEEP_NOTE =
  '赛季结束会保留：荣耀等级、历史最高段位、赛季徽章；段位按规则降 1~2 段，其余赛季数据归档保留 3 个赛季'

export interface SeasonPanelView {
  /** 赛季未启用时为 false，界面整块收起（不画"第 0 天"） */
  readonly visible: boolean
  /** 「S1 赛季 · 第 12 / 45 天」 */
  readonly titleText: string
  /** 「立盟期 · 还剩 5 天」 */
  readonly phaseText: string
  /** 三条闸门（能不能打人 / 王城开没开 / 是否只读期） */
  readonly gates: readonly SeasonGate[]
  /** 「我的名次：第 12 名」；没上榜或没带身份时为 null */
  readonly rankText: string | null
  /** 「荣耀 3 级 · 最高段位 黄金 · 徽章 3 枚」 */
  readonly gloryText: string | null
  /** 保留项那一句（永远显示：它是玩家最关心的"我攒的东西会不会没"） */
  readonly keepText: string
  /** 未启用 / 没带身份时的说明行；没有则为 null */
  readonly noticeText: string | null
}

function gloryTextOf(glory: SeasonGloryView | null | undefined): string | null {
  if (glory === null || glory === undefined) {
    return null
  }
  return `荣耀 ${glory.gloryLevel} 级 · 最高段位 ${tierLabel(glory.highestTier)} · 徽章 ${glory.badges.length} 枚`
}

/**
 * 组装赛季页。
 *
 * @param resp      GET /season/status 的响应；null = 还没拉回来（画一句说明，不画一个假赛季）
 * @param serverNow 用于倒计时的服务端时刻（优先用响应自带的 `serverNow`，见下）
 * @param failureNotice 拉取失败时服务端给的理由（限流/断网），原样写进说明行。
 *                  **失败不清空面板**：手里那份还在就继续显示，只在下面加一行"这次没拉到"
 */
export function buildSeasonPanel(resp: SeasonStatusResp | null,
  serverNow?: number, failureNotice?: string | null): SeasonPanelView {
  if (resp === null || resp === undefined) {
    return {
      visible: false, titleText: '', phaseText: '', gates: [], rankText: null, gloryText: null,
      keepText: SEASON_KEEP_NOTE, noticeText: failureNotice ?? '赛季信息还没拉回来，稍后再试',
    }
  }
  // 未启用：相位为 null 就是权威答案（服务端口径），整块收起
  if (resp.phase === null || resp.dayIndex === null) {
    return {
      visible: false, titleText: '', phaseText: '', gates: [], rankText: null, gloryText: null,
      keepText: SEASON_KEEP_NOTE, noticeText: failureNotice ?? '本服尚未启用赛季',
    }
  }
  const now = serverNow ?? resp.serverNow
  const days = `${(resp.dayIndex ?? 0) + 1} / ${resp.totalDays}`
  return {
    visible: true,
    titleText: `${resp.seasonId} 赛季 · 第 ${days} 天`,
    phaseText: `${phaseLabel(resp.phase)} · ${remainTextOf(resp.phaseEndAt, now)}`,
    gates: [
      { allowed: resp.allowsPvp, text: resp.allowsPvp ? '可以攻击其他玩家' : '当前阶段禁止玩家间攻击' },
      { allowed: resp.allowsCapitalWar, text: resp.allowsCapitalWar ? '中央王城已开放' : '中央王城尚未开放（问鼎期才开）' },
      { allowed: !resp.readOnly, text: resp.readOnly ? '休赛期：只展示荣耀，不再产生新的赛季行为' : '赛季进行中' },
    ],
    rankText: resp.myRank === null || resp.myRank === undefined || resp.myRank <= 0
      ? null
      : `我的名次：第 ${resp.myRank} 名`,
    gloryText: gloryTextOf(resp.glory),
    keepText: SEASON_KEEP_NOTE,
    noticeText: failureNotice ?? (resp.myRank === null || resp.myRank === undefined
      ? '带上身份才看得到自己的名次与荣耀'
      : null),
  }
}
