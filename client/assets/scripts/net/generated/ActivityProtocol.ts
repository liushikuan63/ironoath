/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 一行活动对当前玩家、当前窗口的状态。三个取值各自对应一条服务端判定，客户端只显示不判断。
 */
export type ActivityState =
  | 'RUNNING'
  | 'CLAIMABLE'
  | 'EXPIRED'

/**
 * 领取时入账的一条奖励。与 bag 的 `RewardItemView`、quest 的 `QuestReward`、stage 的 `StageReward`、mail 的 `MailReward` 形状相同，但生成器只支持同文件 `$ref`，所以这里又有一份（第 5 份）。**五份的字段与枚举取值必须一致**，由 `ActivityEndpointTest` 的反射比对与 `StageContractParityTest` 一族一起钉住 —— 复制而不校验才是危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
export interface ActivityReward {
  /** 奖励类型，取值与 bag 协议的 `RewardType` 一致（CI 的枚举一致性守卫会核对同名同序）。 */
  type: string
  /** 资源/道具/碎片的标识。 */
  id: string
  /** 入账数量（不是申请量）。`RewardService.grantReward` 算完溢出之后写回来的就是它。 */
  count: number
  /** 人看得懂的名字，与 `RewardLine.name` 同源（`RewardNames`）。**旧记录不许回填**：名字只在下发那一刻取一次。 */
  name: string
}

/**
 * 一行活动在玩家视图里的样子。窗口内的进度、窗口结束时刻、领没领过 —— 都在这一格里。
 */
export interface ActivityView {
  /** `activity.json` 的 id。客户端把它回传给 `POST /activity/claim`。 */
  id: string
  /** 活动名。**只在服务端与配置表里存一份**：客户端硬编码一份就会出现「改表了但界面没改」。 */
  name: string
  /** 服务端算出来的状态：`EXPIRED`=窗口已过（不可领）；`CLAIMABLE`=达标且本窗口没领过；`RUNNING`=其余（含已领过但窗口未结束 —— 那一格由 `claimed` 表达）。 */
  state: ActivityState
  /** 当前窗口内的进度值。由事件推进（禁止轮询扫表），读取时不做「顺手推进一遍」。 */
  progress: number
  /** 达标目标，来自 `activity.json` 的 `conditionValue`。它下发而不是让客户端读表：客户端读表意味着表与包的版本必须永远同步。 */
  goal: number
  /** 本窗口结束时刻（毫秒）。**可空**：配置表若出现时长为 0 的行（常驻活动），窗口无终点，下发 null 而不是编一个天文数字。 */
  windowEndAt: number | null
  /** 本窗口是否已领过。领取幂等的判据在服务端（同 requestId 重放只发一次、同窗口重复领取被拒），这一格只是结果展示。 */
  claimed: boolean
}

/**
 * 活动列表。8 行表里对当前玩家可见的那些 —— 顺序按 `activity.json` 的行序（配置表就是唯一排序来源，服务端不另排一遍）。
 */
export interface ActivityListResp {
  /** 逐行视图。含已过期的行（`state=EXPIRED`）—— 列表里要看得见「哪个活动结束了」，这与邮件过期即消失不同：活动的窗口是**轮换**的，玩家需要知道下一轮什么时候开始。 */
  activities: ActivityView[]
  /** 服务端当前时刻（毫秒）。剩余时间由它与 `windowEndAt` 相减得出，不用客户端本地时钟（铁律 5）。 */
  serverNow: number
  /** 有可领奖的行数。红点叶 `activity/claimable` 与它是同一个判定（`> 0` 即亮）—— 客户端不自己数一遍。 */
  claimableCount: number
}

/**
 * 领取一次活动奖励。
 */
export interface ActivityClaimReq {
  /** 要领的那一行。 */
  activityId: string
  /** 幂等键，与 `/quest/claim`、`/mail/claimAll` 同一要求：弱网重投与玩家连点都必须只发一次奖。 */
  requestId: string
}

/**
 * 领取结果。奖励走 `RewardService.grantReward`，背包装不下时由邮件兜底（B04 验收 2 已落地），所以这里没有「失败但没提示」的那条暗路。
 */
export interface ActivityClaimResp {
  /** 本次是否真的发了奖。**同 requestId 重放时也为 true**（幂等重放返回的是同一次领取的结果，而不是再发一份）。 */
  claimed: boolean
  /** 本次入账的奖励明细（含溢出转邮件的那部分，`name` 与邮件里的一致）。空数组只在「什么都没发」时出现，而那意味着领取被拒（错误码见下）。 */
  rewards: ActivityReward[]
  /** 领取之后这一行的新状态。领完同轮就要红点熄灭（验收 8），所以状态必须回来 —— 让客户端猜「现在是不是还要亮」等于把判定搬到客户端。 */
  state: ActivityState
}
