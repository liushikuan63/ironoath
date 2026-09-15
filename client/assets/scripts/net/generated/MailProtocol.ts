/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 邮件来源类别。只有两类是现在真有生产者的：SYSTEM=运营/客服补发（`POST /ops/mail/send`），OVERFLOW=发奖溢出转补发（`RewardPorts.Mailbox`，B04 验收 2）。战报邮件属 B12 §3 那一档，等战报入口落地再加第三种取值 —— 先声明一个没人发的类型就是「有名字没读者」那一族缺口。
 */
export type MailKind =
  | 'SYSTEM'
  | 'OVERFLOW'

/**
 * 一封邮件的一条附件。与 bag 协议的 `RewardItemView`、quest 协议的 `QuestReward`、stage 协议的 `StageReward` 形状相同，但生成器只支持同文件 `$ref`，所以这里又有一份。**四份的字段与枚举取值必须一致**，由 `StageContractParityTest` 一族的反射比对钉住 —— 复制而不校验才是危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
export interface MailReward {
  /** 奖励类型，取值与 bag 协议的 `RewardType` 一致（CI 的枚举一致性守卫会核对两边同名同序）。 */
  type: string
  /** 资源/道具/碎片的标识。 */
  id: string
  /** 数量。这里记的是**当初溢出或补发的量**，不是「还能领多少」—— 领取失败时这一格不许被改成 0，否则玩家看不到自己少了什么。 */
  count: number
  /** 展示名，由服务端解析好下发（客户端不查配置表，铁律 2）。 */
  name: string
}

/**
 * 一封邮件在玩家视图里的一行。**只包含已经没过期且没被领走的判断所需字段**：过期与领取都是服务端的判定，客户端不参与（铁律 2）。
 */
export interface MailView {
  /** 服务端生成的邮件 id。客户端把它回传给 `POST /mail/read`；**一键领取不回传它**（见文件头的约束 2）。 */
  mailId: string
  /** 哪一类邮件。 */
  kind: MailKind
  /** 标题。溢出邮件由服务端生成「背包满了，先给你存着」这类话术 —— 文案不在客户端硬编码，理由与 `AppVersionResp.notice` 一致。 */
  title: string
  /** 正文。**B04 验收 2 要求写明溢出数量与原因**，所以这一格对 OVERFLOW 不是可选装饰：它要说清哪几项溢出、溢出了多少、为什么。 */
  text: string
  /** 附件明细。空数组表示这是一封纯通知（无附件可领，`claimed` 恒为 true 的语义就是「没有东西可领」）。 */
  rewards: MailReward[]
  /** 附件是否已经领过。**没附件时为 true**（「无附件可领」与「领过了」对玩家是同一句话，分成两个字段就会有两个家）。 */
  claimed: boolean
  /** 读过没有。未读计数由服务端算（`MailListResp.unreadCount`），客户端不自己数 —— 数法一旦不同，红点与列表就会各说一套。 */
  read: boolean
  /** 生成时刻（毫秒）。排序按它倒序。 */
  createdAt: number
  /** 过期时刻（毫秒）= createdAt + `global.MAIL_RETENTION_DAYS`。**下发到期时刻而不是剩余天数**：时钟以服务端为准（铁律 5），而「还剩几天」要玩家自己减。 */
  expireAt: number
  /** 归因引用：OVERFLOW 那类写的是发奖来源（quest id、战报 id…），SYSTEM 写操作者。`RewardContext.sourceRef` 的同一条理由 —— 「玩家东西多了/少了」唯一可追的路径就是这个字段。 */
  sourceRef: string
}

/**
 * 一封没领成功的邮件。**它不会被删、也不会被标记成已领**（B12 验收 5：失败邮件保留），玩家下一次一键领取还会再试。
 */
export interface MailClaimFailure {
  /** 哪一封。 */
  mailId: string
  /** 为什么没领上（人看得懂的一句）。写侧失败时服务端同时打 WARN —— 玩家的「领不到」与运维的「为什么」必须是同一句话，不能一个给人一个给日志。 */
  reason: string
}

/**
 * 邮件列表（B12 §二 草案形状）。**读这一次会顺带清理已过期的邮件**（惰性清理），所以「列表短了」不需要一条额外的对账逻辑。
 */
export interface MailListResp {
  /** 未过期的邮件，按 `createdAt` 倒序。 */
  mails: MailView[]
  /** 未读封数。**服务端算**：红点判据与列表同源才不会出现「列表里全读过而红点亮着」。 */
  unreadCount: number
  /** 本次列表里「没有附件可领」的封数（含领过的与纯通知的）。给客户端决定要不要显示「暂无可领」这一档 —— 它和 `unreadCount` 一样不许客户端自己数。 */
  claimedCount: number
}

/**
 * 一键领取全部。带幂等键：一次点击发两次请求会重复发奖，而重复发奖在本项目里是经济口子（B04 禁止项「不得重复发放」）。**刻意不带 mailId 列表。**
 */
export interface MailClaimAllReq {
  /** 幂等键，与全仓写接口同一套（`IdempotencyStore`，TTL 取 `global.REQUEST_ID_TTL_SECONDS`）。 */
  requestId: string
}

/**
 * 一键领取的结果（B12 §二 草案的 `MailClaimAllResp`）。三个数各说各的：领了几封、领到什么、哪几封没领上。
 */
export interface MailClaimAllResp {
  /** 这次成功领到的**封数**（不是附件条数 —— 一封可以有三条附件，两个数会打架）。 */
  claimed: number
  /** 这次实际入账的奖励明细（按 type+id 聚合）。与 `QuestClaimResp.rewards` 同一条口径：写的是入账量，不是申请量。 */
  rewards: MailReward[]
  /** 没领上的邮件（含原因）。空数组表示全部领上了。 */
  failed: MailClaimFailure[]
}

/**
 * 把一封邮件标成已读。写接口所以带幂等键 —— 重复标已读没有副作用，但 `release` 的路径仓库里已经有了，不用它反而要多解释一句为什么这里例外。
 */
export interface MailReadReq {
  /** 幂等键。 */
  requestId: string
  /** 要标已读的那一封。 */
  mailId: string
}

/**
 * 标已读的回执：只回新的未读封数。
 */
export interface MailReadResp {
  /** 回显被标的那一封（客户端据此把那一行的粗体去掉，不用重拉列表）。 */
  mailId: string
  /** 标完之后的未读封数。 */
  unreadCount: number
}

/**
 * 运营/客服补发一封邮件（`POST /ops/mail/send`，需 `X-Ops-Token`）。**这是唯一一条能凭空给玩家发奖励的通路，所以它同时受三道闸门约束**：运维令牌、幂等键、审计日志（谁发的、发给谁、发了什么，全部进 WARN 级日志）。
 */
export interface OpsMailSendReq {
  /** 幂等键。工单系统按时钟重投是常态，没有它同一笔补偿会发两遍。 */
  requestId: string
  /** 收件玩家。 */
  playerId: string
  /** 标题。 */
  title: string
  /** 正文（写清为什么补、补什么 —— 玩家拿到钱却不知道原因会变成投诉）。 */
  text: string
  /** 附件。允许为空数组（纯公告）。 */
  rewards: MailReward[]
  /** 操作者标识（工单号或运维账号）。与 `POST /ops/config/reload?actor=` 同一条要求：改了别人东西的操作必须能追到人。 */
  actor: string
}

/**
 * 补发回执。
 */
export interface OpsMailSendResp {
  /** 生成出来的邮件 id。工单要贴这个号，玩家报「补发的没收到」时才有据可查 —— 这正是本轮换掉 `TransientRewardPorts` 假 mailId 的理由。 */
  mailId: string
  /** 这一封的过期时刻（按 `MAIL_RETENTION_DAYS` 算）。 */
  expireAt: number
  /** 附件条数（不是数量之和）。回执里给这个数是为了让运维核对「发出去的那封确实带着它以为带着的几条」。 */
  rewardCount: number
}
