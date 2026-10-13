/** 月卡展示仅使用服务器报价、已购快照、时间与领取资格。 */
import type { CardStatusResp, CardClaimResp, MonthlyCardView, MonthlyCardRewardView } from '../../net/generated/PayProtocol'

export interface MonthlyCardRow {
  readonly productId: string | null
  readonly name: string
  readonly priceText: string | null
  readonly offerText: string
  readonly statusText: string
  readonly expiryText: string
  readonly refreshText: string
  readonly claimable: boolean
  readonly purchasable: boolean
  readonly rewardNames: Readonly<Record<string, string>>
}
export interface MonthlyCardPanel {
  readonly rows: readonly MonthlyCardRow[]
  readonly noticeText: string | null
  readonly serverNow: number
}

function namedRewards(items: readonly MonthlyCardRewardView[]): string {
  return items.map(item => `${item.name?.trim() || '奖励'} ×${item.count}`).join('、')
}
/** 展示服务器时刻；不以本地时钟判生效或领取资格。 */
function beijingTime(ms: number | null | undefined): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '时间待确认'
  const date = new Date(ms + 8 * 3_600_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`
}
function buildRow(card: MonthlyCardView): MonthlyCardRow {
  const daily = Array.isArray(card.dailyRewards) ? card.dailyRewards : []
  const owned = Array.isArray(card.ownedDailyRewards) ? card.ownedDailyRewards : []
  const priceValid = Number.isSafeInteger(card.priceCents) && card.priceCents > 0
  const price = priceValid ? `${(card.priceCents / 100).toFixed(2).replace(/\.00$/, '')}元 / ${card.durationDays}天` : null
  const offers = [card.initialGold > 0 ? `购买赠${card.initialGold}金币` : null, daily.length > 0 ? `每日：${namedRewards(daily)}` : null]
  if (card.offerAdFree) offers.push('免广告播放，不增加每日次数')
  if (card.offerEarlyCollectAll) offers.push('四种手动教学后可提前一键收割')
  if (card.offerBonusQueues > 0) offers.push(`额外建造队列 ${card.offerBonusQueues}`)
  const status: string[] = []
  if (!card.saleable) status.push('已购权益保留')
  status.push(card.active ? (card.claimableDays > 0 ? `可领取${card.claimableDays}天日包` : '当前日包已结清') : '当前尚未生效或已到期')
  if (owned.length > 0 && namedRewards(owned) !== namedRewards(daily)) status.push(`已购日包：${namedRewards(owned)}`)
  if (card.adFree) status.push('免广告生效')
  if (card.earlyCollectAll) status.push('提前收割生效，仍需完成四种手动教学')
  const rewardNames: Record<string,string> = {}
  for (const item of [...daily, ...owned]) rewardNames[`${item.type}:${item.id}`] = item.name?.trim() || '奖励'
  return { productId: card.productId, name: card.name || '月卡', priceText: price,
    offerText: offers.filter((part): part is string => part !== null).join('；'),
    statusText: status.join('；'), expiryText: card.expireAt === null ? '尚未购买' : `到期：${beijingTime(card.expireAt)}（北京时间）`,
    refreshText: `刷新：${beijingTime(card.nextRefreshAt)}（北京时间）`,
    claimable: card.active && card.claimableDays > 0,
    purchasable: card.saleable && priceValid && typeof card.productId === 'string' && card.productId.length > 0,
    rewardNames }
}
export function buildMonthlyCardPanel(resp: CardStatusResp): MonthlyCardPanel {
  if (Array.isArray(resp.cards)) return { rows: resp.cards.map(buildRow), noticeText: null, serverNow: resp.serverNow }
  // 旧响应没有商品列表；只允许原旧卡领取，不发明价格、商品或两新卡权益。
  const rows: MonthlyCardRow[] = resp.active ? [{ productId: null, name: '已购月卡', priceText: null,
    offerText: '日包由服务器确认', statusText: resp.claimableDays > 0 ? `已购权益保留，可领取${resp.claimableDays}天日包` : '当前日包已结清',
    expiryText: `到期：${beijingTime(resp.expireAt)}（北京时间）`,refreshText: '旧卡沿用原刷新规则',claimable:resp.claimableDays > 0,purchasable:false,rewardNames:{} }] : []
  return { rows, noticeText: rows.length > 0 ? null : '当前服务暂未提供月卡列表', serverNow: resp.serverNow }
}
export function monthlyClaimBody(row: MonthlyCardRow): { productId: string | null } | null {
  return row.claimable ? { productId: row.productId } : null
}
export function monthlyPurchaseId(row: MonthlyCardRow): string | null { return row.purchasable ? row.productId : null }
export function monthlyClaimResultText(resp: CardClaimResp, row: MonthlyCardRow): string {
  const items = resp.rewards.map(item => `${row.rewardNames[`${item.type}:${item.id}`] || '奖励'} ×${item.count}`)
  return `已结算${resp.claimedDays}天日包${items.length > 0 ? `，到账：${items.join('、')}` : '，请查看资源、背包和邮件'}`
}
