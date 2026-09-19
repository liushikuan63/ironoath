/**
 * 职责：商店面板的展示数据组装（B24 S-b：四个币种页签 + 货架行 + 兑换）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能兑换、还差几级、本期限购剩几个、余额够不够，
 * 全部由服务端算好下发（`purchasable` / `lockReason` / `remaining`）。
 * 客户端自己比的结果是"按钮亮着、点下去报错"，而它比不了的恰恰是「他今天买过几次」。
 *
 * <p><b>余额为 null 时不显示余额</b>：协议明写那表示"商店没有接这种货币的账本"。
 * 显示一个假的 0 会让玩家以为"我有 0 个赛季币"，而真实情况是"这一页还没有出处"。
 *
 * <p><b>限购文案必须带周期</b>：`NONE / DAILY / WEEKLY / SEASON` 说的是"什么时候能再买一次"，
 * 而玩家看到"还能买 0 个"时最想知道的就是"什么时候恢复"——只写数字等于让他去问客服。
 */

import type { ShopCurrency, ShopListResp, ShopRowView } from '../../net/generated/ShopProtocol'

/** 页签。标签是**玩家可见文案**（币种名），不是协议里的枚举名。 */
export interface ShopTab {
  readonly currency: ShopCurrency
  readonly label: string
}

/** 四个币种页签，顺序即展示顺序（与 `shop.json` 的四个币种一一对应）。 */
export const SHOP_TABS: readonly ShopTab[] = [
  { currency: 'GOLD', label: '金币' },
  { currency: 'ALLIANCE_COIN', label: '贡献' },
  { currency: 'SQUAD_COIN', label: '小队币' },
  { currency: 'SEASON_COIN', label: '赛季币' },
]

/** 币种名（用于「100 赛季币」这类文案）。表里没有的取值退回枚举名，绝不静默显示成空白。 */
export function currencyLabel(currency: ShopCurrency): string {
  return SHOP_TABS.find((tab) => tab.currency === currency)?.label ?? currency
}

/** 限购周期名（「今日」/「本周」/「本赛季」/「永久」）。 */
function refreshLabel(refreshType: ShopRowView['refreshType']): string {
  switch (refreshType) {
    case 'DAILY':
      return '今日'
    case 'WEEKLY':
      return '本周'
    case 'SEASON':
      return '本赛季'
    default:
      return '永久'
  }
}

/** 货架上的一行。 */
export interface ShopRow {
  readonly rowId: string
  readonly name: string
  /** 「100 赛季币」 */
  readonly priceText: string
  /** 「本赛季限 1，已买 0」 */
  readonly limitText: string
  /** 不能买的原因（服务端原话）；能买时为 null */
  readonly lockReason: string | null
  readonly purchasable: boolean
}

/** 整块商店视图。 */
export interface ShopPanelView {
  readonly tabs: readonly ShopTab[]
  /** 当前页签（用于高亮）。 */
  readonly currency: ShopCurrency
  /** 余额文案；服务端没给余额（null）时为 null —— 那种情况下一律不显示余额 */
  readonly balanceText: string | null
  /** 这一页没开时的那句话；开着为 null */
  readonly noticeText: string | null
  readonly open: boolean
  readonly rows: readonly ShopRow[]
}

/**
 * 组装商店面板。
 *
 * @param resp      GET /shop/list 的响应
 * @param currency  当前选中的页签（请求参数的回显；响应里的 `currency` 才是权威，
 *                  两者不一致时以响应为准 —— 那是"切页签时旧响应晚到"的形状）
 */
export function buildShopPanel(resp: ShopListResp, currency: ShopCurrency): ShopPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  return {
    tabs: SHOP_TABS,
    currency: resp.currency ?? currency,
    balanceText: resp.balance === null || resp.balance === undefined
      ? null
      : `${resp.balance} ${currencyLabel(resp.currency)}`,
    noticeText: resp.open ? null : (resp.notice ?? '这一页暂时还不能兑换'),
    open: resp.open,
    rows: resp.rows.map((row) => buildShopRow(row)),
  }
}

/** 组装一行。`name` 与 `price` 都来自服务端下发的那一行，客户端不查表、不拼名字。 */
export function buildShopRow(row: ShopRowView): ShopRow {
  return {
    rowId: row.rowId,
    name: row.name,
    priceText: `${row.price} ${currencyLabel(row.currency)}`,
    limitText: `${refreshLabel(row.refreshType)}限 ${row.limitCount}，已买 ${row.used}`,
    lockReason: row.lockReason,
    // 服务端说不能买时**必须**有原因；真漏了也退回一句人话，而不是一个没有解释的灰按钮
    purchasable: row.purchasable,
  }
}

/** 不能买时给人看的那句话（服务端原话优先；它漏了也要有个说法）。 */
export function shopRowStateText(row: ShopRow): string {
  if (row.purchasable) {
    return '可兑换'
  }
  return row.lockReason ?? '现在还不能兑换'
}

/**
 * 一行能不能点。**只信服务端那一位**：本模块不重新判等级/限购/余额。
 */
export function canBuy(row: ShopRow): boolean {
  return row.purchasable
}

/** 兑换请求的业务字段（requestId 由传输层注入，与其它 `mutate` 调用一致）。 */
export interface ShopBuyBody {
  readonly currency: ShopCurrency
  readonly rowId: string
  readonly count: number
}

/**
 * 组装一次兑换。
 *
 * <p><b>一次点一个</b>：数量选择器属编辑器资产（与军队面板的 ×1/×100 快捷档同一条边界），
 * 所以这里固定 1 —— 而 `count` 仍在协议里，将来加了输入控件只需改这一个函数的参数。
 *
 * <p>不能买时**不发请求**：返回 null 让调用方先把原因说给玩家听。
 */
export function buyBodyOf(view: ShopPanelView, rowId: string): ShopBuyBody | null {
  const row = view.rows.find((candidate) => candidate.rowId === rowId)
  if (row === undefined) {
    return null
  }
  if (!canBuy(row)) {
    return null
  }
  return { currency: view.currency, rowId: row.rowId, count: 1 }
}

/** 一次兑换之后给玩家的那句话。花费取**服务端回执**里的数（本地那份表可能已经过期）。 */
export function buyResultText(itemName: string, spent: number): string {
  return `已兑换 ${itemName}，花费 ${spent}`
}
