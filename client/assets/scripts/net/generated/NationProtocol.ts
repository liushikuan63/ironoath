/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 国家官职（B13 §2）。取值必须与 game-core 的 Nation.Office 逐一对应，由 NationPayEnumParityTest 的 nationOfficeMatchesTheDomainEnum 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端认得的官职客户端显示成未知，UI 只会空白。
 *
 * 席位数：国王 1、首相 1、大将军 2、内政官 4、外交官 4，议员按盟主数动态给（不是固定席位）。
 */
export type NationOffice =
  | 'KING'
  | 'PRIME_MINISTER'
  | 'GENERAL'
  | 'MINISTER'
  | 'DIPLOMAT'
  | 'REPRESENTATIVE'

/**
 * 国家间的四种外交关系（B13 §5）。取值必须与 game-core 的 Nation.Diplomacy 逐一对应（NationPayEnumParityTest 断言）。
 *
 * **关系直接影响国战分组与跨服匹配**，所以它不是装饰性的标签：盟约之间不能互相攻击（mayAttackNation 会拒绝），敌对之间才可以。这也意味着改一次关系会立刻改变谁能打谁 —— 所以变更必须留日志（谁、何时、从什么改成什么），否则一次误操作会变成一场无从追溯的战争。
 */
export type DiplomacyRelation =
  | 'ALLIED'
  | 'HOSTILE'
  | 'NEUTRAL'
  | 'TRIBUTARY'

/**
 * 国库支出的落点类型（B13 §3 的三个用途在 2026-09-11 的裁决里收敛成两类）。PLAYER = 官职俸禄，钱发给某个玩家（走发放器，GOLD 与国库资金 1:1）；SINK = 国家科技 / 国战增益，钱被子系统消耗、没有收款人。**不允许「其它」**：一个自由字符串的支给对象会让「支给谁」写成任意东西，而国库日志存在的理由就是纠纷发生时能查。
 */
export type TreasuryPayeeType =
  | 'PLAYER'
  | 'SINK'

/**
 * 消耗性用途（B13 §3 原文的两个：国家科技 / 国战增益）。名字进日志的 counterparty 列（形如 sink:NATIONAL_TECH），所以可以直接与文档对照。
 */
export type TreasurySink =
  | 'NATIONAL_TECH'
  | 'WAR_BOOST'

/**
 * 国策改的是哪一个数。取值与 `nation_policy.json` 的 `effectAttr` 列逐一对应（由 `ContractEnumParityTest` 断言）。
 *
 * **只有四个取值**：国策的效果最终落在四条算式上 —— `POLICY_ATTACK` / `POLICY_DEFENSE` 进战斗内核的**乘区 G**（B21 §五④ 块③「buff 走独立乘区，不许污染既有乘区」），`OUTPUT` 进 `ResourceRateService` 的每资源产率算式，`MARCH_SPEED` 进 `Rates.shortenSeconds` 那条时长算式。乘区 H（城墙）**刻意不在这个枚举里**：城墙等级 → 加成的幅度至今没有任何出处，声明一个恒为 0 的取值就是给一条不存在的分支起名字。
 */
export type NationPolicyEffectAttr =
  | 'POLICY_ATTACK'
  | 'POLICY_DEFENSE'
  | 'OUTPUT'
  | 'MARCH_SPEED'

/**
 * 国策轮次处在哪一段。**三段而不是两段**：`NationVoteReq` 是二值的（B13 §二 的 `NationVoteReq(String proposalId, boolean support)`），所以「提案」与「投票」必然是两个不同的动作、两个不同的时间窗 —— 把它们压进一个窗口意味着玩家在投票窗口里才能提案，而提案要 24 小时被讨论、投票又要 24 小时，窗口就得 48 小时，那与 `NATION_VOTE_DURATION_HOURS=24` 的原意（一次投票 24 小时）不是一回事。
 *
 * 三个取值各自能做什么：
 * - `PROPOSING` —— 本国可以提案，投票窗口未开。提案不消耗任何资源（B13 §3 的国库三用途里没有「国策」）。
 * - `VOTING` —— 投票窗口开着（长度 = `NATION_VOTE_DURATION_HOURS`），可以投票，不能提案。
 * - `ACTIVE` —— 本轮已结算，通过的国策占住了 `policySlotCount` 个槽位并**生效中**，等最早到期那一刻自动开下一轮。
 *
 * **没有任何一段是「常驻定时器推进的」**：轮次由读取动作惰性推进（与 `settleTax` 同一手法），服务端不跑任何定时任务（`check-no-scheduled.sh` 是门禁）。
 */
export type NationPolicyPhase =
  | 'PROPOSING'
  | 'VOTING'
  | 'ACTIVE'

/**
 * 为什么现在不能做这个动作。`NONE` = 没拦着（此时对应的 `canPropose` / `canVote` 为 true）。
 *
 * `NOT_PROPOSER` 与 `NOT_VOTING` **刻意不合并**：前者是「你这个身份没有提案权」（读 `role_permission` 表的 `SET_NATIONAL_POLICY`，2026-09-30 裁决放开到官员档），后者是「身份够但此刻不是提案段」。合并后面板会对一个内政官说「你不是国王」而他明天可能就该收到别人的提案通知 —— 玩家能做的是等窗口开，而不是换个人。
 *
 * `BOT_NOT_ALLOWED` 同样独立：Bot 不投票是 2026-09-30 的裁决（B11/B13 的红线只管「Bot 不得任官职」，投票不是官职，那条红线一个字都没覆盖到这一格）。它与 `NOT_PROPOSER` 分开是因为**两条红线的来源不同**：一条是权限表，一条是合规。
 *
 * ⚠ **这一位不由领域层产出**：`scripts/check-no-bot-privilege.sh` 是一条门禁，它规定「除了 `BotRegistry` 之外，任何地方都不许问这是不是 Bot」——游戏逻辑里写 `if (isBot)` 哪怕是**拒绝**它也算违规。所以领域层 `Nation.PolicyBlock` 刻意没有这个取值，Bot 的判定与拦截都在 game-web 持有 `BotRegistry` 的那一层完成。
 *
 * `NO_PROPOSAL_YET` 与 `NOT_VOTING` 也分开：前者是「本轮还没有任何提案，投票窗开不起来」（轮次自循环要有人提才有得投），后者是「窗口已经开过或正在开，提案段已经结束」。玩家在两种情况下的下一步不同：一种是自己提一条，另一种是等别人提完再投。
 */
export type NationPolicyBlockReason =
  | 'NONE'
  | 'NOT_PROPOSER'
  | 'NOT_VOTING'
  | 'ALREADY_VOTED'
  | 'ALREADY_PROPOSED'
  | 'NO_PROPOSAL_YET'
  | 'BOT_NOT_ALLOWED'

/**
 * 一个国家的公开视图。
 */
export interface NationView {
  /** 国家 id。 */
  nationId: string
  /** 国名。建国时由发起人取，敏感词校验在服务端（B15 合规）。 */
  name: string
  /** 国王的玩家 id。建国者直接担任（B13 §五 开放问题 1 的当前裁定：不做联盟间竞选）。 */
  kingId: string
  /** 国家等级，来自 nation_config 表。等级决定人数上限、国库上限与国策槽位数。 */
  level: number
  /** 已入籍的联盟数。**国家成员表的最小单位是联盟**，所以这里给联盟数而不是玩家数 —— 玩家名单在联盟那一侧，两处各存一份迟早对不上。 */
  allianceCount: number
  /** 人数上限（nation_config.memberCap）。国家容量 = Σ成员联盟容量，并受这个上限约束。 */
  memberCap: number
  /** 都城横坐标（格）。 */
  capitalX: number
  /** 都城纵坐标（格）。 */
  capitalY: number
  /** 国库余额。来源是成员联盟按周上缴的税收（global.NATION_TAX_WEEKLY_PER_ALLIANCE）。 */
  treasury: number
  /** 国库上限（nation_config.treasuryCap）。达到上限后税收不再入账 —— 这条必须下发，否则玩家会以为税收被吞了。 */
  treasuryCap: number
  /** 请求者本人担任的官职，没有则为 null。**刻意用官职名的字符串而不是 NationOffice 枚举**：客户端要显示的是「大将军」这样的中文名，而那份文案在客户端的本地化表里；服务端下发枚举名，客户端据此查表 —— 下发中文的话，改一次文案就要改服务端配置表。 */
  myOffice: string | null
  /** 服务端时间戳（铁律 5）。 */
  serverNow: number | null
}

/**
 * POST /nation/found 请求体：建国。
 *
 * 前置（B13 §1，全部由服务端校验）：主城 16 级 + 开服 D14 + 当前在某联盟中。这三条都不是客户端能替服务端决定的，所以协议里没有任何「我已满足前置」的字段。
 */
export interface NationFoundReq {
  /** 幂等键。建国是一次不可重复的写操作：重放会造出两个同名国家，而国名唯一性检查在第一次之后就通过了（第一个国家已经占用了那个名字，第二个会被拒 —— 但如果两次请求并发，检查与写入之间没有锁就会双双通过）。 */
  requestId: string
  /** 国名。长度与敏感词校验在服务端。 */
  name: string
  /** 都城横坐标。必须是发起人联盟领地内或无主的格子 —— 具体规则由服务端按世界状态判定，客户端给的只是意愿。 */
  capitalX: number
  /** 都城纵坐标。 */
  capitalY: number
}

/**
 * POST /nation/appoint 请求体：任命官职。
 *
 * **权限走 role_permission 表**（perm_nation_appoint_office：只有国王能任命）。被任命者必须属于某个已入籍的联盟 —— 个人不能脱离联盟单独入籍，所以也不能被单独任命。
 */
export interface NationAppointReq {
  /** 幂等键。 */
  requestId: string
  /** 被任命者。<b>服务端会拒绝 Bot</b>（B13 §2 合规红线：Bot 不得担任任何国家官职）。这条判定不在协议里表达 —— 协议里没有任何字段能让客户端声明「这个人是真人」，所以客户端无法绕过。 */
  playerId: string
  /** 要任命的官职。席位数由 Nation.Office.seatCount() 决定，议员按盟主数动态给。 */
  office: NationOffice
}

/**
 * 国家操作的统一响应：返回操作后的完整视图，而不是只回一个 ok。客户端据此刷新面板，不需要再发一次查询 —— 少一次往返在弱网下就是少一次超时。
 */
export interface NationResp {
  /** 操作后的国家视图。 */
  nation: NationView
  /** 服务端时间戳。 */
  serverNow: number
}

/**
 * POST /nation/join 请求体：一个联盟整体加入国家（B13 §二「由盟主发起，全联盟加入」）。
 *
 * **发起人的身份是结构性判定，不是权限位**：role_permission 表里没有 JOIN_NATION 这一位，而且「代表全盟选择国籍」的权限来源是「你是这个盟的盟主」而不是「你在国家里担任某个官职」—— 入籍那一刻他还不在该国，任何国家侧官职都无从谈起。所以服务端按联盟 leaderId 判定，不去查一张没有这一位的表。哪天真要把它做成可配置的（例如允许干部代为申请），那是**配置表的一次口径变更**，要加行而不是改代码。
 *
 * **目标国家由调用方指名，能不能加入全部由服务端判**：入籍冷却（B13 验收 2）、该国联盟名额（memberCap 推导的上限）、是否已属于另一个国家 —— 协议里不给任何「我已满足条件」的字段，那等于让客户端替服务端做决定。
 */
export interface NationJoinReq {
  /** 幂等键。入籍是一次跨两个聚合的写操作（改国家成员表、还要把该盟成员显示为该国公民），重放一次会在审计日志里留下两条「加入」，而冷却期与名额的判定都可能被这两条之间的一次退出国绕开。 */
  requestId: string
  /** 要加入的国家 id。 */
  nationId: string
}

/**
 * POST /nation/leave 请求体：全联盟退出所属国家。
 *
 * **退出与「联盟解散」走的是同一条领域规则**（Nation.removeAlliance，expelled=false）：两者都是这个联盟不再属于该国，区别只在谁做的决定 —— 这里是盟主自己，解散时是「联盟这个实体不复存在」。B13 §二的冲突表把「被国家开除」和「主动退出」写在同一行，正因为两者的后果（全联盟失去国籍 + 24h 入籍冷却）必须一致：如果主动退出没有冷却，就可以「退出 → 立刻加入敌国」，而国战的胜负恰恰取决于双方人数。
 */
export interface NationLeaveReq {
  /** 幂等键。 */
  requestId: string
}

/**
 * 退出国的响应。**为什么不复用 NationResp 回一份国家视图**：操作完成后这个人已经没有国籍了，GET /nation 会直接回 NATION_NOT_FOUND，回一份「刚刚离开的那个国家的视图」会让客户端刷新到一个它再也无权查询的对象上。这一次操作唯一需要立刻显示给玩家的事实是「什么时候才能再加入」，所以只回冷却时刻。
 */
export interface NationLeaveResp {
  /** 刚离开的国家 id —— 只用于日志与提示里的称呼，不再是一个可查询的入口。 */
  nationId: string
  /** 国名，服务端下发。客户端不得自行缓存拼接：那份名字要与战报、聊天、客服工单里的称呼一致。 */
  nationName: string
  /** 该联盟可再次入籍的时刻（服务端时间戳）。**必须由服务端下发而不是客户端拿一个时长自己加**：冷却时长只有一个家（global.NATION_JOIN_COOLDOWN_HOURS，经 NationRulesAssembler 换算成毫秒），而铁律 5 禁止在展示与判定两侧各算一遍时间 —— 客户端本地钟一偏，就会出现「显示还能加入、服务端却拒绝」这种说不清的提示。 */
  cooldownUntil: number
  /** 服务端时间戳。倒计时要有基准，这个基准与 cooldownUntil 必须同源。 */
  serverNow: number
}

/**
 * POST /nation/disband 请求体：解散国家（B13 §1；领域层 {@code Nation.disband} 早就写完了，缺的只是入口）。
 *
 * **发起权来自「你是这个国的国王」，不是来自成员关系**：领域层判的就是 operator 等于 kingId，所以服务层按 kingId 找国，而不是按「他此刻还在不在某个成员联盟里」找 —— 后者会在国王所在联盟先退出国之后，悄悄把规则改成「国王亡不了自己的国」。
 *
 * 协议里没有任何「我已获授权」的字段：那等于让客户端替服务端做决定。
 */
export interface NationDisbandReq {
  /** 幂等键。这是一次不可逆、且会连带几百人国籍与一笔公共资产的写操作，重放不该产生两次核销日志。 */
  requestId: string
}

/**
 * 解散国家的响应。**同样不回国家视图**（理由与 {@code NationLeaveResp} 一致，而且这次对象是真的不存在了）；一次亡国操作最需要留下的是审计：哪个国、叫什么、解散时带着几个成员联盟与多少钱。
 */
export interface NationDisbandResp {
  /** 被解散的国家 id。记录留在库里供审计（{@code Snapshot.disbandedAt} 非 0），但它不再是一个可查询、可外交、可入籍的对象，也不再占用本服的国家名额。 */
  nationId: string
  /** 国名，服务端下发（要与战报、聊天、客服工单里的称呼一致）。 */
  nationName: string
  /** 解散那一刻的成员联盟数。这些联盟每个都进入入籍冷却（与主动退出、被开除同一条规则），所以这个数就是「本次操作影响到多少个联盟」。 */
  memberAllianceCount: number
  /** 被核销的国库余额。**必须回给调用方并留在国库日志里**：国库是公共资产，一笔静默消失的钱正是「盟主卷款」最容易被写成实现细节的形状（B13 §3 与禁止项「不要让国库支出无日志」）。 */
  treasuryWrittenOff: number
  /** 服务端时间戳，即解散时刻。冷却与那笔核销日志的 at 都以此为基准。 */
  serverNow: number
}

/**
 * POST /nation/diplomacy 请求体：变更与另一个国家的外交关系。
 *
 * 权限走 role_permission 表的 MANAGE_DIPLOMACY（国王与外交官档，普通成员不行）。**单方面变更**：B13 §5 没有要求双方同意，所以盟约是可以被一方单方面宣布的 —— 这与现实外交不同，但要求双方确认会让「结盟」变成一次需要两人同时在线的操作，而那在小服里几乎不可能凑齐。
 */
export interface NationDiplomacyReq {
  /** 幂等键。重放一次「宣布敌对」不该产生两条外交日志，否则审计时看不出到底宣布了几次。 */
  requestId: string
  /** 对方国家 id。不能是自己 —— 与自己结盟没有意义，而与自己敌对会让 mayAttackNation 拒绝一切进攻，等于自废武功。 */
  targetNationId: string
  /** 要设置的关系。 */
  relation: DiplomacyRelation
}

/**
 * 外交变更的响应：回双方之间**生效后**的关系，以及本国对全部国家的关系表。回整张表而不是只回变更的那一条，是因为客户端的外交面板本来就要显示全部关系，只回一条会逼它再发一次查询。
 */
export interface NationDiplomacyResp {
  /** 对方国家 id。 */
  targetNationId: string
  /** 生效后的关系。 */
  relation: DiplomacyRelation
  /** 本国与全部已知国家的关系。 */
  allRelations: NationRelationView[]
  /** 服务端时间戳。 */
  serverNow: number
}

/**
 * 一条外交关系（面板用）。
 */
export interface NationRelationView {
  /** 对方国家 id。 */
  nationId: string
  /** 对方国名，服务端下发。客户端不得自行拼接：那份名字要与战报、聊天、客服工单里的称呼一致。 */
  nationName: string
  /** 当前关系。 */
  relation: DiplomacyRelation
}

/**
 * 一条国库流水（B13 §3 验收 5：谁 / 何时 / 支给谁 / 多少，四样缺一不可）。
 *
 * <b>这张表存在的理由是防贪污</b>：国库是公共资产，而公共资产的纠纷会溢出到现实（盟主卷款、公会撕逼上社交媒体），所以它不是内部账本而是<b>要公示给成员看的东西</b> —— 只有服务端留档而玩家看不到的日志，等于把审计权交给了被审计的那个人。
 */
export interface TreasuryLogView {
  /** 这笔流水发生的服务端时刻。客户端不得用本地时间去排这个序（铁律 5）。 */
  at: number
  /** 谁做的。<b>系统动作写 {@code system}</b>（周税入账就是它），人做的写那个人的 id —— 没有「谁」的日志无法追责，所以领域层在签名上就不允许为空。 */
  operatorId: string
  /** 对手方：支给谁（出账）或来自哪里（入账）。同一个字段承担两个方向是刻意的 —— 领域层就是这么记的（{@code payee}），而把方向拆成两列会让「把金额加总」得出一个说不清的结论。 */
  counterparty: string
  /** 这笔的规模（国库资金单位，与 GOLD 同单位；划拨给玩家时 1:1），**必须是 long**（B01 的定点约定：全项目金额不用浮点）。方向由 counterparty 表达，所以这里恒为正 —— 一旦允许负数，同一张表里就会出现两种符号口径。 */
  amount: number
  /** 用途。领域层同样不允许为空：没有「为什么」的日志等于没有日志。 */
  reason: string
  /** 这笔之后的余额（国库资金单位）。带上它是为了让流水能**自证连贯**：任意相邻两行的余额差就该等于下一行的金额，对不上的那一段就是有人在改账本。 */
  balanceAfter: number
}

/**
 * GET /nation/treasury 的响应（B13 §二 的 {@code TreasuryResp}）。
 *
 * <b>谁能读</b>：本国任一成员联盟的成员 —— 这就是「防贪污」的全部机制所在，只给国王看等于没有。
 *
 * <b>为什么把余额与流水放在一起回</b>：面板要同时显示这两个，而分两次查就会得到两个时刻的数（余额与流水对不上，正是这条表唯一要防的那种形状）。国库容量在 {@code NationView} 里已有，这里不重复第二次。
 */
export interface NationTreasuryResp {
  /** 当前国库余额（国库资金单位，与 GOLD 同单位）。它是**现算后**的数：读国库这个动作会先把当周周税结清，理由与 {@code GET /nation} 同一条 —— 玩家看到的就该是当下的数。 */
  balance: number
  /** 流水，<b>按时间倒序</b>（最新在前，面板直接取前几条）。条数由 global.NATION_TREASURY_LOG_RETENTION 在领域层截断并保留最新的，协议不重复那个决定，只做透传。 */
  logs: TreasuryLogView[]
  /** 服务端时刻。与流水里的 {@code at} 同源，客户端据此算「三分钟前」这类相对时间。 */
  serverNow: number
}

/**
 * POST /nation/treasury/spend 请求体：从国库支出一笔（B13 §3）。
 *
 * **权限走 role_permission 表的 WITHDRAW_TREASURY，限额走领域层**（2026-09-13 裁决 C16）：该表原先只放开国主，而 B13 §2 给首相写的「国库支出（限额）」因为限额没有数值而一次也走不通。现在表放开到 OFFICER 档，限额由 {@code Nation.spend} 判定 = **本周实入库周税 × global.NATION_OFFICER_SPEND_WEEKLY_RATIO**，全国共用一个池子（不是每人一份），国王不受此限。
 *
 * **两种失败是两个错误码**：余额不足 13010、超本周限额 13011 —— 玩家的下一步不同（等国库进钱 vs 等下周额度刷新），合成一个码就是让客户端替玩家猜。
 *
 * **每笔都进国库日志**（谁/何时/支给谁/多少，验收 5）：扣账与写日志在领域层同一步完成，给玩家的那一笔在扣账之后走发放器 —— 顺序是刻意的，失败方向选「记了没发出去」而不是「发了没记」。
 */
export interface NationTreasurySpendReq {
  /** 幂等键。重放一次俸禄不该重复出账 —— 国库是公共资产，重复出账是真金白银的损失。 */
  requestId: string
  /** 落点类型。选 PLAYER 时必须给 payeeId；选 SINK 时必须给 sink。两者都给或都不给都会被拒（含糊的支给对象是这张日志最怕的东西）。 */
  payeeType: TreasuryPayeeType
  /** 收款玩家 id。payeeType=PLAYER 时必填，且必须是一个存在的玩家 —— 记在一个不存在的 id 上等于这笔钱没有收款人，而日志却写着有。 */
  payeeId: string | null
  /** 消耗性用途。payeeType=SINK 时必填。 */
  sink: TreasurySink | null
  /** 支出额（国库资金单位，正整数）。余额不足直接拒绝（NATION_TREASURY_NOT_ENOUGH），不做部分出账 —— 半笔俸禄比不发更难解释。 */
  amount: number
  /** 用途说明。领域层不允许为空：没有「为什么」的日志等于没有日志。 */
  reason: string
}

/**
 * POST /nation/treasury/spend 的响应。**回整条流水行**而不是只回余额：面板要立刻把这一笔显示出来，而它需要的就是日志那一行的四个字段（谁/何时/支给谁/多少）加余额。
 */
export interface NationTreasurySpendResp {
  /** 哪个国家。一次支取只动本国国库。 */
  nationId: string
  /** 支出后的国库余额（国库资金单位）。 */
  balance: number
  /** 落点的文本形态（player:<id> / sink:<用途>），与日志的 counterparty 列同源。 */
  payee: string
  /** 这一笔的规模（正整数）。 */
  amount: number
  /** 用途说明，原样回显。 */
  reason: string
  /** 刚写入的那条流水（它的 balanceAfter 应当等于上面的 balance）。 */
  log: TreasuryLogView
  /** 服务端时刻。 */
  serverNow: number
}

/**
 * 一条国策（`nation_policy.json` 的一行）。全量下发，顺序 = 表序，客户端不知道有几行。
 */
export interface NationPolicyView {
  /** `nation_policy.json` 的行 id，提案时原样回传（服务端按它查表，不认下标）。 */
  policyId: string
  /** 表里的中文名，服务端下发。客户端不硬编码国策名 —— 改一次文案不该要改客户端。 */
  name: string
  /** 改的是哪个数。客户端按它决定这一行画在哪个分组下，但**不自己算合成**（那是服务端的事，铁律 3）。 */
  effectAttr: NationPolicyEffectAttr
  /** 幅度（定点万分比：1500 = +15%）。**允许为负** —— 国策是「全国性增益**或减益**」（`role_permission` 那行 `perm_nation_set_national_policy` 的 why 原话），协议不能把它锁成非负。 */
  effectValueFixed: number
  /** 作用到的兵种中文名，服务端从 `unit.json` 查好下发；不针对特定兵种的国策（坚壁/丰收/征伐）为 null。 **下发中文名而不是 `targetUnit` id**：客户端不抄配置表（数值与中文名一律来自服务端），而玩家要看到的是「轻骑兵 T1」而不是 `unit_cavalry_t1`。 */
  targetUnitName: string | null
  /** 一句可直接上屏的效果说明（服务端拼好的，如「轻骑兵 T1 攻击 +15%」）。有了它，客户端就不必把 `effectAttr` × `effectValueFixed` × `targetUnitName` 自己拼一遍 —— 那正是「第二个家」的形状（改文案要改客户端，而且拼错的版本没人能发现）。 */
  effectText: string
}

/**
 * 调用者本轮投出的一票。**刻意只回「我投了什么」而不是「我能不能改」**：改票本批不做（要改就是先撤回再投，那是另一个动作与另一枚错误码），给一个客户端算得出来而服务端不认的「可改」标志就是第二个家。
 */
export interface NationMyVoteView {
  /** 投的是哪一条提案。 */
  proposalId: string
  /** true = 赞成，false = 反对。 */
  support: boolean
}

/**
 * 公示名单里的一名投票者。**带中文名而不是只给 id**：B13 §4 明文要求「票数与**参与者**可查」，而玩家要看到的是名字。
 */
export interface NationPolicyVoterView {
  playerId: string
  /** 玩家名，服务端下发（要与聊天、战报、客服工单里的称呼一致）。 */
  name: string
}

/**
 * 本轮的一条提案（B13 §二 的 `NationVoteReq(proposalId, support)` 投的就是它）。
 *
 * **公示的两份名单一次给全**（2026-09-30 裁决 A9）：200 人国约 2KB、800 人国约 8KB，都在 `global.PERF_PAYLOAD_MAX_BYTES=20480` 预算内（对照：排行榜最坏 4101B）。分页要引入另一个 N 与一套游标，而 800 人国要翻十几页才看得到名单 —— 为省几 KB 换一个「公示查不到人」，是本末倒置。
 */
export interface NationPolicyProposalView {
  /** 提案 id，投票时原样回传。 */
  proposalId: string
  /** 这条提案指向的国策（表里的一行）。 */
  policy: NationPolicyView
  /** 赞成票数。 */
  yes: number
  /** 反对票数。 */
  no: number
  /** 投了赞成的玩家（B13 §4「参与者可查」的正面那一份）。 */
  supporters: NationPolicyVoterView[]
  /** 投了反对的玩家。 */
  opponents: NationPolicyVoterView[]
  /** 提案人（国王或内政官，2026-09-30 裁决 A1 放开到官员档）的玩家 id。 */
  proposedBy: string
  /** 提案时刻（服务端时间戳）。它同时是槽位竞争的**末位排序键**（见 `NationPolicyRoundView.slotOrderNote`）。 */
  proposedAt: number
}

/**
 * `GET /nation/policy` 的响应：本国国策的全部状态（当前处在哪一段、本轮有哪些提案、哪些正在生效、什么时候开下一轮）。
 *
 * **一次给全**而不是分三个端点：面板本来就要同时显示「当前国策」与「本轮提案」，分两次查会得到两个时刻的数（提案刚被投掉、面板上还挂着），而国策公示的争议恰恰出在这种对不上的时刻。
 */
export interface NationPolicyRoundView {
  nationId: string
  /** 轮次处在哪一段。读这个动作本身就会惰性推进轮次（结算过期的国策、在该开窗的时刻开窗），与 `GET /nation` 顺手 `settleTax` 同一手法。 */
  phase: NationPolicyPhase
  /** 可同时生效的国策数（`nation_config.policySlotCount`，Lv1/Lv2/Lv3 = 1/2/3）。这个数决定了同轮多条提案通过时谁能占住槽位。 */
  policySlotCount: number
  /** 全部国策（表里的 8 行，顺序 = 表序）。提案下拉的候选就是这份，客户端不硬编码。 */
  policies: NationPolicyView[]
  /** 本轮的全部提案（含已投完的）。空数组 = 本轮还没有人提案。 */
  proposals: NationPolicyProposalView[]
  /** **当前正在生效**的国策（B21 块③：「生效期间可查『当前国策』」）。到期那一刻它会从这里消失，所以这个数组天然表达「还剩多久」。 */
  active: NationPolicyView[]
  /** 服务端算好的「此刻点提案会不会成功」（含权限位与轮次段判定）。客户端不许自己判第二遍。 */
  canPropose: boolean
  /** 拦着提案的原因；没拦着时是 `NONE`。 */
  proposeBlockReason: NationPolicyBlockReason
  /** 服务端算好的「此刻点投票会不会成功」。**Bot 恒为 false**（裁决 A8），但那是服务端判定 —— 协议里没有任何字段能让客户端声明「我是真人」，所以客户端绕不过去。 */
  canVote: boolean
  voteBlockReason: NationPolicyBlockReason
  /** 我（调用者）这轮提过的提案 id。同一条国策在同一轮里只能被提一次（`ALREADY_PROPOSED`），所以这个数组天然去重。 */
  myProposals: string[]
  /** 我（调用者）这轮投过的票。同一个提案只能投一次（`ALREADY_VOTED`），改票要走「撤回再投」而那一格本批不做。 */
  myVotes: NationMyVoteView[]
  /** 下一次开投票窗的时刻。取「当前生效国策里最早到期的那一刻」—— 也就是轮次自循环的驱动点（2026-09-30 裁决 A4）。`ACTIVE` 段之外为 0。 **必须由服务端下发而不是客户端拿时长自己加**：铁律 5 禁止在展示与判定两侧各算一遍时间。 */
  nextVoteAt: number
  /** 本轮投票窗的结束时刻（服务端时间戳）。不在 `VOTING` 段时为 0。窗口长度读 `global.NATION_VOTE_DURATION_HOURS`，那是它的唯一家。 */
  voteEndsAt: number
  /** 同轮多条提案都通过时槽位怎么分 —— **一句可上屏的说明**，服务端下发。 规则是「赞成率降序 → 赞成票数降序 → 提案时刻升序 → policyId 字典序」。前三级都能从票数与时刻直接推出，最后一级是**纯粹为了确定性**（同率同数同时刻时不能靠哈希顺序决定，那会让同一份存档复算出不同的结果）。 ⚠ **B13 与 B21 都没写过这一条**（通过门槛用的是相对 50%，槽位竞争是裁决 Q3 明确没选的第三项），所以它是实现侧定的规则，落在服务端一处，改它只改一处。 */
  slotOrderNote: string
  /** 服务端时刻。所有倒计时的基准，与 `nextVoteAt` / `voteEndsAt` 同源。 */
  serverNow: number
}

/**
 * `POST /nation/policy/propose` 请求体：把一条国策放进本轮提案池。
 *
 * **权限走 `role_permission` 表的 `SET_NATIONAL_POLICY`**（2026-09-30 裁决 A1 把 `allowOfficer` 从 false 改成 true，即国王与四类官员都可提案；`B13:49` 的「议员提案」随之退役）。
 *
 * **提案不消耗国库**：`TreasurySink` 只有 `NATIONAL_TECH` 与 `WAR_BOOST` 两值（B13 §3 的国库三用途在 2026-09-11 收敛过一次），国策不在其中 —— 所以这个请求里没有任何金额字段。
 */
export interface NationPolicyProposeReq {
  /** 幂等键。重放一次提案不该在本轮池里出现两条 —— 那会让公示的票数分母与提案数对不上，而公示的争议正是从这里开始的。 */
  requestId: string
  /** 提哪一条（`nation_policy.json` 的行 id）。服务端依次校验：行存在 → 身份有提案权 → 现在是提案段 → 本轮还没提过这一条。 */
  policyId: string
}

/**
 * 提案结果。**回整份轮次视图**而不是只回一个 proposalId：提案面板要立刻显示新提案与刷新后的票数，再发一次查询就会有两个时刻的数（提案刚被投掉、面板还挂着上一份）。
 */
export interface NationPolicyProposeResp {
  proposalId: string
  /** 提案之后的轮次视图（票数此刻全是 0，因为窗口还没开）。 */
  round: NationPolicyRoundView
}

/**
 * `POST /nation/policy/vote` 请求体：对一条提案投赞成或反对（B13 §二 的 `NationVoteReq(String proposalId, boolean support)` 原形）。
 *
 * **二值而不是排序选择**：B13 的契约原文就是 `boolean support`，所以玩家选的是「支持哪几条」，不是「把哪条排第一」。投票是每成员一票（裁决 A2），同一提案只能投一次；改票本批不做（要改就是先撤回再投，那是另一个动作与另一枚错误码）。
 */
export interface NationPolicyVoteReq {
  /** 幂等键。**这一条比提案更要紧**：重放一次投票会让票数凭空 +1，而公示的两个数字（票数与参与者名单）都是从这份账本算出来的 —— 票数与名单对不上正是这一格唯一要防的形状。 */
  requestId: string
  /** 投哪一条提案。 */
  proposalId: string
  /** true = 赞成，false = 反对。**不投也是一种选择**（弃权票不计入分母，裁决 A3 的推论），所以协议里没有「弃权」这个取值。 */
  support: boolean
}

/**
 * 投票结果。同样回整份轮次视图：投票之后要立刻看到票数与（自己的）选择，公示是这一格的核心交付物（B13 验收 11「结果公示可查」）。
 */
export interface NationPolicyVoteResp {
  proposalId: string
  /** 这一票投的是什么（原样回显）。 */
  support: boolean
  /** 投票之后的轮次视图。`yes` 与 `no` 之和必然等于「实际投票人数」（弃权不计入），界面可以拿它与两份名单的长度自证。 */
  round: NationPolicyRoundView
}
