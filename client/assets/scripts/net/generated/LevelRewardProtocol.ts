/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 一行奖励里的一个条目。与 bag 协议的 RewardItemView、quest 协议的 QuestReward、stage 协议的 StageReward 形状相同，但生成器只支持同文件 $ref，所以这里第四份本地定义。**四份的字段名与顺序必须一致**，由 LevelRewardContractParityTest 逐字段比 RewardItemView 钉住 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
export interface LevelRewardItem {
  /** 奖励类型。本表目前只产出 RESOURCE 一种（木/石/金币都是资源），取值集合与 bag 协议的 RewardType 一致。 */
  type: string
  /** 资源 id（WOOD / STONE / GOLD）。它是内部标识，客户端**不得**把它印到屏上 —— 展示一律读同一条目的 name。 */
  id: string
  /** 数量。恒 >= 0：表里 LONG_NONNEG 列，0 的行在服务端就不产出条目。 */
  count: number
  /** 服务端从 resource 表解析后的展示名（木材 / 石料 / 金币）。客户端不得自行翻译，也不得回退成 id —— 「屏上不出现裸 id」这一维在运行时量具里单独钉。 */
  name: string
}

/**
 * 一个等级的领取视图。行数由 level_reward 表决定（现跑 40 行，1..40 逐级都有），服务端不做分页裁剪也不做筛选 —— 客户端拿到的是全表视图，分页是面板的事。
 */
export interface LevelRewardRow {
  /** 等级（表里的 level 列）。领取时原样回传它，而不是行 id —— 行 id 是表内主键，改表就会漂，而「第几级」这件事的本体就是这个数。 */
  level: number
  /** 这一级的展示名（表里的 name，如「主城 31 级奖励」）。客户端不得自行拼接等级文案。 */
  name: string
  /** 这一级给什么。由服务端按表逐列展开（0 的列不出现），顺序即客户端飘字顺序：木、石、金币。 */
  rewards: LevelRewardItem[]
  /** 主城等级还没到这一级。与 claimed 分开是为了让客户端知道该提示「先去升主城」还是「已领取」。 */
  locked: boolean
  /** 此刻能不能领：等级已达到 且 未 claimed。客户端不要自己算这个布尔 —— 判据住在服务端，抄一份就会在口径变更时漂。 */
  claimable: boolean
  /** 这一级的奖励是否已领过。是「领取」按钮置灰的唯一依据（重复领取会被服务端拒并回业务码）。 */
  claimed: boolean
}

/**
 * GET /level-reward/list 的响应。纯读，不推进任何状态 —— 与 quest 的 list 不同，这里没有跨期清零要挂（等级奖励永不过期），所以它不会写库。
 */
export interface LevelRewardListResp {
  /** 全部等级行，按 level 升序（服务端已排，客户端不需要再排 —— 行序变了玩家会看到同一屏内容换位置）。 */
  rows: LevelRewardRow[]
  /** 此刻可领的行数，给红点用。与 rows 里 claimable=true 的行数同源（同一次遍历算出来的两个值，不允许各算一遍）。 */
  claimableCount: number
  /** 玩家当前主城等级。面板要显示「你现在 14 级，还有 3 级可领」这类抬头，而等级真相在服务端存档上 —— 客户端没有这一位，也不许自己从别处推。 */
  mainLevel: number
  /** 服务端时刻。 */
  serverNow: number
}

/**
 * POST /level-reward/claim 请求体：领取某一级的奖励。
 *
 * **requestId 是必需项而不是可选优化**：裁决②把入账挂在「玩家点按钮」这一刻，于是「点了没到账、玩家再点一次」成为常规路径而不是边缘情况。同一 requestId 重放只发一份。
 */
export interface LevelRewardClaimReq {
  /** 幂等键。缺失回 1003，重复回 1002（与任务/活动/社交同一套码）。 */
  requestId: string
  /** 要领的等级。表里没有这一级回 3014，主城还没到这一级回 3015，已经领过回 3016 —— 三种失败各有各的码，客户端才能给出不误导人的提示。 */
  level: number
}

/**
 * POST /level-reward/claim 的响应。**回实际发出的奖励明细**而不是只回 ok：客户端要弹「获得 木材×N」，而那份明细必须来自发放器真正发出去的东西（超出仓储上限、转邮件补发的那部分也在里面），不是客户端按表自己猜的。
 */
export interface LevelRewardClaimResp {
  /** 刚领掉的那一级。 */
  level: number
  /** 本次实际发放的奖励明细。 */
  rewards: LevelRewardItem[]
  /** 领完之后还剩几级可领（与 list 的同一字段同源）。 */
  claimableCount: number
  /** 服务端时刻。 */
  serverNow: number
}
