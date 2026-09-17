/**
 * 由 tools/config-gen 依据 contract/config/*.json 的 fieldTypes 自动生成，禁止手改。
 * 要改表结构请改 JSON 后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * ⚠️ 标注「定点数」的字段是真实值 ×10000 的整数（见 core/FixedPoint），
 *   不要当成真实值直接显示或比较。
 *
 * ⚠️ 铁律 2：客户端不得用这些配置做任何影响数值或胜负的判断。
 *   配置在客户端只用于展示文案、图标索引与输入提示；服务端会独立重新校验。
 */

/** activity.activityActivityType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ActivityActivityType =
  | 'LOGIN_STREAK'
  | 'KILL_MONSTER'
  | 'JOIN_RALLY'
  | 'DONATE'
  | 'PVP_WIN'
  | 'UPGRADE_BUILDING'
  | 'HELP_SQUAD'

/** activity.activityConditionType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ActivityConditionType =
  | 'LOGIN_DAYS'
  | 'KILL_MONSTER_TOTAL'
  | 'JOIN_RALLY_TOTAL'
  | 'DONATE_TOTAL'
  | 'PVP_WIN_TOTAL'
  | 'UPGRADE_COUNT'
  | 'HELP_COUNT'

/**
 * 配置表 activity 的一行。
 * 活动表。B02 字段：活动类型/条件/奖励。durationDays 是开放时长，conditionValue 是达标门槛。
 *
 * 源表 version=1
 */
export interface ActivityCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 ActivityActivityType */
  activityType: ActivityActivityType
  /** 枚举，取值见 ActivityConditionType */
  conditionType: ActivityConditionType
  conditionValue: number
  durationDays: number
  rewardGold: number
  /** 外键，指向 item 表的 id */
  rewardItemId: string
  rewardItemCount: number
}

/**
 * 配置表 alliance_config 的一行。
 * 联盟配置表（B10 交付数据）。按联盟等级给出人数上限、领地容量、集结容量、科技加成。
 *
 * 源表 version=3
 */
export interface AllianceConfigCfg {
  /** 主键 */
  id: string
  allianceLevel: number
  memberCap: number
  unlockMainLevel: number
  unlockDayOffset: number
  territoryCap: number
  rallyCapacity: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  techCapBonus: number
  donationDailyCap: number
}

/** alliance_tech.allianceTechEffectAttr 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type AllianceTechEffectAttr =
  | 'UNIT_ATTACK'
  | 'UNIT_DEFENSE'
  | 'MARCH_SPEED'
  | 'LOAD_CAPACITY'
  | 'HOSPITAL_CAPACITY'
  | 'RALLY_CAPACITY'
  | 'BUILD_SPEED'
  | 'HELP_SPEED'

/**
 * 配置表 alliance_tech 的一行。
 * 联盟科技表。B02 字段：id/等级上限/消耗/效果。消耗单位为联盟捐献点数（B10 落地捐献系统）。
 *
 * 源表 version=2
 */
export interface AllianceTechCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 AllianceTechEffectAttr */
  effectAttr: AllianceTechEffectAttr
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  effectValue: number
  maxLevel: number
  costBaseDonation: number
}

/** bot_archetype.botArchetypePlayStyle 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type BotArchetypePlayStyle =
  | 'FARMER'
  | 'RAIDER'
  | 'BUILDER'
  | 'SOCIAL'
  | 'MIXED'

/**
 * 配置表 bot_archetype 的一行。
 * Bot 原型表（B11 交付数据）。本批次只定稿结构。每个 Bot 实例绑定一个原型，并在原型的区间内取自己的独立参数。
 *
 * 源表 version=4
 */
export interface BotArchetypeCfg {
  /** 主键 */
  id: string
  name: string
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  aggression: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  socialness: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  greed: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  powerFactor: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  growthFactorMin: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  growthFactorMax: number
  reactionDelayMinSec: number
  reactionDelayMaxSec: number
  helpDelayMinSec: number
  helpDelayMaxSec: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  suboptimalChanceMin: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  suboptimalChanceMax: number
  activeHoursPattern: string
  /** 枚举，取值见 BotArchetypePlayStyle */
  playStyle: BotArchetypePlayStyle
}

/** bot_chat.botChatScene 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type BotChatScene =
  | 'HELP_REQUEST'
  | 'RALLY_CALL'
  | 'ATTACKED'
  | 'VICTORY'
  | 'DEFEAT'
  | 'CHAT_IDLE'
  | 'ALLIANCE_JOIN'
  | 'TRADE'

/**
 * 配置表 bot_chat 的一行。
 * Bot 聊天语料表（B11 交付数据）。scene 决定这句话在什么场合出现，minCityLevel/maxCityLevel 限定说话者的等级区间。
 *
 * 源表 version=2
 */
export interface BotChatCfg {
  /** 主键 */
  id: string
  /** 枚举，取值见 BotChatScene */
  scene: BotChatScene
  text: string
  weight: number
  minCityLevel: number
  maxCityLevel?: number | null
}

/**
 * 配置表 bot_name 的一行。
 * Bot 名字池（B11 交付数据）。namePool 是字符串数组，按 weight 抽池、池内等概率取名。
 *
 * 源表 version=2
 */
export interface BotNameCfg {
  /** 主键 */
  id: string
  /** 任意结构 */
  namePool: unknown
  weight: number
  culture: string
}

/** bot_schedule.botScheduleActionType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type BotScheduleActionType =
  | 'LOGIN'
  | 'LOGOUT'
  | 'BUILD'
  | 'TRAIN'
  | 'GATHER'
  | 'ATTACK_MONSTER'
  | 'JOIN_RALLY'
  | 'CHAT'
  | 'DONATE'

/**
 * 配置表 bot_schedule 的一行。
 * Bot 作息表（B11 交付数据）。一行 = 某小时某行为的相对权重，24 小时 × 若干行为。
 *
 * 源表 version=3
 */
export interface BotScheduleCfg {
  /** 主键 */
  id: string
  hourOfDay: number
  /** 枚举，取值见 BotScheduleActionType */
  actionType: BotScheduleActionType
  weight: number
  isWeekendOnly: boolean
}

/** building.buildingType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type BuildingType =
  | 'CORE'
  | 'RESOURCE'
  | 'MILITARY'
  | 'SCIENCE'
  | 'DEFENSE'
  | 'UTILITY'

/**
 * 配置表 building 的一行。
 * 建筑表。每建筑一行，只给「基数」；每级的耗时/消耗/产出/战力由 game-core 的 Formula 套 curve 表的曲线算出（铁律 6：新增建筑只改配置表，不改代码）。timeBaseSec=0 表示沿用 curve.BUILDING_TIME 自带的基数（30 秒）。costBase* 是 1→2 级的消耗，按 BUILDING_COST（比率 1.22）递增；outputBasePerHour 是 1 级产量，按 BUILDING_OUTPUT（指数 1.08）递增；powerBase 是 1 级战力贡献，按 POWER_CONTRIB（指数 1.15）递增。
 *
 * 源表 version=3
 */
export interface BuildingCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 BuildingType */
  type: BuildingType
  maxLevel: number
  timeBaseSec: number
  costBaseWood: number
  costBaseStone: number
  costBaseIron: number
  costBaseGrain: number
  /** 外键，指向 resource 表的 id */
  outputResource?: string
  outputBasePerHour?: number | null
  capBase: number
  woundedCapBase: number
  powerBase: number
  /** 外键，指向 building 表的 id */
  requireBuilding?: string
  requireMainLevel: number
}

/**
 * 配置表 chapter 的一行。
 * 章节表：5 章、每章 10 关（B02 要求每章 10 关）。逐关数据在 stage 表，本表只放章级信息。
 *
 * **difficultyBase 是推导值不是手写值** = 本章第 1 关的敌方总兵力，由 global 表的 STAGE_DIFFICULTY_BASE / RATIO_EARLY / EARLY_THROUGH / RATIO_LATE 四个参数决定。
 *
 * 源表 version=4
 */
export interface ChapterCfg {
  /** 主键 */
  id: string
  name: string
  chapterNo: number
  stageCount: number
  requireMainLevel: number
  difficultyBase: number
  /** 外键，指向 chapter 表的 id */
  preChapter?: string
  rewardGold: number
  rewardHeroFragment: number
  rewardStamina: number
}

/**
 * 配置表 chest 的一行。
 * 宝箱本体表。一行 = 一个可批量开启的宝箱道具，记录它的保底阈值与单次批量上限。B04 §4「宝箱走随机（服务端 PRNG + seed），结果可复现」的配置来源。掉落内容在 chest_drop 表，按 chestId 关联。
 *
 * 源表 version=1
 */
export interface ChestCfg {
  /** 主键 */
  id: string
  name: string
  pityThreshold: number
  maxBatchCount: number
}

/** chest_drop.chestDropRewardType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ChestDropRewardType =
  | 'RESOURCE'
  | 'ITEM'
  | 'HERO_FRAGMENT'
  | 'HERO'
  | 'STAMINA'
  | 'PRIVILEGE'

/**
 * 配置表 chest_drop 的一行。
 * 宝箱掉落表。一行 = 一条掉落项：权重 weight、产出 rewardType/rewardId、单次数量 count、是否算稀有 rare。同 chestId 的所有行构成一个掉落组，按权重抽取。 【2026-09-12 同步：rewardType 枚举补 HERO】bag 协议新增整卡武将奖励类型后，本表的 ENUM 声明必须同步（ContractEnumParityTest 三处一致），否则「配置里能写、下发时翻译不出来」。**没有新增任何行**：宝箱产出不变，只是这张表从此能表达整卡。
 *
 * 源表 version=1
 */
export interface ChestDropCfg {
  /** 主键 */
  id: string
  /** 外键，指向 chest 表的 id */
  chestId: string
  /** 枚举，取值见 ChestDropRewardType */
  rewardType: ChestDropRewardType
  rewardId: string
  weight: number
  count: number
  rare: boolean
}

/** curve.curveKind 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type CurveKind =
  | 'GEOMETRIC'
  | 'POWER'

/** curve.curveUnit 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type CurveUnit =
  | 'SECOND'
  | 'FIXED'
  | 'FIXED_PER_HOUR'
  | 'COUNT'

/**
 * 配置表 curve 的一行。
 * 成长曲线参数表（B00 数值速查「成长曲线」的唯一落地处）。运行期由 game-core 的 Formula 读取本表套公式，代码中不得出现任何曲线常量（铁律 1、跨语言一致性第 4 条）。base=0 表示基数由具体业务表逐行提供，本表只给比率与指数。小数一律写成十进制字符串，禁止 JSON number（见 contract/README.md）。
 *
 * 源表 version=2
 */
export interface CurveCfg {
  /** 主键 */
  id: string
  /** 枚举，取值见 CurveKind */
  kind: CurveKind
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  base: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  ratio: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  exponent: number
  /** 枚举，取值见 CurveUnit */
  unit: CurveUnit
  formula: string
}

/** equip.equipSlot 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type EquipSlot =
  | 'WEAPON'
  | 'ARMOR'
  | 'MOUNT'
  | 'ACCESSORY'

/** equip.equipRarity 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type EquipRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 配置表 equip 的一行。
 * 武将装备表。一行 = 一件装备：槽位、稀有度、三维固定加成、需求武将等级、所属套装、强化上限。套装效果在 equip_set 表，强化价格与斜率在 curve 表。
 *
 * 源表 version=1
 */
export interface EquipCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 EquipSlot */
  slot: EquipSlot
  /** 枚举，取值见 EquipRarity */
  rarity: EquipRarity
  might: number
  command: number
  wisdom: number
  requireLevel: number
  /** 外键，指向 equip_set 表的 id */
  setId?: string
  forgeMax: number
}

/** equip_set.equipSetPieces2Attr 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type EquipSetPieces2Attr =
  | 'MIGHT'
  | 'COMMAND'
  | 'WISDOM'

/** equip_set.equipSetPieces4Attr 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type EquipSetPieces4Attr =
  | 'MIGHT'
  | 'COMMAND'
  | 'WISDOM'

/**
 * 配置表 equip_set 的一行。
 * 装备套装表。一行 = 一个套装，给出 2 件套与 4 件套的百分比加成。装备本体在 equip 表，用 setId 关联。
 *
 * 源表 version=1
 */
export interface EquipSetCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 EquipSetPieces2Attr */
  pieces2Attr: EquipSetPieces2Attr
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  pieces2Ratio: number
  /** 枚举，取值见 EquipSetPieces4Attr */
  pieces4Attr: EquipSetPieces4Attr
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  pieces4Ratio: number
}

/** gacha.gachaPoolType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type GachaPoolType =
  | 'STANDARD'
  | 'LIMITED'
  | 'NEWBIE'

/**
 * 配置表 gacha 的一行。
 * 抽卡表。B02 字段：池子/概率/保底次数/UP 内容，并强制包含概率公示字段 disclosureText。四档概率之和必须精确等于定点 1.0（10000），由单测强制校验。
 *
 * 源表 version=3
 */
export interface GachaCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 GachaPoolType */
  poolType: GachaPoolType
  /** 外键，指向 item 表的 id */
  costItemId?: string
  /** 外键，指向 resource 表的 id */
  costResource?: string
  costCount: number
  lifetimeLimit: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  ssrChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  ssrBaseChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  srChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  srBaseChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  rChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  rBaseChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  nChance: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  nBaseChance: number
  ssrPity: number
  srPity: number
  /** 外键，指向 hero 表的 id */
  upHeroId?: string
  disclosureText: string
}

/** gift.giftTrigger 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type GiftTrigger =
  | 'STUCK_STAGE'
  | 'BUILDING_DONE'
  | 'BATTLE_LOST'

/**
 * 配置表 gift 的一行。
 * 礼包弹窗表。一行 = 一个礼包：什么时候推、推的是哪一档商品、每天能买几次、弹出后多久内有效。发货内容与价格不在本表（在 product_reward 与 pay_product）。
 *
 * 源表 version=1
 */
export interface GiftCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 GiftTrigger */
  trigger: GiftTrigger
  /** 外键，指向 pay_product 表的 id */
  productId: string
  limitCount: number
  offerTtlMinutes: number
}

/** guide.guideTrigger 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type GuideTrigger =
  | 'PANEL_OPEN'
  | 'STATE_REACHED'

/** guide.guideJudge 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type GuideJudge =
  | 'QUEST_DONE'
  | 'QUEST_CLAIMED'

/**
 * 配置表 guide 的一行。
 * 新手引导脚本表（B18）。7 步与主线 quest_main_01~quest_main_06 逐条对齐：引导走真实主线，不另造一条链。
 * 本表是**步骤序列的唯一出处**（改脚本不改包 = 硬要求），客户端全仓不得出现任何步骤文案（验收 1 的静态检查盯的就是这个）。
 *
 * 源表 version=1
 */
export interface GuideCfg {
  /** 主键 */
  id: string
  name: string
  stepIndex: number
  /** 枚举，取值见 GuideTrigger */
  trigger: GuideTrigger
  panelKey?: string
  highlightPath?: string
  maskArea: string
  text: string
  skippable: boolean
  /** 枚举，取值见 GuideJudge */
  judge: GuideJudge
  /** 外键，指向 quest 表的 id */
  judgeTarget: string
}

/** hero.heroRarity 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type HeroRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 配置表 hero 的一行。
 * 武将表。B02 要求字段：id/名称/稀有度/初始三维/成长率/主技能/副技能/缘分/觉醒上限。稀有度四档 N/R/SR/SSR。三维为 武力(might)/统率(command)/智力(wisdom)。全部名称为原创，与任何既有作品无对应关系（B00 版权合规要求）。
 *
 * 源表 version=1
 */
export interface HeroCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 HeroRarity */
  rarity: HeroRarity
  might: number
  command: number
  wisdom: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  growthRate: number
  maxLevel: number
  /** 外键，指向 skill 表的 id */
  mainSkill: string
  /** 外键，指向 skill 表的 id */
  subSkill: string
  /** 外键，指向 hero 表的 id */
  bondWith?: string
  awakenMax: number
}

/**
 * 配置表 hero_rarity 的一行。
 * 武将稀有度经济表。一行 = 一个稀有度档：重复武将转多少碎片、合成需要多少碎片、每升一星需要多少碎片。
 *
 * 源表 version=1
 */
export interface HeroRarityCfg {
  /** 主键 */
  id: string
  name: string
  dupFragment: number
  composeFragment: number
  starUpFragment: number
}

/** item.itemType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ItemType =
  | 'SPEEDUP'
  | 'RESOURCE'
  | 'CHEST'
  | 'MATERIAL'
  | 'BUFF'
  | 'EQUIP'

/** item.itemRarity 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ItemRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/** item.itemEffectKind 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ItemEffectKind =
  | 'REDUCE_BUILD_SECONDS'
  | 'REDUCE_TRAIN_SECONDS'
  | 'REDUCE_RESEARCH_SECONDS'
  | 'GRANT_RESOURCE'
  | 'GRANT_RANDOM_RESOURCE'
  | 'OPEN_GACHA'
  | 'COMPOSE_HERO'
  | 'GRANT_SHIELD'
  | 'GRANT_RALLY_BONUS'
  | 'GRANT_HERO_EXP'
  | 'UP_HERO_SKILL'
  | 'AWAKEN_HERO'
  | 'EQUIP_HERO'
  | 'CLOSE_CITY'

/**
 * 配置表 item 的一行。
 * 道具表。B02 字段：id/名称/类型/使用效果/堆叠上限/是否可出售。类型五种：加速/资源/宝箱/材料/增益（B02 原文的「加速/资源/宝箱/材料」加上护盾与集结令所需的 BUFF）。
 *
 * 源表 version=7
 */
export interface ItemCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 ItemType */
  type: ItemType
  /** 枚举，取值见 ItemRarity */
  rarity: ItemRarity
  /** 枚举，取值见 ItemEffectKind */
  effectKind: ItemEffectKind
  effectValue: number
  effectTarget?: string
  stackMax: number
  sellable: boolean
  sellPriceGold: number
  obtainFrom?: string
}

/**
 * 配置表 mapmonster 的一行。
 * 野外怪物表，等级 1~50（B02 要求）。每行一个等级。战力与掉落按 1.25^(level-1) 递增（curve.CHAPTER_DIFFICULTY）。**与关卡共用「难度单位」（敌方总兵力）但不共用「曲线」**：野怪是开放世界的阶梯，玩家按自己的节奏接近（打不过 LV30 就去打 LV20，没有任何要求说首日必须能打野怪 LV30）；关卡是有首日底线的线性推进，前三章必须让零氪新玩家第一天走完（B09 §二），所以关卡前 30 关走 1.12 的缓坡。两者终点仍然对齐：LV50 野怪 560,520 兵 ≈ 第 50 关。原先这里写的是「共用同一条难度标尺」，那句话把两个需求不同的系统强行绑在一起，正是「第三章成为一堵墙」这个矛盾的来源。兵种构成、阶级、体力消耗随等级分段演化，分段理由写在每行 compositionNote 里。
 *
 * 源表 version=2
 */
export interface MapmonsterCfg {
  /** 主键 */
  id: string
  name: string
  level: number
  unitTier: number
  infantryCount: number
  cavalryCount: number
  archerCount: number
  siegeCount: number
  power: number
  staminaCost: number
  dropWood: number
  dropStone: number
  dropIron: number
  dropGrain: number
  dropGold: number
  compositionNote?: string
}

/** match_rule.matchRuleKind 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type MatchRuleKind =
  | 'PROTECTION'
  | 'SCENARIO'

/**
 * 配置表 match_rule 的一行。
 * 战力匹配与保护规则表（B08 依赖）。两类行：PROTECTION 是保护规则，SCENARIO 是 PVP 场景。本表不重复写任何数值，全部用 *Param 字段外键引用 global 表的参数 id —— 同一个数字只能有一个家，两处都写一定会不同步。
 *
 * 源表 version=2
 */
export interface MatchRuleCfg {
  /** 主键 */
  id: string
  /** 枚举，取值见 MatchRuleKind */
  kind: MatchRuleKind
  triggerCond: string
  /** 外键，指向 global 表的 id */
  durationParam?: string
  /** 外键，指向 global 表的 id */
  triggerCountParam?: string
  /** 外键，指向 global 表的 id */
  powerMinParam?: string
  /** 外键，指向 global 表的 id */
  powerMaxParam?: string
  /** 外键，指向 global 表的 id */
  bonusParam?: string
  blocksActiveAttack: boolean
  /** 外键，指向 global 表的 id */
  windowParam?: string
  /** 外键，指向 global 表的 id */
  durationParam2?: string
  /** 外键，指向 global 表的 id */
  triggerCountParam2?: string
}

/**
 * 配置表 nation_config 的一行。
 * 国家配置表（B13 交付数据）。按国家等级给出人数上限、官职数量、国库容量、国策槽位、宣战冷却。
 *
 * 源表 version=2
 */
export interface NationConfigCfg {
  /** 主键 */
  id: string
  nationLevel: number
  memberCap: number
  unlockMainLevel: number
  unlockDayOffset: number
  officeCount: number
  treasuryCap: number
  policySlotCount: number
  warCooldownHours: number
}

/** nation_tech.nationTechSchool 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type NationTechSchool =
  | 'AGRICULTURE'
  | 'MILITARY'
  | 'COMMERCE'
  | 'FORTIFICATION'

/** nation_tech.nationTechEffectAttr 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type NationTechEffectAttr =
  | 'GRAIN_OUTPUT'
  | 'TRAIN_SPEED'
  | 'MARCH_SPEED'
  | 'BUILD_SPEED'

/**
 * 配置表 nation_tech 的一行。
 * 国家科技表（B20 块③）。与个人科技（tech.json）的关系：同一套 effectAttr 词汇、同一条 costCurve 曲线族，但**出资方与承载方都不同** —— 钱出自国库（Nation.Sink.NATIONAL_TECH 核销），等级记在国家上而不是个人身上，生效范围是全国成员。每学派一行、共 4 行（§五③）。
 *
 * 源表 version=1
 */
export interface NationTechCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 NationTechSchool */
  school: NationTechSchool
  /** 枚举，取值见 NationTechEffectAttr */
  effectAttr: NationTechEffectAttr
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  effectValue: number
  maxLevel: number
  costBaseTreasury: number
  /** 外键，指向 curve 表的 id */
  costCurve: string
  requireNationLevel: number
}

/** pay_product.payProductKind 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type PayProductKind =
  | 'MONTHLY_CARD'
  | 'GROWTH_FUND'
  | 'FIRST_CHARGE'
  | 'GIFT'

/** pay_product.payProductGrantOccasion 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type PayProductGrantOccasion =
  | 'ON_PURCHASE'
  | 'DAILY'
  | 'TIER'

/**
 * 配置表 pay_product 的一行。
 * 付费商品表（B19 §一.1）。三行 = 支付域唯一在卖的三类商品：月卡 / 成长基金 / 首充。
 * 本表只放**结构与权益**；价格不放这里（见 designNote 第 1 条：价格住在 global 的 PRODUCT_*_CENTS，本表用 priceCentsParam 指它的名字）。
 *
 * 源表 version=3
 */
export interface PayProductCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 PayProductKind */
  kind: PayProductKind
  priceCentsParam: string
  /** 枚举，取值见 PayProductGrantOccasion */
  grantOccasion: PayProductGrantOccasion
  durationDays?: number | null
  adFree: boolean
  extraQueues: number
  heroChoices?: string
}

/** product_reward.productRewardRewardType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ProductRewardRewardType =
  | 'RESOURCE'
  | 'ITEM'

/**
 * 配置表 product_reward 的一行。
 * 付费商品的发货内容（B19 §一.1）。一个商品多行奖励：月卡的日包三行、首充的金币一行、成长基金的六档各一行。
 * 价格与「按什么节奏领」在 `pay_product`，本表只回答「一次发的是哪些东西、各多少个」。
 *
 * 源表 version=2
 */
export interface ProductRewardCfg {
  /** 主键 */
  id: string
  /** 外键，指向 pay_product 表的 id */
  productId: string
  requireMainLevel?: number | null
  /** 枚举，取值见 ProductRewardRewardType */
  rewardType: ProductRewardRewardType
  rewardId: string
  count: number
}

/** quest.questQuestType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type QuestQuestType =
  | 'MAIN'
  | 'SIDE'
  | 'DAILY'
  | 'WEEKLY'
  | 'ACHIEVEMENT'

/** quest.questGoalType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type QuestGoalType =
  | 'UPGRADE_BUILDING'
  | 'REACH_RESOURCE'
  | 'TRAIN_UNIT'
  | 'KILL_MONSTER'
  | 'GACHA_PULL'
  | 'JOIN_SQUAD'
  | 'JOIN_ALLIANCE'
  | 'RESEARCH_TECH'
  | 'CLEAR_CHAPTER'
  | 'CLEAR_STAGE'
  | 'GATHER_RESOURCE'
  | 'HELP_SQUAD'
  | 'JOIN_RALLY'

/** quest.questRewardFragmentRarity 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type QuestRewardFragmentRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 配置表 quest 的一行。
 * 任务表。B02 字段：目标类型/目标值/奖励，四类主/支/日/周常。goalTarget 指向具体配置 id（建筑/兵种/野怪/章节/卡池）；无特定目标的任务（如「打野 3 次」不限定哪只怪）直接省略该字段，而不是填空串——空串是一个「看起来有值但没值」的状态，会让读取方分不清是漏填还是本就不需要。
 *
 * 源表 version=3
 */
export interface QuestCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 QuestQuestType */
  questType: QuestQuestType
  /** 枚举，取值见 QuestGoalType */
  goalType: QuestGoalType
  goalTarget?: string
  goalValue: number
  rewardGold: number
  rewardWood: number
  rewardIron: number
  rewardGrain: number
  rewardHeroFragment: number
  /** 枚举，取值见 QuestRewardFragmentRarity */
  rewardFragmentRarity?: QuestRewardFragmentRarity
  /** 外键，指向 hero 表的 id */
  rewardHeroId?: string
  rewardHeroChoices?: string
  /** 外键，指向 quest 表的 id */
  preQuest?: string
}

/** resource.resourceKind 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ResourceKind =
  | 'BASE'
  | 'CURRENCY'
  | 'STAMINA'

/**
 * 配置表 resource 的一行。
 * 资源定义表。B01 最小集：只定义 5 种资源的初始值、容量与底产。perHour 在 B03 起改为由建筑聚合计算，basePerHour 仅作为无任何产出建筑时的兜底底产。
 *
 * v3 新增 STAMINA（体力）与第三个 kind：体力是 B09 打野与关卡的消耗闸门。把它做成资源行而不是独立系统，是为了复用已经修好的惰性结算 —— 「每 X 分钟恢复 1 点」「溢出不超上限」「不用定时器给全体玩家重置」这三条 B09 要求，在资源模型里分别对应 basePerHour、cap 截断、lastSettle 时间差，全部是现成的。
 *
 * 源表 version=3
 */
export interface ResourceCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 ResourceKind */
  kind: ResourceKind
  initAmount: number
  initCap: number
  basePerHour: number
}

/** role_permission.rolePermissionScope 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type RolePermissionScope =
  | 'SQUAD'
  | 'ALLIANCE'
  | 'NATION'

/**
 * 配置表 role_permission 的一行。
 * 角色权限矩阵（B10 / B13 依赖）。一行 = 一个（组织范围, 权限位）。三个角色层级 Leader / Officer / Member 在 squad / alliance / nation 三个范围内语义一致。allow* 字段用 BOOL。
 *
 * 源表 version=3
 */
export interface RolePermissionCfg {
  /** 主键 */
  id: string
  /** 枚举，取值见 RolePermissionScope */
  scope: RolePermissionScope
  /** 主键型标识符，只含字母数字下划线 */
  permission: string
  permissionName: string
  allowLeader: boolean
  allowOfficer: boolean
  allowMember: boolean
}

/** season.seasonRulePhase 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type SeasonRulePhase =
  | 'PREPARE'
  | 'EXPAND'
  | 'CAPITAL_WAR'
  | 'SETTLE'

/** season.seasonGoalType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type SeasonGoalType =
  | 'REACH_MAIN_LEVEL'
  | 'JOIN_ALLIANCE'
  | 'KILL_MONSTER_TOTAL'
  | 'CAPTURE_TERRITORY'
  | 'SEASON_RANK'

/**
 * 配置表 season 的一行。
 * 赛季表。B02 字段：阶段时间轴/目标/结算奖励。一个赛季 = 5 阶段共 45 天。startDayOffset 是相对开服的天数偏移。
 *
 * 源表 version=1
 */
export interface SeasonCfg {
  /** 主键 */
  id: string
  name: string
  phaseNo: number
  /** 枚举，取值见 SeasonRulePhase */
  rulePhase: SeasonRulePhase
  startDayOffset: number
  durationDays: number
  /** 枚举，取值见 SeasonGoalType */
  goalType: SeasonGoalType
  goalValue: number
  rewardGold: number
  rewardSeasonCoin: number
  rewardHeroFragment: number
}

/** shop.shopPriceCurrency 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ShopPriceCurrency =
  | 'GOLD'
  | 'ALLIANCE_COIN'
  | 'SQUAD_COIN'
  | 'SEASON_COIN'

/** shop.shopRefreshType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type ShopRefreshType =
  | 'NONE'
  | 'DAILY'
  | 'WEEKLY'

/**
 * 配置表 shop 的一行。
 * 商店表。B02 字段：商品/价格/限购/上架条件。四种货币：金币(可充值)、联盟币(捐献获得)、小队币(互助获得)、赛季币(赛季参与获得)。
 *
 * 源表 version=1
 */
export interface ShopCfg {
  /** 主键 */
  id: string
  name: string
  /** 外键，指向 item 表的 id */
  itemId: string
  /** 枚举，取值见 ShopPriceCurrency */
  priceCurrency: ShopPriceCurrency
  price: number
  limitCount: number
  /** 枚举，取值见 ShopRefreshType */
  refreshType: ShopRefreshType
  requireMainLevel: number
}

/** skill.skillTrigger 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type SkillTrigger =
  | 'ROUND_START'
  | 'EVERY_ROUND'
  | 'ON_HIT'
  | 'ON_DEATH'

/** skill.skillEffect 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type SkillEffect =
  | 'DAMAGE'
  | 'HEAL'
  | 'BUFF_ATK'
  | 'BUFF_DEF'
  | 'DEBUFF_ATK'
  | 'DEBUFF_DEF'
  | 'SKIP_TURN'

/**
 * 配置表 skill 的一行。
 * 武将技能表。B02 要求字段：id/名称/触发时机/触发概率/效果类型/数值/持续回合。触发时机四种来自 B02 原文（回合开始/每回合/受击/死亡）。所有概率与数值都是定点小数（×10000 后存 long），配置里写成十进制字符串。技能由 hero 表的 mainSkill/subSkill 外键引用。
 *
 * 源表 version=1
 */
export interface SkillCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 SkillTrigger */
  trigger: SkillTrigger
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  chance: number
  /** 枚举，取值见 SkillEffect */
  effect: SkillEffect
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  value: number
  durationRounds: number
}

/**
 * 配置表 squad_config 的一行。
 * 小队配置表（B10 交付数据）。按小队等级给出人数上限、集结容量、互助加成。
 *
 * 源表 version=3
 */
export interface SquadConfigCfg {
  /** 主键 */
  id: string
  squadLevel: number
  memberCap: number
  unlockMainLevel: number
  unlockDayOffset: number
  rallyCapacity: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  helpSpeedBonus: number
  shopUnlock: boolean
}

/** stage.stageBossMechanic 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type StageBossMechanic =
  | 'NONE'
  | 'REINFORCEMENT'
  | 'SHIELD_PHASE'
  | 'COUNTER_STRIKE'

/** stage.stageUnitRestriction 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type StageUnitRestriction =
  | 'NONE'
  | 'NO_SIEGE'
  | 'CAVALRY_ONLY'
  | 'RANGED_ONLY'

/**
 * 配置表 stage 的一行。
 * 章节关卡表：5 章 × 10 关 = 50 行（B09 §4）。本表由 chapter/global 表的难度口径推导生成，逐关数值不要手改 —— 要改请改 global 表的四个 STAGE_DIFFICULTY_* 参数，否则曲线会被改出无法解释的突起，而「零氪首日可通前三章」这条底线会静默失效。
 *
 * 源表 version=2
 */
export interface StageCfg {
  /** 主键 */
  id: string
  /** 外键，指向 chapter 表的 id */
  chapterId: string
  stageNo: number
  name: string
  enemyTier: number
  enemyInfantry: number
  enemyCavalry: number
  enemyArcher: number
  enemySiege: number
  staminaCost: number
  checkpoint: boolean
  /** 枚举，取值见 StageBossMechanic */
  bossMechanic: StageBossMechanic
  roundLimit: number
  /** 枚举，取值见 StageUnitRestriction */
  unitRestriction: StageUnitRestriction
  rewardGold: number
}

/** tech.techSchool 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type TechSchool =
  | 'AGRICULTURE'
  | 'MILITARY'
  | 'COMMERCE'
  | 'FORTIFICATION'

/** tech.techEffectAttr 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type TechEffectAttr =
  | 'WOOD_OUTPUT'
  | 'STONE_OUTPUT'
  | 'IRON_OUTPUT'
  | 'GRAIN_OUTPUT'
  | 'UNIT_ATTACK'
  | 'UNIT_DEFENSE'
  | 'MARCH_SPEED'
  | 'TRAIN_SPEED'
  | 'BUILD_SPEED'
  | 'HOSPITAL_CAPACITY'
  | 'LOAD_CAPACITY'

/**
 * 配置表 tech 的一行。
 * 科技表。B02 字段：id/名称/所属学派/等级上限/消耗/效果。四学派对应 B00 的四种资源与三条玩法线（内政/军事/经济/工事）。两条曲线分家：每行消耗按 costCurve 指向的消耗曲线递增（首版全表 BUILDING_COST，比率 1.22），每级时长走 curve 表的 TECH_TIME（比率 1.28 全项目最陡，基数 13 秒由 `tools/calibrate-tech-time.mjs` 量出）；两者不可互换 —— TECH_TIME 量纲是 SECOND。effectValue 是每级增益（定点小数）。
 *
 * 源表 version=1
 */
export interface TechCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 TechSchool */
  school: TechSchool
  maxLevel: number
  /** 枚举，取值见 TechEffectAttr */
  effectAttr: TechEffectAttr
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  effectValue: number
  costBaseWood: number
  costBaseStone: number
  costBaseIron: number
  costBaseGrain: number
  /** 外键，指向 curve 表的 id */
  costCurve: string
  requireAcademyLevel: number
}

/** unit.unitType 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type UnitType =
  | 'INFANTRY'
  | 'CAVALRY'
  | 'ARCHER'
  | 'SIEGE'

/**
 * 配置表 unit 的一行。
 * 兵种表。四兵种 × T1~T5 共 20 行（用户确认阶级上限为 T5）。T2~T5 的攻击/防御/生命/训练耗时/训练消耗全部由 T1 基数按 curve.UNIT_STRENGTH 的比率 1.12^(tier-1) 推导后取整，不是独立拍出来的数 —— 改 T1 基数或改 1.12 就等于改全部五个阶级。速度与负载是兵种身份特征，不随阶级变化（T5 弓兵不该比 T1 弓兵跑得快，那会破坏「骑兵=机动」的辨识度）。克制关系不在本表，见 unit_counter 表。
 *
 * 源表 version=2
 */
export interface UnitCfg {
  /** 主键 */
  id: string
  name: string
  /** 枚举，取值见 UnitType */
  type: UnitType
  tier: number
  attack: number
  defense: number
  hp: number
  speed: number
  load: number
  trainTimeSec: number
  trainCostWood: number
  trainCostIron: number
  trainCostGrain: number
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  vsBuildingBonus: number
  /** 外键，指向 building 表的 id */
  unlockBuilding: string
  unlockBuildingLevel: number
}

/** unit_counter.unitCounterAttacker 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type UnitCounterAttacker =
  | 'INFANTRY'
  | 'CAVALRY'
  | 'ARCHER'
  | 'SIEGE'

/** unit_counter.unitCounterDefender 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */
export type UnitCounterDefender =
  | 'INFANTRY'
  | 'CAVALRY'
  | 'ARCHER'
  | 'SIEGE'
  | 'WALL'
  | 'TRAP'

/**
 * 配置表 unit_counter 的一行。
 * 兵种克制矩阵。B00 的「兵种克制」表是「谁克谁」的自然语言描述，直接塞进 unit 行会变成逗号分隔字符串，既没有类型安全也无法校验外键，所以拆成独立的关系表：一行 = 一条有向克制关系。加成与减益都是定点数，来自 global 表的 COUNTER_BONUS(+25%) 与 COUNTER_PENALTY(-20%)，本表允许逐对覆盖（留空则用全局默认）。
 *
 * 源表 version=2
 */
export interface UnitCounterCfg {
  /** 主键 */
  id: string
  /** 枚举，取值见 UnitCounterAttacker */
  attacker: UnitCounterAttacker
  /** 枚举，取值见 UnitCounterDefender */
  defender: UnitCounterDefender
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  bonusFixed?: number | null
  /** 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double */
  penaltyFixed?: number | null
}
