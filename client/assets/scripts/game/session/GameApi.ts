/**
 * 职责：客户端 API 适配层 —— 把 12 个 Controller 的端点绑成类型安全的方法，
 *       并在每次成功响应后统一做时钟校准、Store 写入与世界模型喂数据。
 * 依赖：net/NetModule、game/store/Store、core/TimeSync、game/world/WorldContext、生成的契约类型。
 *
 * <p><b>这一层是「场景」与「传输」之间唯一缺的那块</b>。场景层的数据入口是 {@code attach(resp)}、
 * 意图出口是 {@code onXxx} 回调，它们刻意不碰网络（换传输实现不用动表现层）；
 * 本类就是接线的人：把回调翻译成带 requestId 的请求，把响应喂回场景。
 *
 * <p><b>与 GameSession 同一条纪律（铁律 2、3）：本类是客户端少数几个会写入 Store 的地方</b>。
 * 它不做任何数值判定 —— 不校验战力、不计算产出、不预测战斗结果、不猜服务端会不会同意。
 * 服务端返回什么就是什么。
 *
 * <p><b>每个响应都是一次免费的时钟校准</b>：信封里恒有 serverNow，
 * 所以每次 ok 都调一次 {@code TimeSync.offer(rtt, serverNow - sentAt)}。
 * 这正是 TimeSync 存在的理由 —— 单次采样含一个单程网络延迟，
 * 必须由它做 rtt/2 补偿 + 加权平均 + 抖动剔除，本类只负责把样本递过去。
 *
 * <p><b>变更类接口默认不入离线队列</b>。NetModule 有这个能力（幂等请求可安全重放），
 * 但「玩家三小时前点的那次抽卡在网络恢复后悄悄执行」是一个产品决策而不是技术决策：
 * 它会扣掉玩家当下的资源去兑现一个他已经忘了的操作。所以默认失败即失败并如实提示，
 * 需要入队的端点由调用方显式打开 {@code queueWhenOffline}（绝不静默失败）。
 */

import type { TimeSync } from '../../core/TimeSync'
import type { NetModule, NetOutcome } from '../../net/NetModule'
import type { Store } from '../store/Store'
import {
  applyExileResult, bindWorldRequester, feedMarches, feedTimeOffset, feedViewport, initializeWorld,
} from '../world/WorldContext'
import type { WorldActionResult } from '../world/WorldContext'
import type {
  AvatarFrameListResp, PowerDetailResp, StaminaBuyReq, StaminaBuyResp, StaminaResp, WearFrameReq,
  WearFrameResp,
} from '../../net/generated/Protocol'
import type {
  BattlePassClaimReq, BattlePassClaimResp, BattlePassStatusResp,
} from '../../net/generated/BattlePassProtocol'
import type {
  CityCancelReq, CityCancelResp, CityCollectReq, CityCollectResp, CityListResp, CityUpgradeReq,
  CityUpgradeResp, SpeedUpReq, SpeedUpResp,
} from '../../net/generated/CityProtocol'
import type { ResourceDetailResp } from '../../net/generated/BagProtocol'
import type {
  BagListResp, ItemUseReq, ItemUseResp, OpenBatchReq, OpenBatchResp,
} from '../../net/generated/BagProtocol'
import type {
  ArmyListResp, ArmyUnitReq, AutoTrainReq, AutoTrainResp, TrainCancelResp, TrainReq, TrainResp,
  TreatReq, TreatResp,
} from '../../net/generated/ArmyProtocol'
import type {
  GachaDrawReq, GachaDrawResp, GachaPoolsResp, GachaProbResp, HeroEquipReq, HeroGrowResp, HeroIdReq, HeroItemReq,
  HeroLevelUpReq, HeroListResp, SetLineupReq, SetLineupResp,
} from '../../net/generated/HeroProtocol'
import type {
  ExileReq, ExileResp, GatherResp, MarchIdReq, MarchListResp, MarchReq, MarchResp, RecallResp,
  ScoutListResp, ScoutReq,
  SearchTargetsReq, SearchTargetsResp, ViewportReq, ViewportResp,
} from '../../net/generated/WorldProtocol'
import type {
  BattleReportListResp, BattleReportResp, ReportShareReq, ReportShareResp,
} from '../../net/generated/BattleProtocol'
import type {
  AppVersionReq, AppVersionResp, CrashReportReq, CrashReportResp, TrackBatchReq, TrackBatchResp, TrackEvent,
} from '../../net/generated/OpsProtocol'
import type {
  ChallengeStageReq, ChallengeStageResp, StageListResp, SweepReq, SweepResp,
} from '../../net/generated/StageProtocol'
import type {
  BlockListView,
  BlockReq,
  FollowReq,
  FriendListView,
  ReportReq,
  ReportResp,
  AllianceCreateReq, AllianceDonateReq, AllianceDonateResp, AllianceIdReq, AllianceMemberReq,
  AllianceReviewReq, AllianceRoleReq, AllianceSelfReq, AllianceSyncReq, AllianceSyncResp,
  AllianceTechReq, AllianceTechResp, ChatListReq, ChatListResp, ChatSendReq, ChatSendResp,
  HelpReq, HelpResp, PermissionListResp, RallyJoinReq, RallyResp, SocialEventAckReq,
  SocialHelpListResp, SocialSummaryResp, SquadCreateReq, SquadIdReq, SquadMemberReq, SquadRallyReq,
  SquadSelfReq, AllianceRallyReq, ReddotTreeResp, RallyListResp,
} from '../../net/generated/SocialProtocol'
import type { ShopBuyReq, ShopBuyResp, ShopCurrency, ShopListResp } from '../../net/generated/ShopProtocol'
import type { QuestClaimReq, QuestClaimResp, QuestListResp } from '../../net/generated/QuestProtocol'
import type {
  MailClaimAllReq, MailClaimAllResp, MailListResp, MailReadReq, MailReadResp,
} from '../../net/generated/MailProtocol'
import type {
  ActivityClaimReq, ActivityClaimResp, ActivityListResp,
} from '../../net/generated/ActivityProtocol'
import type { CreateOrderReq, CreateOrderResp, GiftPopupResp, OrderStatusResp } from '../../net/generated/PayProtocol'
import type {
  GuideProgressReq, GuideProgressResp, GuideScriptResp,
} from '../../net/generated/GuideProtocol'
import type {
  RankListResp, RankSnapshotResp, RankType,
} from '../../net/generated/RankProtocol'
import type { SeasonStatusResp } from '../../net/generated/SeasonProtocol'
import type { TechListView } from '../../net/generated/TechProtocol'
import type { EquipInstanceListView } from '../../net/generated/EquipProtocol'

export interface GameApiDeps {
  readonly net: NetModule
  readonly store: Store
  readonly timeSync: TimeSync
  readonly now: () => number
  readonly newRequestId: () => string
}

export class GameApi {
  private readonly deps: GameApiDeps
  private worldReady = false

  constructor(deps: GameApiDeps) {
    this.deps = deps
  }

  // ---------- 玩家与战力（B01 / B08） ----------

  /** GET /player/power。成功后写入 Store.power —— 战力明细面板与顶部横幅都读它。 */
  async playerPower(): Promise<NetOutcome<PowerDetailResp>> {
    const outcome = await this.read<PowerDetailResp>('/player/power')
    if (outcome.kind === 'ok') {
      this.deps.store.patch({ power: outcome.data.power, serverNow: outcome.data.serverNow })
    }
    return outcome
  }

  /** GET /stamina/view（B09 §5）。 */
  staminaView(): Promise<NetOutcome<StaminaResp>> {
    return this.read<StaminaResp>('/stamina')
  }

  /**
   * GET /rank/list?type=&page=（B23 §一 1）。纯读，客户端不做任何名次计算（B23 禁止项）。
   *
   * <p>`page` 出界由服务端夹到最后一页（而不是回空页）：所以这里不预判范围，
   * 直接把服务端回显的 `page` 写进界面 —— 玩家点"下一页"点到头时，看到的是最后一页而不是一片空白。
   */
  rankList(type: RankType, page: number, size: number): Promise<NetOutcome<RankListResp>> {
    return this.read<RankListResp>('/rank/list', { type, page, size })
  }

  /** GET /rank/snapshot?type=&dayKey=（B23 §一 2 的申诉读取；只回自己那一行）。 */
  rankSnapshot(type: RankType, dayKey: string): Promise<NetOutcome<RankSnapshotResp>> {
    return this.read<RankSnapshotResp>('/rank/snapshot', { type, dayKey })
  }

  /**
   * GET /season/status（V04-S1）。**不要求身份**：阶段与时限是全服信息，
   * 带身份时才多回 `myRank` 与荣耀三件套。
   *
   * <p>未启用赛季时服务端把 `phase` / 日期一律回 null（不是 0）——
   * 客户端按 null 把整块收起，不显示"第 0 天"。
   */
  seasonStatus(): Promise<NetOutcome<SeasonStatusResp>> {
    return this.read<SeasonStatusResp>('/season/status')
  }

  /**
   * GET /tech/list（V03-a-S1 研究页读侧）。**要身份**：`level` 是"我研究到几级了"，
   * 与阶段那种全服信息不同。
   *
   * <p>响应里已经带了 `canResearch` / `blockedReason` / `nextCost` / `nextTimeSec` ——
   * 客户端不再自己判前置、不算曲线（判一遍只会与服务器分叉）。
   */
  techList(): Promise<NetOutcome<TechListView>> {
    return this.read<TechListView>('/tech/list')
  }

  /**
   * GET /equip/instances（V03-b-S1 读侧）。**要身份**：这些是"我拥有的装备实例"。
   *
   * <p>响应里已经带了 `canForge` / `blockReason` / `nextCostIron` —— 客户端不再自己判前置、不重算铁耗曲线。
   */
  equipInstances(): Promise<NetOutcome<EquipInstanceListView>> {
    return this.read<EquipInstanceListView>('/equip/instances')
  }

  /** POST /stamina/buy。扣金币与体力上限判定都在服务端。 */
  staminaBuy(req: Omit<StaminaBuyReq, 'requestId'>): Promise<NetOutcome<StaminaBuyResp>> {
    return this.mutate<StaminaBuyReq, StaminaBuyResp>('/stamina/buy', req)
  }

  // ---------- 城建（B03） ----------

  /**
   * GET /city/list。
   *
   * <p>这是个「带副作用的读」：服务端会顺带收割到点的升级（惰性结算，不跑定时器）。
   * 所以面板每次打开都调它，而不是只在登录后调一次。
   */
  async cityList(): Promise<NetOutcome<CityListResp>> {
    const outcome = await this.read<CityListResp>('/city/list')
    if (outcome.kind === 'ok') {
      // ResourceStateView 与 Store 的 ResourceState 结构完全一致（同一个 Schema 的两份拷贝），
      // 所以可以整体替换。刻意不做逐字段合并：合并意味着客户端在猜哪个字段更权威
      this.deps.store.patch({ resources: outcome.data.resources, serverNow: outcome.data.serverNow })
    }
    return outcome
  }

  cityUpgrade(req: Omit<CityUpgradeReq, 'requestId'>): Promise<NetOutcome<CityUpgradeResp>> {
    return this.mutate<CityUpgradeReq, CityUpgradeResp>('/city/upgrade', req)
  }

  citySpeedUp(req: Omit<SpeedUpReq, 'requestId'>): Promise<NetOutcome<SpeedUpResp>> {
    return this.mutate<SpeedUpReq, SpeedUpResp>('/city/speedUp', req)
  }

  cityCancel(req: Omit<CityCancelReq, 'requestId'>): Promise<NetOutcome<CityCancelResp>> {
    return this.mutate<CityCancelReq, CityCancelResp>('/city/cancel', req)
  }

  cityCollect(req: Omit<CityCollectReq, 'requestId'>): Promise<NetOutcome<CityCollectResp>> {
    return this.mutate<CityCollectReq, CityCollectResp>('/city/collect', req)
  }

  // ---------- 资源与背包（B04） ----------

  /** GET /resource/detail。产出明细面板的数据源（Σ明细必须等于 perHour，验收 5）。 */
  resourceDetail(): Promise<NetOutcome<ResourceDetailResp>> {
    return this.read<ResourceDetailResp>('/resource/detail')
  }

  /** GET /bag/list。type 为 null 时不带该参数（服务端 required=false，空串与缺参是两种语义）。 */
  bagList(type: string | null = null): Promise<NetOutcome<BagListResp>> {
    return this.read<BagListResp>('/bag/list', { type })
  }

  itemUse(req: Omit<ItemUseReq, 'requestId'>): Promise<NetOutcome<ItemUseResp>> {
    return this.mutate<ItemUseReq, ItemUseResp>('/item/use', req)
  }

  itemOpenBatch(req: Omit<OpenBatchReq, 'requestId'>): Promise<NetOutcome<OpenBatchResp>> {
    return this.mutate<OpenBatchReq, OpenBatchResp>('/item/openBatch', req)
  }

  // ---------- 军队与医院（B05） ----------

  /** GET /army/list。同样是「带副作用的读」：会顺带收割到点的训练与治疗。 */
  armyList(): Promise<NetOutcome<ArmyListResp>> {
    return this.read<ArmyListResp>('/army/list')
  }

  armyTrain(req: Omit<TrainReq, 'requestId'>): Promise<NetOutcome<TrainResp>> {
    return this.mutate<TrainReq, TrainResp>('/army/train', req)
  }

  armyCancel(req: Omit<ArmyUnitReq, 'requestId'>): Promise<NetOutcome<TrainCancelResp>> {
    return this.mutate<ArmyUnitReq, TrainCancelResp>('/army/cancel', req)
  }

  armySpeedUp(req: Omit<ArmyUnitReq, 'requestId'>): Promise<NetOutcome<TrainResp>> {
    return this.mutate<ArmyUnitReq, TrainResp>('/army/speedUp', req)
  }

  armyTreat(req: Omit<TreatReq, 'requestId'>): Promise<NetOutcome<TreatResp>> {
    return this.mutate<TreatReq, TreatResp>('/army/treat', req)
  }

  armyTreatSpeedUp(req: Omit<ArmyUnitReq, 'requestId'>): Promise<NetOutcome<TreatResp>> {
    return this.mutate<ArmyUnitReq, TreatResp>('/army/treatSpeedUp', req)
  }

  armyCollectTreated(req: Omit<TreatReq, 'requestId'>): Promise<NetOutcome<TreatResp>> {
    return this.mutate<TreatReq, TreatResp>('/army/collectTreated', req)
  }

  /**
   * 开关自动续训 / 自动补兵（B25-S2d）。
   *
   * <p>它是**策略**而不是一次性动作：服务端保存它、并在军队结算那次读里惰性执行
   * （真扣资源、真占队列，与手动训练同一条路径）。开起来之后这批兵的账单是持续的，
   * 所以面板上要写清"还剩几批"与"为什么停了"。
   */
  armyAutoTrain(req: Omit<AutoTrainReq, 'requestId'>): Promise<NetOutcome<AutoTrainResp>> {
    return this.mutate<AutoTrainReq, AutoTrainResp>('/army/autoTrain', req)
  }

  // ---------- 武将与抽卡（B06） ----------

  heroList(): Promise<NetOutcome<HeroListResp>> {
    return this.read<HeroListResp>('/hero/list')
  }

  heroLevelUp(req: Omit<HeroLevelUpReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroLevelUpReq, HeroGrowResp>('/hero/levelUp', req)
  }

  heroStarUp(req: Omit<HeroIdReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroIdReq, HeroGrowResp>('/hero/starUp', req)
  }

  heroCompose(req: Omit<HeroIdReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroIdReq, HeroGrowResp>('/hero/compose', req)
  }

  heroAwaken(req: Omit<HeroItemReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroItemReq, HeroGrowResp>('/hero/awaken', req)
  }

  heroSkillUp(req: Omit<HeroItemReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroItemReq, HeroGrowResp>('/hero/skillUp', req)
  }

  heroEquip(req: Omit<HeroEquipReq, 'requestId'>): Promise<NetOutcome<HeroGrowResp>> {
    return this.mutate<HeroEquipReq, HeroGrowResp>('/hero/equip', req)
  }

  heroSetLineup(req: Omit<SetLineupReq, 'requestId'>): Promise<NetOutcome<SetLineupResp>> {
    return this.mutate<SetLineupReq, SetLineupResp>('/hero/lineup', req)
  }

  /**
   * POST /gacha/draw。
   *
   * <p><b>刻意不开离线队列</b>：抽卡会扣资源，三小时前点的那一次在网络恢复后悄悄执行，
   * 玩家看到的是「我的钻石少了但我不记得抽过」—— 那比抽卡失败严重得多。
   */
  gachaDraw(req: Omit<GachaDrawReq, 'requestId'>): Promise<NetOutcome<GachaDrawResp>> {
    return this.mutate<GachaDrawReq, GachaDrawResp>('/gacha/draw', req)
  }

  /**
   * GET /gacha/pools。有哪些池、叫什么、抽一次花什么、这个号在该池抽过几次 ——
   * 没有这一份，poolId 就只能硬编码进客户端（= 抄 gacha 表），而上一个新池就得发一次版本。
   */
  gachaPools(): Promise<NetOutcome<GachaPoolsResp>> {
    return this.read<GachaPoolsResp>('/gacha/pools')
  }

  /** GET /gacha/probability。合规公示的数据源，必须原文展示（B06 §6）。 */
  gachaProbability(poolId: string): Promise<NetOutcome<GachaProbResp>> {
    return this.read<GachaProbResp>('/gacha/probability', { poolId })
  }

  // ---------- 世界大地图（B07 / B08） ----------

  /**
   * 进入世界地图：先拉一次行军列表拿到家坐标与地图布局，再据此建立世界模型。
   *
   * <p><b>为什么用 /world/marches 而不是让玩家自己给坐标</b>：家坐标是权威数据，
   * 它随 MarchListResp.home 下发（那个响应本来就要拉，因为地图上必须画出自己的队伍）。
   * 视野初始中心用家坐标而不是 (0,0)：玩家打开大地图第一眼要看到自己的城。
   *
   * <p><b>布局参数（worldSize / chunkSize / maxChunks）也来自这个响应</b>：
   * 以前它们由启动流程注入（GameBootstrap 里镜像 global.json 的常数），
   * 现在服务端随响应下发 —— 改表即两端同步，客户端不再持有 512/32/9 的第二份家。
   */
  async enterWorld(): Promise<NetOutcome<MarchListResp>> {
    const outcome = await this.marches()
    if (outcome.kind === 'ok' && !this.worldReady) {
      initializeWorld({
        worldSize: outcome.data.worldSize,
        chunkSize: outcome.data.chunkSize,
        maxChunks: outcome.data.maxChunks,
      }, this.deps.timeSync.offsetMs(), outcome.data.home)
      bindWorldRequester({
        viewport: (req) => {
          void this.worldViewport(req)
        },
        marches: () => {
          void this.marches()
        },
        // 必须把 Promise 交回场景：按钮要等真正的迁城响应落地后才解除“迁城中”锁。
        exile: () => this.doExile().then(() => undefined),
        recall: (marchId) => this.doRecall(marchId),
        collectGather: (marchId) => this.doCollectGather(marchId),
      })
      this.worldReady = true
    }
    return outcome
  }

  /**
   * POST /world/exile（B08 §5 流亡迁城）。走 {@link mutate} 是为了让幂等键由适配层注入 ——
   * 迁城改的是世界坐标，重放一次等于白送一次逃生。
   */
  worldExile(req: Omit<ExileReq, 'requestId'>): Promise<NetOutcome<ExileResp>> {
    return this.mutate<ExileReq, ExileResp>('/world/exile', req)
  }

  /**
   * 场景按下「流亡迁城」之后的落地。
   *
   * <p><b>成功与失败都要重拉一次行军列表</b>：按钮的每一个输入（冷却、在外的队伍数、免战）
   * 都来自那份响应。失败大概率就是服务端否决了冷却或在外队伍，此时若不刷新，
   * 按钮会一直显示「可以迁」而每次都回同样的错误 —— 玩家看到的是按钮在骗他。
   * 中心已经搬走后地图会缺新块，场景按需向 requester 要视野，这里不替它发请求。
   */
  async doExile(): Promise<NetOutcome<ExileResp>> {
    const outcome = await this.worldExile({})
    if (outcome.kind === 'ok') {
      applyExileResult(outcome.data.coord, outcome.data.peaceUntil,
          outcome.data.nextExileAt, outcome.data.serverNow)
    }

    // 必须 await：调用方（和单测）在这个函数返回后读到的状态才是一致的那份。
    // 挂在后台刷新，表现就是「失败之后按钮还是亮的」——而它只差一次没被等待的刷新
    await this.marches()
    return outcome
  }

  /** 退出世界地图（切账号、回登录）。解绑传输层，否则旧账号的响应会写进新账号的地图。 */
  leaveWorld(): void {
    bindWorldRequester(null)
    this.worldReady = false
  }

  /** POST /world/viewport。响应喂给 WorldContext，场景订阅模型变化后自动重绘。 */
  async worldViewport(req: ViewportReq): Promise<NetOutcome<ViewportResp>> {
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<ViewportReq, ViewportResp>('/world/viewport', req)
    const calibrated = this.calibrate(outcome, sentAt)
    if (calibrated.kind === 'ok') {
      feedViewport(calibrated.data)
    }
    return calibrated
  }

  /** GET /world/marches。响应喂给 WorldContext，行军插值与纠偏都从它来（B07 验收 10）。 */
  async marches(): Promise<NetOutcome<MarchListResp>> {
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.get<MarchListResp>('/world/marches')
    const calibrated = this.calibrate(outcome, sentAt)
    if (calibrated.kind === 'ok') {
      // localNow 必须是「收到响应那一刻」的本地时刻，理由见 WorldViewModel.applyMarches
      feedMarches(calibrated.data, this.deps.now())
    }
    return calibrated
  }

  worldMarch(req: Omit<MarchReq, 'requestId'>): Promise<NetOutcome<MarchResp>> {
    return this.mutate<MarchReq, MarchResp>('/world/march', req)
  }

  worldScout(req: Omit<ScoutReq, 'requestId'>): Promise<NetOutcome<MarchResp>> {
    return this.mutate<ScoutReq, MarchResp>('/world/scout', req)
  }

  worldRecall(req: Omit<MarchIdReq, 'requestId'>): Promise<NetOutcome<RecallResp>> {
    return this.mutate<MarchIdReq, RecallResp>('/world/recall', req)
  }

  worldCollectGather(req: Omit<MarchIdReq, 'requestId'>): Promise<NetOutcome<GatherResp>> {
    return this.mutate<MarchIdReq, GatherResp>('/world/collectGather', req)
  }

  /** 场景按下召回后的落地；无论成功失败都重拉列表，避免面板停留在旧状态。 */
  async doRecall(marchId: string): Promise<WorldActionResult> {
    const outcome = await this.worldRecall({ marchId })
    return this.finishWorldAction(outcome,
      data => `召回成功，${Math.max(0, data.returnSeconds)} 秒后到家`)
  }

  /** 场景按下收取后的落地；成功时把服务端结算的资源原样告诉玩家。 */
  async doCollectGather(marchId: string): Promise<WorldActionResult> {
    const outcome = await this.worldCollectGather({ marchId })
    return this.finishWorldAction(outcome, data => {
      const loot = data.collected
        .map(entry => `${entry.resourceType} ×${entry.amount}`)
        .join('、')
      return loot.length > 0 ? `已收取 ${loot}，队伍返程中` : '采集已结算，队伍返程中'
    })
  }

  private async finishWorldAction<T>(outcome: NetOutcome<T>,
                                     success: (data: T) => string): Promise<WorldActionResult> {
    const result = outcome.kind === 'ok'
      ? { ok: true, message: success(outcome.data) }
      : { ok: false, message: outcomeMessage(outcome) }
    await this.marches()
    return result
  }
  /**
   * 下单（B19 S3-iv）。**价格与发货内容都由服务端按 productId 现查**，客户端只报"买哪一档"。
   *
   * <p>失败要把服务端的话原样带给面板：15011（今天买过了）与 15012（报价过期）都是给玩家写的句子。
   */
  /**
   * 这一屏弹不弹（B19 S3-ii 的端点）。**弹不弹由服务端算**：它读的是触发时刻与频控，
   * 客户端只把答案交给面板。
   */
  async giftPopup(): Promise<NetOutcome<GiftPopupResp>> {
    return this.read<GiftPopupResp>('/gift/popup')
  }

  async createPayOrder(productId: string): Promise<NetOutcome<CreateOrderResp>> {
    return this.mutate<CreateOrderReq, CreateOrderResp>("/pay/order", { productId, count: 1, heroChoice: null })
  }

  /**
   * 查订单状态。**发货只认这个端点的回答** —— 拉起支付的返回值只说"渠道侧成没成"，
   * 而补单是服务端在收到渠道回调之后做的（契约里那句注释就是这条纪律）。
   */
  async payOrderStatus(orderId: string): Promise<NetOutcome<OrderStatusResp>> {
    return this.read<OrderStatusResp>("/pay/order", { orderId })
  }

  scoutReports(): Promise<NetOutcome<ScoutListResp>> {
    return this.read<ScoutListResp>('/world/reports')
  }

  /** POST /world/searchTargets（B08 §8）。响应里没有距离数值字段，只有档位（验收 12）。 */
  async searchTargets(req: SearchTargetsReq): Promise<NetOutcome<SearchTargetsResp>> {
    // 搜索没有副作用，不需要幂等键
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<SearchTargetsReq, SearchTargetsResp>('/world/searchTargets', req)
    return this.calibrate(outcome, sentAt)
  }

  // ---------- 运营（B16：版本闸门与埋点） ----------

  /**
   * POST /ops/app/version。既是强制更新的判定入口，也是**埋点攒批策略的唯一来源**
   * （`trackPolicy`：攒够多少条或隔多少秒发一批，由服务端下发而不是客户端写死）。
   *
   * @param playerId 未登录传 null。灰度按它做稳定哈希，所以**未登录的人不进灰度** ——
   *                 灰度批次里的崩溃必须能归因到具体玩家。
   */
  appVersion(clientVersion: string, playerId: string | null): Promise<NetOutcome<AppVersionResp>> {
    return this.postRead<AppVersionReq, AppVersionResp>('/ops/app/version', { clientVersion, playerId })
  }

  /**
   * POST /ops/track/batch。
   *
   * <p>与 `mutate` 的区别是**协议里就没有 requestId**（`TrackBatchReq` 只有 `events`）：
   * 埋点是允许丢的统计流，给它加幂等键等于在分析库里留一份"这条到底算几次"的争议。
   * 因此这里走 `postRead`：不发幂等键，但仍然享受免费的一次时钟校准。
   */
  trackBatch(events: readonly TrackEvent[], droppedBatches = 0): Promise<NetOutcome<TrackBatchResp>> {
    return this.postRead<TrackBatchReq, TrackBatchResp>('/ops/track/batch',
      { events: [...events], droppedBatches })
  }

  /** POST /ops/crash。完整堆栈 + traceId，服务端同步落库（见 OpsController 的说明）。 */
  reportCrash(req: CrashReportReq): Promise<NetOutcome<CrashReportResp>> {
    return this.postRead<CrashReportReq, CrashReportResp>('/ops/crash', req)
  }

  // ---------- 战报（B05 / B09） ----------

  battleReports(): Promise<NetOutcome<BattleReportListResp>> {
    return this.read<BattleReportListResp>('/battle/reports')
  }

  battleReport(reportId: string): Promise<NetOutcome<BattleReportResp>> {
    return this.read<BattleReportResp>('/battle/report', { reportId })
  }

  /**
   * 把一份自己的战报分享到小队 / 联盟频道（B22 §一 2）。
   * 走 mutate：它会往频道里写一条消息（并过限流与内容送检），是有副作用的一次动作。
   */
  reportShare(req: Omit<ReportShareReq, 'requestId'>): Promise<NetOutcome<ReportShareResp>> {
    return this.mutate<ReportShareReq, ReportShareResp>('/battle/share', req)
  }

  // ---------- 关卡（B09） ----------

  stageList(): Promise<NetOutcome<StageListResp>> {
    return this.read<StageListResp>('/stage/list')
  }

  stageChallenge(req: Omit<ChallengeStageReq, 'requestId'>): Promise<NetOutcome<ChallengeStageResp>> {
    return this.mutate<ChallengeStageReq, ChallengeStageResp>('/stage/challenge', req)
  }

  /**
   * POST /stage/sweep。
   *
   * <p><b>一次请求带 count，绝不逐次发 N 个请求</b>（验收 9）：
   * 逐次发的话每一次都要走幂等、加锁、结算，弱网下会有几次超时，
   * 玩家看到的是「扫荡了 7 次」这种无法解释的结果。
   */
  stageSweep(req: Omit<SweepReq, 'requestId'>): Promise<NetOutcome<SweepResp>> {
    return this.mutate<SweepReq, SweepResp>('/stage/sweep', req)
  }

  // ---------- 小队与联盟（B10） ----------

  /** GET /social/summary。三层社交一屏给全，红点数由服务端算好（验收 6）。 */
  socialSummary(): Promise<NetOutcome<SocialSummaryResp>> {
    return this.read<SocialSummaryResp>('/social/summary')
  }

  /**
   * GET /social/reddot。整棵红点树一次下发，客户端不按业务字段自行拼判断。
   *
   * <p>响应刻意是全量的：合并增量会让已经消失的红点留在界面上，
   * 那正是 B12 验收 1 要禁止的假红点。
   */
  socialReddot(): Promise<NetOutcome<ReddotTreeResp>> {
    return this.read<ReddotTreeResp>('/social/reddot')
  }

  /**
   * GET /social/helpRequests。互助面板的数据源。
   *
   * <p>徽标数取响应里的 `pendingHelps`，**不在客户端数 `requests`**：服务端那份是
   * 一次遍历同时算出来的（含"已经帮过的跳过"和今日额度截断），客户端再数一遍就是第二份真相。
   */
  socialHelpList(): Promise<NetOutcome<SocialHelpListResp>> {
    return this.read<SocialHelpListResp>('/social/helpRequests')
  }

  /**
   * GET /social/permissions（验收 4）。
   *
   * <p>下发的是「我能做什么」的结论列表，不是整张权限矩阵：
   * 把矩阵给客户端等于把权限模型交出去，而客户端的任何判断都可以被绕过。
   */
  socialPermissions(): Promise<NetOutcome<PermissionListResp>> {
    return this.read<PermissionListResp>('/social/permissions')
  }

  /** POST /social/help。帮助一次，消耗共用的每日额度。 */
  socialHelp(req: Omit<HelpReq, 'requestId'>): Promise<NetOutcome<HelpResp>> {
    return this.mutate<HelpReq, HelpResp>('/social/help', req)
  }

  /**
   * POST /social/helpAll（验收 6：一键帮助全部，红点清零）。
   *
   * <p><b>一次请求，不是 N 次</b>：与扫荡同一条纪律（B09 验收 9）。
   * 逐条发 20 个请求的话，弱网下会有几次超时，
   * 玩家看到的是「我点了全部却只帮到 5 个人」而红点还剩一半 ——
   * 那比没有一键功能更糟。
   */
  socialHelpAll(): Promise<NetOutcome<HelpResp>> {
    return this.mutate<{ requestId: string }, HelpResp>('/social/helpAll', {})
  }

  /** POST /social/ackEvents。离线补偿的事件必须能标记已读，否则每次上线都会重收同一批（验收 12）。 */
  socialAckEvents(req: Omit<SocialEventAckReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SocialEventAckReq, SocialSummaryResp>('/social/ackEvents', req)
  }

  squadCreate(req: Omit<SquadCreateReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SquadCreateReq, SocialSummaryResp>('/squad/create', req)
  }

  squadJoin(req: Omit<SquadIdReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SquadIdReq, SocialSummaryResp>('/squad/join', req)
  }

  squadLeave(req: Omit<SquadSelfReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SquadSelfReq, SocialSummaryResp>('/squad/leave', req)
  }

  squadKick(req: Omit<SquadMemberReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SquadMemberReq, SocialSummaryResp>('/squad/kick', req)
  }

  squadTransfer(req: Omit<SquadMemberReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<SquadMemberReq, SocialSummaryResp>('/squad/transfer', req)
  }

  /** POST /squad/rally。人数上限由服务端按 global.RALLY_MAX_SIZE_SQUAD 夹，客户端不自己夹。 */
  squadRally(req: Omit<SquadRallyReq, 'requestId'>): Promise<NetOutcome<RallyResp>> {
    return this.mutate<SquadRallyReq, RallyResp>('/rally/squad', req)
  }

  allianceCreate(req: Omit<AllianceCreateReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceCreateReq, SocialSummaryResp>('/alliance/create', req)
  }

  allianceApply(req: Omit<AllianceIdReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceIdReq, SocialSummaryResp>('/alliance/apply', req)
  }

  allianceReview(req: Omit<AllianceReviewReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceReviewReq, SocialSummaryResp>('/alliance/review', req)
  }

  allianceLeave(req: Omit<AllianceSelfReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceSelfReq, SocialSummaryResp>('/alliance/leave', req)
  }

  allianceKick(req: Omit<AllianceMemberReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceMemberReq, SocialSummaryResp>('/alliance/kick', req)
  }

  allianceTransfer(req: Omit<AllianceMemberReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceMemberReq, SocialSummaryResp>('/alliance/transfer', req)
  }

  allianceSetRole(req: Omit<AllianceRoleReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceRoleReq, SocialSummaryResp>('/alliance/setRole', req)
  }

  /** POST /alliance/donate（验收 8：资金与贡献值同步增加，响应里两个总额都下发）。 */
  allianceDonate(req: Omit<AllianceDonateReq, 'requestId'>): Promise<NetOutcome<AllianceDonateResp>> {
    return this.mutate<AllianceDonateReq, AllianceDonateResp>('/alliance/donate', req)
  }

  allianceExpand(req: Omit<AllianceSelfReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceSelfReq, SocialSummaryResp>('/alliance/expand', req)
  }

  allianceResearchTech(req: Omit<AllianceTechReq, 'requestId'>): Promise<NetOutcome<AllianceTechResp>> {
    return this.mutate<AllianceTechReq, AllianceTechResp>('/alliance/tech', req)
  }

  allianceDisband(req: Omit<AllianceSelfReq, 'requestId'>): Promise<NetOutcome<SocialSummaryResp>> {
    return this.mutate<AllianceSelfReq, SocialSummaryResp>('/alliance/disband', req)
  }

  /**
   * POST /alliance/sync（验收 10：只下发 diff，不全量同步）。
   *
   * <p>客户端要带上手里的版本号；版本相同时服务端返回 unchanged=true 与三个空列表。
   * 150 人的成员列表是联盟数据里最大的一块，
   * 每次心跳都带上它就把 diff 同步的意义抵消掉了。
   */
  allianceSync(req: AllianceSyncReq): Promise<NetOutcome<AllianceSyncResp>> {
    return this.postRead<AllianceSyncReq, AllianceSyncResp>('/alliance/sync', req)
  }

  allianceRally(req: Omit<AllianceRallyReq, 'requestId'>): Promise<NetOutcome<RallyResp>> {
    return this.mutate<AllianceRallyReq, RallyResp>('/rally/alliance', req)
  }

  /** POST /rally/join。承诺的兵力会被锁定，所以必须幂等。 */
  rallyJoin(req: Omit<RallyJoinReq, 'requestId'>): Promise<NetOutcome<RallyResp>> {
    return this.mutate<RallyJoinReq, RallyResp>('/rally/join', req)
  }

  rallyQuit(req: Omit<RallyJoinReq, 'requestId'>): Promise<NetOutcome<RallyResp>> {
    return this.mutate<RallyJoinReq, RallyResp>('/rally/quit', req)
  }

  /**
   * GET /rally/list：我所在的小队与联盟里**进行中**的集结（面板列表）。
   *
   * <p>这个读口此前在客户端零调用点 —— 服务端一直有（`RallyController` 的 `GET /rally/list`），
   * 所以玩家发起了集结，同队/同盟的人看不到它。
   */
  rallyList(): Promise<NetOutcome<RallyListResp>> {
    return this.read<RallyListResp>('/rally/list')
  }

  /**
   * POST /rally/cancel：发起人取消整支集结（与「退出」是两条路 —— 退出是成员自己走，
   * 取消是把大家召集的这波解散掉，服务端要退掉所有人承诺的兵）。
   */
  rallyCancel(req: { rallyId: string }): Promise<NetOutcome<RallyResp>> {
    return this.mutate<{ rallyId: string } & { requestId: string }, RallyResp>('/rally/cancel', req)
  }

  /**
   * POST /chat/send。
   *
   * <p><b>刻意不开离线队列</b>：重放一句几小时前的话没有意义，
   * 而且它会撞上防刷屏限流（同内容 3 次 / 10 秒），
   * 玩家看到的是「我发一句话却提示刷屏」。
   * 被限流时服务端返回 SOCIAL_CHAT_RATE_LIMITED，客户端必须如实显示 ——
   * 假装发成功会让玩家以为对方收到了（验收 9）。
   */
  chatSend(req: Omit<ChatSendReq, 'requestId'>): Promise<NetOutcome<ChatSendResp>> {
    return this.mutate<ChatSendReq, ChatSendResp>('/chat/send', req)
  }

  /** 举报一个玩家 / 一条消息（B22 §一 3）。 */
  socialReport(req: Omit<ReportReq, 'requestId'>): Promise<NetOutcome<ReportResp>> {
    return this.mutate<ReportReq, ReportResp>('/social/report', req)
  }

  /** 拉黑（幂等）。回的是更新后的名单，界面直接照着画。 */
  socialBlock(req: Omit<BlockReq, 'requestId'>): Promise<NetOutcome<BlockListView>> {
    return this.mutate<BlockReq, BlockListView>('/social/block', req)
  }

  /** 取消拉黑。 */
  socialUnblock(req: Omit<BlockReq, 'requestId'>): Promise<NetOutcome<BlockListView>> {
    return this.mutate<BlockReq, BlockListView>('/social/unblock', req)
  }

  /** 我拉黑了谁。 */
  socialBlocks(): Promise<NetOutcome<BlockListView>> {
    return this.read<BlockListView>('/social/blocks')
  }

  /** 关注一个人（单向，B22 §一 4）。对方不会收到通知。 */
  socialFollow(req: Omit<FollowReq, 'requestId'>): Promise<NetOutcome<FriendListView>> {
    return this.mutate<FollowReq, FriendListView>('/social/follow', req)
  }

  /** 取消关注。 */
  socialUnfollow(req: Omit<FollowReq, 'requestId'>): Promise<NetOutcome<FriendListView>> {
    return this.mutate<FollowReq, FriendListView>('/social/unfollow', req)
  }

  /** 我关注的人（带在线状态）。 */
  socialFollows(): Promise<NetOutcome<FriendListView>> {
    return this.read<FriendListView>('/social/follows')
  }

  chatList(req: ChatListReq): Promise<NetOutcome<ChatListResp>> {
    return this.postRead<ChatListReq, ChatListResp>('/chat/list', req)
  }

  /**
   * GET /shop/list：某个货币页签的货架。
   *
   * <p>等级门槛与限购剩余都由服务端算好下发（含 `purchasable` 与 `lockReason`），
   * 客户端<b>不要自己比</b>：它比不了「他今天买过几次」，猜的结果是按钮亮着、点下去报错。
   */
  shopList(currency: ShopCurrency): Promise<NetOutcome<ShopListResp>> {
    return this.read<ShopListResp>('/shop/list', { currency })
  }

  /**
   * POST /shop/buy：兑换。会扣货币并发道具，所以必须幂等。
   *
   * <p>响应里的 `spent` 是服务端按当时表算的总价 —— 客户端应当显示这个数而不是自己乘出来的，
   * 否则热更之后显示的与实际扣的会不一致，而差额投诉只会打给客服。
   */
  shopBuy(req: Omit<ShopBuyReq, 'requestId'>): Promise<NetOutcome<ShopBuyResp>> {
    return this.mutate<ShopBuyReq, ShopBuyResp>('/shop/buy', req)
  }

  /**
   * GET /player/frames：全部头像框（含没拥有的）。
   *
   * <p>没拥有的也要下发 —— 让玩家看见有什么可收集，正是外观存在的意义（与商店把
   * 等级不够的货列出来同一条口径）。
   */
  playerFrames(): Promise<NetOutcome<AvatarFrameListResp>> {
    return this.read<AvatarFrameListResp>('/player/frames')
  }

  /**
   * POST /player/frame：戴上或卸下（`frameId: null` = 卸下）。
   *
   * <p>响应是操作之后的**完整列表**：客户端照它重画，不自己在本地那份上改一位 ——
   * 本地改法在「服务端拒了但界面已经翻过去了」时会让玩家以为戴上了。
   */
  wearFrame(req: Omit<WearFrameReq, 'requestId'>): Promise<NetOutcome<WearFrameResp>> {
    return this.mutate<WearFrameReq, WearFrameResp>('/player/frame', req)
  }

  /**
   * GET /battlePass/status：本赛季战令全貌（积分、付费线解锁位、20 档含未达成的）。
   *
   * <p>能不能领由服务端算好（`reached` / `freeClaimed` / `paidClaimed`）—— 客户端**不自己比积分**：
   * 它比不了"这一档我领过没有"，猜的结果是按钮亮着、点下去报错。
   */
  battlePassStatus(): Promise<NetOutcome<BattlePassStatusResp>> {
    return this.read<BattlePassStatusResp>('/battlePass/status')
  }

  /**
   * POST /battlePass/claim：领某一档的某一条线。会往背包/资源里写东西，所以必须带幂等键。
   *
   * <p>回执里带**领取之后的全量状态**：客户端照它重画，不在本地把那一档翻成已领。
   */
  battlePassClaim(req: Omit<BattlePassClaimReq, 'requestId'>): Promise<NetOutcome<BattlePassClaimResp>> {
    return this.mutate<BattlePassClaimReq, BattlePassClaimResp>('/battlePass/claim', req)
  }

  // ---------- 内部 ----------

  /**
   * 无副作用但必须用 POST 的请求（带查询体的同步与拉取）。
   *
   * <p>不走 {@link #mutate}：它们没有副作用，带上 requestId 反而会让服务端的幂等表
   * 白白多存一条记录。但仍然要过一次时钟校准。
   */
  private async postRead<TReq extends object, TResp>(
    path: string, req: TReq,
  ): Promise<NetOutcome<TResp>> {
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<TReq, TResp>(path, req)
    return this.calibrate(outcome, sentAt)
  }

  /**
   * 读请求。GET 无副作用，NetModule 会自动重试且不入离线队列。
   *
   * @param query 查询参数。值为 null 的键会被 NetModule 跳过而不是发一个空值出去
   */
  private read<TResp>(path: string,
                      query?: Readonly<Record<string, string | number | boolean | null | undefined>>,
  ): Promise<NetOutcome<TResp>> {
    const sentAt = this.deps.now()
    return this.deps.net.get<TResp>(path, query).then((outcome) => this.calibrate(outcome, sentAt))
  }

  /**
   * 变更请求。统一在这里生成 requestId —— 调用方忘填幂等键的后果是重试刷奖励，
   * 那种 bug 只在弱网下出现，所以不给调用方留忘记的机会。
   *
   * @param queueWhenOffline 断网时是否入离线队列。默认 false，理由见文件头
   */
  private async mutate<TReq extends { requestId: string }, TResp>(
    path: string,
    req: Omit<TReq, 'requestId'>,
    queueWhenOffline = false,
  ): Promise<NetOutcome<TResp>> {
    const requestId = this.deps.newRequestId()
    const body = { ...req, requestId } as TReq
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<TReq, TResp>(path, body,
      { idempotent: true, requestId, queueWhenOffline })
    return this.calibrate(outcome, sentAt)
  }

  // ---------- 任务（B12 §1） ----------

  /**
   * GET /quest/list。
   *
   * <p><b>它不是纯读</b>：服务端在这一次读里顺手做日切/周切（跨期清零）与状态型目标的快照刷新，
   * 所以面板每次打开都要真拉一次，不能拿缓存糊弄 —— 拿旧数据会让「每日任务今天已清零」
   * 这件事在界面上晚一整天。
   */
  questList(): Promise<NetOutcome<QuestListResp>> {
    return this.read<QuestListResp>('/quest/list')
  }

  /**
   * POST /quest/claim。
   *
   * <p>`heroChoice` 是「三选一」那类奖励的选择结果：**有候选列表的任务必须带上它，
   * 否则服务端会拒**（它刻意不替玩家默认挑一个 —— 那会让三选一变成系统内定）。
   * 没有候选的任务必须传 null（多传也会被拒），所以调用方只该把 {@code QuestRow.heroChoices} 原样传进来。
   */
  questClaim(req: Omit<QuestClaimReq, 'requestId'>): Promise<NetOutcome<QuestClaimResp>> {
    return this.mutate<QuestClaimReq, QuestClaimResp>('/quest/claim', req)
  }

  // ---------- 邮件（B12 §2） ----------

  /**
   * GET /mail/list。
   *
   * <p>又是一个「带副作用的读」：服务端在这一次读里顺手清掉过期邮件（惰性清理，不跑定时器），
   * 所以面板每次打开都要真拉，不能拿缓存糊弄 —— 缓存会让一封已经过期的邮件还能被点。
   */
  mailList(): Promise<NetOutcome<MailListResp>> {
    return this.read<MailListResp>('/mail/list')
  }

  /**
   * POST /mail/claimAll。**不带 mailId 列表**：哪几封可领是服务端状态（B12 禁止一键领发 N 次请求，
   * 而把 id 交给客户端选，等于把「已领过没有」的判断搬到对端去判）。
   */
  mailClaimAll(req: Omit<MailClaimAllReq, 'requestId'>): Promise<NetOutcome<MailClaimAllResp>> {
    return this.mutate<MailClaimAllReq, MailClaimAllResp>('/mail/claimAll', req)
  }

  /** POST /mail/read。回执带新的未读封数，省一次重拉。 */
  mailRead(req: Omit<MailReadReq, 'requestId'>): Promise<NetOutcome<MailReadResp>> {
    return this.mutate<MailReadReq, MailReadResp>('/mail/read', req)
  }

  /**
   * GET /activity/list（B17）。读取路径会在服务端顺手做一次窗口同步
   * （上一轮没领的行标 EXPIRED、没碰过的行进新一轮），所以每次都真拉，不拿缓存糊弄。
   */
  activityList(): Promise<NetOutcome<ActivityListResp>> {
    return this.read<ActivityListResp>('/activity/list')
  }

  /** POST /activity/claim。requestId 由 mutate 补（同任务/邮件：客户端不生成幂等键）。 */
  activityClaim(req: Omit<ActivityClaimReq, 'requestId'>): Promise<NetOutcome<ActivityClaimResp>> {
    return this.mutate<ActivityClaimReq, ActivityClaimResp>('/activity/claim', req)
  }

  /**
   * GET /guide/script（B18）：步骤序列 + 版本 + 这个号的续传位置 + 该不该看引导。
   *
   * <p>服务端不做条件返回（七步的响应远小于单响应体积预算），所以每次都真拉；
   * 比对 `version` 决定要不要重画是调用方的事，权威始终在服务端。
   */
  guideScript(): Promise<NetOutcome<GuideScriptResp>> {
    return this.read<GuideScriptResp>('/guide/script')
  }

  /** POST /guide/progress。requestId 由 mutate 补，同一次上报重投不会把两步并作一步。 */
  guideProgress(req: Omit<GuideProgressReq, 'requestId'>): Promise<NetOutcome<GuideProgressResp>> {
    return this.mutate<GuideProgressReq, GuideProgressResp>('/guide/progress', req)
  }

  /**
   * 把这次响应变成一次时钟校准样本，并把最新偏移推给世界模型。
   *
   * <p>rtt 用本次请求的实测值（now - sentAt），rawOffset 用 serverNow - sentAt。
   * TimeSync 会自己做 rtt/2 补偿、加权平均与抖动剔除 —— 本类绝不直接把 serverNow 当偏移用，
   * 那会让客户端时间系统性超前一个单程延迟。
   */
  private calibrate<T>(outcome: NetOutcome<T>, sentAt: number): NetOutcome<T> {
    if (outcome.kind !== 'ok' || outcome.serverNow <= 0) {
      return outcome
    }
    const receivedAt = this.deps.now()
    this.deps.timeSync.offer(receivedAt - sentAt, outcome.serverNow - sentAt)
    // 世界模型的行军插值全靠这个偏移；不推的话玩家本地时钟漂移会让自己的队伍比别人早到。
    // Store.serverNow 不在这里写：GameSession 已经订阅了 serverTimeHint 事件，
    // 两处都写会让「谁是权威」变得模糊
    feedTimeOffset(this.deps.timeSync.offsetMs())
    return outcome
  }
}

function outcomeMessage(outcome: NetOutcome<unknown>): string {
  switch (outcome.kind) {
    case 'network':
      return outcome.queued ? `网络不通，操作已排队：${outcome.message}` : `网络不通：${outcome.message}`
    case 'biz':
      return outcome.detail ?? outcome.msg
    default:
      return '操作未完成'
  }
}
