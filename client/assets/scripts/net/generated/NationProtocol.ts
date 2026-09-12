/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 国家官职（B13 §2）。取值必须与 game-core 的 Nation.Office 逐一对应，由 NationContractParityTest 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端认得的官职客户端显示成未知，UI 只会空白。
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
 * **权限走 role_permission 表的 WITHDRAW_TREASURY**（该表 allowLeader=true、其余两档 false，why 写明「国库支取涉及全国资源，只给国主」）—— B13 §2 给首相写了「国库支出（限额）」，但那个限额没有数值，所以本轮以权限表为准、首相暂不可支取（要放开就先给限额定数并改表）。
 *
 * **每笔都进国库日志**（谁/何时/支给谁/多少，验收 5）：扣账与写日志在领域层同一步完成，给玩家的那一笔在扣账之后走发放器 —— 顺序是刻意的，失败方向选「记了没发出去」（那笔有补偿队列与客服入口，反过来没有）。
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
