/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * POST /bot/tick 请求体。
 *
 * <b>requestId 在这里只是追踪号，不是幂等键</b> —— 与 /season/settle 恰好相反：结算是「发钱」，重复调用必须只发一次；而 tick 是<b>泵</b>，每调一次就该把到点的待办往前推一轮，把它幂等掉等于让世界上所有 Bot 永远不动。所以本端点<b>刻意不占幂等键</b> —— 谁顺手加一次 tryAcquire，症状就是「第二次开始没有反应，而日志一切正常」。
 */
export interface AgentTickReq {
  /** 调用方（外部调度系统或巡检脚本）自己生成的追踪号，会进日志。服务端不校验它是否重复。 */
  requestId: string
}

/**
 * 一轮 tick 的体检数。<b>光有一组累计值没有诊断价值</b>：本轮 processed 必须和累计 executed / failed 一起看，否则「跑了很多轮但全失败」与「根本没跑」长得一模一样。
 */
export interface AgentTickResp {
  /** 累计跑过的 tick 轮数（进程内，重启归零）。 */
  rounds: number
  /** <b>本轮</b>处理的待办条数。0 表示没有到点的待办，属正常。 */
  processed: number
  /** 累计成功执行的动作数。processed 一直涨而它不涨，说明轮在跑但什么都没发生。 */
  executed: number
  /** 累计被拟人延迟推后的动作数（受击 3~30 秒、求助 10~120 秒）。非零才说明「延迟」这条路径真的在生效（B11 验收 9）。 */
  deferred: number
  /** 累计因「已不是 Bot」而摘掉的待办数（回收与转真人那条出口）。 */
  dropped: number
  /** 累计失败的 tick 数（读世界状态或执行动作抛出来）。<b>失败不许让那个 Bot 退出调度</b>，所以这里必须看得见。 */
  failed: number
  /** 累计「预算用完、把已摘出的待办放回队列」的次数。持续非零 = Bot 数或每轮预算要调（§五 C1 那条算术）。 */
  budgetHit: number
  /** 队列里还没到点的待办数。 */
  pending: number
  /** 累计成功升级建筑的次数（C1 落地的两条动作之一）。 */
  upgraded: number
  /** 累计成功训练兵种的次数（另一条）。 */
  trained: number
  /** 累计成功发出的打野出征次数（C1b：目标从「看得见的野怪」里按「打得过的最强」选，收口清单 #96）。它与 trained 一起构成验收 1「Bot 会造兵、会打野」的可核对计数。 */
  hunted: number
  /** 累计成功发出的采集出征次数（C1b：目标 = 看得见的最近资源点）。 */
  gathered: number
  /** 累计「体面跳过」的次数：决策与执行之间状态变了（或动作来自失误掷骰），动作没做成但什么都没坏。它与 failed 分开看 —— 「Bot 在空转」看这里，「Bot 在报错」看 failed，两件事的下一步动作完全不同。 */
  skipped: number
  /** 累计「决策树给出了、但 BotWorldAdapter 里没有对应分支」的动作数。<b>#96 之后 13 个动作全部可达，它应恒为 0</b> —— 非零说明决策树加了枚举而执行侧忘了接，那正是「有入口没有调用点」这一族的形状。 */
  unhandled: number
  /** 服务端此刻。 */
  serverNow: number
  /** 注册表里当前的托管账号数（运维视角）。<b>字段名刻意不叫 bots</b> —— B11 那条「Bot 标识不下发客户端」的红线由 NoBotFieldLeakTest 按字段名扫协议生成物，而它扫不到「这是运维专用契约」这种语义；一个运维计数没必要去撞那条线，改名比给卡口开豁免便宜，也不削弱它对玩家协议的约束力。 */
  agents: number
}
