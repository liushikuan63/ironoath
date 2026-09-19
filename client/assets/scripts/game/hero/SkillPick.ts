/**
 * 职责：武将技能「用哪本技能书升哪一路」的选择状态（V03-d 最后一条养成线）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>槽位不是让玩家选的，是书决定的</b>：`POST /hero/skillUp` 的 `skillSlot` 必须与那本书的
 * `effectTarget` 对上（`HeroAppService#growByItem` 里那句 `target != null && !target.equals(...)`
 * 会把猜错的拒掉）。两本书在 `type` 与 `effectKind` 两列下**完全同型**，所以契约补了
 * `BagItem.effectTarget` —— 没有这一列，客户端只能先选一个槽位再赌一本对得上的书。
 *
 * <p><b>与觉醒那一格的差别**：这里**没有抄服务端的规则**。满级判定 `level < maxSkillLevel` 的
 * 两个数都来自 `HeroView`（`maxSkillLevel` 就是服务端 `rules.skillMaxLevel()` 下发的那一份），
 * 客户端只做一次减法。觉醒那边不得不抄，是因为"哪一阶认哪块石"只写在 Java 里。
 *
 * <p><b>没标注的书不猜</b>：`effectTarget` 为空的书升哪一路无从判断，把行灰掉并说明，
 * 而不是发一个服务端可能接受、也可能拒的请求 —— 玩家看到"这本没标注主 / 副技能"就知道是配置的事。
 */

import type { BagItem } from '../../net/generated/BagProtocol'
import type { SkillSlot } from '../../net/generated/HeroProtocol'

/** 技能书的判别值（与 `ItemCfg.EffectKind.UP_HERO_SKILL` 同源）。 */
export const SKILL_UP_KIND = 'UP_HERO_SKILL'

/** 目标武将两路技能的当前等级与共同上限（三个数都来自 `HeroView`）。 */
export interface SkillStage {
  readonly mainLevel: number
  readonly subLevel: number
  readonly maxLevel: number
}

export interface SkillRow {
  readonly itemId: string
  readonly name: string
  /** 持有数（来自背包行，不重算） */
  readonly held: number
  /** 这本升哪一路；`effectTarget` 没配时为 null */
  readonly slot: SkillSlot | null
  /** 这一行升的是哪一路（画在行上）；slot 为 null 时是空串 */
  readonly slotText: string
  /** 「Lv3 → Lv4（上限 10）」那一行 */
  readonly levelText: string
  readonly usable: boolean
  /** 不能用时给玩家看的原因；能用时为 null */
  readonly reason: string | null
}

export interface SkillPickView {
  readonly rows: readonly SkillRow[]
  /** 选中的那本（只在可用的行里成立） */
  readonly selectedItemId: string | null
  /** 选中那本对应的槽位 —— 发请求要带，且只有这一处来源 */
  readonly selectedSlot: SkillSlot | null
  readonly canSend: boolean
  /** 确认键上的字：满级与"还没选"是两件事 */
  readonly sendText: string
  /** 一本技能书都没有时的说明行；有候选时为 null */
  readonly emptyText: string | null
}

const SLOT_NAMES: Record<SkillSlot, string> = { MAIN: '主技能', SUB: '副技能' }

/** 筛出技能书（`effectKind` 说了算），槽位取 `effectTarget`，满级取 `HeroView` 那两个数。 */
export function skillRows(items: readonly BagItem[], stage: SkillStage): readonly SkillRow[] {
  return items
    .filter((item) => item.effectKind === SKILL_UP_KIND)
    .map((item) => {
      const slot = item.effectTarget === 'MAIN' || item.effectTarget === 'SUB'
        ? item.effectTarget
        : null
      if (slot === null) {
        return {
          itemId: item.itemId, name: item.name, held: item.count,
          slot: null, slotText: '', levelText: '',
          usable: false, reason: '这本没标注主 / 副技能',
        }
      }
      const level = slot === 'MAIN' ? stage.mainLevel : stage.subLevel
      const maxed = level >= stage.maxLevel
      return {
        itemId: item.itemId, name: item.name, held: item.count,
        slot, slotText: SLOT_NAMES[slot],
        levelText: maxed
          ? `Lv${level}（上限 ${stage.maxLevel}，已满级）`
          : `Lv${level} → Lv${level + 1}（上限 ${stage.maxLevel}）`,
        usable: !maxed,
        reason: maxed ? `${SLOT_NAMES[slot]}已满级` : null,
      }
    })
}

/** 组装整个弹层的视图。 */
export function buildSkillPick(items: readonly BagItem[], stage: SkillStage,
  selectedItemId: string | null = null): SkillPickView {
  const rows = skillRows(items, stage)
  const chosen = rows.find((row) => row.itemId === selectedItemId && row.usable)
  return {
    rows,
    selectedItemId: chosen === undefined ? null : chosen.itemId,
    selectedSlot: chosen === undefined ? null : chosen.slot,
    canSend: chosen !== undefined,
    sendText: chosen !== undefined
      ? '确认升级'
      : rows.some((row) => row.usable) ? '先选技能书' : '没有可用技能书',
    // 去哪刷这本书由背包行的 obtainFrom 回答（长按看得到），弹层不抄第二份
    emptyText: rows.length === 0 ? '背包里没有技能书' : null,
  }
}
