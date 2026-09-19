/**
 * 职责：装备实例页的展示数据组装（V03-b-S1 读侧，台账 #265）。
 * 依赖：生成的协议类型 + FixedPoint（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不判"能不能强化"</b>：`canForge` 与 `blockReason` 是服务端的权威答案，
 * 客户端只把原因翻成句子（自己再判一遍铁耗只会与服务器分叉，症状是"看着能点、点了被拒"）。
 *
 * <p><b>铁耗用服务端给的数</b>（`nextCostIron`），不在客户端重算曲线；三个 `*Fixed` 是**定点**（×10000）⇒
 * 走 `FixedPoint.format`，不是 `percentText`（那是比率）。
 *
 * <p><b>`wornByHeroId` 不许印到面板上</b>：那是 id 不是名字（与收口清单 #255 的"建筑名印配置 id"同一根因）。
 * 读侧只说「已装备/未装备」，"穿在谁身上"由武将页那一侧回答。
 *
 * <p><b>顺序照抄服务端，不做"未装备排前面"的重排</b>：重排等于用客户端的一套口径覆盖服务端的编排，
 * 而玩家对"上次第三件是哪个"有肌肉记忆（与科技树、榜单同一条纪律）。已装备与否在行上标注，不靠位置。
 */

import * as FixedPoint from '../../core/FixedPoint'
import type {
  EquipForgeBlockReason, EquipInstanceListView, EquipInstanceView, EquipRarity, EquipSlot,
} from '../../net/generated/EquipProtocol'

/** 槽位名。表里没有的取值退回枚举名，不显示空白。 */
export function slotLabel(slot: EquipSlot): string {
  switch (slot) {
    case 'WEAPON':
      return '武器'
    case 'ARMOR':
      return '护甲'
    case 'MOUNT':
      return '坐骑'
    case 'ACCESSORY':
      return '饰品'
    default:
      return String(slot)
  }
}

/**
 * 稀有度记号**保留原文**（N / R / SR / SSR）。
 *
 * 刻意不翻：这四个记号在同类游戏里是玩家互相报的通用写法，而仓库里没有任何中文口径可抄 ——
 * 自己编一套"普通/稀有/史诗/传说"就是发明术语（与 #255 那条"名字必须有出处"同一条纪律的反面）。
 */
export function rarityLabel(rarity: EquipRarity): string {
  return String(rarity)
}

/** 拒绝原因 → 玩家语言。`NONE` 不是拒绝，回 null（不写一句"没问题"占地方）。 */
export function blockReasonText(reason: EquipForgeBlockReason): string | null {
  switch (reason) {
    case 'NONE':
      return null
    case 'MAX_LEVEL':
      return '已满级'
    case 'IRON_LOW':
      return '铁矿不足'
    default:
      return String(reason)
  }
}

/** 「强化 +2 / 20」。 */
export function forgeTextOf(view: EquipInstanceView): string {
  return `强化 +${view.forgeLevel} / ${view.forgeMax}`
}

/** 「武力 +12 · 统率 +8 · 智力 +4」。三维词沿用在用的那三个（见 HeroPanel），定点走 format。 */
export function statsTextOf(view: EquipInstanceView): string {
  const parts: string[] = []
  if (view.mightFixed !== 0) {
    parts.push(`武力 +${FixedPoint.format(view.mightFixed)}`)
  }
  if (view.commandFixed !== 0) {
    parts.push(`统率 +${FixedPoint.format(view.commandFixed)}`)
  }
  if (view.wisdomFixed !== 0) {
    parts.push(`智力 +${FixedPoint.format(view.wisdomFixed)}`)
  }
  return parts.length === 0 ? '无属性加成' : parts.join(' · ')
}

/** 「强化消耗 铁矿 480」；满级时回 null（那时成本是 0，写出来只会让人以为还能强化）。 */
export function costTextOf(view: EquipInstanceView): string | null {
  if (view.forgeLevel >= view.forgeMax) {
    return null
  }
  return `强化消耗 铁矿 ${view.nextCostIron}`
}

/** 装备实例页的一行。判定字段全部来自服务端，本模块只做文本化。 */
export interface EquipRow {
  readonly uid: string
  readonly name: string
  /** 原始槽位：视图要用它发换装请求（`slotText` 是给人看的，别拿它反推） */
  readonly slot: EquipSlot
  readonly slotText: string
  readonly rarityText: string
  /** 「强化 +2 / 20」 */
  readonly forgeText: string
  /** 「武力 +12 · 统率 +8」 */
  readonly statsText: string
  /** 「强化消耗 铁矿 480」；满级为 null */
  readonly costText: string | null
  /** 「已装备」/「未装备」——**不印 heroId** */
  readonly wornText: string
  readonly worn: boolean
  readonly canForge: boolean
  /** 不能强化的原因（能强化时为 null） */
  readonly reasonText: string | null
  /**
   * 对**当前选中的武将**可执行的动作：「装备」/「卸下」；没选武将、或这件穿在别人身上时为 null。
   *
   * <p>这里只做**显示层的比较**（行上的 `wornByHeroId` 与目标武将是不是同一个 id），
   * 不判"这仗能不能打"：真正允不允许穿由服务端说了算，客户端筛错的下场只是一次被拒。
   */
  readonly actionText: string | null
}

export interface EquipPanelView {
  readonly rows: readonly EquipRow[]
  /** 「已装备 3 / 共 9 件」 */
  readonly summaryText: string
  /** 拉取失败或还没拉回来时的说明行 */
  readonly noticeText: string | null
  /** 选中的武将（「给 关羽 换装」）；没选时为 null，此时所有行都不给动作 */
  readonly targetText: string | null
}

/**
 * 组装装备实例页。
 *
 * @param resp          GET /equip/instances 的响应；null = 还没拉回来
 * @param failureNotice 拉取失败时服务端给的理由，原样进说明行（**不清空手里那份**）
 * @param target          换装目标：`{ heroId, heroName }`。给了才在行上出「装备/卸下」动作
 *                        （入口是武将行 → 装备库，所以玩家多数时候带着一个具体武将进来）
 */
export function buildEquipPanel(resp: EquipInstanceListView | null,
  failureNotice?: string | null,
  target?: { readonly heroId: string, readonly heroName: string } | null): EquipPanelView {
  const targetHeroId = target?.heroId ?? null
  if (resp === null || resp === undefined) {
    return {
      rows: [], summaryText: '', noticeText: failureNotice ?? '装备列表还没拉回来，稍后再试',
      targetText: target === null || target === undefined ? null : `给 ${target.heroName} 换装`,
    }
  }
  const rows = resp.instances.map((item: EquipInstanceView): EquipRow => {
    const worn = item.wornByHeroId !== null && item.wornByHeroId !== undefined
    // 动作只对"这件是我要换的那个武将的、或者还没人穿"有意义；穿在别人身上时给不出动作
    // （要换下来得先让那个人脱下 —— 那是另一条路，不在本格）
    const actionText = targetHeroId === null
      ? null
      : (worn ? (item.wornByHeroId === targetHeroId ? '卸下' : null) : '装备')
    return {
      uid: item.uid,
      name: item.name,
      slot: item.slot,
      slotText: slotLabel(item.slot),
      rarityText: rarityLabel(item.rarity),
      forgeText: forgeTextOf(item),
      statsText: statsTextOf(item),
      costText: costTextOf(item),
      wornText: worn ? '已装备' : '未装备',
      worn,
      canForge: item.canForge,
      reasonText: item.canForge ? null : blockReasonText(item.blockReason),
      actionText,
    }
  })
  const wornCount = rows.filter((row) => row.worn).length
  return {
    rows,
    summaryText: `已装备 ${wornCount} / 共 ${rows.length} 件`,
    // 空列表要说实话：**"正在载入…"是给"还没拉回来"用的**，一件都没有时写它会让人一直等
    // （这条是探针截图里看出来的：新号 instances=0，而面板写着"正在载入…"）
    noticeText: failureNotice ?? (rows.length === 0 ? '还没有装备' : null),
    targetText: target === null || target === undefined ? null : `给 ${target.heroName} 换装`,
  }
}
