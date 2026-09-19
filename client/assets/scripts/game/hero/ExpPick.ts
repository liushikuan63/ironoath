/**
 * 职责：武将升级"喂经验道具"的选择状态（V03-d，口径由用户 2026-09-19 裁决：**逐件选数量**）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>候选从服务端给的行里筛</b>：`effectKind === 'GRANT_HERO_EXP'` —— 这是契约里那一列存在的唯一理由
 * （三本经验书的 `type` 都是 MATERIAL，与另外 11 种材料同型）。**不硬编码 itemId**。
 *
 * <p><b>默认全 0，不替玩家选</b>：裁决是"逐件选数量"，那"选多少"就得由玩家按出来；
 * 客户端替他定一个默认值属于把决策权拿走（而且溢出会白白亏道具，见服务端是否卡上限 —— 那是它的事）。
 *
 * <p><b>全 0 不给发</b>：`canSend=false` 时视图把确认键置灰 —— 发一个注定被拒的请求（服务端要求 expItems 非空）
 * 只会把一次失误变成一条错误提示。
 */

import type { BagItem } from '../../net/generated/BagProtocol'
import type { ItemCount } from '../../net/generated/HeroProtocol'

/** 经验道具的判别值（契约 `BagItem.effectKind` 的原值，与 `ItemCfg.EffectKind.GRANT_HERO_EXP` 同源）。 */
export const HERO_EXP_KIND = 'GRANT_HERO_EXP'

export interface ExpPickRow {
  readonly itemId: string
  readonly name: string
  /** 持有数（来自背包行，不重算） */
  readonly held: number
  /** 已选数量：恒在 [0, held] */
  readonly picked: number
}

export interface ExpPickView {
  readonly rows: readonly ExpPickRow[]
  /** 已选总本数（各行的 picked 之和） */
  readonly totalPicked: number
  /** 全 0 时为 false：视图据此把确认键置灰 */
  readonly canSend: boolean
  /** 一件经验道具都没有时的说明行；有候选时为 null */
  readonly emptyText: string | null
}

/** 筛出可喂的经验道具（`effectKind` 说了算）。顺序照抄背包行（服务端已按 sortKey 排好）。 */
export function expPickRows(items: readonly BagItem[],
  picks: Readonly<Record<string, number>> = {}): readonly ExpPickRow[] {
  return items
    .filter((item) => item.effectKind === HERO_EXP_KIND)
    .map((item) => ({
      itemId: item.itemId,
      name: item.name,
      held: item.count,
      picked: clamp(picks[item.itemId] ?? 0, item.count),
    }))
}

/** 夹取到 [0, held] 的整数：越界不报错，直接夹（视图上的 +/- 只会一步越界一次）。 */
function clamp(value: number, held: number): number {
  if (!Number.isFinite(value)) {
    return 0
  }
  const whole = Math.trunc(value)
  return Math.min(Math.max(whole, 0), held)
}

/**
 * 加减一件。**夹取而不是报错**：按到 0 再按一下、按到持有数再按一下，都是玩家的正常操作。
 * 返回新的 picks 记录（不改原对象）。
 */
export function bumpPick(rows: readonly ExpPickRow[], itemId: string,
  delta: number): Record<string, number> {
  const next: Record<string, number> = {}
  for (const row of rows) {
    const picked = row.itemId === itemId ? clamp(row.picked + delta, row.held) : row.picked
    next[row.itemId] = picked
  }
  return next
}

/** 组装发给服务端的 `expItems`：**只带选了的**（0 的项不上报 —— 服务端要的是"喂什么"，不是"不喂什么"）。 */
export function pickedPayload(rows: readonly ExpPickRow[]): readonly ItemCount[] {
  return rows
    .filter((row) => row.picked > 0)
    .map((row) => ({ itemId: row.itemId, count: row.picked }))
}

/** 组装整个弹层的视图。 */
export function buildExpPick(items: readonly BagItem[],
  picks: Readonly<Record<string, number>> = {}): ExpPickView {
  const rows = expPickRows(items, picks)
  const totalPicked = rows.reduce((sum, row) => sum + row.picked, 0)
  return {
    rows,
    totalPicked,
    canSend: totalPicked > 0,
    // 只说事实，不编来源：三本经验书的 obtainFrom 都在表里（背包长按看得到），
    // 弹层再抄一遍就是第二份会过期的文案
    emptyText: rows.length === 0 ? '还没有经验道具' : null,
  }
}
