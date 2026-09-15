/**
 * 职责：客户端埋点事件字典 —— 事件名与参数的唯一归属。
 * 依赖：无（纯常量）。
 *
 * <p><b>为什么字典放在客户端代码里而不是协议里</b>：`ops.schema.json` 的 `TrackEvent.name`
 * 刻意是自由字符串（见 `数据看板需求.md` §七 的说明）。写进契约就等于每加一个按钮都要改双端
 * 代码并重新生成，而埋点恰恰是最常增删的那类清单。
 *
 * <p><b>代价是要有卡口</b>：字典不在契约里，就意味着"名字写错了"不会有任何地方报错，
 * 只会在看板上表现为某一环永远是 0。所以配套有 `scripts/check-track-coverage.sh`
 * （B16 验收 3），它比对 UI 动作清单与这里的事件名，漏一个就构建失败。
 */

export const TRACK_EVENTS = {
  /** 客户端启动（登录前，此时没有 playerId）。 */
  startup: 'startup',
  /**
   * 一次启动的最终结果（`[boot]` 自检行的字段）。
   *
   * <p><b>与 {@code startup} 不是重复事件</b>：那条是"有客户端开始启动"（登录前、无身份，
   * 漏斗最前一环，也是崩溃率的分母），这条是"这一次启动最终到没到可交互"。
   * 合在一起的后果是分母里掺进了半途失败的那批，崩溃率会被稀释。
   *
   * <p><b>为什么要有这条</b>：自检行只存在于开发者工具的 Console 里，而 IDE 不把它落到任何可读文件
   * —— 于是"在模拟器里跑通了"没有机器可复核的证据。它带着 `bootMs`，所以小游戏运行时的
   * 首屏耗时第一次变得可查（真机没有自动化接口，那条只能靠人扫码看）。
   */
  bootCheck: 'boot_check',
  /**
   * 某个面板的数据读取失败（网络或业务码）。
   *
   * <p>此前这条路径的出口只有 `console.warn`：面板停在空数据上，玩家以为"这个功能就是这样"，
   * 而看板上那一格永远是 0 也没人怀疑到读取失败上。发出来才有人能查（`GET /ops/track/recent`）。
   */
  panelLoadFailed: 'panel_load_failed',
  /** 登录成功。 */
  login: 'login',
  /** 引导每一步完成。引导系统本身还没实现（B12），字典先占位以免看板缺一环。 */
  guideStep: 'guide_step',
  /** 开始升级建筑。 */
  buildingUpgradeStart: 'building_upgrade_start',
  /** 升级完成（含离线结算那种）。 */
  buildingUpgradeFinish: 'building_upgrade_finish',
  /** 使用加速类道具。 */
  speedupUsed: 'speedup_used',
  /** 点击付费入口。 */
  payClick: 'pay_click',
  /** 支付成功且发货完成。 */
  paySuccess: 'pay_success',
  /** 战斗发起。 */
  battleStart: 'battle_start',
  /** 战败。 */
  battleLost: 'battle_lost',
  /** 客户端预判的流失信号（长时间无操作后退出）。 */
  churn: 'churn',
  /** 流亡迁城（B08 §5 反击工具箱）。看板要拿它判断"被追杀到没法玩"的强度。 */
  exile: 'exile',
  /** 收割到点的建筑（含一键收割）。 */
  gatherCollect: 'gather_collect',
  /** 开始训练士兵。 */
  armyTrain: 'army_train',
  /** 治疗伤兵。 */
  armyTreat: 'army_treat',
  /** 使用道具（含因缺选择器而被挡下的那一次点击）。 */
  itemUse: 'item_use',
  /** 尝试卖出道具。服务端还没有出售端点，所以这个名字同时也是那条缺口的计数器。 */
  itemSell: 'item_sell',
  /** 踢成员。`from` 区分小队与联盟。 */
  memberKick: 'member_kick',
  /** 社交事件标记已读（红点与离线补偿同一本账）。 */
  eventsAck: 'social_events_ack',
  /** 发起一次目标搜索。 */
  targetsSearch: 'targets_search',
  /** 捐献联盟。 */
  allianceDonate: 'alliance_donate',
  /** 帮助一次队友的请求。 */
  socialHelp: 'social_help',
  /** 一键帮助全部。 */
  socialHelpAll: 'social_help_all',
  /**
   * 领取一条任务的奖励（B12 §1）。`needsChoice` 标记「这次领取要不要先三选一」——
   * 首日那个送将任务是唯一带候选的，看板靠它区分「直接领」与「选完再领」两条路径。
   */
  questClaim: 'quest_claim',
  /**
   * 一键领取邮件（B12 §2）。**不带参数是刻意的**：领几封、领到什么全是服务端的结果，
   * 客户端在点下去的那一刻只知道「玩家要领」。结果的计数在 `/mail/claimAll` 的响应侧，
   * 而失败本来就有 `panel_load_failed` 那一条兜着。
   */
  mailClaimAll: 'mail_claim_all',
  /** 点开一封邮件（标已读）。带 mailId：「哪一类邮件没人看」是这格唯一能回答的问题。 */
  mailRead: 'mail_read',
} as const

export type TrackEventName = typeof TRACK_EVENTS[keyof typeof TRACK_EVENTS]

/**
 * 参数值统一成字符串，且**空值落成空串而不是 null**。
 *
 * <p>协议注释写得很直接：`params` 里不允许 null，因为下游遍历 null 会 NPE，
 * 而 NPE 发生在上报线程里会让**整批**事件消失 —— 一个空参数换一批数据，是最亏的交换。
 */
export function trackParam(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined) {
    return ''
  }
  return String(value)
}
