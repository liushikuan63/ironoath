/**
 * 职责：「自上次登录以来」的汇总（B25-S3）—— **只聚合既有账本**，不新造第二本账。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>起点是服务端给的那一个</b>：`offlineReport.previousLoginAt`，不是 `profile.lastLoginAt`
 * （后者在 init 返回时已经推进成"现在"，拿它算永远是 0）。新号拿到 null ⇒ 没有「上一次」可言，
 * 此时一条都不列、也不弹（见 {@link offlineReportGate}）。
 *
 * <p><b>为什么不许把"结算量"与"当前库存"混着加总</b>（§8.8.3 P1 原文）：资源那一行的诱惑写法是
 * 「现在 − 上次看到的」，但**自动续训会在离线期间真的花掉资源**，那样算出来的差会把花费记成产出。
 * 服务端没有逐窗口的流水账（那要新开一本账，超出"零新系统"），所以这里给的是**估算**：
 * 每条资源 `perHour × 窗口小时`，文案里明写「约」，且**不与任何面板数字做等式**。
 * 事件类条目（建筑到点 / 战斗 / 社交）相反 —— 它们逐条可点进去看到那一笔，是严格对得上的。
 *
 * <p><b>三条事件来源都是玩家能点进去的那一页</b>：建筑取 `/city/list` 里"到点待收割"的那批
 * （升级完成在面板上就是那个亮着的收割键）、战斗取战报列表里 `createdAt` 落在窗口内的那几封、
 * 社交取 `/social/summary.events` 里 `occurredAt` 落在窗口内且没过期的条。
 * 每一条都带 `jump`，客户端据此"点条目跳到对应面板"（验收 6 的可见即可核对）。
 */

import type { OfflineReportView } from '../../net/generated/Protocol'
import type { BuildingView, ResourceStateView } from '../../net/generated/CityProtocol'
import { RESOURCE_NAMES } from '../ui/ResourceNames'
import type { BattleReportBrief } from '../../net/generated/BattleProtocol'
import type { SocialEventView } from '../../net/generated/SocialProtocol'

/** 汇总条目能跳去的那几页。刻意只列本模块会用到的那三个，取值与 `PanelNav` 的 key 一致。 */
export type OfflineJump = 'city' | 'reports' | 'social'

/** 一条汇总。`key` 是稳定身份（同一批明细的指纹由它拼出来，用来"同一批不重复弹"）。 */
export interface OfflineItem {
  readonly key: string
  /** 一句话（标题），玩家读的就是它 */
  readonly text: string
  /** 明细（第二条信息，可为空） */
  readonly detail: string | null
  /** 点它跳到哪一页 */
  readonly jump: OfflineJump
}

/** 组装汇总需要的输入：全是客户端**已经拉到**的那几份响应 + 服务端给的边界与阈值。 */
export interface OfflineSources {
  readonly offlineReport: OfflineReportView
  /** 服务端当前时刻（取自同一次响应，用来判断"到点"） */
  readonly serverNow: number
  /** `/city/list` 的结算快照 */
  readonly resources: Record<string, ResourceStateView>
  /** `/city/list` 的建筑（找"到点待收割"的那批） */
  readonly buildings: readonly BuildingView[]
  /** `/battle/reports` 的列表（按时间倒序） */
  readonly reports: readonly BattleReportBrief[]
  /** `/social/summary` 的未读事件 */
  readonly events: readonly SocialEventView[]
}

/**
 * 组装汇总条目。**顺序即展示顺序**（资源 → 建筑 → 战斗 → 社交），没有条目的类别不出现。
 */
export function buildOfflineItems(src: OfflineSources): OfflineItem[] {
  const from = src.offlineReport.previousLoginAt
  if (from === null) {
    return []   // 新号：没有「上一次」，一条都不列
  }
  const items: OfflineItem[] = []

  // ① 资源产出（约）：服务端没有逐窗口流水账，而"当前库存 − 上次库存"会把自动续训的花费算成产出
  // （红线禁止的算法），所以这里只给"按产率算的估算"，文案写「约」，也不与任何面板数字做等式
  const hours = Math.max(0, src.serverNow - from) / 3_600_000
  const produced: string[] = []
  for (const [id, state] of Object.entries(src.resources)) {
    if (state.perHour <= 0 || id === 'STAMINA') {
      continue   // 体力不按产率走（买与恢复是两条路），列它只会让人对不上账
    }
    const amount = Math.floor(state.perHour * hours)
    if (amount > 0) {
      produced.push(`${RESOURCE_NAMES[id] ?? id} +${amount}`)
    }
  }
  if (produced.length > 0) {
    items.push({
      key: 'resources',
      text: `资源产出（约 ${formatHours(hours)}）`,
      detail: produced.join('、'),
      jump: 'city',
    })
  }

  // ② 建筑到点：升级完成但还没收割的那些 —— 面板上就是那个亮着的收割键
  const ready = src.buildings.filter((b) => b.status === 'UPGRADING'
    && b.finishAt !== null && b.finishAt <= src.serverNow)
  if (ready.length > 0) {
    items.push({
      key: 'buildings',
      text: `${ready.length} 座建筑已升级完成`,
      detail: '回城里收割一下就能拿到产出',
      jump: 'city',
    })
  }

  // ③ 战斗：窗口内产生的战报（逐封可点开回放）
  const battles = src.reports.filter((r) => r.createdAt > from)
  if (battles.length > 0) {
    const won = battles.filter((r) => r.won).length
    items.push({
      key: `battles:${battles.length}:${won}`,
      text: `${battles.length} 场战斗`,
      detail: `胜 ${won} · 败 ${battles.length - won}`,
      jump: 'reports',
    })
  }

  // ④ 社交：窗口内的未读事件（过期的置灰不可跳，这里直接不列 —— 汇总里列一条点不动的更糟）
  const social = src.events.filter((e) => e.occurredAt > from && !e.expired)
  if (social.length > 0) {
    items.push({
      key: `social:${social.length}`,
      text: `${social.length} 条社交动态`,
      detail: social[0]?.title ?? null,
      jump: 'social',
    })
  }

  return items
}

/** 「3 小时」/「45 分钟」这种给玩家读的时长。窗口不足 1 分钟时说「1 分钟内」。 */
export function formatHours(hours: number): string {
  const minutes = Math.round(hours * 60)
  if (minutes < 1) {
    return '1 分钟内'
  }
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  return `${Math.floor(minutes / 60)} 小时${minutes % 60 === 0 ? '' : ` ${minutes % 60} 分钟`}`
}

/** 这一批明细的指纹：同一批（键集合相同）就不该再弹第二次。 */
export function fingerprintOf(items: readonly OfflineItem[]): string {
  return items.map((i) => i.key).join('|')
}

/** 判定结果。`reason` 在 `show=false` 时说清为什么（纯逻辑用例与排障都用它，不必猜）。 */
export interface OfflineGate {
  readonly show: boolean
  readonly reason: string | null
  /** 真要展示时把它交给调用方记住（下次同一批就不弹了）；不展示时为 null */
  readonly fingerprint: string | null
}

/**
 * 该不该弹这一屏汇总（验收 7）。
 *
 * <p>四条闸门，缺一不可：**新号没有起点**（previousLoginAt 为 null）、**离得太近**（不足
 * `minIdleMinutes`）、**没有东西可说**（条目数不足 `minItems`）、**同一批已经给他看过**
 * （指纹相同）。阈值全部来自服务端下发的那两个数 —— 客户端一个都不填。
 *
 * <p><b>为什么"同一批"要靠指纹而不是一个布尔</b>：玩家看完汇总、去打了场仗再切回来，
 * 这时窗口没变而明细变了（多了一封战报）—— 那条新的值得说，旧的不用再说。指纹让"新出现的"
 * 自然通过，而"已经看过的同一批"被挡掉。
 */
export function offlineReportGate(
  report: OfflineReportView, items: readonly OfflineItem[], serverNow: number,
  shownFingerprint: string | null,
): OfflineGate {
  if (report.previousLoginAt === null) {
    return { show: false, reason: '新号没有「上一次登录」，这份汇总没有起点', fingerprint: null }
  }
  const idleMinutes = Math.floor((serverNow - report.previousLoginAt) / 60_000)
  if (idleMinutes < report.minIdleMinutes) {
    return {
      show: false,
      reason: `距上次登录只有 ${idleMinutes} 分钟（不足 ${report.minIdleMinutes} 分钟）`,
      fingerprint: null,
    }
  }
  if (items.length < report.minItems) {
    return {
      show: false,
      reason: `只有 ${items.length} 条可说的（至少要 ${report.minItems} 条）`,
      fingerprint: null,
    }
  }
  const fingerprint = fingerprintOf(items)
  if (fingerprint === shownFingerprint) {
    return { show: false, reason: '这一批明细已经给他看过了', fingerprint: null }
  }
  return { show: true, reason: null, fingerprint }
}

