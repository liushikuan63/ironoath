/**
 * 职责：编队编辑（`POST /hero/lineup`）的槽位与选将状态 —— 三槽（主将 + 2 副将）× 三套预设。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>客户端只判"服务端会判的那几条"</b>：`HeroRoster#setLineup` 的规则是
 * presetIndex 在范围内、副将数不超 `lineupSize-1`、每个非空位必须**已拥有**、**同一队内不许重复**。
 * 它**不判跨队重复** —— 一个武将可以同时在三套编队里。所以"已经在第 2 队"的那一行
 * 必须照常能点，只在行上标注；把它灰掉等于客户端凭空加一条服务端没有的规则，
 * 后果是玩家明明能保存却被界面挡住（而界面说不出为什么）。
 *
 * <p><b>空位是合法状态</b>：主将为 null 服务端接受（清空这一队）。所以「保存」在
 * 三槽全空时也是可发的 —— 那是"我要清空这一队"，不是"我什么都没选"。
 */

import type { HeroView, LineupView } from '../../net/generated/HeroProtocol'

/** 三个槽位。顺序即界面从上到下的顺序，`sub1`/`sub2` 与契约字段同名。 */
export type LineupSlot = 'main' | 'sub1' | 'sub2'

export const LINEUP_SLOTS: readonly LineupSlot[] = ['main', 'sub1', 'sub2']

const SLOT_LABELS: Record<LineupSlot, string> = { main: '主将', sub1: '副将一', sub2: '副将二' }

export function slotLabel(slot: LineupSlot): string {
  return SLOT_LABELS[slot]
}

/** 一个槽位画出来那一行。 */
export interface SlotRow {
  readonly slot: LineupSlot
  readonly label: string
  readonly heroId: string | null
  /** 武将中文名（`HeroView.name`，服务端给的）；空位为 null */
  readonly heroName: string | null
  readonly empty: boolean
  /** 这一槽现在是不是"正在选"（弹层打开着） */
  readonly picking: boolean
}

/** 一套编队的三个槽位。`lineup` 读不到（老服务端没这一套）时三槽都空。 */
export function slotRows(lineup: LineupView | null | undefined,
  heroes: readonly HeroView[], pickingSlot: LineupSlot | null): readonly SlotRow[] {
  const bySlot = (slot: LineupSlot): string | null => {
    if (lineup === undefined || lineup === null) {
      return null
    }
    const heroId = slot === 'main' ? lineup.main : slot === 'sub1' ? lineup.sub1 : lineup.sub2
    return heroId === undefined ? null : heroId
  }
  return LINEUP_SLOTS.map((slot) => {
    const heroId = bySlot(slot)
    const hero = heroes.find((h) => h.heroId === heroId)
    return {
      slot,
      label: SLOT_LABELS[slot],
      heroId,
      // 名字只从 HeroView 读：id 在而人不在（存档比列表新）时宁可写"空"，也不印一个内部编号
      heroName: hero === undefined ? null : hero.name,
      empty: heroId === null || hero === undefined,
      picking: pickingSlot === slot,
    }
  })
}

/** 选将弹层里的一行。 */
export interface PickRow {
  readonly heroId: string
  readonly name: string
  /** 战力那一行（`HeroView.power` 原样，客户端不做任何换算） */
  readonly powerText: string
  readonly usable: boolean
  /** 不能用时给玩家看的原因；能用时为 null */
  readonly reason: string | null
  /**
   * 能点但要告知的标注，如「已在第 2 队」。
   *
   * <p>**这不能变成 `usable=false`**：服务端允许一个武将同时在几套编队里，
   * 灰掉就是客户端自己加规则。
   */
  readonly note: string | null
}

/**
 * 列出可选的武将：**能点的排前面**，两组内部各自照名册顺序。
 *
 * <p>与合成弹层同一条理由：一屏画不下时视图按可视高度截断，不排序就会把"能点的那几名"
 * 挤到屏外，玩家对着一屏灰的找不到入口。
 *
 * <p>`takenInThisPreset` 是**同一队其他槽位**已经占用的人 —— 只有这些才灰
 * （服务端那句"在同一支队伍里出现了两次"）。
 */
export function pickRows(heroes: readonly HeroView[], lineups: readonly LineupView[],
  presetIndex: number, takenInThisPreset: readonly (string | null)[]): readonly PickRow[] {
  const taken = new Set(takenInThisPreset.filter((id): id is string => id !== null))
  const out: PickRow[] = heroes.map((hero) => {
    const elsewhere = lineups
      .filter((lineup) => lineup.presetIndex !== presetIndex)
      .filter((lineup) => lineup.main === hero.heroId || lineup.sub1 === hero.heroId
        || lineup.sub2 === hero.heroId)
      .map((lineup) => lineup.presetIndex + 1)
    return {
      heroId: hero.heroId,
      name: hero.name,
      powerText: `战力 ${hero.power}`,
      usable: !taken.has(hero.heroId),
      reason: taken.has(hero.heroId) ? '已在本队其他槽位' : null,
      note: elsewhere.length === 0 ? null : `已在第 ${elsewhere.join('、')} 队`,
    }
  })
  // sort 稳定（ES2019 起规范保证）⇒ 两组内部仍是名册那份顺序
  return out.sort((a, b) => Number(b.usable) - Number(a.usable))
}

/** 槽位 → 请求体（`SetLineupReq` 去掉 requestId）。空位一律 null，不留空串。 */
export function lineupBody(presetIndex: number,
  slots: Readonly<Record<LineupSlot, string | null>>): {
    presetIndex: number, main: string | null, sub1: string | null, sub2: string | null
  } {
  return {
    presetIndex,
    main: slots.main ?? null,
    sub1: slots.sub1 ?? null,
    sub2: slots.sub2 ?? null,
  }
}

/**
 * 这一屏能不能保存。
 *
 * <p>只有一种情况不给发：**一个人都没读过到**（`heroes` 为空 ⇒ 列表还没拉回来，
 * 发了就是把玩家送回"未知武将"的拒绝里）。三槽全空是合法意图（清空这一队）。
 */
export function canSave(slots: Readonly<Record<LineupSlot, string | null>>,
  heroes: readonly HeroView[]): boolean {
  if (heroes.length === 0) {
    return false
  }
  const ids = LINEUP_SLOTS.map((slot) => slots[slot]).filter((id): id is string => id !== null)
  // 选了人却不在名册里 = 存档与列表不一致（热更删过将？），这种请求必被 requireOwned 拒
  return ids.every((id) => heroes.some((hero) => hero.heroId === id))
}

/** 保存键上的字：清空那一档要说清楚，否则玩家以为按了没反应。 */
export function saveText(slots: Readonly<Record<LineupSlot, string | null>>,
  heroes: readonly HeroView[]): string {
  const filled = LINEUP_SLOTS.filter((slot) => slots[slot] !== null).length
  if (heroes.length === 0) {
    return '武将列表还没读到'
  }
  if (!canSave(slots, heroes)) {
    return '选中的武将不在名册里'
  }
  return filled === 0 ? '保存（清空这一队）' : `保存 · ${filled} 名`
}

/** 整块弹层视图。编排层每次下发一份完整的，视图不做任何判断。 */
export interface LineupEditView {
  readonly presetIndex: number
  /** 「编队 1」—— 与武将页编队行同一份措辞（`HeroPanel.presetText`）；presetIndex 从 0 起算要 +1 */
  readonly presetText: string
  readonly slots: readonly SlotRow[]
  readonly pickingSlot: LineupSlot | null
  /** 正在选哪一槽时才有内容；没在选时是空数组（视图据此不画列表） */
  readonly picks: readonly PickRow[]
  readonly canSave: boolean
  readonly saveText: string
}

export function buildLineupEdit(heroes: readonly HeroView[], lineups: readonly LineupView[],
  presetIndex: number, slots: Readonly<Record<LineupSlot, string | null>>,
  pickingSlot: LineupSlot | null): LineupEditView {
  const others = LINEUP_SLOTS
    .filter((slot) => slot !== pickingSlot)
    .map((slot) => slots[slot] ?? null)
  return {
    presetIndex,
    presetText: `编队 ${presetIndex + 1}`,
    slots: slotRows(lineups.find((lineup) => lineup.presetIndex === presetIndex) ?? null,
      heroes, pickingSlot)
      // 槽位行画的是**编辑中的状态**而不是存档：刚换上的人要立刻看得见，回读之后两者必然一致
      .map((row) => {
        const heroId = slots[row.slot] ?? null
        const hero = heroes.find((h) => h.heroId === heroId)
        return heroId === null || hero === undefined
          ? { ...row, heroId, heroName: null, empty: true }
          : { ...row, heroId, heroName: hero.name, empty: false }
      }),
    pickingSlot,
    picks: pickingSlot === null ? []
      : pickRows(heroes, lineups, presetIndex, others),
    canSave: canSave(slots, heroes),
    saveText: saveText(slots, heroes),
  }
}
