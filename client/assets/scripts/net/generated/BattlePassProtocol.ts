/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 战令的两条线。`FREE` 人人可领；`PAID` 要本赛季战令已购买（随付费发货解锁，见 `pay_product` 的 `battle_pass` 行）。
 */
export type BattlePassTrack =
  | 'FREE'
  | 'PAID'

/**
 * 奖励类型。取值与 `battle_pass` 表的 `fieldTypes` 声明一致（`RESOURCE` / `ITEM`）—— 只有这两种，因为战令的奖励必须进得了邮件（赛季结束未领的档位要按档补发，而邮件附件只能是这两种）。
 */
export type BattlePassRewardType =
  | 'RESOURCE'
  | 'ITEM'

/**
 * 一档上的那份奖励。**只允许资源与道具**（与 `product_reward` 表同一套词汇）—— 理由是战令的奖励必须进得了邮件：赛季结束未领的档位要按档补发，而邮件附件只能是 `RewardType` 表达得了的东西。外观不在其中：它随购买立即到账，见 `battle_pass_season.json`。
 */
export interface BattlePassRewardView {
  rewardType: BattlePassRewardType
  /** 奖励 id：`RESOURCE` 时是 `resource` 表的行 id（如 `GOLD`），`ITEM` 时是 `item` 表的行 id。**不下发跨表解析后的对象**：客户端只拿它去查本地图集与名字，判定与发放都在服务端。 */
  rewardId: string
  /** 显示名，服务端从对应的表里查出后下发 —— 改个名字不该发一次版。 */
  name: string
  /** 份数。 */
  count: number
}

/**
 * 一档（两行奖励 + 三个结论位）。客户端**不重新计算**：`reached` 是服务端按当前赛季积分与这一档的 `requiredPoints` 比出来的，`freeClaimed` / `paidClaimed` 是领取账本里的两位。
 */
export interface BattlePassTierView {
  /** 第几档（1 起）。档位号是这一档在表里的 `tier` 列，不是数组下标 —— 将来往中间插一档时，玩家已经领过的档位号不会整体错位。 */
  tier: number
  /** 累计到多少分才达成这一档（来自 `battle_pass` 表）。 */
  requiredPoints: number
  /** 是否已达成（当前赛季积分 ≥ requiredPoints）。未达成的档位**也要下发**：让玩家看见「下一档还差什么」正是战令的动力来源，藏起来等于把 20 档变成 1 档。 */
  reached: boolean
  /** 免费线这一档领过没有。领过就是领过（本表不重置）。 */
  freeClaimed: boolean
  /** 付费线这一档领过没有。与 `freeClaimed` 是两位：合成一位会让「领了免费那份」顺手把付费那份也标成已领。 */
  paidClaimed: boolean
  freeReward: BattlePassRewardView
  paidReward: BattlePassRewardView
}

/**
 * GET /battlePass/status 响应：本赛季的战令全貌。积分、解锁位、20 档一起下发，客户端一次画完。
 */
export interface BattlePassStatusResp {
  /** 这些进度属于哪个赛季（与赛季账本、商店 `SEASON` 限购同一个键）。**下发给客户端是为了让界面能说清「这是哪一季的战令」**：跨赛季那一刻玩家的积分会归零，而界面上如果没有赛季名，看到的就只是「我的分突然没了」。 */
  seasonId: string
  /** 本赛季已获得的战令积分。来源只有两处：任务领取与活动领取（各自的表里配分值）—— 战令**没有**自己的任务体系。 */
  points: number
  /** 付费线是否已解锁（本赛季战令买过没有）。它随付费发货落下，不由客户端上报。 */
  paidUnlocked: boolean
  /** 本赛季结束的服务端毫秒时刻（赛季时间轴的末段终点）。界面据此显示「还剩几天」—— 客户端不得用本地时钟推算（铁律 5）。 */
  seasonEndAt: number
  /** 20 档，按 tier 升序。**含未达成的档**：未达成的档位是玩家的目标，而不是噪音。 */
  tiers: BattlePassTierView[]
  /** 服务端时刻（与 `seasonEndAt` 相减得到剩余时间）。 */
  serverNow: number
}

/**
 * POST /battlePass/claim 请求体：领某一档的某一条线。
 */
export interface BattlePassClaimReq {
  /** 幂等键。领奖会往背包/资源里写东西，重放等于刷奖励。 */
  requestId: string
  /** 要领第几档（表里的 `tier`）。档位号由客户端回传而不是「领下一个」：玩家点的是他眼前那一行，而服务端按「下一个未领的档」发会在弱网重试下与玩家的点击目标错开。 */
  tier: number
  track: BattlePassTrack
}

/**
 * POST /battlePass/claim 响应：领到了什么 + 领取之后的全量状态。**状态整份回传**：客户端据此重画，不在本地把那一档翻成已领 —— 本地翻法在「服务端拒了但界面已经翻过去了」时会骗人。
 */
export interface BattlePassClaimResp {
  track: BattlePassTrack
  /** 刚领的是哪一档。 */
  tier: number
  reward: BattlePassRewardView
  status: BattlePassStatusResp
}
