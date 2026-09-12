package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.GachaCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.HeroRarityCfg;
import com.ironoath.core.gacha.GachaEngine;
import com.ironoath.core.gacha.GachaLogStore;
import com.ironoath.core.gacha.GachaState;
import com.ironoath.core.gacha.GachaStateRepository;
import com.ironoath.core.gacha.Tier;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.GachaDrawReq;
import com.ironoath.web.dto.generated.GachaDrawResp;
import com.ironoath.web.dto.generated.GachaProbItem;
import com.ironoath.web.dto.generated.GachaProbResp;
import com.ironoath.web.dto.generated.GachaResult;
import com.ironoath.web.dto.generated.HeroRarity;
import com.ironoath.web.dto.generated.PityRule;
import com.ironoath.web.dto.generated.TierRate;
import com.ironoath.web.reward.ServerSeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：抽卡应用服务 —— 扣费、抽取、发放、写合规日志（B06 §1/§6）。
 * 依赖：game-core 的抽卡引擎与端口、game-config 的卡池与武将表。
 *
 * <p><b>合规是这个类存在的一半理由</b>（B06 §6：不做完不许上线付费）：
 * <ul>
 *   <li>概率与保底全部按 gacha 表执行，代码里没有任何一个概率字面量（禁止项：不要写死）</li>
 *   <li>每次抽取写结构化日志，含 {@code isPity}（禁止项点名两次：监管会查这个字段）</li>
 *   <li>保底计数持久化且按卡池隔离（公示文案承诺「不因赛季或版本更新而清零」）</li>
 *   <li>概率面板读的是同一份 gacha 表（禁止项：客户端不得写死一份）</li>
 * </ul>
 *
 * <p><b>顺序是「先扣费 → 再抽 → 再发放 → 最后写日志」</b>，且任何一步失败都要回滚前一步。
 * 反过来（先抽再扣）在余额不足时会让玩家白抽一次 —— 那是可以直接刷的漏洞。
 * 日志写在最后但不允许失败：抽了却没日志，等监管来查时就是拿不出凭证。
 */
@Service
public class GachaAppService {

    private static final Logger LOG = LoggerFactory.getLogger(GachaAppService.class);

    private static final long LOCK_TIMEOUT_MS = 3000L;

    /** B06 §2：count 只能是 1 或 10（单抽或十连）。 */
    private static final List<Integer> ALLOWED_COUNTS = List.of(1, 10);

    private final ConfigRegistry configs;
    private final HeroRepository heroes;
    private final GachaPoolFactory poolFactory;
    private final GachaStateRepository gachaStates;
    private final GachaLogStore gachaLogs;
    private final RewardPorts.Bag bagPort;
    private final RewardPorts.Wallet wallet;
    private final RewardService rewardService;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ServerSeedSource seeds;

    /** 任务进度的事件入口（B12 §1）：抽卡按"几次"累加（十连 += 10）。 */
    private final com.ironoath.web.quest.QuestEvents questEvents;

    public GachaAppService(ConfigRegistry configs, HeroRepository heroes,
                           GachaPoolFactory poolFactory,
                           GachaStateRepository gachaStates, GachaLogStore gachaLogs,
                           RewardPorts.Bag bagPort, RewardPorts.Wallet wallet,
                           RewardService rewardService, PlayerLock playerLock,
                           IdempotencyStore idempotency, TimeService timeService,
                           ServerSeedSource seeds,
                           com.ironoath.web.quest.QuestEvents questEvents) {
        this.configs = configs;
        this.heroes = heroes;
        this.poolFactory = poolFactory;
        this.gachaStates = gachaStates;
        this.gachaLogs = gachaLogs;
        this.bagPort = bagPort;
        this.wallet = wallet;
        this.rewardService = rewardService;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.seeds = seeds;
        this.questEvents = questEvents;
    }

    // ---------- 概率公示（B06 §2：客户端面板读这个，与服务端同一份配置） ----------

    /**
     * 概率公示。
     *
     * <p>返回<b>公示概率</b>（综合概率，含保底），不是每抽基础概率 ——
     * 玩家要看到的是「我实际能拿到 SSR 的概率」，而基础概率加上保底之后才等于它。
     * 两组数的关系与校准方式见 gacha 表的 designNote 与 tools/gacha-calibrate。
     */
    public GachaProbResp probability(String poolId) {
        GachaCfg pool = requirePool(poolId);
        Map<Tier, List<HeroCfg>> byTier = poolFactory.heroesByTier();

        List<GachaProbItem> items = new ArrayList<>();
        // UP 档位由 UP 武将自己的稀有度决定，不写死 SSR：新手池 UP 的是一名 SR
        Tier upTier = poolFactory.upTierOf(pool);
        for (Tier tier : Tier.values()) {
            long tierRate = GachaPoolFactory.disclosedRate(pool, tier);
            List<HeroCfg> candidates = byTier.get(tier);
            if (candidates.isEmpty()) {
                continue;
            }
            // UP 武将在自己那一档单列，占该档的 upShare；其余同档武将平分剩下的部分。
            // 这样面板上每一行都是「抽到这个具体武将的概率」，加总恰好等于该档公示概率
            boolean isUpTier = tier == upTier;
            long upShare = isUpTier ? FixedPoint.mul(tierRate, GachaPoolFactory.UP_SSR_SHARE) : 0L;
            long restRate = tierRate - upShare;
            int restCount = isUpTier ? candidates.size() - 1 : candidates.size();
            long each = restCount > 0 ? restRate / restCount : 0L;
            long remainder = restCount > 0 ? restRate - each * restCount : 0L;
            for (int i = 0; i < candidates.size(); i++) {
                HeroCfg hero = candidates.get(i);
                boolean isUp = hero.id().equals(pool.upHeroId());
                long rate = isUp ? upShare : each;
                // 整除的余数补给该档第一个非 UP 武将：公示概率逐行相加必须恰好等于档位概率，
                // 差 1 个定点单位在监管眼里就是「公示不实」
                if (!isUp && remainder > 0 && i == firstNonUpIndex(candidates, pool.upHeroId())) {
                    rate += remainder;
                    remainder = 0L;
                }
                items.add(new GachaProbItem(hero.id(), hero.name(),
                        HeroRarity.valueOf(hero.rarity().name()), rate, isUp));
            }
        }

        List<TierRate> tierRates = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            tierRates.add(new TierRate(HeroRarity.valueOf(tier.name()), GachaPoolFactory.disclosedRate(pool, tier)));
        }
        long ssrUpGuarantee = pool.upHeroId() == null ? 0L : GachaPoolFactory.UP_SSR_GUARANTEE_AFTER;
        return new GachaProbResp(pool.id(), pool.name(), pool.poolType().name(),
                items, tierRates,
                new PityRule(pool.ssrPity(), pool.srPity(), ssrUpGuarantee),
                pool.disclosureText(), pool.costItemId(),
                pool.costCount(), pool.lifetimeLimit(), timeService.serverNow(),
                pool.costResource());
    }

    // ---------- 抽卡 ----------

    public GachaDrawResp draw(String playerId, GachaDrawReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        if (!ALLOWED_COUNTS.contains(req.count())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "count 只能是 " + ALLOWED_COUNTS + "（单抽或十连），实际=" + req.count());
        }
        GachaCfg pool = requirePool(req.poolId());

        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doDraw(playerId, req, pool, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private GachaDrawResp doDraw(String playerId, GachaDrawReq req, GachaCfg pool, long now) {
        GachaState state = gachaStates.find(playerId, pool.id())
                .orElseGet(() -> GachaState.fresh(playerId, pool.id()));
        if (pool.lifetimeLimit() > 0L && state.lifetimeDraws() + req.count() > pool.lifetimeLimit()) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    pool.name() + " 每个账号限抽 " + pool.lifetimeLimit() + " 次，已抽 "
                            + state.lifetimeDraws() + " 次");
        }

        // ① 先扣费。资源计价走钱包（受容量与惰性结算约束），道具计价走背包（原子扣减）
        long totalCost = pool.costCount() * req.count();
        boolean paidByResource = pool.costResource() != null;
        if (paidByResource) {
            if (wallet.deduct(playerId, pool.costResource(), totalCost, now) == 0L) {
                throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                        "需要 " + pool.costResource() + " " + totalCost + "，当前 "
                                + wallet.available(playerId, pool.costResource(), now));
            }
        } else {
            if (bagPort.remove(playerId, pool.costItemId(), totalCost) == 0L) {
                throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                        "需要 " + pool.costItemId() + " " + totalCost + " 个，当前持有 "
                                + bagPort.countOf(playerId, pool.costItemId()) + " 个");
            }
        }

        GachaEngine.Batch batch;
        HeroRoster roster = loadOrCreateRoster(playerId);
        long rosterVersion = heroes.versionOf(playerId);
        long seed;
        List<GachaResult> results = new ArrayList<>(req.count());
        List<GachaLogStore.Entry> logs = new ArrayList<>(req.count());
        List<RewardItem> fragmentRewards = new ArrayList<>();
        long fragmentsTotal = 0L;
        try {
            // ② 抽。种子由服务端生成，与任何请求字段无关（见 ServerSeedSource）
            seed = seeds.nextSeed();
            batch = GachaEngine.draw(poolFactory.pool(pool), seed, req.count(), state.counters());

            // ③ 发放：首次获得入武将存档，重复获得按 hero_rarity.dupFragment 转碎片
            for (int i = 0; i < batch.draws().size(); i++) {
                GachaEngine.Draw draw = batch.draws().get(i);
                HeroCfg hero = configs.get(HeroCfg.class, draw.heroId());
                boolean isNew = roster.obtain(draw.heroId());
                long fragments = 0L;
                if (!isNew) {
                    fragments = rarityEconomy(hero.rarity()).dupFragment();
                    fragmentsTotal += fragments;
                    fragmentRewards.add(new RewardItem(RewardType.HERO_FRAGMENT, draw.heroId(), fragments));
                }
                results.add(new GachaResult(draw.heroId(), hero.name(),
                        HeroRarity.valueOf(hero.rarity().name()), isNew, draw.pity(), fragments));
                logs.add(new GachaLogStore.Entry(playerId, pool.id(), now, req.requestId(),
                        seed, i, draw.heroId(), draw.tier(), draw.pity(), isNew, fragments));
            }
            heroes.save(playerId, roster, rosterVersion);
        } catch (RuntimeException e) {
            refund(playerId, pool, totalCost, paidByResource);
            throw e;
        }

        // ④ 保底进度落库。公示承诺「不因赛季或版本更新而清零」，所以这一步失败必须让整个抽卡失败
        gachaStates.save(state.after(batch.counters(), req.count()));

        // ⑤ 碎片走通用发放器：背包满时自动转邮件，不会凭空消失（B04 验收 2 的同一条纪律）
        if (!fragmentRewards.isEmpty()) {
            GrantResult grant = rewardService.grant(playerId, fragmentRewards,
                    RewardContext.toMail("gacha", pool.id(), req.requestId()));
            if (grant.hasCompensation()) {
                LOG.error("【抽卡碎片发放失败已进补偿队列】playerId={} pool={} traceId={} compensationId={} 明细={}",
                        playerId, pool.id(), TraceContext.traceId(), grant.compensationId(), grant.overflow());
            }
            if (grant.hasOverflow()) {
                fragmentsTotal = grant.granted().stream().mapToLong(RewardItem::count).sum();
            }
        }

        // ⑥ 合规日志。写在最后但绝不允许静默失败：抽了却没日志，监管来查时拿不出凭证
        gachaLogs.appendAll(logs);

        LOG.info("抽卡 playerId={} pool={} 次数={} seed={} 结果={} 碎片={} 保底进度=SSR {}/SR {}",
                playerId, pool.id(), req.count(), seed, results, fragmentsTotal,
                batch.counters().ssr(), batch.counters().sr());
        // 抽卡次数记在"这一池"上（B12 §1 的 GACHA_PULL）：十连 += 10，而不是 +1 ——
        // 「抽 1 次新手池」那类任务靠的就是这个数
        questEvents.progress(playerId, com.ironoath.core.quest.GoalType.GACHA_PULL,
                pool.id(), req.count(), now);
        return new GachaDrawResp(results, batch.counters().ssr(), batch.counters().sr(),
                fragmentsTotal, pool.costItemId(), totalCost, seed, now, pool.costResource());
    }

    /** 扣费成功但后续失败时必须退还，否则玩家白付一次钱。 */
    private void refund(String playerId, GachaCfg pool, long totalCost, boolean paidByResource) {
        if (paidByResource) {
            long back = wallet.grant(playerId, pool.costResource(), totalCost, timeService.serverNow());
            if (back < totalCost) {
                LOG.error("【抽卡退款不足】playerId={} pool={} 应退 {} {} 实退 {} traceId={}",
                        playerId, pool.id(), totalCost, pool.costResource(), back, TraceContext.traceId());
            }
        } else {
            long back = bagPort.add(playerId, pool.costItemId(), totalCost);
            if (back < totalCost) {
                LOG.error("【抽卡退道具不足】playerId={} pool={} 应退 {}×{} 实退 {} traceId={}",
                        playerId, pool.id(), pool.costItemId(), totalCost, back, TraceContext.traceId());
            }
        }
    }

    private static int firstNonUpIndex(List<HeroCfg> candidates, String upHeroId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (!candidates.get(i).id().equals(upHeroId)) {
                return i;
            }
        }
        return -1;
    }

    private HeroRoster loadOrCreateRoster(String playerId) {
        var existing = heroes.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        heroes.insertIfAbsent(playerId, new HeroRoster());
        return heroes.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "武将存档创建后立即读不到"));
    }

    private GachaCfg requirePool(String poolId) {
        if (poolId == null || poolId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "poolId 不得为空");
        }
        try {
            return configs.get(GachaCfg.class, poolId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "卡池不存在: " + poolId);
        }
    }

    private HeroRarityCfg rarityEconomy(HeroCfg.Rarity rarity) {
        try {
            return configs.get(HeroRarityCfg.class, rarity.name());
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND,
                    "hero_rarity 表缺少 " + rarity + " 档，重复武将无法折算碎片");
        }
    }

    /** 主动清理过期的合规日志。保留期来自 global.GACHA_LOG_RETENTION_DAYS。 */
    public int purgeExpiredLogs() {
        long retentionDays = configs.longParam("GACHA_LOG_RETENTION_DAYS");
        long cutoff = timeService.serverNow() - retentionDays * 86_400_000L;
        int removed = gachaLogs.purgeBefore(cutoff);
        LOG.info("清理过期抽卡日志 保留={}天 截止时间={} 删除={}条", retentionDays, cutoff, removed);
        return removed;
    }

    /**
     * 最近若干次抽取记录（B15 §三 合规必做：概率公示 + 抽取记录查询）。
     *
     * <p><b>这是监管要求，不是产品功能</b>：公示了概率却查不到自己的记录，
     * 等于让玩家只能相信而无法验证 —— 而「无法验证的公示」在监管口径里等同于没有公示。
     * 所以 {@code isPity} 必须下发：公示里写了保底，玩家就要能在自己的记录里看到保底确实生效过。
     *
     * <p><b>查询窗口与保留窗口是两个口径</b>：这里返回最近 GACHA_HISTORY_LIMIT 条（给玩家看），
     * 而日志本身保留 GACHA_LOG_RETENTION_DAYS 天（给监管与客服取证）。
     * 两者的下限都由后者决定 —— 保留期短于查询窗口时，玩家会看到一段莫名其妙的空白。
     *
     * <p>只查本人的记录：别人的抽取记录含卡池与保底进度，那是能反推对方氪金量的信息。
     */
    public com.ironoath.web.dto.generated.GachaHistoryResp history(String playerId) {
        long now = timeService.serverNow();
        int retentionDays = (int) configs.longParam("GACHA_LOG_RETENTION_DAYS");
        int limit = (int) configs.longParam("GACHA_HISTORY_LIMIT");
        long since = now - retentionDays * 86_400_000L;
        List<GachaLogStore.Entry> entries = new ArrayList<>(gachaLogs.query(playerId, since));
        // 按抽取时刻倒序（最新的在前）：存储的返回顺序不该被当成契约，
        // 一旦它改成倒序返回，这里不排序就会把最旧的 50 条当成「最近 50 条」下发
        entries.sort((a, b) -> Long.compare(b.drawnAt(), a.drawnAt()));
        List<com.ironoath.web.dto.generated.GachaRecord> records = new ArrayList<>(Math.min(limit, entries.size()));
        for (GachaLogStore.Entry entry : entries) {
            if (records.size() >= limit) {
                break;
            }
            records.add(new com.ironoath.web.dto.generated.GachaRecord(
                    entry.drawnAt(), entry.poolId(), entry.heroId(), entry.isPity()));
        }
        return new com.ironoath.web.dto.generated.GachaHistoryResp(records, retentionDays, now);
    }
}
