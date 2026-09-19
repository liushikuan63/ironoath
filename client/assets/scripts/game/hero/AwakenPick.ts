/**
 * 职责：武将觉醒「用哪一块觉醒石」的选择状态（V03-d 第二条养成线）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>与升级那条线只差一处</b>：觉醒一次只吃**一件**道具（服务端 `growByItem` 里那句
 * `consumeItem(playerId, req.itemId(), 1L, ...)`），所以没有计数、只有单选。
 * 候选仍按 `effectKind === 'AWAKEN_HERO'` 筛，**不硬编码 itemId**。
 *
 * <p><b>两种觉醒石不是"随便挑一种"</b>：`HeroAppService#growByItem` 里那条
 * `finalTier != highTierStone` 判定（最后一阶只能用高阶石、其余阶只能用初阶石）意味着
 * 候选里**同一时刻至多一种可用**。把两块都点亮让玩家挨个试、每试错一次拿一条服务端拒绝，
 * 等于把一条配置规则摊给玩家猜，所以这里把不可用的那一档灰掉并写明原因。
 *
 * <p><b>这条镜像的代价与兜底</b>："高阶石 = 稀有度 SSR 那一块"在客户端抄了一遍，
 * 服务端仍是唯一裁判（这里只决定按钮灰不灰，不决定成败）。抄的东西会分叉，
 * 故 `tests/AwakenPick.test.ts` 里有一条直接读服务端源码、判这句镜像还对不对。
 */

import type { BagItem } from '../../net/generated/BagProtocol'

/** 觉醒石的判别值（契约 `BagItem.effectKind` 的原值，与 `ItemCfg.EffectKind.AWAKEN_HERO` 同源）。 */
export const AWAKEN_KIND = 'AWAKEN_HERO'

/** 高阶觉醒石的稀有度 —— 服务端那条 `highTierStone` 用的就是同一个取值。 */
export const FINAL_TIER_STONE_RARITY = 'SSR'

/** 目标武将当前的觉醒进度（两个数都来自 `HeroView`，客户端不自己推）。 */
export interface AwakenStage {
  readonly awaken: number
  readonly maxAwaken: number
}

export interface AwakenRow {
  readonly itemId: string
  readonly name: string
  /** 持有数（来自背包行，不重算） */
  readonly held: number
  /** 按当前这一阶能不能用 */
  readonly usable: boolean
  /** 不能用时给玩家看的原因；能用时为 null。满阶不在这儿说（那句写在 {@link AwakenPickView.stageText}） */
  readonly reason: string | null
}

export interface AwakenPickView {
  readonly rows: readonly AwakenRow[]
  /** 标题下面那一行进度，两块石共用（同一时刻只推一阶） */
  readonly stageText: string
  /** 选中的那块石。灰掉的行不算选中（点了也不会发出去） */
  readonly selectedItemId: string | null
  /** 没选中可用的石时为 false：视图据此把确认键置灰 */
  readonly canSend: boolean
  /** 确认键上的字：满阶与"还没选"是两件事，混成一句会把玩家引去点一块点不动的石 */
  readonly sendText: string
  /** 一块觉醒石都没有时的说明行；有候选时为 null */
  readonly emptyText: string | null
}

/** 筛出觉醒石（`effectKind` 说了算），并按当前这一阶标出哪块能用。顺序照抄背包行。 */
export function awakenRows(items: readonly BagItem[], stage: AwakenStage): readonly AwakenRow[] {
  const atMax = stage.awaken >= stage.maxAwaken
  // 与 HeroAppService#growByItem 同一个式子：下一阶恰好是最后一阶时只能用高阶石
  const finalTier = stage.awaken + 1 === stage.maxAwaken
  return items
    .filter((item) => item.effectKind === AWAKEN_KIND)
    .map((item) => {
      const highTierStone = item.rarity === FINAL_TIER_STONE_RARITY
      if (atMax) {
        // 原因写在进度那一行，不在每一块石上重复一遍（两块石都挂同一句是噪音，见台账 #272 那张截图）
        return { ...bare(item), usable: false, reason: null }
      }
      if (highTierStone === finalTier) {
        return { ...bare(item), usable: true, reason: null }
      }
      return {
        ...bare(item),
        usable: false,
        reason: finalTier ? '最后一阶要用高阶觉醒石' : '这一阶用初阶觉醒石',
      }
    })
}

function bare(item: BagItem): { itemId: string, name: string, held: number } {
  return { itemId: item.itemId, name: item.name, held: item.count }
}

/** 组装整个弹层的视图。选中项只在**可用**的行里成立。 */
export function buildAwakenPick(items: readonly BagItem[], stage: AwakenStage,
  selectedItemId: string | null = null): AwakenPickView {
  const rows = awakenRows(items, stage)
  const chosen = rows.find((row) => row.itemId === selectedItemId && row.usable)
  const atMax = stage.awaken >= stage.maxAwaken
  return {
    rows,
    stageText: atMax
      ? `已达觉醒上限 ${stage.maxAwaken} 阶`
      : `第 ${stage.awaken} 阶 → 第 ${stage.awaken + 1} 阶（共 ${stage.maxAwaken} 阶）`,
    selectedItemId: chosen === undefined ? null : chosen.itemId,
    canSend: chosen !== undefined,
    sendText: chosen !== undefined ? '确认觉醒' : atMax ? '已达觉醒上限' : '先选觉醒石',
    // 只说事实：哪来的石由背包行的 obtainFrom 回答（长按看得到），弹层再抄一遍就是第二份会过期的文案
    emptyText: rows.length === 0 ? '背包里没有觉醒石' : null,
  }
}
