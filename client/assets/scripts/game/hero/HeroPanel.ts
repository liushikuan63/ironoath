/**
 * 职责：武将面板的展示数据组装（B06 §2 五条养成线、§4 编队、硬约束 1 乘区标注）。
 * 依赖：core/FixedPoint、生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何数值计算</b>（铁律 2）：属性、加成、战力、带兵上限全部由服务端算好下发。
 * 这里只做三件搬运工作 ——
 * 把定点加成格式化成百分比、把 heroId 换成武将名字、把「已触上限」这类结论翻成玩家能懂的提示。
 *
 * <p><b>乘区必须标出来</b>（B06 硬约束 1）：配置表里每个加成都标了落在哪个乘区，
 * 服务端把它随 BonusBreak.zone 下发。面板不显示乘区，玩家侧的「为什么我这么强」
 * 与开发侧的「数值为什么算错」就都无从查起 —— 这条约束存在的理由就是可解释性。
 *
 * <p><b>触到乘区上限时必须提示</b>：HeroBonus.capped 为 true 说明继续堆养成不会再变强。
 * 不提示的话玩家会把资源一直投进去而看不到任何变化，那会被理解成数值造假。
 *
 * <p><b>只有两个武将侧乘区，没有独立的生命乘区</b>：B05 已交付的内核把生命折进了有效防御
 * （HP_DEFENSE_WEIGHT），所以协议给的是 atkFixed / defFixed / skillFixed。
 * 硬造一个内核消费不了的 hpMultiplier 只会让面板显示一个不影响战斗的数字。
 */

import * as FixedPoint from '../../core/FixedPoint'
import type {
  BonusZone, HeroListResp, HeroView, LineupView,
} from '../../net/generated/HeroProtocol'

/** 一名武将在面板上的一行。 */
export interface HeroRow {
  readonly heroId: string
  /** 名字来自 hero 表下发，客户端不得自行翻译 */
  readonly name: string
  readonly rarity: string
  /** 「Lv60/60」；满级时 expToNext 为 0 */
  readonly levelText: string
  /** 经验进度文本；满级时为 null */
  readonly expText: string | null
  readonly starText: string
  readonly awakenText: string
  /** 主副技能。副技能只在副将位生效（B06 §2.4），文案里要说清 */
  readonly mainSkillText: string
  readonly subSkillText: string
  /** 四个装备槽，按 WEAPON/ARMOR/MOUNT/ACCESSORY 顺序；空槽显示「空」 */
  readonly equipTexts: readonly string[]
  readonly emptyEquipSlots: number
  /** 属性：基础 → 养成后。两个都显示，玩家才看得出养成到底涨了多少 */
  readonly attrText: string
  readonly powerText: string
  /** 缘分对象的名字；没有缘分为 null */
  readonly bondText: string | null
}

/** 一套编队的加成明细。 */
export interface LineupPanel {
  readonly presetIndex: number
  readonly presetText: string
  /** 三个位置上的武将名字；空位显示「空」 */
  readonly slotTexts: readonly string[]
  readonly emptySlots: number
  readonly atkText: string
  readonly defText: string
  readonly skillText: string
  readonly commandText: string
  /** 是否触到乘区上限（HERO_ZONE_CAP） */
  readonly capped: boolean
  readonly cappedHint: string | null
  readonly breakdownLines: readonly string[]
  /** 已激活的缘分（成对同队才算）。id 能对上武将就显示名字，对不上原样显示 */
  readonly bondTexts: readonly string[]
}

/** 整个武将面板。 */
export interface HeroPanelView {
  readonly heroes: readonly HeroRow[]
  readonly lineups: readonly LineupPanel[]
  readonly troopCapText: string
  readonly fragmentTexts: readonly string[]
}

/** 稀有度展示顺序。列表按它排？不 —— 顺序照搬服务端，这里只用于分页页签。 */
const RARITY_ORDER: readonly string[] = ['SSR', 'SR', 'R', 'N']

/** 装备槽顺序。协议明写 equips 是定长数组、按 EquipSlot 声明顺序，所以这里只是给槽位起名。 */
const EQUIP_SLOT_NAMES: readonly string[] = ['武器', '护甲', '坐骑', '饰品']

/** 组装整个武将面板。 */
export function buildHeroPanel(resp: HeroListResp): HeroPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const nameById = new Map<string, string>()
  for (const hero of resp.heroes) {
    nameById.set(hero.heroId, hero.name)
  }
  return {
    heroes: resp.heroes.map((hero: HeroView): HeroRow => buildHeroRow(hero, nameById)),
    lineups: resp.lineups.map((lineup: LineupView): LineupPanel => buildLineupPanel(lineup, nameById)),
    troopCapText: `带兵上限 ${resp.troopCap}（已用 ${resp.troopsInUse}）`,
    fragmentTexts: resp.fragments.map((item) => `${item.itemId} ×${item.count}`),
  }
}

/** 组装一名武将的行。 */
export function buildHeroRow(hero: HeroView, nameById: ReadonlyMap<string, string>): HeroRow {
  if (hero === undefined || hero === null) {
    throw new Error('hero 不得为空')
  }
  const maxLevel = hero.level >= hero.maxLevel
  const equipTexts = hero.equips.map((equip, index) => {
    const slot = EQUIP_SLOT_NAMES[index] ?? `槽位${index + 1}`
    if (equip === null) {
      return `${slot}：空`
    }
    return `${slot}：${equip}`
  })
  let empty = 0
  for (const equip of hero.equips) {
    if (equip === null) {
      empty++
    }
  }
  return {
    heroId: hero.heroId,
    name: hero.name,
    rarity: hero.rarity,
    levelText: `Lv${hero.level}/${hero.maxLevel}`,
    expText: maxLevel ? null : `经验 ${hero.exp}/${hero.expToNext}`,
    starText: starText(hero.star, hero.maxStar),
    awakenText: `觉醒 ${hero.awaken}/${hero.maxAwaken}`,
    // 技能名字由服务端随视图下发（`mainSkillName`）—— 这一行原先印的是 `skill_guanyu_main`，
    // 与 #255 建筑名、#268 资源名同一族的配置 id 外泄（#276 的截图里看见的）
    mainSkillText: `${hero.mainSkillName} Lv${hero.mainSkillLevel}/${hero.maxSkillLevel}`,
    // 副技能只在副将位生效（B06 §2.4）。不写明的话玩家会把主将放在副将位，
    // 然后发现技能没触发，认为技能是坏的
    subSkillText: `${hero.subSkillName} Lv${hero.subSkillLevel}/${hero.maxSkillLevel}（仅副将位生效）`,
    equipTexts,
    emptyEquipSlots: empty,
    attrText: `武力 ${hero.baseAttrs.might}→${hero.finalAttrs.might}`
      + ` 统率 ${hero.baseAttrs.command}→${hero.finalAttrs.command}`
      + ` 智力 ${hero.baseAttrs.wisdom}→${hero.finalAttrs.wisdom}`,
    powerText: `战力 ${hero.power}`,
    bondText: hero.bondWith === null
      ? null
      : `缘分：${nameById.get(hero.bondWith) ?? hero.bondWith}`,
  }
}

/** 组装一套编队。 */
export function buildLineupPanel(lineup: LineupView, nameById: ReadonlyMap<string, string>): LineupPanel {
  if (lineup === undefined || lineup === null) {
    throw new Error('lineup 不得为空')
  }
  const slots = [lineup.main, lineup.sub1, lineup.sub2]
  const slotNames = ['主将', '副将', '副将']
  const slotTexts = slots.map((heroId, index) => {
    const position = slotNames[index] ?? `位置${index + 1}`
    return heroId === null ? `${position}：空` : `${position}：${nameById.get(heroId) ?? heroId}`
  })
  let empty = 0
  for (const heroId of slots) {
    if (heroId === null) {
      empty++
    }
  }
  const bonus = lineup.bonus
  return {
    presetIndex: lineup.presetIndex,
    presetText: `编队 ${lineup.presetIndex + 1}`,
    slotTexts,
    emptySlots: empty,
    atkText: `攻击 +${FixedPoint.percentText(bonus.atkFixed)}`,
    defText: `防御 +${FixedPoint.percentText(bonus.defFixed)}`,
    skillText: `技能强度 +${FixedPoint.percentText(bonus.skillFixed)}`,
    // 统帅值决定带兵上限（主将 100% + 副将各 50%），是玩家换阵型时最关心的那个数
    commandText: `统帅 ${bonus.commandValue}`,
    capped: bonus.capped,
    cappedHint: bonus.capped
      ? '已触到乘区上限：继续堆养成不会再变强，资源请转投其它系统'
      : null,
    breakdownLines: bonus.breakdown.map((line) =>
      `${zoneText(line.zone)} ${line.source} +${FixedPoint.percentText(line.value)}`),
    bondTexts: lineup.activeBonds.map((id) => nameById.get(id) ?? id),
  }
}

/**
 * 乘区标签（B06 硬约束 1）。
 *
 * <p>不认识的乘区原样显示而不是丢掉：静默丢掉一行加成，
 * 玩家算出来的总数就会和面板对不上，而这种不一致他会当成数值造假。
 */
export function zoneText(zone: BonusZone | string): string {
  switch (zone) {
    case 'HERO': return '[武将]'
    case 'BOND': return '[缘分]'
    case 'EQUIP_SET': return '[装备套装]'
    default: return `[${zone}]`
  }
}

/** 星级文本。用实心/空心星而不是「3/5」：星级是玩家扫一眼就要读出的信息。 */
export function starText(star: number, maxStar: number): string {
  if (!Number.isInteger(star) || star < 0) {
    throw new Error(`star 必须是非负整数，实际=${star}`)
  }
  const filled = Math.min(star, maxStar)
  return '★'.repeat(filled) + '☆'.repeat(Math.max(0, maxStar - filled))
}

/** 稀有度页签顺序。列表本身照搬服务端顺序，这里只决定页签怎么排。 */
export function rarityOrder(): readonly string[] {
  return RARITY_ORDER
}
