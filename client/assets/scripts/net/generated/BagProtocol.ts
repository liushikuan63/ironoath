/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 资源类型枚举。取值必须与 contract/config/resource.json 的 rows[].id 完全一致（CI 校验）。
 *
 * 取值与配置表 resource 的 id 集合强制一致，由生成器在 CI 中校验。
 */
export type ResourceType =
  | 'WOOD'
  | 'STONE'
  | 'IRON'
  | 'GRAIN'
  | 'GOLD'
  | 'STAMINA'

/**
 * 道具稀有度。取值与 item 表、hero 表的 rarity 列一致。<b>声明顺序就是稀有度升序（N→SSR）</b>：服务端的背包排序键直接取生成枚举的 ordinal 反序（越稀有 ordinal 越大 ⇒ 排越前），所以这个顺序本身就是口径，改顺序等于改排序规则，必须双端同时重新生成。
 */
export type ItemRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 奖励类型枚举。取值必须与 com.ironoath.core.reward.RewardType 一一对应（由 RewardTypeContractTest 断言，两边漂移会让服务端下发的字符串在客户端解析不出来）。没有对应的配置表，因此不能用 x-enum-source 自动校验。HERO 是整卡武将（写进武将册），HERO_FRAGMENT 是碎片（进背包），两者下游不同，不可互替。
 */
export type RewardType =
  | 'RESOURCE'
  | 'ITEM'
  | 'HERO_FRAGMENT'
  | 'HERO'
  | 'STAMINA'
  | 'PRIVILEGE'

/**
 * 资源数量条目。用数组而不是 Map 是为了让客户端能保持配置表顺序展示。
 */
export interface ResourceAmount {
  type: ResourceType
  amount: number
}

/**
 * 产出明细的一行（B04 §2 转化关键 UI）。isPercent=true 时客户端显示成「+120 (+10%)」，否则显示成「+600」。所有行的 amount 之和必须精确等于该资源的实际每小时产量（B04 验收 5，误差 0）——这个不变量由服务端 ResourceOutputCalculator.Breakdown 在构造期强制。
 */
export interface OutputBreak {
  /** 来源标签，直接展示，如「农田 Lv8」「科技加成」 */
  source: string
  /** 该行贡献的每小时产量 */
  amount: number
  /** 是否为百分比加成行 */
  isPercent: boolean
  /** 百分比值（定点 ×10000），仅 isPercent=true 时有意义 */
  percentFixed: number | null
}

/**
 * 单一资源的明细：当前状态 + 产出分解 + 是否满仓。
 */
export interface ResourceDetail {
  type: ResourceType
  current: number
  cap: number
  /** 受保护不可掠夺量（B04 验收 6） */
  protectedAmount: number
  /** 实际每小时产量，必须等于 breakdown 各行之和 */
  perHour: number
  lastSettle: number
  /** 是否已满仓停产。UI 据此显示红色「已满」警告（B04 验收 1/11） */
  full: boolean
  breakdown: OutputBreak[]
}

/**
 * GET /resource/detail 响应体。
 */
export interface ResourceDetailResp {
  resources: ResourceDetail[]
  serverNow: number
}

/**
 * 背包中一个道具条目（B04 §3）。sortKey 由服务端算好下发，客户端不再自行排序——排序规则（稀有度>类型>数量）属于业务逻辑，放客户端会导致双端排序不一致。
 *
 * **2026-09-13 裁决：`sellable` / `sellPriceGold` 不再下发**（原先这两个字段在这里、客户端据此渲染「可出售」，而服务端没有 /bag/sell 端点、B04 整篇没有出售规则 ⇒ 下发一份「点了只会失败」的数据，与 #18/#19 的「卖了没用」同族）。item 表里那两列**保留**（那是将来定规则的数据），规则与端点落地后再随协议回来。
 */
export interface BagItem {
  /** item 表的行 id */
  itemId: string
  /** 中文名，来自配置表，客户端不得自行翻译 */
  name: string
  /** 道具类型，用于分页：SPEEDUP / RESOURCE / CHEST / MATERIAL / BUFF */
  type: string
  rarity: ItemRarity
  /** 来源提示（B04 §3：长按显示「来自：第七章宝箱」，让玩家知道去哪再刷）。来自 item 表，未配置时为空串。 */
  obtainFrom: string | null
  count: number
  stackMax: number
  /** 服务端算好的排序键（稀有度>类型>数量），客户端按它升序展示即可 */
  sortKey: number
  /** 这道具用下去是干什么的（item 表 effectKind 列的原值，如 GRANT_HERO_EXP / AWAKEN_HERO / UP_HERO_SKILL / COMPOSE_HERO）。 **为什么必须下发**：`type` 太粗 —— 三本经验书是 MATERIAL，而 MATERIAL 底下还有 11 种别的材料。「哪个道具能喂武将」这种问题不该由客户端按 id 硬编码回答（与 #255「名字不许客户端自己拼」同一根因，只是这次缺的是**判别字段**而不是名字）。武将养成的四个选择弹层按这一列筛候选，筛错的下场只是一次被拒（服务端仍各自校验）。 */
  effectKind: string
}

/**
 * GET /bag/list 响应体。items 已按 sortKey 升序排好。
 */
export interface BagListResp {
  items: BagItem[]
  capacityUsed: number
  capacityMax: number
}

/**
 * POST /item/use 请求体。加速类道具必须给 targetId（B04 §4：弹出可选目标，选择后应用）。
 */
export interface ItemUseReq {
  requestId: string
  itemId: string
  /** 一次使用几个。资源类支持一次开 N 个（B04 §4） */
  count: number
  /** 加速类道具的目标建筑实例 id */
  targetId: string | null
}

/**
 * POST /item/use 响应体。granted 是实际入账的部分，overflow 是装不下（资源满仓 / 背包满格）而转邮件的部分 —— 两者都要回，否则满仓时用资源箱的玩家只看到 granted 为空，从他视角这就是一次静默失败（B04 禁止项：不得静默吞掉）。
 */
export interface ItemUseResp {
  /** 实际消耗的道具数量 */
  consumed: number
  /** 使用后实际入账的资源 */
  granted: ResourceAmount[]
  /** 装不下而转邮件的资源（B04 验收 2） */
  overflow: ResourceAmount[]
  /** 溢出转邮件的邮件 id，无溢出时为 null */
  mailId: string | null
  /** 加速类道具实际提前的秒数 */
  reducedSeconds: number | null
  /** 免战类道具使用后的停战到期时刻（服务端毫秒）。null = 这次使用与免战无关。 必须下发而不是让客户端自己按 effectValue 推算：多张牌叠加时取的是「延长到多晚」，而延长规则在服务端（只延长不缩短），客户端算出来的时刻会与真实值不一致 —— 面板显示还剩 3 小时、实际 24 小时，玩家会据此决定要不要再买一张。 */
  peaceUntil: number | null
  serverNow: number
}

/**
 * 一条奖励。id 的含义由 type 决定：RESOURCE 时是资源类型（WOOD/GOLD...），ITEM 时是 item 表的行 id，HERO 与 HERO_FRAGMENT 时是 hero 表的行 id。name 由服务端从配置表解析后下发，客户端不得自行翻译（与 BagItem.name 同一口径）。
 */
export interface RewardItemView {
  type: RewardType
  id: string
  /** 聚合后的数量。批量开箱 100 次抽到 30 次木材时这里是 30 次的总量，不是 30 行。 */
  count: number
  name: string
}

/**
 * POST /item/openBatch 请求体。B04 §2 原契约只有 itemId 与 count，这里补了 requestId —— 开箱是有副作用的写操作，断网重放会让玩家白丢一箱，必须走与城建同一套幂等（B00 陷阱 3）。
 */
export interface OpenBatchReq {
  requestId: string
  /** 必须是 chest 表里登记过的宝箱道具 id */
  itemId: string
  /** 一次开几个。100 是协议天花板（B04 验收 3 就是按 100 定的），逐箱的实际上限取 chest.maxBatchCount，两者取小。 */
  count: number
}

/**
 * POST /item/openBatch 响应体。results 已按稀有度聚合排序（B04 验收 3）。seed 回传是为了可复现：客服接到「我开了 100 个什么都没有」的申诉时，用 (seed, count) 重跑 ChestOpener 就能还原当时的每一次抽取。
 */
export interface OpenBatchResp {
  /** 实际消耗的宝箱数量 */
  consumed: number
  /** 抽到并成功入账的奖励 */
  results: RewardItemView[]
  /** 资源超上限或背包满而装不下的部分，已转邮件（B04 验收 2） */
  overflow: RewardItemView[]
  /** 溢出转邮件的邮件 id，无溢出时为 null */
  mailId: string | null
  /** 本次开箱使用的随机种子。由服务端生成，客户端无法影响（B04 禁止项：不得在客户端本地开箱）。 */
  seed: number
  serverNow: number
}
