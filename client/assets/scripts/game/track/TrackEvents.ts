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
  /** 开始升级建筑。 */
  buildingUpgradeStart: 'building_upgrade_start',
  /** 升级完成（含离线结算那种）。 */
  buildingUpgradeFinish: 'building_upgrade_finish',
  /** 使用加速类道具。 */
  speedupUsed: 'speedup_used',
  /** 点击付费入口。 */
  payClick: 'pay_click',
  /** 礼包弹窗真的显示了一次（B19 S3-iv）。判据是「服务端说弹且面板确实画出来了」，不是「拉到了接口」。 */
  payPopupShow: 'pay_popup_show',
  /** 支付成功且发货完成。 */
  paySuccess: 'pay_success',
  /** 战斗发起。 */
  battleStart: 'battle_start',
  /** 战败。 */
  battleLost: 'battle_lost',
  /** 把一场战报分享到小队 / 联盟频道（B22 §一 2）。分享率是社交留存的一环，而它**不发奖励**。 */
  reportShare: 'report_share',
  /** 提交一条举报（B22 §一 3）。举报量是审核侧的输入，也是平台合规要看的那个数。 */
  reportSubmit: 'report_submit',
  /** 拉黑 / 取消拉黑（B22 §一 3）。它只影响交流，不动战斗 —— 这个数突然涨说明社区氛围出了问题。 */
  blockChanged: 'block_changed',
  /** 关注 / 取消关注（B22 §一 4）。关注是"我想再找他"，它的涨落比拉黑更早反映社交温度。 */
  followChanged: 'follow_changed',
  /** 客户端预判的流失信号（长时间无操作后退出）。 */
  churn: 'churn',
  /** 流亡迁城（B08 §5 反击工具箱）。看板要拿它判断"被追杀到没法玩"的强度。 */
  exile: 'exile',
  /** 收割到点的建筑（含一键收割）。 */
  gatherCollect: 'gather_collect',
  /** 开始训练士兵。 */
  armyTrain: 'army_train',
  /**
   * 取消某一口的训练（B26 S15）。带 unitId：取消一直集中在同一兵种上，
   * 说明那一口的时长或队列容量配得不合适（玩家排了又后悔）。
   */
  armyTrainCancel: 'army_train_cancel',
  /**
   * 开关自动续训 / 自动补兵（B25-S2d）。
   * `on=true/false` 分开看：开得多说明减负被接受，关得多多半是"资源被自动花掉"这类反馈。
   */
  autoTrain: 'auto_train',
  /**
   * 弹出「自上次登录以来」那一屏（B25-S3）。items 是条目数 ——
   * 它一直是 0 或 1 说明阈值把绝大多数登录都挡掉了，那是调参信号而不是"没人看"。
   */
  offlineReport: 'offline_report',
  /** 点开汇总里的某一条（target 是那一条跳去的页）。哪一类最常被点开决定这个功能往哪投。 */
  offlineReportJump: 'offline_report_jump',
  /** 治疗伤兵。 */
  armyTreat: 'army_treat',
  /** 使用道具（含因缺选择器而被挡下的那一次点击）。 */
  itemUse: 'item_use',
  /** 兑换一行商品（B24 商店）。currency+rowId 分开看：哪一页被换得多、哪一行最常被换。 */
  shopBuy: 'shop_buy',
  /** 切商店页签。四个币种的余额与限购各是各的账本，各页的流量是要分开看的。 */
  shopTab: 'shop_tab',
  /**
   * 戴上 / 卸下头像框（B24 块③）。`frameId` 是那一枚的 id，卸下记 `none` ——
   * 两者分开看才知道玩家是在收集还是在退坑。
   */
  frameWear: 'frame_wear',
  /** 加入一支集结（V02）。承诺的兵力会被锁住，所以这是"真的投入"而不是浏览。 */
  rallyJoin: 'rally_join',
  /** 退出一支集结（自己走，队伍还在）。 */
  rallyQuit: 'rally_quit',
  /** 发起人取消整支集结（与退出分开：这一下会退掉所有人的承诺兵力）。 */
  rallyCancel: 'rally_cancel',
  /**
   * 领一档战令奖励（B24）。 区分免费/付费 —— 两条线的领取比例是'付费线值不值'的第一手证据。
   */
  battlePassClaim: 'battle_pass_claim',
  /** 尝试卖出道具。服务端还没有出售端点，所以这个名字同时也是那条缺口的计数器。 */
  /** 踢成员。`from` 区分小队与联盟。 */
  memberKick: 'member_kick',
  /** 社交事件标记已读（红点与离线补偿同一本账）。 */
  eventsAck: 'social_events_ack',
  /** 发起一次目标搜索。 */
  targetsSearch: 'targets_search',
  /** 捐献联盟。 */
  allianceDonate: 'alliance_donate',
  /** 建了一个组织（B26 S2）。带 scope：小队与联盟是两条完全不同的漏斗，混在一起就看不出卡在哪。 */
  socialCreate: 'social_create',
  /** 离开组织（B26 S3）。第一下"确认…"不计数 —— 只记真发出去的那一枪。 */
  socialLeave: 'social_leave',
  /** 解散组织（B26 S3，不可逆）。它和 social_leave 分开：一个是走人，一个是把房子拆了。 */
  socialDisband: 'social_disband',
  /** 把队长/盟主交给某个成员（B26 S4）。带 memberId：转让去向集中在少数人身上就是"队长在培养接班人"的信号。 */
  socialTransfer: 'social_transfer',
  /** 扩联盟人数上限（B26 S5）：中后期最大的资金消耗点，它为零说明没人把联盟当长期投入。 */
  allianceExpand: 'alliance_expand',
  /** 申请加入一个联盟（B26 S6）。它与 alliance_create 的比例就是"这个世界里加入比建团容易多少"。 */
  allianceApply: 'alliance_apply',
  /**
   * 加入一支小队（B26 S7）。它与 socialCreate{scope:squad} 分开计：加入是零成本的，
   * 两个人数靠得最近的动作混在一个事件里，就看不出新人到底是被"找队"还是"建队"卡住的。
   */
  squadJoin: 'squad_join',
  /**
   * 审核一条入盟申请（B26 S8）。带 approve：批准与拒绝的比例看得出盟主是在挑人还是在清队列，
   * 而这一条为零、申请数却在涨，说明门槛外的人一直进不来。
   */
  allianceReview: 'alliance_review',
  /**
   * 研究一级联盟科技（B26 S9）。带 techId：哪几项一直没人研究，就是这份科技表该不该重做的依据。
   */
  allianceResearch: 'alliance_research',
  /**
   * 任命一个联盟成员的职位（B26 S11）。带目标与新任：任命集中在少数人身上、
   * 或几乎只往 MEMBER 方向走（一直在撤职），都是联盟人事出问题的信号。
   */
  allianceSetRole: 'alliance_set_role',
  /**
   * 发起一次集结（B26 S12）。带层级与兵力：集结发起率与"有没有人加入"合看，
   * 才知道这玩法是没人用还是用了凑不齐人。
   */
  rallyInitiate: 'rally_initiate',
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
  /** 领取一次活动奖励（B17）。带 activityId —— 看板上要能看出哪条活动在发奖。 */
  activityClaim: 'activity_claim',
  /**
   * 新手引导的一步（B18 §一.4）。一个事件名、`action` 分三种：
   * `enter`（这一步弹出来了）/ `complete`（玩家喊做完了，服务端认不认看回执）/
   * `skip`（点了跳过，只有可跳的步会发）。多出来的一种 `outside_tap` 记"遮罩外乱点"，
   * 每步最多一次 —— 看板上它是"这一步没讲明白"的信号，而不是玩家在手滑。
   *
   * <p>带 `guideVersion` 是为了把"改了脚本之后掉了一截完成率"与"玩家就是不做"分开：
   * 没有它，一次热更会在看板上长得像一次流失。
   */
  guideStep: 'guide_step',

  /**
   * 打开了一张榜（B23 §一 3）。参数是榜的类型 —— 看板上问的是"玩家到底看哪张榜"，
   * 那决定了下一批内容往哪张榜上投人。翻页不发（同一次浏览的延续，发了会把一次阅读记成多次）。
   */
  rankView: 'rank_view',

  /**
   * 打开赛季页（V04-S1）。**不带参数**：这一页要答的是"玩家会不会去看赛季规则、
   * 时限与保留项"（B14/B09 反复说明玩家最怕的是"我攒的东西会不会没"），
   * 而它可以参数化的只有"第几次打开"这类没人会看的东西。
   * 与 rank_view 分开上报：它是同一面板里的第六个页签，但不是一个榜。
   */
  seasonView: 'season_view',

  /**
   * 打开研究页（V03-a-S1）。**不带参数**：要答的是"玩家会不会去看科技树"——
   * V03 的初衷是"已经有研究能力，但玩家不知道"，所以"打开过没有"就是这一格的核心读数。
   * 具体看了哪一条不在这里上报（行上的动作将来各自有事件）。
   */
  techView: 'tech_view',

  /**
   * 打开装备页（V03-b-S1）。**不带参数**：要答的是"玩家会不会去看自己有哪些装备、要不要强化"——
   * 与 `tech_view` 同一条理由（V03 的初衷是"能力已经在，玩家不知道"）。
   */
  equipView: 'equip_view',

  /**
   * 武将升星（V03 前置：把已有的养成能力接到玩家手上）。参数是武将 id ——
   * 看板上要答的是"玩家有没有在养成武将、养的是谁"，而升星是六条养成里两条**不需要先选道具**的动作之一。
   */
  heroStarUp: 'hero_star_up',

  /**
   * 给武将穿/卸装备（V03-d 第一批）。参数 `action`（wear / takeOff）、`heroId`、`slot` ——
   * 看板上要答的是"换装是不是一条玩家真的在用的路"（V03 的初衷：能力已经在，玩家够不到）。
   */
  heroEquip: 'hero_equip',

  /**
   * 喂经验道具升级（V03-d）。参数 `heroId`、`kinds`（喂了几种道具）——
   * 看板上要答的是"玩家有没有真的在升武将"；**开弹层与加减不单独上报**（那还不是意图）。
   */
  heroLevelUp: 'hero_level_up',

  /**
   * 用一块觉醒石推进一阶（V03-d 第二条养成线）。参数 `heroId`、`itemId`（用了哪块石）、
   * `tier`（推到第几阶）—— 看板上要答的是"稀缺的觉醒材料有没有被用出去、玩家卡在第几阶"。
   * **开弹层与选石头不单独上报**：那还不是意图，与 `hero_level_up` 同一条口径。
   */
  heroAwaken: 'hero_awaken',

  /**
   * 用一本技能书升一路技能（V03-d 最后一条）。参数 `heroId`、`itemId`（用了哪本）、
   * `slot`（升的是主技能还是副技能）—— 看板上要答的是"两路技能有没有人真的都在升"：
   * 副技能只在副将位生效，如果 `MAIN` 占了绝大多数，说明玩家根本没用过副将那一档。
   * **开弹层与换书不单独上报**：与 `hero_level_up`、`hero_awaken` 同一条口径。
   */
  heroSkillUp: 'hero_skill_up',

  /**
   * 用一档碎片合成一名未拥有的武将（V03-d 第六条线）。参数 `heroId`（合成出的是谁）——
   * 看板上要答的是"碎片到底有没有被花出去"：`/hero/compose` 是碎片唯一的出口之一，
   * 这个事件为零就说明玩家在手攒一堆用不掉的碎片，那是经济系统卡住的直接信号。
   * **开弹层与换选中不单独上报**：与 `hero_level_up`、`hero_awaken` 同一条口径（还不是意图）。
   */
  heroCompose: 'hero_compose',

  /**
   * 抽一次或十次（抽卡入口）。参数 `poolId`（在哪个池抽的）、`count`（几连）——
   * 看板上要答的是"三个池子各自有没有人抽、十连占多少"：新手池限抽 1 次，
   * 没人抽它就说明首日漏斗在抽卡之前就断了。
   * **打开面板与换选中不单独上报**：与 `hero_compose` 同一条口径（还不是意图）。
   */
  gachaDraw: 'gacha_draw',

  /**
   * 强化一件装备一级（B20 块②，V11）。一次一级、纯消耗必成，所以这一条就是"铁被吃掉了多少"的计数。
   * 参数刻意只有件数：等级与战力增量都在服务端那份视图里，客户端上报一份就成了第二真相。
   */
  equipForge: 'equip_forge',

  /**
   * 保存一套编队（B06 §4）。参数 `presetIndex` 与三名武将 id（空位是空串）——
   * 看板上要答的是"玩家到底编不编队"：编队决定缘分与乘区，全是默认编队就说明这一层没人用。
   * **打开编辑器 / 换槽位 / 换选中都不单独上报**：编队是"改完一次提交"，
   * 逐次上报会把一次意图记成四五次（与 `hero_compose` 同一条口径）。
   */
  heroLineupSave: 'hero_lineup_save',

  /**
   * 发起一次出征（B25-S1 的首次出征入口）。参数是行动类型与带兵总数 ——
   * 看板上要答的是"玩家一次派多少兵出门"，那是"倾巢还是试探"的唯一直接读数。
   * **准备步骤（开编成面板 / 改数量 / 取消）不单独上报**：它们不是出征意图，
   * 上报会把一次出征记成多次。
   */
  marchSend: 'march_send',
  /**
   * 离开世界地图（切到别的面板即收尾）。
   *
   * <p>为什么要单独一个事件：它标的是"世界地图这一屏的会话有多长"与"玩家是从世界走的还是直接杀进程"。
   * 与 `march_send` 分开记 —— 后者是军事意图，前者只是换屏，混在一起会让"出征率"被换屏次数冲淡。
   */
  worldLeave: 'world_leave',
  /**
   * 打开体力详情弹层（B09 §5）。
   *
   * <p>与 `stamina_buy` 分开：这一条是"玩家关心体力够不够"的读数（打开率），
   * 后者是"他愿不愿意为它花钱"。只有前者高而后者为 0，说明卡点是真的、但付费点没说服力。
   */
  staminaView: 'stamina_view',
  /**
   * 派出一支侦察队（B26 S18）。带承诺兵力：侦察队会被打，"随手看一眼"在数值上就是
   * 派一支小队出去 —— 这条与 `march_send` 的比例看得出玩家是不是把侦察当成免费情报。
   */
  scoutSend: 'scout_send',
  /**
   * 用金币买一次体力（B09 §5）。带**当时的单价**而不只是"买了"：体力是付费点，
   * 单价随当日已购次数递增，这条与 `login` 的比例才看得出玩家在什么价位上开始嫌贵。
   */
  staminaBuy: 'stamina_buy',
  /**
   * 开始研究一行科技（V03-a-S2）。带目标等级：科技是长线养成，
   * "卡在哪一级不再动"只有从等级分布才看得出来。
   */
  techResearch: 'tech_research',
  /**
   * 取消当前研究（B20 §一）。带被取消的 techId：取消率与"卡在哪一行"合起来才看得出
   * 是队列排太长，还是玩家被某一级的前置挡住了。
   */
  techCancel: 'tech_cancel',
  /**
   * 用一张研究加速道具（B20 §一）。带 itemId：三种加速令分别通向建造 / 训练 / 研究三个出口，
   * "玩家把研究令用在哪"只有这条能回答。
   */
  techSpeedUp: 'tech_speed_up',
  /**
   * 开一批宝箱（B04 §2）。带 `count`：开箱是产出与付费的交汇点，
   * "一次开 1 个"与"一次开 100 个"在运营曲线上是两种玩家。
   */
  chestOpen: 'chest_open',
  /**
   * 取消一格建造（B03 §2）。带 buildingId：取消率按建筑拆开才看得出是"玩家排错了"
   * 还是"某一种建筑的排队代价让人反复反悔"。
   */
  buildingCancel: 'building_cancel',
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
