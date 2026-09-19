/**
 * 职责：抽卡面板（抽卡入口）的行组装与"能不能抽"判定 —— 池页签、单抽/十抽、限购与余额。
 * 依赖：生成的协议类型 + `game/ui/ResourceNames` 那份资源名真源（引擎无关，可脱离 Cocos 跑单测）。
 *
 * <p><b>客户端手里没有 gacha 表</b>：有哪些池、叫什么、抽一次花什么、这个号在该池抽过几次，
 * 全部来自 `/gacha/pools`（B06 §6 禁止把概率与池子写死在客户端）。本文件只做"下发的数 → 展示文本"，
 * 唯一的算术是 `costCount × 10`，而服务端 `doDraw` 收钱用的正是同一个式子。
 *
 * <p><b>抽满过的池要看得出来</b>：新手池终身限抽 1 次，而"已抽几次"只在服务端的池状态里。
 * 不下发这一项的话，界面会给一个抽满过的玩家留一个照常能点的页签，点下去吃一条 RATE_LIMITED ——
 * 那是把"已经用掉了"这件事表达不出来，玩家只会以为抽卡坏了。
 *
 * <p><b>这里不显示保底进度</b>：玩家的**当前**保底计数没有任何读口（`/gacha/probability` 给的是规则，
 * `GachaDrawResp` 给的是抽完之后的计数）。规则可以写，进度不能猜 —— 要做得先把计数并进池列表响应。
 */

import type { BagItem, ResourceDetail } from '../../net/generated/BagProtocol'
import type { GachaDrawResp, GachaPoolSummary } from '../../net/generated/HeroProtocol'
import { resourceName } from '../ui/ResourceNames'

/** 十抽的倍数。服务端按 `costCount × count` 收钱，count 只允许 1 或 10（B06 §2）。 */
export const TEN_DRAW_COUNT = 10

export interface GachaBalances {
  /** `ResourceDetailResp.resources`：按资源计价的池拿它比余额 */
  readonly resources: readonly ResourceDetail[]
  /** `BagListResp.items`：按道具计价的池拿它比余额 */
  readonly items: readonly BagItem[]
}

export interface GachaPoolRow {
  readonly poolId: string
  /** 池中文名（服务端 gacha 表的 name 列） */
  readonly name: string
  /** 单抽那一行的消耗文本，如 `150 金币` / `1 个 招募宝箱` */
  readonly costText: string
  /** 限购那一行，如 `终身限抽 1 次 · 还剩 1 次`；不限次时写 `不限次` */
  readonly limitText: string
  /** 该号在该池已抽满上限 */
  readonly exhausted: boolean
  /** 能不能抽单抽 / 十抽（键灰不灰由它定，成败仍由服务端裁） */
  readonly canDrawOnce: boolean
  readonly canDrawTen: boolean
  /**
   * 两个键各带一条理由（能抽时为 null）。
   *
   * <p><b>为什么不共用一条</b>：「单抽够、十抽不够」是最常见的那一屏 —— 共用一条就会把
   * 一个能点的键写成灰的，或者反过来把灰的键写成能点。
   */
  readonly onceReason: string | null
  readonly tenReason: string | null
}

export interface GachaPanelView {
  readonly rows: readonly GachaPoolRow[]
  /** 选中的那个池。读不到（首次进面板）时为 null，由调用方取第一行 */
  readonly selectedPoolId: string | null
  readonly selected: GachaPoolRow | null
  /** 选中池那一档计价的当前余额，如 `金币 1200`；读不到时为 null */
  readonly balanceText: string | null
  readonly singleText: string
  readonly tenText: string
  readonly notice: string | null
  /** 最近一次抽取的结果行（'李劲 · 新武将'）；还没抽过时为 null */
  readonly resultTexts: readonly string[] | null
}

/**
 * 一次抽取的结果行。**「新武将 / 转碎片」这一句是这一屏的全部意义**：
 * 抽到重复的会转成碎片（B06 §1），不写出来的话玩家只会以为"抽了个没用的"，
 * 而碎片确实进包了 —— 那句解释是防"抽卡是不是骗人"的投诉。
 */
export function drawResultTexts(draw: GachaDrawResp | null): readonly string[] | null {
  if (draw === null) {
    return null
  }
  return draw.results.map((result) => {
    const base = result.isNew ? `${result.name} · 新武将` : `${result.name} · 转 ${result.fragments} 碎片`
    return result.isPity ? `${base}（保底）` : base
  })
}

/** 单抽消耗数（十抽由它乘 10，与服务端同一式子）。 */
function unitCost(pool: GachaPoolSummary): number {
  return pool.costCount
}

/** 该档计价的余额：道具优先（限定池用宝箱），否则按资源。读不到返回 null，不当 0 用。 */
function balanceOf(pool: GachaPoolSummary, balances: GachaBalances): number | null {
  if (pool.costItemId !== null && pool.costItemId !== undefined) {
    const item = balances.items.find((i) => i.itemId === pool.costItemId)
    return item === undefined ? 0 : item.count
  }
  const resource = balances.resources.find((r) => r.type === pool.costResource)
  // 背包/资源读得到就一定有这一行（生产上六档资源全列）；真读不到时宁可说"还没读到"，
  // 说 0 会把一个有钱的玩家灰掉按钮，那是把读侧故障伪装成玩家的错
  return resource === undefined ? null : resource.current
}

/** 消耗那一种计价的中文名。道具名随行下发（#255 一路），资源名走 ResourceNames 那一份真源。 */
function costUnit(pool: GachaPoolSummary): string {
  if (pool.costItemId !== null && pool.costItemId !== undefined) {
    // 名字随行下发（costItemName）：客户端手里只有 item_chest_hero 这种行 id
    return pool.costItemName ?? pool.costItemId
  }
  return resourceName(String(pool.costResource))
}

export function gachaRows(pools: readonly GachaPoolSummary[],
  balances: GachaBalances): readonly GachaPoolRow[] {
  return (pools ?? []).map((pool) => {
    const cost = unitCost(pool)
    const balance = balanceOf(pool, balances)
    const exhausted = pool.lifetimeLimit > 0 && pool.lifetimeDraws >= pool.lifetimeLimit
    const unit = costUnit(pool)
    // 一条理由函数两用：先说"抽满了"，再说"读不到"，最后才说差几 —— 顺序反了会把
    // 一个已经用掉的池说成"还差 150 金币"，玩家会去挣一笔根本不需要挣的钱
    const reason = (need: number): string | null => {
      if (exhausted) {
        return '这个号在该池已抽满'
      }
      if (balance === null) {
        return '余额还没读到'
      }
      return balance >= need ? null : `还差 ${need - balance} ${unit}`
    }
    const onceReason = reason(cost)
    const tenReason = reason(cost * TEN_DRAW_COUNT)
    return {
      poolId: pool.poolId,
      name: pool.name,
      costText: `${cost} ${unit}`,
      limitText: pool.lifetimeLimit > 0
        ? `终身限抽 ${pool.lifetimeLimit} 次 · 还剩 ${Math.max(0, pool.lifetimeLimit - pool.lifetimeDraws)} 次`
        : '不限次',
      exhausted,
      canDrawOnce: onceReason === null,
      canDrawTen: tenReason === null,
      onceReason,
      tenReason,
    }
  })
}

/** 组装整块面板视图。选中项只在**读得到的那些行**里成立。 */
export function buildGachaPanel(pools: readonly GachaPoolSummary[],
  balances: GachaBalances, selectedPoolId: string | null = null,
  notice: string | null = null, lastDraw: GachaDrawResp | null = null): GachaPanelView {
  const rows = gachaRows(pools, balances)
  const selectedRow = rows.find((r) => r.poolId === selectedPoolId) ?? rows[0] ?? null
  const pool = selectedRow === null ? null
    : (pools ?? []).find((p) => p.poolId === selectedRow.poolId) ?? null
  const balance = pool === null ? null : balanceOf(pool, balances)
  return {
    rows,
    selectedPoolId: selectedRow === null ? null : selectedRow.poolId,
    selected: selectedRow,
    balanceText: pool === null || balance === null
      ? null : `${balance} ${costUnit(pool)}`,
    singleText: selectedRow === null ? '先选一个卡池' : `抽一次 · ${selectedRow.costText}`,
    tenText: pool === null || selectedRow === null ? '先选一个卡池'
      : `抽十次 · ${unitCost(pool) * TEN_DRAW_COUNT} ${costUnit(pool)}`,
    notice,
    resultTexts: drawResultTexts(lastDraw),
  }
}
