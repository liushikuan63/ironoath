/**
 * 职责：新素材族（G1~G7 生成批次）的**纯数据层** —— 键、运行时路径、配置表 id → 素材键的映射。
 * 依赖：无（刻意不 import 'cc'，让 node 测试能直接扫这张表核对磁盘文件）。
 *
 * <p>为什么单独一层：`ArtCatalog` 绑在 cc 的 SpriteFrame 加载上，测试进不去；
 * 而「每个键都有对应文件」「每个 item.json 行要么映射到图要么明确没有图」这两条
 * 恰恰是最该被机器钉住的断言 —— 它们错了，界面上就是一个永远不出图的空位。
 *
 * <p>键的命名沿用图集图标那一族的 `族:成员` 形式（如 `icon:resources/wood`），
 * 新族用 `item:` / `equip:` / `hero:` / `activity:` / `currency:` / `avatar:` 前缀。
 */

export type ArtFamily = 'item' | 'equip' | 'hero'

/**
 * 族内成员 → resources 相对路径（不带 /spriteFrame，那是加载层的事）。
 *
 * <p>本表**只登记已随包下发且已有消费面板的族**（本轮：背包的 item/equip、武将面板的 hero）。
 * 活动/头像/赛季币/抽卡横幅/弹窗封面等族在 art-src 有草稿，但首包余量已尽
 * （G1 接入后 wechat release 实测 3.98MB / 4.00MB）——
 * 接它们之前必须先做 resources 分包（收口清单 #112 的"下一刀"，进展见 素材缺口清单.md §五），
 * 届时补进本表并同步 tests/ArtFamilies.test.ts 的对账断言。
 */
export const FAMILY_ASSETS: Record<ArtFamily, Readonly<Record<string, string>>> = {
  item: {
    scroll_build: 'ui/generated/items/item-scroll-build-v1',
    scroll_train: 'ui/generated/items/item-scroll-train-v1',
    scroll_research: 'ui/generated/items/item-scroll-research-v1',
    chest_hero: 'ui/generated/items/item-chest-hero-v1',
    chest_resource: 'ui/generated/items/item-chest-resource-v1',
    shard_sr: 'ui/generated/items/item-shard-sr-v1',
    shard_ssr: 'ui/generated/items/item-shard-ssr-v1',
    shield_peace: 'ui/generated/items/item-shield-peace-v1',
    charm_closed: 'ui/generated/items/item-charm-closed-v1',
    horn_rally: 'ui/generated/items/item-horn-rally-v1',
    book_exp_s: 'ui/generated/items/item-book-exp-s-v1',
    book_exp_m: 'ui/generated/items/item-book-exp-m-v1',
    ticket_gacha: 'ui/generated/items/item-ticket-gacha-v1',
  },
  equip: {
    'weapon-iron': 'ui/generated/equip/equip-weapon-iron-v1',
    'weapon-pojun': 'ui/generated/equip/equip-weapon-pojun-v1',
    'weapon-xuanwu': 'ui/generated/equip/equip-weapon-xuanwu-v1',
    'weapon-wenqu': 'ui/generated/equip/equip-weapon-wenqu-v1',
    'armor-iron': 'ui/generated/equip/equip-armor-iron-v1',
    'armor-pojun': 'ui/generated/equip/equip-armor-pojun-v1',
    'armor-xuanwu': 'ui/generated/equip/equip-armor-xuanwu-v1',
    'armor-wenqu': 'ui/generated/equip/equip-armor-wenqu-v1',
    'mount-iron': 'ui/generated/equip/equip-mount-iron-v1',
    'mount-pojun': 'ui/generated/equip/equip-mount-pojun-v1',
    'mount-xuanwu': 'ui/generated/equip/equip-mount-xuanwu-v1',
    'mount-wenqu': 'ui/generated/equip/equip-mount-wenqu-v1',
    'tally-iron': 'ui/generated/equip/equip-tally-iron-v1',
    'tally-pojun': 'ui/generated/equip/equip-tally-pojun-v1',
    'tally-xuanwu': 'ui/generated/equip/equip-tally-xuanwu-v1',
    'tally-wenqu': 'ui/generated/equip/equip-tally-wenqu-v1',
  },
  hero: {
    hero_ssr_01: 'ui/generated/heroes/hero-ssr-01-v1',
    hero_ssr_02: 'ui/generated/heroes/hero-ssr-02-v1',
    hero_ssr_03: 'ui/generated/heroes/hero-ssr-03-v1',
    hero_sr_01: 'ui/generated/heroes/hero-sr-01-v1',
    hero_sr_02: 'ui/generated/heroes/hero-sr-02-v1',
    hero_sr_03: 'ui/generated/heroes/hero-sr-03-v1',
    hero_r_01: 'ui/generated/heroes/hero-r-01-v1',
    hero_r_02: 'ui/generated/heroes/hero-r-02-v1',
    hero_r_03: 'ui/generated/heroes/hero-r-03-v1',
    hero_n_01: 'ui/generated/heroes/hero-n-01-v1',
    hero_n_02: 'ui/generated/heroes/hero-n-02-v1',
    hero_n_03: 'ui/generated/heroes/hero-n-03-v1',
  },
}

export function familyArtKey(family: ArtFamily, member: string): string {
  return `${family}:${member}`
}

/** 道具行 id → 图标键。eq_* 走装备族；映射不到的（技能书/觉醒券/R·N 碎片）返回 null，行继续用 Graphics 占位。 */
export function itemArtKeyForConfig(configId: string): string | null {
  const equip = EQUIP_ICON_BY_CONFIG[configId]
  if (equip !== undefined) {
    return equip
  }
  const item = ITEM_ICON_BY_CONFIG[configId]
  return item ?? null
}

/** 武将立绘键：hero.json 行 id 就是成员名；没有立绘的行返回 null，行退回稀有度图标。 */
export function heroPortraitKey(heroConfigId: string): string | null {
  return FAMILY_ASSETS.hero[heroConfigId] === undefined ? null : `hero:${heroConfigId}`
}

/** 装备槽位族键：行 id 里认不出家族/槽位组合时返回 null（映射表是唯一真相，不做字符串猜测）。 */
export const EQUIP_ICON_BY_CONFIG: Readonly<Record<string, string>> = {
  eq_iron_sword: 'equip:weapon-iron',
  eq_pojun_blade: 'equip:weapon-pojun',
  eq_xuanwu_hammer: 'equip:weapon-xuanwu',
  eq_wenqu_brush: 'equip:weapon-wenqu',
  eq_leather_armor: 'equip:armor-iron',
  eq_pojun_plate: 'equip:armor-pojun',
  eq_xuanwu_shield: 'equip:armor-xuanwu',
  eq_wenqu_robe: 'equip:armor-wenqu',
  eq_post_horse: 'equip:mount-iron',
  eq_pojun_warhorse: 'equip:mount-pojun',
  eq_xuanwu_heavyhorse: 'equip:mount-xuanwu',
  eq_wenqu_lighthorse: 'equip:mount-wenqu',
  eq_bamboo_tally: 'equip:tally-iron',
  eq_pojun_tiger_tally: 'equip:tally-pojun',
  eq_xuanwu_wall_charm: 'equip:tally-xuanwu',
  eq_wenqu_jade: 'equip:tally-wenqu',
}

export const ITEM_ICON_BY_CONFIG: Readonly<Record<string, string>> = {
  item_speedup_build_5m: 'item:scroll_build',
  item_speedup_build_1h: 'item:scroll_build',
  item_speedup_build_8h: 'item:scroll_build',
  item_speedup_train_1h: 'item:scroll_train',
  item_speedup_research_1h: 'item:scroll_research',
  item_chest_hero: 'item:chest_hero',
  item_chest_resource: 'item:chest_resource',
  item_mat_hero_frag_sr: 'item:shard_sr',
  item_mat_hero_frag_ssr: 'item:shard_ssr',
  item_buff_peace_24h: 'item:shield_peace',
  item_buff_closed_8h: 'item:charm_closed',
  item_buff_rally_2h: 'item:horn_rally',
  item_hero_exp_s: 'item:book_exp_s',
  item_hero_exp_m: 'item:book_exp_m',
  item_hero_exp_l: 'item:book_exp_m',
}
