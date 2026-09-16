/**
 * 职责：活动面板的展示数据组装（B17 §一/§六）。引擎无关，可脱离 Cocos 跑单测（B00 铁律 2）。
 * 依赖：生成的协议类型（`ActivityProtocol`）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能领（`state === 'CLAIMABLE'`）、达没达标、
 * 窗口过没过，全部原样取自服务端。客户端**不自己算**「进度 ≥ 目标」—— 那条规则在服务端有一条
 * 同名的判定，两边各算一遍的结局是「按钮亮着点下去被拒」，而这种分叉只在改表或跨轮那一刻暴露。
 *
 * <p><b>剩余时间在这里算，但时钟来自服务端</b>：协议下发的是 `windowEndAt` 与 `serverNow`
 * （不是「还剩几秒」），差值由本模块算成文案 —— 铁律 5 要求结算用服务端时间，
 * 而"还剩多久"是展示量，用服务端下发的两个时刻相减就与本地时钟无关。
 *
 * <p><b>三种状态各有各的文案，包括 EXPIRED</b>：上一轮有进展但没领的行会被服务端标成 EXPIRED
 * （见 `ActivityProgress.syncOnRead` 的注释），界面上要看得见「上一轮结束了」，
 * 而不是让一行灰着的按钮自己解释自己。
 */

import type { ActivityClaimResp, ActivityListResp, ActivityView } from '../../net/generated/ActivityProtocol'

/** 活动列表的一行。 */
export interface ActivityRow {
  readonly activityId: string
  /** 活动名（表里的 name，服务端下发）。客户端不翻译、不拼接分类 */
  readonly name: string
  /** 进度文本：「30/50」；模板与任务面板同一条口径 */
  readonly progressText: string
  /** 剩余时间文本：「剩余 2 天 3 小时」/「已结束」；常驻活动为「长期开放」 */
  readonly remainingText: string
  /** 状态文本：进行中 / 可领取 / 上一轮已结束（EXPIRED）/ 已领取 */
  readonly statusText: string
  /** 能不能点「领取」。等于服务端的 `state === 'CLAIMABLE'`，不自己算 */
  readonly claimable: boolean
}

/** 整个活动页的数据。 */
export interface ActivityListView {
  readonly rows: readonly ActivityRow[]
  /** 「N 个奖励可领取」；没有可领时为 null（不显示一个 0） */
  readonly claimableText: string | null
}

/** 服务端的三态（契约里的 ActivityState，客户端只显示不判断）。 */
const STATE_RUNNING = 'RUNNING'
const STATE_CLAIMABLE = 'CLAIMABLE'
const STATE_EXPIRED = 'EXPIRED'

/**
 * 把一次 `/activity/list` 响应组装成界面要的行。
 *
 * @param nowMs 服务端当前时刻。**用响应里的 `serverNow`**，不要用本地时钟（铁律 5）
 */
export function buildActivityList(resp: ActivityListResp, nowMs: number): ActivityListView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  if (!Number.isFinite(nowMs)) {
    throw new Error(`nowMs 必须是服务端时刻，实际=${nowMs}`)
  }
  const rows = resp.activities.map(view => toRow(view, nowMs))
  return {
    rows,
    claimableText: resp.claimableCount > 0 ? `${resp.claimableCount} 个奖励可领取` : null,
  }
}

/** 领取请求体。`requestId` 由 transport 层补（与任务/邮件同一条：客户端不生成幂等键）。 */
export function claimActivityReq(activityId: string): { activityId: string } {
  if (activityId === '') {
    throw new Error('activityId 不得为空：领哪个活动是服务端状态，客户端只回传 id')
  }
  return { activityId }
}

/**
 * 领奖回执文案：「已领取：金币 ×500、一小时训练令 ×2」。
 *
 * <p>用服务端回来的 `rewards`（入账量）而不是活动表里的奖励配置：背包满时一部分会走邮件兜底，
 * 而玩家想知道的是"我刚才实际拿到了什么"。**没有奖励时返回空串** —— 界面据此不显示这一行，
 * 而不是显示一个断句的「已领取：」。
 */
export function claimReceiptText(resp: ActivityClaimResp): string {
  if (resp === undefined || resp === null) {
    return ''
  }
  const parts = resp.rewards.map(item => `${item.name} ×${formatCount(item.count)}`)
  return parts.length === 0 ? '' : `已领取：${parts.join('、')}`
}

function toRow(view: ActivityView, nowMs: number): ActivityRow {
  return {
    activityId: view.id,
    name: view.name,
    progressText: `${formatCount(view.progress)}/${formatCount(view.goal)}`,
    remainingText: remainingText(view.windowEndAt, nowMs),
    statusText: statusText(view),
    claimable: view.state === STATE_CLAIMABLE,
  }
}

/**
 * 状态文案。四句话对应用户能做的四件事：
 * 继续做 / 去领奖 / 这一轮错过了 / 这轮已经领过。
 */
function statusText(view: ActivityView): string {
  if (view.state === STATE_EXPIRED) {
    return '上一轮已结束'
  }
  if (view.claimed) {
    return '本轮已领取'
  }
  if (view.state === STATE_CLAIMABLE) {
    return '可领取'
  }
  if (view.state === STATE_RUNNING) {
    return '进行中'
  }
  // 服务端加了新状态而这里没跟上：把原文显示出来而不是假装它是"进行中" ——
  // 一个看不懂的状态至少能让问题被报上来，而错译会让它永远查不出
  return view.state
}

/**
 * 剩余时间。按自然粒度递减：还剩两天时精确到分钟没有意义，而"0 秒"比"已结束"更难懂。
 *
 * @param windowEndAt 窗口结束时刻；null 表示常驻活动（时长 ≤ 0 的行），没有终点
 */
function remainingText(windowEndAt: number | null, nowMs: number): string {
  if (windowEndAt === null) {
    return '长期开放'
  }
  if (windowEndAt <= nowMs) {
    return '已结束'
  }
  const seconds = Math.floor((windowEndAt - nowMs) / 1000)
  const days = Math.floor(seconds / 86_400)
  if (days >= 1) {
    const hours = Math.floor((seconds % 86_400) / 3600)
    return hours > 0 ? `剩余 ${days} 天 ${hours} 小时` : `剩余 ${days} 天`
  }
  const hours = Math.floor(seconds / 3600)
  if (hours >= 1) {
    const minutes = Math.floor((seconds % 3600) / 60)
    return minutes > 0 ? `剩余 ${hours} 小时 ${minutes} 分` : `剩余 ${hours} 小时`
  }
  const minutes = Math.max(1, Math.floor(seconds / 60))
  return `剩余 ${minutes} 分`
}

/** 大数字加千分位：5 位数的进度（捐献 2000）在面板上读起来差别很大。 */
function formatCount(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
