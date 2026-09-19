/**
 * 职责：美术资源的**纯数据层** —— 素材族的键与路径、配置表 id → 素材键的映射、九宫格的几何常量。
 * 依赖：无（刻意不 import 'cc'，让 node 测试能直接扫这张表核对磁盘文件）。
 *
 * <p>为什么单独一层：`ArtCatalog` 绑在 cc 的 SpriteFrame 加载上，测试进不去；
 * 而「每个键都有对应文件」「每个 item.json 行要么映射到图要么明确没有图」这两条
 * 恰恰是最该被机器钉住的断言 —— 它们错了，界面上就是一个永远不出图的空位。
 *
 * <p>键的命名沿用图集图标那一族的 `族:成员` 形式（如 `icon:resources/wood`），
 * 新族用 `item:` / `equip:` / `hero:` / `activity:` / `currency:` / `avatar:` 前缀。
 */

export type ArtFamily = 'item' | 'equip' | 'hero' | 'activity'

/**
 * 面板框 `ui/generated/ui/panel-kingdom-v1` 九宫格的**四角带厚**，交给布局用。
 *
 * <p>切分几何的唯一真源是那张图的 `.png.meta`（border* 四值）—— 代码里不再抄第二份，
 * 因为"两份数字"这件事本轮真的咬过一口：`ArtCatalog` 曾有一份 `insets: [51,47,51,47]`
 * 的覆盖，后写且生效，于是 meta 里的 border 改了个像素都不会变（收口清单 #213）。
 * 两者现在由 `tests/ArtFamilies.test.ts` 对账：改图不改这里，测试就红。
 */
export const PANEL_FRAME_BAND = 44

/**
 * 族内成员 → resources 相对路径（不带 /spriteFrame，那是加载层的事）。
 *
 * <p>本表**只登记已随包下发且已有消费面板的族**（背包的 item/equip、武将面板的 hero、
 * 任务面板活动页的 activity）。收进包里这一步走 `art-src/accept_to_runtime.py`
 * （512px RGBA 草稿 → 128/256px 调色板图），resources 已配成微信小游戏分包，
 * 这些图不进首包（收口清单 #196）。
 *
 * <p>剩下的族（头像框/立绘封面/学派徽/战令/赛季币）缺的不是包体余量而是**消费位**：
 * 社交行没有头像槽、活动/战令/科技面板还没建。按 art-src/README 的纪律
 * 不预接没人消费的资源，等各自功能批次再登记。
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
  activity: {
    login_7d: 'ui/generated/activities/activity-login-7d-v1',
    login_30d: 'ui/generated/activities/activity-login-30d-v1',
    monster_hunt: 'ui/generated/activities/activity-monster-hunt-v1',
    rally_week: 'ui/generated/activities/activity-rally-week-v1',
    donate_week: 'ui/generated/activities/activity-donate-week-v1',
    pvp_win: 'ui/generated/activities/activity-pvp-win-v1',
    build_sprint: 'ui/generated/activities/activity-build-sprint-v1',
    squad_help: 'ui/generated/activities/activity-squad-help-v1',
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

/** 活动行图标键：activity.json 行 id 去掉 `activity_` 前缀就是成员名，认不出来的返回 null（行继续用 Graphics 占位）。 */
export function activityIconKey(activityConfigId: string): string | null {
  return ACTIVITY_ICON_BY_CONFIG[activityConfigId] ?? null
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

/** activity.json 八行 → 活动族键。表里加行而这里没跟上时，tests/ArtFamilies.test.ts 的对账会红。 */
export const ACTIVITY_ICON_BY_CONFIG: Readonly<Record<string, string>> = {
  activity_login_7d: 'activity:login_7d',
  activity_login_30d: 'activity:login_30d',
  activity_monster_hunt: 'activity:monster_hunt',
  activity_rally_week: 'activity:rally_week',
  activity_donate_week: 'activity:donate_week',
  activity_pvp_win: 'activity:pvp_win',
  activity_build_sprint: 'activity:build_sprint',
  activity_squad_help: 'activity:squad_help',
}
