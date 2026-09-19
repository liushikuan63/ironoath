/**
 * 职责：战令面板的展示数据组装（B24 块②，S-d-e）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：这一档达没达成、这条线领没领、付费线解锁了没有，
 * 全部由服务端下发（`reached` / `freeClaimed` / `paidClaimed` / `paidUnlocked`）。
 * 客户端自己比"积分够不够"的结果是按钮亮着、点下去报错，而它比不了的正是"这一档我领过没有"。
 *
 * <p><b>一档两行、两条线各点各的</b>：免费线与付费线是同一档上的两份奖励 ——
 * 合成一个「领取」会造出"点了之后到底领了哪一份"的含糊，而玩家想知道的恰恰是那个。
 *
 * <p><b>窗口而不是全部 20 档</b>：一屏画不下 20 档（实测可视高度约 540px），
 * 而战令真正要玩家看的是"我接下来能领哪一档"。所以视图从一个**起点**开始画：
 * 起点优先落在"第一条还没领完的已达成档位"，全领完了就落在"下一个没达成的档位"。
 * 被跳过的档位由表头那句「第 a–b 档 / 共 N 档」交代，绝不静默少画。
 */

import type {
  BattlePassRewardView, BattlePassStatusResp, BattlePassTierView, BattlePassTrack,
} from '../../net/generated/BattlePassProtocol'

/** 一行 = 一档（两条线各一份奖励）。 */
export interface BattlePassRow {
  readonly tier: number
  readonly requiredPoints: number
  readonly reached: boolean
  /** 「第 3 档 · 450 分」 */
  readonly tierText: string
  /** 未达成时是「还差 150 分」；已达成是「已达成」 */
  readonly reachedText: string
  readonly freeText: string
  readonly paidText: string
  readonly freeClaimed: boolean
  readonly paidClaimed: boolean
  /** 免费线能不能点（服务端的 reached 与 freeClaimed 一起决定） */
  readonly freeClaimable: boolean
  /** 付费线能不能点；付费线没解锁时是 false（原因见 `paidHint`） */
  readonly paidClaimable: boolean
}

/** 整块战令视图。 */
export interface BattlePassPanelView {
  /** 「第 1–6 档 / 共 20 档」 */
  readonly rangeText: string
  /** 「本赛季积分 300 / 3000」 */
  readonly pointsText: string
  /** 「已领 4 / 40 份」（两条线合起来数） */
  readonly claimedText: string
  /** 剩余时间那行：「本赛季还剩 12 天」；赛季未启用时给一句说明 */
  readonly remainText: string
  readonly paidUnlocked: boolean
  /** 付费线没解锁时那一句提示；解锁了为 null */
  readonly paidHint: string | null
  readonly rows: readonly BattlePassRow[]
  /** 这一批里被跳过的档位数（>0 时表头已经交代了范围，视图不再重复说） */
  readonly windowStart: number
  /** 列表还没拉回来时那一句；拉到了为 null */
  readonly noticeText: string | null
}

/** 一档里"还没领的部分"还有几份（0/1/2）—— 用来找窗口起点。 */
function pendingCount(tier: BattlePassTierView, paidUnlocked: boolean): number {
  let pending = tier.freeClaimed ? 0 : 1
  if (paidUnlocked && !tier.paidClaimed) {
    pending += 1
  }
  return pending
}

/**
 * 窗口起点：第一条"已达成但还没领完"的档位；都领完了就是第一条未达成的档位；
 * 全部达成且领完则回到第一档（此时窗口里都是已领的，表头会说清范围）。
 */
export function windowStartOf(resp: BattlePassStatusResp): number {
  const tiers = resp.tiers
  for (let i = 0; i < tiers.length; i += 1) {
    const tier = tiers[i]!
    if (tier.reached && pendingCount(tier, resp.paidUnlocked) > 0) {
      return i
    }
  }
  for (let i = 0; i < tiers.length; i += 1) {
    if (!tiers[i]!.reached) {
      return i
    }
  }
  return 0
}

function rewardText(reward: BattlePassRewardView): string {
  return `${reward.name} ×${reward.count}`
}

export function buildBattlePassRow(tier: BattlePassTierView, paidUnlocked: boolean): BattlePassRow {
  return {
    tier: tier.tier,
    requiredPoints: tier.requiredPoints,
    reached: tier.reached,
    tierText: `第 ${tier.tier} 档 · ${tier.requiredPoints} 分`,
    reachedText: tier.reached ? '已达成' : `还差 ${tier.requiredPoints} 分`,
    freeText: rewardText(tier.freeReward),
    paidText: rewardText(tier.paidReward),
    freeClaimed: tier.freeClaimed,
    paidClaimed: tier.paidClaimed,
    freeClaimable: tier.reached && !tier.freeClaimed,
    // 付费线的两个前提都要满足：达成 + 解锁。少判一个都会给出一颗点了报错的按钮
    paidClaimable: tier.reached && paidUnlocked && !tier.paidClaimed,
  }
}

/** 剩余时间：用服务端时刻相减（铁律 5），绝不用本地时钟。 */
export function remainTextOf(resp: BattlePassStatusResp): string {
  if (resp.seasonEndAt <= 0) {
    return '赛季尚未启用，战令进度从赛季开启当天开始'
  }
  const remainMs = resp.seasonEndAt - resp.serverNow
  if (remainMs <= 0) {
    return '本赛季已结束，未领的档位会随结算发进邮箱'
  }
  const days = Math.floor(remainMs / 86_400_000)
  if (days >= 1) {
    return `本赛季还剩 ${days} 天`
  }
  const hours = Math.max(1, Math.floor(remainMs / 3_600_000))
  return `本赛季还剩 ${hours} 小时`
}

/**
 * 组装整块战令视图。
 *
 * @param resp  GET /battlePass/status 的响应；null = 还没拉回来（画一句说明，不画一个假进度）
 * @param maxRows 一屏画几行（由视图按实测可视高度算好传进来：写死会在矮窗口里压到导航条下面）
 */
export function buildBattlePassPanel(resp: BattlePassStatusResp | null,
  maxRows: number): BattlePassPanelView {
  if (resp === null || resp === undefined) {
    return {
      rangeText: '', pointsText: '', claimedText: '', remainText: '', paidUnlocked: false,
      paidHint: null, rows: [], windowStart: 0, noticeText: '战令进度还没拉回来，稍后再试',
    }
  }
  const start = windowStartOf(resp)
  const window = resp.tiers.slice(start, start + Math.max(1, maxRows))
  const last = window.length === 0 ? start : start + window.length - 1
  const total = resp.tiers.length
  // 分母是**整条梯子的总目标**（最后一档的分数），不是"窗口里最后一档"——
  // 后者会随窗口滑动而变，于是同一份进度在不同窗口下读出不同的分母，像一根会自己变的进度条
  const ranks = total === 0 ? 0 : resp.tiers[total - 1]!.requiredPoints
  const claimedUnits = resp.tiers.reduce((sum, tier) => {
    let count = tier.freeClaimed ? 1 : 0
    if (tier.paidClaimed) {
      count += 1
    }
    return sum + count
  }, 0)
  const totalUnits = total * (resp.paidUnlocked ? 2 : 1)
  return {
    rangeText: total === 0 ? '' : `第 ${start + 1}–${last + 1} 档 / 共 ${total} 档`,
    pointsText: `本赛季积分 ${resp.points} / ${ranks}`,
    claimedText: `已领 ${claimedUnits} / ${totalUnits} 份`,
    remainText: remainTextOf(resp),
    paidUnlocked: resp.paidUnlocked,
    // 没买时把"付费线为什么是灰的"说出来：一颗没有解释的灰按钮会被当成坏了
    paidHint: resp.paidUnlocked ? null : '付费线还没解锁 —— 购买本赛季战令后即可领取，免费线现在就能领',
    rows: window.map((tier) => buildBattlePassRow(tier, resp.paidUnlocked)),
    windowStart: start,
    noticeText: null,
  }
}

/** 领取请求的业务字段（requestId 由传输层注入，与其它 `mutate` 调用一致）。 */
export interface BattlePassClaimBody {
  readonly tier: number
  readonly track: BattlePassTrack
}

/**
 * 一行上某条线的领取请求。
 *
 * <p><b>不能领时返回 null、调用方不发请求</b>：服务端已经把原因算好了（没达成 / 没买 / 已领），
 * 发一次注定被拒的请求只会换来一句报错，而玩家看到的应该是"为什么点不动"。
 */
export function claimBodyOf(row: BattlePassRow, track: BattlePassTrack): BattlePassClaimBody | null {
  const claimable = track === 'PAID' ? row.paidClaimable : row.freeClaimable
  if (!claimable) {
    return null
  }
  return { tier: row.tier, track }
}

/** 一行上某条线现在的状态文案。 */
export function trackStateText(row: BattlePassRow, track: BattlePassTrack): string {
  const claimed = track === 'PAID' ? row.paidClaimed : row.freeClaimed
  const claimable = track === 'PAID' ? row.paidClaimable : row.freeClaimable
  if (claimed) {
    return '已领取'
  }
  return claimable ? '可领取' : '不可领取'
}

/** 领到之后给玩家的那句话（用服务端回执里的名字与份数，不用本地那份可能过期的表）。 */
export function claimResultText(reward: BattlePassRewardView): string {
  return `已领取 ${reward.name} ×${reward.count}`
}
