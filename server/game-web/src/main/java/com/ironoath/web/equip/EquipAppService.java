package com.ironoath.web.equip;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.EquipCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.EquipForgeBlockReason;
import com.ironoath.web.dto.generated.EquipForgeReq;
import com.ironoath.web.dto.generated.EquipForgeResp;
import com.ironoath.web.dto.generated.EquipInstanceListView;
import com.ironoath.web.dto.generated.EquipInstanceView;
import com.ironoath.web.dto.generated.EquipRarity;
import com.ironoath.web.dto.generated.EquipSlot;
import com.ironoath.web.hero.EquipLedger;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.HeroStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：装备实例的清单与强化（B20 块② S3 —— §五② 那个"纯消耗、必成"的动作本身）。
 * 依赖：背包账本、城建那条「锁 + 惰性结算」的共用入口、武将属性算式、幂等表。
 *
 * <p><b>骨架为什么长得很像 {@code TechAppService}</b>：花钱抬一位等级这件事，两个域完全同形
 * （幂等 → 玩家锁 → 结算 → 判定 → 扣资源 → 改账本 → 落库）。刻意不去抽一个公共基类：
 * 三处相似好过一层提前抽象，而这里的每一处相似都有一个不同的地方会先腐坏
 * （科技的判定在领域里、装备的判定一半在资源上）。
 *
 * <p><b>"能不能强化"只有一个函数说</b>（{@link #blockOf}）：清单里的 {@code canForge}/{@code blockReason}
 * 与写路径抛的错误码都出自它。两份判定的分叉不报错，症状是"按钮亮着却按失败"（#164 同一条教训）。
 *
 * <p><b>价格与效果都住表</b>（铁律 1）：一级铁耗 = 该行属性总和 × {@code curve.EQUIP_FORGE_COST.base}
 * × 1.22^(n-1)，基数 70 由 {@code tools/calibrate-equip-forge.mjs} 量出；每级效果 = 本行三维各 +
 * {@code global.EQUIP_FORGE_ATTR_GAIN}，作用点在 {@link EquipLedger}。这里一个数都不抄。
 */
@Service
public class EquipAppService {

    private static final Logger LOG = LoggerFactory.getLogger(EquipAppService.class);
    private static final String CURVE_ID = "EQUIP_FORGE_COST";
    private static final String IRON = "IRON";

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final InventoryRepository inventories;
    private final HeroRepository heroes;
    private final HeroStatsService stats;
    private final EquipLedgers equipLedgers;
    private final CityAppService cityAppService;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;

    public EquipAppService(ConfigRegistry configs, PlayerRepository players,
                           InventoryRepository inventories, HeroRepository heroes,
                           HeroStatsService stats, EquipLedgers equipLedgers,
                           CityAppService cityAppService, PlayerLock playerLock,
                           IdempotencyStore idempotency, TimeService timeService) {
        this.configs = configs;
        this.players = players;
        this.inventories = inventories;
        this.heroes = heroes;
        this.stats = stats;
        this.equipLedgers = equipLedgers;
        this.cityAppService = cityAppService;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
    }

    // ---------- 清单 ----------

    /** 这个玩家的全部装备实例（包里的与穿着的都在这一个数组里）。 */
    public EquipInstanceListView list(String playerId) {
        return cityAppService.withSettledCity(playerId, snap -> {
            Inventory bag = requireBag(playerId);
            Map<String, String> wornBy = wornByOf(playerId);
            EquipLedger ledger = equipLedgers.ofBag(bag);
            List<EquipInstanceView> views = new ArrayList<>(bag.equipCount());
            for (Inventory.EquipInstance instance : bag.equipInstances()) {
                views.add(view(instance, ledger, wornBy, snap.player()));
            }
            return new EquipInstanceListView(views, snap.now());
        });
    }

    // ---------- 强化 ----------

    /**
     * 强化一件装备一级：扣铁、抬等级、返回这一件的新状态。
     *
     * <p><b>校验顺序是刻意的</b>：先认 uid（参数问题）、再判能不能（等级与铁），
     * 全部判完才扣。反过来（先扣再判）就会在"等级已满"这条路上拿走玩家的铁。
     */
    public EquipForgeResp forge(String playerId, EquipForgeReq req) {
        if (req == null || req.equipUid() == null || req.equipUid().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "equipUid 不得为空");
        }
        if (!Inventory.isInstanceUid(req.equipUid())) {
            // 传行 id 是这个域里最容易犯的错（穿戴那条老路还留着），所以这里不兼容：
            // 强化必须有"是哪一件"的答案，替玩家挑一件等于花他的钱决定他的财产
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "equipUid 要的是装备实例 uid（形如 e7），不是配置行 id " + req.equipUid()
                            + "。请先取 GET /equip/instances");
        }
        return guarded(playerId, req.requestId(), () ->
                cityAppService.withSettledCity(playerId, snap -> {
                    PlayerSave player = snap.player();
                    Inventory bag = requireBag(playerId);
                    long bagVersion = inventories.versionOf(playerId);
                    Inventory.EquipInstance before = bag.equipInstance(req.equipUid());
                    if (before == null) {
                        throw new BizException(ErrorCode.ITEM_NOT_FOUND,
                                "背包里没有 uid=" + req.equipUid() + " 这件装备（GET /equip/instances 可查现有的）");
                    }
                    EquipCfg row = configs.get(EquipCfg.class, before.equipId());
                    int forgeMax = (int) row.forgeMax();
                    long cost = forgeCost(row, before.forgeLevel() + 1);
                    EquipForgeBlockReason blocked = blockOf(before, forgeMax, cost, player);
                    if (blocked != EquipForgeBlockReason.NONE) {
                        throw new BizException(blocked == EquipForgeBlockReason.MAX_LEVEL
                                ? ErrorCode.EQUIP_FORGE_MAX : ErrorCode.RESOURCE_NOT_ENOUGH,
                                blocked == EquipForgeBlockReason.MAX_LEVEL
                                        ? "装备 " + row.name() + "（uid=" + before.uid()
                                                + "）已到强化上限 " + forgeMax + " 级"
                                        : IRON + " 需要 " + cost + "，当前 "
                                                + player.resource(IRON).current());
                    }
                    // 穿着的那一件涨战力，在包里的一件不涨：powerDelta 就是这条规则的机器化版本
                    String wearerId = wornByOf(playerId).get(before.uid());
                    HeroInstance wornBy = wearerId == null ? null : requireRoster(playerId).hero(wearerId);
                    long powerBefore = wornBy == null ? 0L : stats.power(equipLedgers.ofBag(bag), wornBy);

                    deductIron(player, cost);
                    Inventory.EquipInstance forged = bag.forge(req.equipUid(), forgeMax);
                    players.save(player);
                    inventories.save(playerId, bag, bagVersion);

                    long powerAfter = wornBy == null ? 0L
                            : stats.power(equipLedgers.of(playerId), wornBy);
                    long delta = Math.max(0L, powerAfter - powerBefore);
                    LOG.info("强化装备 playerId={} uid={} 行={} +{}→+{} 铁={} 战力+={} 剩余铁={}",
                            playerId, forged.uid(), forged.equipId(), before.forgeLevel(),
                            forged.forgeLevel(), cost, delta, player.resource(IRON).current());
                    return new EquipForgeResp(view(forged, equipLedgers.of(playerId),
                                    wornByOf(playerId), player),
                            cost, delta, timeService.serverNow());
                }));
    }

    // ---------- 判定与算式（只有一个家） ----------

    /** 这一件现在能不能强化。清单与写路径共用，见类注释。 */
    private EquipForgeBlockReason blockOf(Inventory.EquipInstance instance, int forgeMax,
                                          long nextCost, PlayerSave player) {
        if (instance.forgeLevel() >= forgeMax) {
            return EquipForgeBlockReason.MAX_LEVEL;
        }
        return player.resource(IRON).current() >= nextCost
                ? EquipForgeBlockReason.NONE : EquipForgeBlockReason.IRON_LOW;
    }

    /**
     * 第 {@code level} 级的铁耗 = 该行属性总和 × 曲线基数 × ratio^(level-1)。
     *
     * <p>属性总和作乘数是 §五② 那条"价格跟着强度走"的落地：它让"每 1 铁买到多少属性"
     * 在 16 行之间处处相等，于是强化不是一条套利线（#165 的推导）。
     */
    private long forgeCost(EquipCfg row, int level) {
        long attrs = row.might() + row.command() + row.wisdom();
        // baseFixed 已经是定点（曲线里写 70 → 这里读到 700000），所以 attrs × base 就直接是行价的
        // 定点基数，**不能再套一次 FixedPoint.of**：那等于乘两万一，测试抓到过一回
        // （840 报成 8 400 000，症状是"谁都强化不起"而不是报错，很容易被当成数值调参问题放走）
        long rowBaseFixed = attrs * configs.curve(CURVE_ID).baseFixed();
        long ratio = configs.curve(CURVE_ID).ratioFixed();
        return FixedPoint.round(Formula.buildingCost(rowBaseFixed, Math.max(1, level), ratio));
    }

    private EquipInstanceView view(Inventory.EquipInstance instance, EquipLedger ledger,
                                   Map<String, String> wornBy, PlayerSave player) {
        EquipCfg row = configs.get(EquipCfg.class, instance.equipId());
        EquipLedger.Resolved resolved = ledger.resolve(instance.uid());
        int forgeMax = (int) row.forgeMax();
        long nextCost = forgeCost(row, instance.forgeLevel() + 1);
        // 面板与写路径同一个判定：这里如果只判等级不判铁，界面就会对"钱够不够"说一句假话
        EquipForgeBlockReason reason = blockOf(instance, forgeMax, nextCost, player);
        return new EquipInstanceView(instance.uid(), row.id(), row.name(),
                EquipSlot.valueOf(row.slot().name()), EquipRarity.valueOf(row.rarity().name()),
                instance.forgeLevel(), forgeMax,
                resolved == null ? 0L : resolved.mightFixed(),
                resolved == null ? 0L : resolved.commandFixed(),
                resolved == null ? 0L : resolved.wisdomFixed(),
                reason == EquipForgeBlockReason.MAX_LEVEL ? 0L : nextCost,
                reason == EquipForgeBlockReason.NONE, reason,
                wornBy.get(instance.uid()));
    }

    private Map<String, String> wornByOf(String playerId) {
        Map<String, String> wornBy = new HashMap<>();
        HeroRoster roster = heroes.findByPlayerId(playerId).orElse(null);
        if (roster == null) {
            return wornBy;
        }
        for (HeroInstance hero : roster.heroes()) {
            hero.equips().values().forEach(uid -> wornBy.put(uid, hero.heroId()));
        }
        return wornBy;
    }

    private HeroRoster requireRoster(String playerId) {
        return heroes.findByPlayerId(playerId).orElseGet(HeroRoster::new);
    }

    private Inventory requireBag(String playerId) {
        return inventories.findByPlayerId(playerId).orElseGet(() -> {
            Inventory fresh = Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY"));
            inventories.insertIfAbsent(playerId, fresh);
            return inventories.findByPlayerId(playerId).orElse(fresh);
        });
    }

    private void deductIron(PlayerSave player, long cost) {
        PlayerResourceState s = player.resource(IRON);
        player.putResource(IRON, new PlayerResourceState(
                s.current() - cost, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
    }

    /** 幂等 → 锁：与科技/训练同一套（键必须在加锁之前占，失败必须释放）。 */
    private <T> T guarded(String playerId, String requestId,
                          java.util.function.Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "强化必须带 requestId");
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId + " 已经用过了");
        }
        try {
            return playerLock.runLocked(playerId, 3000L, action);
        } catch (RuntimeException e) {
            idempotency.release(requestId);
            throw e;
        }
    }
}
