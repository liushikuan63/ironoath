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

export type ArtFamily = 'item' | 'equip' | 'hero' | 'activity' | 'building'

/**
 * 内城舞台素材。它们是启动预载的静态键，不属于按需族：
 * 内城可能不是首屏，但打开时背景必须已经在，否则会先闪一帧深色底再补图。
 *
 * 真源放在这层而不是 `ArtCatalog`，让 node:test 能在不 import `cc` 的前提下
 * 对账“键表 → 磁盘 PNG”；运行期加载仍统一走 `ArtCatalog`。
 *
 * <p>`reference` 当前采用统一写实 v3 的 ground-only 舞台：1536×1024 的石台、道路、草地与远景，
 * 不烘焙主城或任何功能建筑，已建建筑由生产节点逐栋叠加。
 * 运行期沿用 `city-base-v2` 路径与 UUID；CityPanelView 按 SpriteFrame 的原尺寸等比 cover，
 * 不把 3:2 母版压成 8:5。原稿与采用记录见 `art-src/generated/accepted/iron-unified-v3/`。
 */
export const CITY_STAGE_ASSETS = {
  ground: 'ui/generated/city/city-ground-cobble-v1',
  wall: 'ui/generated/city/city-wall-band-v1',
  ridge: 'ui/generated/city/city-ridge-v1',
  reference: 'ui/generated/city/city-base-v2',
} as const

/**
 * 面板框 `ui/generated/ui/panel-kingdom-v1` 九宫格的**四角带厚**，交给布局用。
 *
 * <p>切分几何的唯一真源是那张图的 `.png.meta`（border* 四值）—— 代码里不再抄第二份，
 * 因为"两份数字"这件事本轮真的咬过一口：`ArtCatalog` 曾有一份 `insets: [51,47,51,47]`
 * 的覆盖，后写且生效，于是 meta 里的 border 改了个像素都不会变（收口清单 #213）。
 * 两者现在由 `tests/ArtFamilies.test.ts` 对账：改图不改这里，测试就红。
 */
export const PANEL_FRAME_BAND = 24

/**
 * V25「铁誓」主底板 `ui/generated/ui/panel-iron-v1` 的四边带厚，给**内容排版**用。
 *
 * <p>与上面同一条纪律：真源是那张图的 `.png.meta`，这里只是镜像，
 * 由 `client/tests/ArtFamilies.test.ts` 逐值对账（改图不改这里 ⇒ 测试红）。
 *
 * <p>为什么非要有它：首版接线只把底板换成贴图、内容 y 坐标沿用旧的 `PANEL_H / 2 - 34`，
 * 结果标题压在铜边内线上、底部那句被下铜边切掉半截（V25-c 真截图抓到的）。
 * 铜边占掉的上下空间必须显式还给内容，否则"换了材质"就等于"文字被材质盖住"。
 * 统一 v3 采用版为 512×326，角帽实测约16–18px；四边24完整保角并留出文字净区。
 * 五种底板同步使用这张薄框母版，不再让 Kingdom 的44与 A 档的80·72混用。
 */
export const PANEL_IRON_INSET = { left: 24, right: 24, top: 24, bottom: 24 } as const

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
    shard_n: 'ui/generated/items/item-shard-n-v1',
    shard_r: 'ui/generated/items/item-shard-r-v1',
    shard_sr: 'ui/generated/items/item-shard-sr-v1',
    shard_ssr: 'ui/generated/items/item-shard-ssr-v1',
    shield_peace: 'ui/generated/items/item-shield-peace-v1',
    charm_closed: 'ui/generated/items/item-charm-closed-v1',
    horn_rally: 'ui/generated/items/item-horn-rally-v1',
    book_exp_s: 'ui/generated/items/item-book-exp-s-v1',
    book_exp_m: 'ui/generated/items/item-book-exp-m-v1',
    ticket_gacha: 'ui/generated/items/item-ticket-gacha-v1',
    // A10（素材缺口清单 §六）：技能书与觉醒令此前在背包里是**无色占位方块**，
    // 碎片四档只有 SR/SSR 两张 —— N/R 蹭不到图（宁可空也不能指错）。
    // R/N 碎片由 SR 母图 HSV 派生（art-src/derive_rarity_variant.py），不另一次生成，
    // 因为两次独立生成必然画成两枚不同的碎片，四档并排时读起来是四个道具而不是一条稀有度阶梯。
    skillbook_main: 'ui/generated/items/item-skillbook-main-v1',
    skillbook_sub: 'ui/generated/items/item-skillbook-sub-v1',
    awaken_1: 'ui/generated/items/item-awaken-1-v1',
    awaken_2: 'ui/generated/items/item-awaken-2-v1',
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
  /**
   * A18：内城 15 种建筑的等距正稿（`art-src/generated/drafts/a18-masters`）。
   * 成员名就是 `building.json` 的行 id，所以 `buildingArtKey` 不需要第二张映射表 ——
   * 表里加一行而图没跟上时 `applyAnyIconSprite` 返回 false，格子退回图集小图标，
   * 而 `tests/ArtFamilies.test.ts` 会把"有行没有图"当场判红。
   */
  building: {
    main_city: 'ui/generated/buildings/building-main-city-v1',
    lumber_camp: 'ui/generated/buildings/building-lumber-camp-v1',
    quarry: 'ui/generated/buildings/building-quarry-v1',
    farm: 'ui/generated/buildings/building-farm-v1',
    iron_mine: 'ui/generated/buildings/building-iron-mine-v1',
    warehouse: 'ui/generated/buildings/building-warehouse-v1',
    barracks: 'ui/generated/buildings/building-barracks-v1',
    stable: 'ui/generated/buildings/building-stable-v1',
    archery_range: 'ui/generated/buildings/building-archery-range-v1',
    siege_workshop: 'ui/generated/buildings/building-siege-workshop-v1',
    drill_ground: 'ui/generated/buildings/building-drill-ground-v1',
    hospital: 'ui/generated/buildings/building-hospital-v1',
    wall: 'ui/generated/buildings/building-wall-v1',
    academy: 'ui/generated/buildings/building-academy-v1',
    embassy: 'ui/generated/buildings/building-embassy-v1',
  },
}

export function familyArtKey(family: ArtFamily, member: string): string {
  return `${family}:${member}`
}

/** 道具行 id → 图标键。eq_* 走装备族。A10/A11 之后 item.json 全部 42 行都有图；
 *  表里新加一行而映射没跟上时返回 null，行退回 Graphics 占位 —— 这条由 tests/ArtFamilies.test.ts 卡住。 */
export function itemArtKeyForConfig(configId: string): string | null {
  const equip = EQUIP_ICON_BY_CONFIG[configId]
  if (equip !== undefined) {
    return equip
  }
  const item = ITEM_ICON_BY_CONFIG[configId]
  return item ?? null
}

/**
 * 内城建筑正稿键：`building.json` 行 id 就是成员名。没有正稿的行返回 null，
 * 格子退回图集小图标（不是退回空白），所以"新加一行建筑"不会立刻在城景里开个洞。
 */
export function buildingArtKey(buildingConfigId: string): string | null {
  return FAMILY_ASSETS.building[buildingConfigId] === undefined
    ? null : `building:${buildingConfigId}`
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
  // A10：R/N 两档碎片的图是从 SR 母图调色派生的，不是独立生成（见 FAMILY_ASSETS.item 注释）。
  item_mat_hero_frag_r: 'item:shard_r',
  item_mat_hero_frag_n: 'item:shard_n',
  // A10：技能书主/副、觉醒令一/二段。主/副与一/二段各自共用一张母题、靠配色区分，
  // 背包 26px 下真正要分开的是"这是技能书"还是"这是经验书"，不是主副。
  item_hero_skillbook_main: 'item:skillbook_main',
  item_hero_skillbook_sub: 'item:skillbook_sub',
  item_hero_awaken_1: 'item:awaken_1',
  item_hero_awaken_2: 'item:awaken_2',
  // A11（素材缺口清单 §六）：资源箱/袋**不新增纹理** —— 背包行用启动图集里那五枚资源图标，
  // 数量与包装（1万 / 5千 / 袋）由行上的名称与数量 Label 表达，不烘焙进图片。
  item_res_wood_10k: 'icon:resources/wood',
  item_res_stone_10k: 'icon:resources/stone',
  item_res_iron_5k: 'icon:resources/iron',
  item_res_grain_20k: 'icon:resources/grain',
  item_gold_1000: 'icon:resources/gold',
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

// BEGIN GENERATED BUILDING_ART_GEOMETRY
/** 采用版实体(alpha>128)下缘到原canvas底的比例，只是贴图几何，不是玩法配表。
 * install_unified_v3.py --write-building-geometry现读；Sprite.trim=false保Creator裁边后接地。
 */
const BUILDING_ART_FOOT_RATIO: Readonly<Record<string, number>> = {
  academy: 0.08203125,
  archery_range: 0.16015625,
  barracks: 0.171875,
  drill_ground: 0.19140625,
  embassy: 0.1328125,
  farm: 0.1640625,
  hospital: 0.21484375,
  iron_mine: 0.14453125,
  lumber_camp: 0.21875,
  main_city: 0.048828125,
  quarry: 0.1328125,
  siege_workshop: 0.140625,
  stable: 0.1875,
  wall: 0.1640625,
  warehouse: 0.1640625,
}

export function buildingArtFootRatio(configId: string): number {
  return BUILDING_ART_FOOT_RATIO[configId] ?? 0
}
// END GENERATED BUILDING_ART_GEOMETRY
