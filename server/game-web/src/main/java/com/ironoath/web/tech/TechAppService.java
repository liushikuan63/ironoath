package com.ironoath.web.tech;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.TechCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.ResourceAmount;
import com.ironoath.web.dto.generated.ResourceType;
import com.ironoath.web.dto.generated.TechBlockReason;
import com.ironoath.web.dto.generated.TechCancelReq;
import com.ironoath.web.dto.generated.TechCancelResp;
import com.ironoath.web.dto.generated.TechEffectAttr;
import com.ironoath.web.dto.generated.TechListView;
import com.ironoath.web.dto.generated.TechQueueView;
import com.ironoath.web.dto.generated.TechResearchReq;
import com.ironoath.web.dto.generated.TechResearchResp;
import com.ironoath.web.dto.generated.TechSchool;
import com.ironoath.web.dto.generated.TechSpeedUpReq;
import com.ironoath.web.dto.generated.TechSpeedUpResp;
import com.ironoath.web.dto.generated.TechView;
import com.ironoath.web.service.CityAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：个人科技应用服务 —— 列树、开始研究、取消、加速（B20 块①）。
 * 依赖：game-config（tech / curve / building / item 四张表）、game-core（{@link PlayerTech} 与 {@link Formula}）、
 * {@link RewardPorts.Bag}（加速道具的扣减唯一入口）、城建服务（加锁 + 分段结算 + 落库的唯一入口）。
 *
 * <p><b>不自己加锁、不自己 load 存档</b>：与军队同一做法，全部走
 * {@link CityAppService#withSettledCity}。原因有两条，第二条尤其针对科技：
 * <ul>
 *   <li>仓储返回深拷贝，两个域各改各的会让后写的那份覆盖前一份（那个方法的注释里有完整推演）；</li>
 *   <li>学院的<b>当前等级</b>是研究前置，而学院是城建里的一个建筑实例 —— 自建一条读城建的通路
 *       就是给「学院等级」找第二个读法，热更或并发下两边会分叉，分叉的表现是「明明够级了却研不了」。</li>
 * </ul>
 *
 * <p><b>动作抛异常时什么都不落库</b>（{@code withSettledCity} 的既有语义），所以这里
 * 「先扣资源再占队列」与「先占队列再扣资源」都不会留下半个操作：中途失败时那份被改过的
 * 存档根本不会被写回去。结算是幂等的，下次读取会重新算。
 *
 * <p><b>「一次一队列」（§五① 裁定）落在结构上，不是落在一个计数里</b>：{@link PlayerTech} 只有一个
 * 研究槽，{@code started(...)} 在槽位被占时直接抛 {@code IllegalStateException}，而本服务在它之前
 * 先回 {@code TECH_QUEUE_BUSY}。要改成多位队列得先有一条裁决与一行真参数，不是在这里改个数字。
 *
 * <p><b>本类没有做的事</b>（各属 B20 的下一步，不在这里半成品地开个头）：
 * 装备强化与国家科技（块②③），以及客户端的科技面板（{@code check-endpoint-paths} 是单向卡口，
 * 服务端先有端点不算违规）。研究本身四个动作都已落地：列树、开始、取消、加速（验收 8）。
 */
@Service
public class TechAppService {

    private static final Logger LOG = LoggerFactory.getLogger(TechAppService.class);

    /**
     * 学院建筑的 id（{@code building.json} 里 SCIENCE 那一行）。
     *
     * <p>为什么是代码常量而不是表里的某一列：{@code tech.json} 只有 {@code requireAcademyLevel}
     * （要<b>几级</b>），没有任何一列说「学院是哪栋建筑」。写死这一个 id 是现状的最保守读法，
     * 而它认错家的症状是「永远读成 0 级、所有科技都研究不了」——
     * 所以 {@code TechEndpointTest.academyMustExistInBuildingTable} 钉住了这个 id 真的在表里
     * （公开是为了让那条用例能引用它，与 {@code QuestAppService.STATE_TYPES_WITH_SOURCE} 同一条做法）。
     */
    public static final String ACADEMY_BUILDING_ID = "academy";

    private final ConfigRegistry configs;
    private final Formula formula;
    private final CityAppService cityAppService;
    private final IdempotencyStore idempotency;
    private final RewardPorts.Bag bagPort;
    private final com.ironoath.common.time.TimeService timeService;

    public TechAppService(ConfigRegistry configs, Formula formula, CityAppService cityAppService,
                          IdempotencyStore idempotency, RewardPorts.Bag bagPort,
                          com.ironoath.common.time.TimeService timeService) {
        this.configs = configs;
        this.formula = formula;
        this.cityAppService = cityAppService;
        this.idempotency = idempotency;
        this.bagPort = bagPort;
        this.timeService = timeService;
    }

    // ---------- 读 ----------

    /**
     * 整棵树 + 当前队列 + 学院等级。
     *
     * <p>读取有副作用：顺带结算到期的研究（与 {@code GET /city/list} 同一条惰性结算）。
     * 不这么做的话，完成时刻已过的研究在玩家眼里还是「进行中」，而下一次任何写入都会把它结掉 ——
     * 读数与写数不一致比"要多点一次刷新"更糟。
     */
    public TechListView list(String playerId) {
        return cityAppService.withSettledCity(playerId, snap -> {
            settleDue(snap);
            return view(snap.player(), snap.city(), snap.now());
        });
    }

    // ---------- 写 ----------

    /** 开始研究下一等级。校验顺序与城建一致：先挡「不该花的钱」，最后才扣资源。 */
    public TechResearchResp research(String playerId, TechResearchReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        String techId = requireText(req.techId(), "techId");
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            settleDue(snap);
            PlayerSave player = snap.player();
            PlayerTech tech = player.tech();
            TechCfg cfg = requireTech(techId);
            int current = tech.levelOf(cfg.id());
            if (current >= cfg.maxLevel()) {
                throw new BizException(ErrorCode.TECH_LEVEL_MAX,
                        cfg.name() + " 已经研究到上限 " + cfg.maxLevel() + " 级");
            }
            if (tech.isResearching()) {
                throw new BizException(ErrorCode.TECH_QUEUE_BUSY,
                        "队列里还有 " + tech.researchingId() + " 在研究（一次一队列）");
            }
            int academy = academyLevel(snap.city());
            if (academy < cfg.requireAcademyLevel()) {
                throw new BizException(ErrorCode.TECH_ACADEMY_REQUIRED,
                        "需要学院 " + cfg.requireAcademyLevel() + " 级，当前 " + academy + " 级");
            }
            int target = current + 1;
            Map<String, Long> cost = researchCost(cfg, target);
            requireResources(player, cost);
            long seconds = researchSeconds(target);
            long finishAt = snap.now() + seconds * 1000L;
            deductResources(player, cost);
            player.setTech(tech.started(cfg.id(), finishAt, snap.now(), seconds));
            LOG.info("开始研究 playerId={} tech={} 目标等级={} 秒数={} 消耗={}",
                    playerId, cfg.id(), target, seconds, cost);
            return new TechResearchResp(cfg.id(), target, finishAt, toAmounts(cost), seconds);
        }));
    }

    /**
     * 取消当前研究，按<b>城建同一个比例</b>返还（{@code city_rule_cancel_refund_ratio}，现值 0.60）。
     *
     * <p>花费不存副本而是<b>按表重算</b>：与 {@code CityAppService.doCancel} 同一条理由 ——
     * 花费完全由配置与等级决定，存一份副本只会在配置热更后拿到过期数字。
     * 返还比例也不另配一个参数：两处各配一个数字，玩家问的就是「为什么取消建造返 60% 取消研究返 40%」。
     */
    public TechCancelResp cancel(String playerId, TechCancelReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            settleDue(snap);
            PlayerSave player = snap.player();
            PlayerTech tech = player.tech();
            if (!tech.isResearching()) {
                throw new BizException(ErrorCode.TECH_NOT_RESEARCHING, "队列空着，没有可取消的研究");
            }
            String cancelled = tech.researchingId();
            TechCfg cfg = requireTech(cancelled);
            Map<String, Long> refund = refundOf(researchCost(cfg, tech.levelOf(cancelled) + 1),
                    snap.rules().cancelRefundFixed());
            player.setTech(tech.cancelled());
            refund.forEach((resource, amount) -> grantResource(player, resource, amount));
            LOG.info("取消研究 playerId={} tech={} 返还={}", playerId, cancelled, refund);
            return new TechCancelResp(cancelled, toAmounts(refund));
        }));
    }

    /**
     * 用研究加速道具推进当前研究（B20 验收 8 —— {@code item_speedup_research_1h}，#46 闭环的那一件）。
     *
     * <p>请求不带 {@code techId}：一次一队列。道具走 {@code /tech/speedUp} 还是 {@code /item/use}
     * 是同一个动作的两个入口（前者是科技面板上的按钮，后者是背包里的「使用」），两条都落到
     * {@link #applySpeedUp}，所以减时长的口径只有一处。
     */
    public TechSpeedUpResp speedUp(String playerId, TechSpeedUpReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        String itemId = requireText(req.itemId(), "itemId");
        return guarded(playerId, req.requestId(), () -> applySpeedUp(playerId, itemId, req.count()));
    }

    /**
     * 供 {@code BagAppService} 的 {@code /item/use} 调用：与 {@link #speedUp} 同一个核心、
     * 同一条幂等纪律，只是请求形状不同（背包那条路带的是道具与张数）。
     *
     * <p><b>键必须由本域占，不能指望背包域</b>：{@code BagAppService.useItem} 对 SPEEDUP 类
     * 在占键之前就 return 了。症状不是报错而是<b>弱网重投白扣一张付费道具</b>，而这一张是刚重新上架的那件 ——
     * 训练与建造那两条路同样是各自占键（{@code ArmyAppService.speedUp}、
     * {@code CityAppService.useSpeedUpItem}），所以背包侧不能提前占，
     * 否则同一个 requestId 会被 acquire 两次而永久失败。
     */
    public TechSpeedUpResp speedUpByItem(String playerId, String requestId, String itemId, long count) {
        String item = requireText(itemId, "itemId");
        return guarded(playerId, requestId, () -> applySpeedUp(playerId, item, count));
    }

    /**
     * 加速的核心：校验队列 → 校验道具 → 扣道具 → 应用效果。
     *
     * <p><b>顺序是「先扣道具再加速」，且加速失败必须退还道具</b> —— 抄 {@code CityAppService.speedUpByItem}
     * 那条已被论证过的取舍：反过来（先加速再扣）在库存不足时让玩家白拿一次加速，那是能主动刷的漏洞；
     * 先扣再加速的唯一风险是「扣了但加速失败」，try/catch 退还就能闭合。
     *
     * <p><b>道具的持有量校验交给 {@code bagPort.remove} 本身</b>：它是原子的，不足时返回 0 且一个都不扣
     * （B04 验收 10）。这里不再自己 {@code countOf} 比一遍 —— 那是把同一个判据放两处，
     * 比对的还是扣减之前的快照。
     */
    private TechSpeedUpResp applySpeedUp(String playerId, String itemId, long count) {
        return cityAppService.withSettledCity(playerId, snap -> {
            settleDue(snap);
            PlayerSave player = snap.player();
            PlayerTech tech = player.tech();
            if (!tech.isResearching()) {
                // 队列空着时先把话说明白：不是「道具不能用」，而是「现在没有东西可加速」，
                // 而这一条必须在扣道具之前判 —— 扣完再拒就得多写一条退还路径去赌异常
                throw new BizException(ErrorCode.TECH_NOT_RESEARCHING,
                        "队列空着，没有可加速的研究（先研究一项，或用 /tech/cancel 腾出队列）");
            }
            ItemCfg item = requireResearchSpeedUpItem(itemId);
            if (count <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID, "道具个数必须为正，实际=" + count);
            }
            if (bagPort.remove(playerId, itemId, count) == 0L) {
                throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                        "需要 " + itemId + " " + count + " 个，当前持有 "
                                + bagPort.countOf(playerId, itemId) + " 个");
            }
            PlayerTech.SpeedUp result;
            try {
                result = tech.speedUp(item.effectValue() * count, snap.now());
            } catch (RuntimeException e) {
                refundItems(playerId, itemId, count);
                throw e;
            }
            player.setTech(result.tech());
            long remaining = result.tech().remainingSeconds(snap.now());
            LOG.info("研究加速 playerId={} tech={} item={} 个数={} 提前={}秒 剩余={}秒 是否完成={}",
                    playerId, tech.researchingId(), itemId, count, result.reducedSeconds(), remaining,
                    result.finished());
            return new TechSpeedUpResp(tech.researchingId(), result.reducedSeconds(), remaining,
                    result.finished());
        });
    }

    /**
     * 只认「加速类 + 减研究秒数」这一种道具。
     *
     * <p>两种错位分开报：类型不对（拿资源箱去加速）是 {@code ITEM_CANNOT_USE}，
     * 类型对但效果是建造/训练（拿建造令去加速研究）是 {@code PARAM_INVALID} 并点名它属于哪个域 ——
     * 建造令与研究令的 effectValue 都是秒数，<b>静默按另一种加速处理不会报错，只会让玩家以为</b>
     * 「我扣了一张建造令却减了研究时间」，那正是 {@code CityAppService} 那句拒绝要挡的事。
     */
    private ItemCfg requireResearchSpeedUpItem(String itemId) {
        ItemCfg item;
        try {
            item = configs.get(ItemCfg.class, itemId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "道具配置不存在: " + itemId);
        }
        if (item.type() != ItemCfg.Type.SPEEDUP) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + itemId + " 不是加速道具（类型=" + item.type() + "）");
        }
        if (item.effectKind() != ItemCfg.EffectKind.REDUCE_RESEARCH_SECONDS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "道具 " + itemId + " 的效果类型是 " + item.effectKind() + "，不能加速研究"
                            + "（建造令走 /city/speedUp、训练令走 /item/use）");
        }
        return item;
    }

    /**
     * 加速失败时把道具还给玩家。
     *
     * <p>退还不足必须留 error 日志：那说明玩家真的丢了道具，而这一位没有任何别的记录能把它找回来。
     */
    private void refundItems(String playerId, String itemId, long count) {
        long back = bagPort.add(playerId, itemId, count);
        if (back < count) {
            LOG.error("【研究加速道具退还失败】playerId={} item={} 应退={} 实退={} traceId={} "
                            + "退还不足说明玩家真的丢了道具，必须人工补偿",
                    playerId, itemId, count, back,
                    com.ironoath.common.log.TraceContext.traceId());
        }
    }

    // ---------- 结算 ----------

    /**
     * 到点就把这一级记进账本并腾空队列。没到期时什么都不做，因此可以被任意读路径反复调用。
     *
     * <p>全项目无 {@code @Scheduled}（B00 陷阱 2）：完成时刻一到，<b>谁先读到谁就顺手结掉</b>。
     * 任务的进度不在这里推 —— 状态型目标走拉取那条路（{@code QuestAppService} 的
     * {@code STATE_TYPES_WITH_SOURCE}），推一次就多一个会漏事件的 producer。
     */
    private PlayerTech settleDue(CityAppService.CitySnapshot snap) {
        PlayerTech.Completion done = snap.player().tech().settled(snap.now());
        if (done == null) {
            return snap.player().tech();
        }
        snap.player().setTech(done.tech());
        LOG.info("研究完成 playerId={} tech={} 新等级={}", snap.player().playerId(), done.techId(), done.level());
        return done.tech();
    }

    // ---------- 数值（全部来自表，代码里没有一个游戏数值字面量） ----------

    /**
     * 研究到第 {@code targetLevel} 级要多少秒。
     *
     * <p><b>刻意不照抄城建那句 {@code Math.max(1, targetLevel - 1)}</b>：建筑从 1 级开始往上升，
     * 第 n 次升级取 {@code T(n)}；科技从 <b>0 级</b>（没研究过）开始，第 L 次研究取 {@code TR(L)}。
     * 少这一档会让「升到 1 级」和「升到 2 级」都取 TR(1)=13 秒，而 #152 那次校准算的
     * 就是 {@code Σ_{L=1..maxLevel} TR(L)}（全树 340 级 = 27.58 天）—— 照抄会把量出来的区间弄假。
     */
    private long researchSeconds(int targetLevel) {
        return formula.evaluateSeconds("TECH_TIME", targetLevel);
    }

    /** 研究到第 {@code targetLevel} 级的消耗：四种资源各自基数 × 该行的 {@code costCurve}（比率 1.22 一族）。 */
    private Map<String, Long> researchCost(TechCfg cfg, int targetLevel) {
        long ratio = configs.curve(cfg.costCurve()).ratioFixed();
        Map<String, Long> cost = new LinkedHashMap<>();
        putIfPositive(cost, ResourceIds.WOOD, cfg.costBaseWood(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.STONE, cfg.costBaseStone(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.IRON, cfg.costBaseIron(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.GRAIN, cfg.costBaseGrain(), targetLevel, ratio);
        return cost;
    }

    private void putIfPositive(Map<String, Long> cost, String resource, long base,
                              int targetLevel, long ratioFixed) {
        if (base <= 0L) {
            return;
        }
        // 与 researchSeconds 同一条错位理由：建筑的第一次升级是 1→2 级（所以城建传 targetLevel-1，
        // 基数正好落在曲线的第 1 项），而科技的第一次研究是 0→1 级 —— 这里直接传 targetLevel，
        // 于是第 L 次研究取曲线的第 L 项。少这一档会让 1 级与 2 级收一样的钱。
        long fixed = Formula.buildingCost(FixedPoint.of(base), targetLevel, ratioFixed);
        long amount = FixedPoint.round(fixed);
        if (amount > 0L) {
            cost.put(resource, amount);
        }
    }

    /** 返还：每一项按定点比例向下取整，算出 0 的那一项直接不进结果（不下发一串 0）。 */
    private Map<String, Long> refundOf(Map<String, Long> spent, long ratioFixed) {
        Map<String, Long> refund = new LinkedHashMap<>();
        spent.forEach((resource, amount) -> {
            long back = FixedPoint.round(FixedPoint.mul(FixedPoint.of(amount), ratioFixed));
            if (back > 0L) {
                refund.put(resource, back);
            }
        });
        return refund;
    }

    // ---------- 视图 ----------

    private TechListView view(PlayerSave player, CityState city, long now) {
        PlayerTech tech = player.tech();
        int academy = academyLevel(city);
        List<TechView> views = new ArrayList<>();
        for (TechCfg cfg : configs.all(TechCfg.class)) {
            views.add(viewOf(cfg, player, tech, academy, now));
        }
        TechQueueView queue = new TechQueueView(tech.researchingId(), tech.finishAt(),
                tech.startedAt(), tech.totalSeconds(), tech.remainingSeconds(now));
        return new TechListView(views, queue, academy, now);
    }

    private TechView viewOf(TechCfg cfg, PlayerSave player, PlayerTech tech, int academy, long now) {
        int level = tech.levelOf(cfg.id());
        boolean researching = cfg.id().equals(tech.researchingId());
        boolean maxed = level >= cfg.maxLevel();
        long nextTime = maxed ? 0L : researchSeconds(level + 1);
        Map<String, Long> cost = maxed ? Map.of() : researchCost(cfg, level + 1);
        TechBlockReason reason = blockReasonOf(cfg, player, tech, maxed, researching, academy, cost);
        return new TechView(cfg.id(), cfg.name(), TechSchool.valueOf(cfg.school().name()),
                TechEffectAttr.valueOf(cfg.effectAttr().name()), cfg.effectValue(),
                level, (int) cfg.maxLevel(), (int) cfg.requireAcademyLevel(),
                nextTime, toAmounts(cost), researching, reason == TechBlockReason.NONE, reason);
    }

    /**
     * 拦着的原因，按「信息量最大」排：满级是永久事实，不该因为别的行在研究而显示成「队列被占」；
     * 前置不满足是这行自己的事，比全局的队列状态更该先告诉玩家；买不起排在最后 ——
     * 资源是随产出每分钟都在变的量，把它排前面会让「明明只差资源」的行被别的理由盖住。
     *
     * <p>资源这一项读的是<b>已结算</b>的余额（整棵树的视图建在 {@code withSettledCity} 的分段结算之后），
     * 所以这里不再推时间。不判它的话，界面会在玩家付不起的时候把按钮点亮，
     * 而玩家收到的是一条他看不懂的失败。
     */
    private TechBlockReason blockReasonOf(TechCfg cfg, PlayerSave player, PlayerTech tech, boolean maxed,
                                          boolean researching, int academy, Map<String, Long> cost) {
        if (maxed) {
            return TechBlockReason.MAX_LEVEL;
        }
        if (academy < cfg.requireAcademyLevel()) {
            return TechBlockReason.ACADEMY_LOW;
        }
        if (researching || tech.isResearching()) {
            return TechBlockReason.QUEUE_BUSY;   // 包括「就是这一行正在研究」：再点一次不会缩短它
        }
        return affordable(player, cost) ? TechBlockReason.NONE : TechBlockReason.RESOURCE_LOW;
    }

    /** 逐项比 {@code cost} 与已结算余额。空表（满级行）恒为真。 */
    private boolean affordable(PlayerSave player, Map<String, Long> cost) {
        for (Map.Entry<String, Long> e : cost.entrySet()) {
            if (player.resource(e.getKey()).current() < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ---------- 资源与校验 ----------

    private int academyLevel(CityState city) {
        BuildingInstance academy = city.findByConfigId(ACADEMY_BUILDING_ID);
        return academy == null ? 0 : academy.level();
    }

    private TechCfg requireTech(String techId) {
        if (!configs.rawTable("tech").has(techId)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "tech.json 里没有这一行: " + techId + "（可研究的行随表走，不存在「隐藏科技」）");
        }
        return configs.get(TechCfg.class, techId);
    }

    private void requireResources(PlayerSave player, Map<String, Long> cost) {
        cost.forEach((resource, need) -> {
            long current = player.resource(resource).current();
            if (current < need) {
                throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                        resource + " 需要 " + need + "，当前 " + current);
            }
        });
    }

    /** 扣资源。调用前必须已经在 {@code withSettledCity} 的锁与结算之内。 */
    private void deductResources(PlayerSave player, Map<String, Long> cost) {
        cost.forEach((resource, need) -> {
            PlayerResourceState s = player.resource(resource);
            player.putResource(resource, new PlayerResourceState(
                    s.current() - need, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        });
    }

    /** 返还入账受容量上限约束，超出部分丢弃并留日志 —— 与城建的取消返还是同一条口径。 */
    private void grantResource(PlayerSave player, String resource, long amount) {
        if (amount <= 0L) {
            return;
        }
        PlayerResourceState s = player.resource(resource);
        long next = Math.min(s.cap(), s.current() + amount);
        if (next < s.current() + amount) {
            LOG.info("返还资源超出容量上限，多余部分丢弃 playerId={} resource={} 请求={} 实发={}",
                    player.playerId(), resource, amount, next - s.current());
        }
        player.putResource(resource, new PlayerResourceState(
                next, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
    }

    private List<ResourceAmount> toAmounts(Map<String, Long> amounts) {
        List<ResourceAmount> out = new ArrayList<>();
        amounts.forEach((resource, amount) ->
                out.add(new ResourceAmount(ResourceType.valueOf(resource), amount)));
        return out;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
        return value;
    }

    /**
     * 「幂等 → 交给城建锁」的骨架，与 {@code ArmyAppService.guarded} 同一条纪律：
     * 幂等必须<b>在加锁之前</b>占用（反过来会让重放请求白占一次玩家锁），
     * 失败必须释放幂等键（否则玩家重试被永久挡在门外）。
     */
    private <T> T guarded(String playerId, String requestId, java.util.function.Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "研究类操作必须带 requestId");
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId + " 已经用过了");
        }
        try {
            return action.get();
        } catch (RuntimeException e) {
            idempotency.release(requestId);
            throw e;
        }
    }
}
