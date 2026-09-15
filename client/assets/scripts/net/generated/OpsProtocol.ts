/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 一个埋点事件（B16 §3 事件字典的一行）。事件名不在契约里枚举：字典是运营与分析侧的资产，会随版本增删，写进契约就等于每次加一个按钮都要改双端代码并重新生成 —— 而验收 3 要求的覆盖率是拿脚本比对 UI 清单，不是比对枚举。
 */
export interface TrackEvent {
  /** 事件名，形如 startup / login / guide_step / building_upgrade_start / speedup_used / pay_click / pay_success / battle_start / battle_lost / churn。没有名字的事件在分析侧无法归类，却已经占了上报配额，所以服务端与客户端都拒绝空名。 */
  name: string
  /** 事件发生的客户端毫秒时间戳。**只用它排序，不用它算时长**：B00 硬约束「时间以服务端时间戳为准」，客户端时钟可以被玩家改，所以任何留存/时长口径都以服务端落库时间为准，这个字段的价值是保留同一批事件内部的先后顺序 —— 而那个顺序在服务端落库时会因为批量到达而丢失。 */
  ts: number
  /** 事件参数。值统一为字符串：埋点参数会进日志与看板，类型化的代价是每加一种参数类型就要改契约，而收益只有「少写一次 String.valueOf」。空 map 表示无参数，不允许 null —— 下游遍历 null 会 NPE，而 NPE 发生在上报线程里会让整批事件消失。 */
  params: Record<string, string>
}

/**
 * POST /ops/track/batch 请求体。一次上报一批（B16 §3：10 条或 10 秒触发）。
 *
 * **不带 requestId**：见本文件 description 的约束 2。
 */
export interface TrackBatchReq {
  /** 本批事件，按发生顺序。攒批上限来自 global.TRACK_BATCH_MAX_SIZE（随版本检查下发给客户端，所以那一份与客户端用的是同一个数）。 **入口不做拒绝式硬上限**（2026-09-13 裁决）：服务端只按 global.TRACK_INGEST_SOFT_LIMIT_FACTOR × TRACK_BATCH_MAX_SIZE 设一道**软**上限，超了保留前面若干条、截掉多余的并计入响应的 failed，HTTP 仍是 200。为什么不是硬拒：一次战斗本身就产生十几个事件，拿攒批上限当门槛会把真实战斗事件整批丢掉 —— 丢看板数据比来噪音糟。为什么这条判断必须存在：/ops/ 是不要求身份的公开路径，它是服务端唯一的工作量上界。 （本字段原先写着「服务端按 PERF_PAYLOAD_MAX_BYTES 校验体积」，而服务端从未有过那段代码 —— 那句话描述的是一个提案，不是事实。） */
  events: TrackEvent[]
}

/**
 * 埋点上报结果。**部分失败是正常返回而不是错误**：埋点是尽力而为的数据，一批里有一条参数非法就让整批失败，等于用一条脏数据换掉九条好数据。
 */
export interface TrackBatchResp {
  /** 落库成功的事件数。 */
  accepted: number
  /** 被丢弃的事件数 —— **两种原因合并计数**：事件名为空，以及超出入口软上限被截断。合并而不加第三个字段，是因为客户端对两者的处置完全相同（都不重投：一条脏数据重试一万次还是脏的，被截断的那一段则已经明确放弃了）。持续非零说明字典与实现已经漂移，或有人在直接刷这个不需要身份的端点，需要人去看而不是让它静默地一直失败。截断的累计条数由 GET /ops/ingest 单独带出。 */
  failed: number
}

/**
 * GET /ops/ingest 响应：埋点入口的健康度（只读，需运维令牌）。
 *
 * **这个端点存在的理由是「计数必须有出口」**：一个只有测试在读的 AtomicLong 与一个没有调用点的方法是一回事 —— 都会长成「机制在、没人看」，而 #58 那条裁决要的是「洪水必须可见」。可见的意思不是代码里有个计数器，是有人能查得到。
 */
export interface TrackIngestResp {
  /** 当前生效的入口软上限（= TRACK_BATCH_MAX_SIZE × TRACK_INGEST_SOFT_LIMIT_FACTOR）。随配置热更变化，所以每次都要回出来，不要让运维记住一个数。 */
  softLimitEvents: number
  /** 本进程启动以来因超过软上限而被截断的事件累计条数。非零即需人看：要么有客户端的攒批策略与本服不同步，要么有人在刷这个不要求身份的端点。**做成 64 位**是因为它是一个只增不减的累计值 —— 一个 int 装不下一个跑了一年的进程。 */
  truncatedEvents: number
  /** 服务端二次攒批器里尚未落库的事件数（TrackFlusher 的待发队列）。 */
  pendingEvents: number
  /** 服务端已写库的批次数。与事件数一起看才是验收 3 的「批数远小于事件数」，只看一个数说明不了什么。 */
  flushedBatches: number
}

/**
 * GET /ops/pay/debt 响应：钱收了、货没发出去的负债（只读，需运维令牌）。
 *
 * **为什么必须有出口**：#27 那条 ERROR 日志的原话是「这笔钱已经收了，必须有人跟进」，而 `unfulfilledCents()` 与 `retryQueue()` 此前**生产调用点为零** —— 只有测试在读。日志喊了但没人能查账，等于没有账。
 */
export interface PayDebtResp {
  /** 未发货负债总额（分）。对账口径：**这个数必须最终归零**，它变大就是负债积压，该报警而不是该优化查询。金额一律 64 位（本仓库所有「分」都是）。 */
  unfulfilledCents: number
  /** 未发货的订单笔数（与端口 {@code unfulfilledOrderCount()} 同为 64 位）。与总额一起看才分得清「一笔大的」和「一堆小的」这两种完全不同的成因。 */
  unfulfilledOrders: number
  /** 本响应实际带出的笔数（受 limit 约束）。**listed 小于 unfulfilledOrders 就说明还有没列出来的**，所以不把两者合并成一个数 —— 合并了运维就会以为看到的就是全部。 */
  listed: number
  /** 待补发的订单明细，按存储层的顺序。 */
  orders: DebtOrderView[]
}

/**
 * 一笔负债。只给跟进需要的字段：谁、哪一单、多少钱、试了几次、上次为什么没发出去。
 *
 * **刻意不给 transactionId**：那是渠道侧的支付凭证号，出现在一个运维列表里对它没有任何用处，而多一处出现就多一处泄露面。要对着渠道查账的人应该走渠道后台。
 */
export interface DebtOrderView {
  /** 订单号（本服生成）。 */
  orderId: string
  /** 该给谁发货。 */
  playerId: string
  /** 这一单的金额（分），与 unfulfilledCents 同一口径。 */
  cents: number
  /** 确认收款的时刻（毫秒）。玩家已经付了多久 —— 这是排优先级的第一依据。 */
  paidAt: number
  /** 已尝试发货的次数。为 0 表示回调进来了但从没试过，非 0 表示试过且都失败 —— 两者的处理人不同。 */
  fulfillAttempts: number
  /** 最后一次失败的原因。可空：从未尝试过的时候没有原因可说（不是「原因为空字符串」）。 */
  failureReason: string | null
}

/**
 * POST /ops/crash 请求体（B16 §6 全局错误捕获 + 上报，验收 9：后台能收到完整堆栈 + traceId）。
 *
 * **traceId 是这个契约存在的理由**：没有它，后台收到的是一堆匿名堆栈，而线上排查的第一步永远是「这个玩家当时在做什么」。禁止项写死了「不要让日志无 traceId」。
 */
export interface CrashReportReq {
  /** 全链路追踪 id。头名在服务端是 TraceIdFilter.TRACE_HEADER 常量，客户端网络层持同一字面量 —— **它刻意不做成配置项**：客户端发第一个请求时就要用这个头，无法跟随服务端配置热更，做成配置只会多出一份会与代码漂移的副本。客户端在每次请求时透传，崩溃时把最后一条上报出来 —— 于是「玩家说卡住了」可以被还原成一条完整链路。 */
  traceId: string
  /** 错误摘要。取异常的第一行而不是整个 message：某些异常的 message 里嵌了完整的请求体，会把这一条上报撑到超过 payload 预算。 */
  message: string
  /** 完整堆栈。验收 9 要求「完整」，所以不做截断 —— 但客户端必须在发送前把它压到 payload 预算内（超长时保留头部与尾部，中间省略并标注省略行数），因为丢掉的往往正是最深的那一帧。 */
  stack: string
  /** 崩溃时的客户端版本。灰度期间这是最关键的一个字段：5% 灰度批次里崩溃率翻倍，只有按版本分组才能看出来，而「崩溃率上升」这个总量指标在 5% 的批次里根本不动。 */
  clientVersion: string
  /** 崩溃时所在场景（world / battle / city / ...）。为 null 表示崩在场景切换之间 —— 那本身就是一种有价值的定位信息，所以用 null 而不是空串，空串会被读成「有个叫空名字的场景」。 */
  sceneName: string | null
  /** 崩溃的客户端毫秒时间戳。落库时同时记录服务端时间，两者之差就是客户端时钟偏移量 —— 那是判断「这个玩家的倒计时为什么不对」的直接证据。 */
  ts: number
}

/**
 * 崩溃上报结果。**永远返回 accepted=true 或走 HTTP 错误，不做业务失败分支**：崩溃上报的调用方是一个已经处于异常状态里的客户端，给它一个需要再处理一遍的失败语义，等于在崩溃处理里再制造一次崩溃机会。
 */
export interface CrashReportResp {
  /** 是否已收下。false 只在服务端自身落库失败时出现，此时客户端不重试（重试也没用），但会在下次启动时补报。 */
  accepted: boolean
}

/**
 * 一张配置表的元信息（B16 §5 配置热更）。
 */
export interface TableMeta {
  /** 表名，形如 global / unit / season。 */
  name: string
  /** 表版本号。**仅供人读与日志排查，不参与热更判定**：版本号是人给的，会出现「改了内容忘了升版本」，那时客户端认为自己是最新的而实际上不是。判定一律走 hash。用字符串而不是整数，是为了将来能放 git 短 sha 这类非数字版本。 */
  version: string
  /** 表内容的指纹，由行数据算出（不含版本号）。内容变了 hash 就变 —— 内容变了而 hash 没变在数学上不可能，这正是验收 7「修改配置表后客户端拉取新版本，不改包生效」的判定依据。 */
  hash: string
}

/**
 * POST /ops/config/manifest 请求体：客户端报上自己手里各表的 hash。
 */
export interface ConfigManifestReq {
  /** 客户端本地各表的 hash。缺一张表（新装或首次登录）就不放这个 key —— 缺 key 与「hash 为空串」语义不同：前者是「我没有这张表」，后者是「我有一张内容未知的表」，而后者不该存在，所以不允许。 */
  tableHashes: Record<string, string>
}

/**
 * 配置清单响应（验收 7）。同时给全量清单与「哪些表要更新」：清单用于展示与排查，outdated 用于决定下载什么。
 */
export interface ConfigManifestResp {
  /** 清单版本，取服务端全部表版本的指纹。客户端把它记在本地，下次请求时若相同则可以整份跳过 —— 那是一次纯粹的省流量优化，正确性仍然由每张表的 hash 保证。 */
  version: string
  /** 全部可热更的表（范围见 global.HOT_UPDATE_SCOPE）。 */
  tables: TableMeta[]
  /** 客户端需要下载的表名，按 tables 的顺序。**由服务端算**：比 hash 不比版本号这条规则只能存在一份。 */
  outdated: string[]
}

/**
 * 客户端的埋点攒批策略，由服务端下发（B16 §3：10 条或 10 秒触发）。
 *
 * **为什么由服务端下发而不是客户端写死**：客户端没有配置表加载器，写死 10 条 / 10 秒就是铁律 1 禁止的硬编码；而这两个数字是运营口径 —— 分析侧发现漏斗数据太粗时会想把批调小，那时不该要求客户端发版。
 *
 * **为什么搭 AppVersionResp 的车而不是单开端点**：版本检查是每个会话的第一个请求，它本身就在下发「这个客户端应当如何行为」（要不要强制更新、在不在灰度里），埋点策略属于同一类。多一个端点意味着多一次弱网下的往返，而弱网恰恰是埋点最需要工作的场景。
 */
export interface TrackPolicy {
  /** 攒够多少条发一批。来源 global.TRACK_BATCH_MAX_SIZE。客户端据此决定何时触发一次上报请求；服务端按同一个值攒批落库，两边同源所以不会出现「客户端发 50 条而服务端按 10 条拒收」这种漂移。 */
  maxBatchSize: number
  /** 最长攒多少秒。来源 global.TRACK_BATCH_FLUSH_SECONDS。这一路是给低频玩家兜底的：只按条数的话，一个点两下就退出的玩家那两个事件永远发不出去，而「进来就退」正是流失分析最需要的样本。时间窗从队首事件算起，不是从上一次发送算起。 */
  flushSeconds: number
}

/**
 * POST /ops/app/version 请求体：客户端启动时报上自己的版本与玩家 id。
 */
export interface AppVersionReq {
  /** 客户端自报版本，点分数字（形如 1.4.0）。**服务端按段比较而不是按字符串比较**：字符串序里 "1.10.0" < "1.9.0"，于是 1.9 之后的所有版本都会被判为更旧，全服被要求强制更新到一个不存在的版本 —— 那是一个会直接停服的 bug，而它在 1.9 之前永远测不出来。 */
  clientVersion: string
  /** 玩家 id，未登录时为 null。**灰度按它做稳定哈希**，所以未登录的人不进灰度：灰度批次里的崩溃必须能归因到具体玩家，否则那 5% 里发生的事无法排查。null 与空串在这里同义，都读作「还没有玩家身份」。 */
  playerId: string | null
}

/**
 * 版本检查结果（验收 8：低版本客户端收到强制更新提示且无法进入游戏）。
 */
export interface AppVersionResp {
  /** 最新客户端版本，来自 global.RELEASE_LATEST_VERSION。 */
  latest: string
  /** 是否需要强制更新。true 时客户端必须停在提示页，不得进入游戏 —— 放行一个低于 minSupported 的版本等于让它在服务器上写坏数据，而那种损坏在玩家更新之后才会显现，届时已经无法归因。 */
  forceUpdate: boolean
  /** 该玩家是否落在灰度批次内（global.RELEASE_GRAY_PERCENT）。**与 forceUpdate 正交**：灰度 5% 不等于另外 95% 的人不能玩。同一个玩家的判定结果每次一致（稳定哈希），否则他刷新一次就可能从灰度里掉出去，而「刚才能玩现在不能玩」是最难排查的一类投诉。 */
  grayEnabled: boolean
  /** 强制更新的提示文案（global.RELEASE_FORCE_UPDATE_NOTICE），forceUpdate=false 时为 null —— 不强制更新时不该打扰玩家。文案必须说明「为什么」：只说「请更新」的话，被挡在门外的玩家会以为游戏坏了而直接卸载。 */
  notice: string | null
  /** 本次会话应当使用的埋点攒批策略。<b>必填而不是可空</b>：客户端从第一个事件（startup）开始就要按策略攒批，而版本检查正是启动的第一个请求，所以策略在这一刻必然已经拿到。做成可空的话客户端就必须准备一套兜底数字，而那套兜底数字正是铁律 1 禁止的硬编码 —— 更糟的是它会与服务端悄悄漂移。 */
  trackPolicy: TrackPolicy
}
