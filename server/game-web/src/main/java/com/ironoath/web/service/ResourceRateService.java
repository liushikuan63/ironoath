package com.ironoath.web.service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.city.ResourceSettlement;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.core.resource.ResourceProtection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：资源产率与容量的唯一计算入口 —— 从城建状态算出「每小时产多少」「能囤多少」，
 * 并负责把结果写回存档（B04 §1 资源模型、§2 产出明细面板）。
 * 依赖：game-config（配置表）、game-core（惰性结算、明细计算、保护量）。
 *
 * <p><b>为什么必须有这个类</b>：产率与容量是「读出来的」而不是「存出来的」——
 * 它们完全由城建状态与配置表决定。存档里那两个字段（perHour / cap）只是上一次读取时的缓存，
 * 存在的唯一理由是惰性结算需要一个速率来做时间差乘法。
 * 一旦有第二处代码自己算产率，缓存与真相就会分叉，而分叉的表现形式是
 * 「面板写 1200/小时，实际到账 200/小时」——B04 说得很清楚，这个面板做得好会提升付费转化，
 * 那么它算错就会直接摧毁信任。所以全项目只允许这一处算。
 *
 * <p><b>分段结算是本类最关键的正确性要求</b>：产率在每次升级完成时发生阶跃。
 * 若把整个离线窗口都按读档时的等级追溯，玩家只要挂机就能白拿一段高等级产量
 * （升级在完成时刻就已经生效，但读档发生在很久之后）。
 * 因此按 {@link CityState#pendingFinishTimes} 逐段结算：先结算到完成时刻、再收割、再结算到下一段。
 *
 * <p><b>升级中的建筑不计产率</b>（B03 §2：升级中建筑不产资源）。收割后它按新等级计入，
 * 从完成时刻起算，所以既不会少给也不会重复给。
 */
@Service
public class ResourceRateService {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceRateService.class);

    private final ConfigRegistry configs;
    /** 科技加成的唯一读取口（B20 块①：产量那一格今天真的有值了）。 */
    private final com.ironoath.web.tech.TechEffects techEffects;
    /** 国家科技那一份（B20 块③）。与上面那一位<b>相加</b>后作用一次（§五④），不各乘一遍。 */
    private final com.ironoath.web.nation.NationTechBonuses nationTechBonuses;

    public ResourceRateService(ConfigRegistry configs, com.ironoath.web.tech.TechEffects techEffects,
                               com.ironoath.web.nation.NationTechBonuses nationTechBonuses) {
        this.configs = configs;
        this.techEffects = techEffects;
        this.nationTechBonuses = nationTechBonuses;
    }

    /**
     * 一次产率计算的结果。
     *
     * @param breakdowns 资源 id → 产出明细（明细之和恒等于实际每小时产量，B04 验收 5）
     * @param caps       资源 id → 容量上限
     */
    public record Rates(Map<String, ResourceOutputCalculator.Breakdown> breakdowns,
                        Map<String, Long> caps) {

        public Rates {
            // 用保序的不可变视图而不是 Map.copyOf：面板按 resource 表的配置顺序展示，
            // Map.copyOf 的迭代顺序不确定，会让资源条的顺序在不同 JVM 上不一样
            breakdowns = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(breakdowns));
            caps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(caps));
        }

        public long perHour(String resourceId) {
            ResourceOutputCalculator.Breakdown b = breakdowns.get(resourceId);
            return b == null ? 0L : b.totalPerHour();
        }

        public long cap(String resourceId) {
            return caps.getOrDefault(resourceId, 0L);
        }
    }

    /**
     * 一次分段结算的结果。
     *
     * @param states    资源 id → 结算后的状态（已写回存档）
     * @param credited  资源 id → 本次结算实际入账的产量（用于 /city/collect 的 output 字段）
     * @param harvested 本次被收割的升级（等级已 +1、队列已释放），按完成时刻升序；
     *                  客户端据此播放升级动效与「+X 战力」飘字
     * @param rates     最后一段使用的产率与容量。带上它是为了让产出明细面板直接用同一份数字 ——
     *                  面板若自己再算一次，两次计算之间只要发生一次收割就会与存档对不上
     */
    public record Settlement(Map<String, PlayerResourceState> states,
                             Map<String, Long> credited,
                             List<String> harvested,
                             Rates rates) {

        public Settlement {
            states = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(states));
            credited = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(credited));
            harvested = List.copyOf(harvested);
        }
    }

    /**
     * 从城建状态算出每种资源的产出明细与容量上限。
     *
     * <p>明细结构（B04 §2）：
     * <pre>
     * 领地基础产出        +200      ← resource 表 basePerHour
     * 农田 Lv8           +1,150    ← 每个产出建筑实例一行
     * 科技加成  +0 (+0%)            ← 三行始终出现，面板结构稳定
     * 联盟加成  +0 (+0%)
     * 道具 buff +0 (+0%)
     * </pre>
     */
    public Rates compute(CityState city, PlayerTech tech, String playerId) {
        if (city == null) {
            throw new IllegalArgumentException("city 不得为 null");
        }
        long outputExponent = configs.curve("BUILDING_OUTPUT").exponentFixed();
        long protectRatio = configs.fixedParam("RESOURCE_PROTECT_RATIO");
        int mainCityLevel = mainCityLevel(city);

        Map<String, ResourceOutputCalculator.Breakdown> breakdowns = new LinkedHashMap<>();
        Map<String, Long> caps = new LinkedHashMap<>();

        for (ResourceCfg res : configs.allResources()) {
            List<ResourceOutputCalculator.Line> baseLines = new ArrayList<>();
            if (res.basePerHour() > 0L) {
                baseLines.add(ResourceOutputCalculator.Line.flat("领地基础产出", res.basePerHour()));
            }
            // 体力的容量随主城等级成长，不走仓库（B09 §5：上限随等级提升）
            long capacity = res.kind() == ResourceCfg.Kind.STAMINA
                    ? staminaCap(mainCityLevel)
                    : res.initCap();
            for (BuildingInstance b : city.buildings()) {
                BuildingCfg cfg = configs.get(BuildingCfg.class, b.configId());
                if (res.id().equals(cfg.outputResource())) {
                    long perHour = buildingPerHour(cfg, b, outputExponent);
                    if (perHour > 0L) {
                        baseLines.add(ResourceOutputCalculator.Line.flat(
                                cfg.name() + " Lv" + b.level(), perHour));
                    }
                }
                // 仓库容量：capBase 走与产量同一条曲线，使「能囤几小时」与等级无关（见 building.json designNote）
                if (cfg.capBase() > 0L && res.kind() == ResourceCfg.Kind.BASE && b.level() > 0
                        && !b.isUpgrading()) {
                    capacity += FixedPoint.round(Formula.buildingOutput(
                            FixedPoint.of(cfg.capBase()), b.level(), outputExponent));
                }
            }
            // 科技这一行从 B20 块① 起是真实值（个人：该资源对应的 *_OUTPUT 行 每级幅度 × 等级），
            // 块③ 起再加一份国家的（§五④：**同类加成相加成总率，再作用于基础值一次**）——
            // 相加发生在这一句，不在两个读取口里各乘一遍。
            // 联盟加成与道具 buff 两位仍是 0 —— 那不是"忘了填"，而是那两个生产者还不存在：
            // 联盟科技表没有 *_OUTPUT 属性，道具 buff 没有承载。0 是"还没做"，
            // 编一个值才是"做了但不算数"（明细结构与 Σ==总量 的不变量都不受影响）。
            breakdowns.put(res.id(), ResourceOutputCalculator.compute(baseLines,
                    techEffects.outputPercent(res.id(), tech)
                            + nationTechBonuses.outputPercent(res.id(), playerId), 0L, 0L));
            caps.put(res.id(), capacity);
        }
        return new Rates(breakdowns, caps);
    }

    /**
     * 保护量 = 容量 × RESOURCE_PROTECT_RATIO（B04 §1、验收 6）。
     *
     * <p><b>付费货币不参与保护</b>：GOLD 在 resource 表里 kind=CURRENCY，直接返回 0 额度。
     * 理由是金币主要来自充值与付费礼包，被掠夺等于直接拿走玩家花的钱 ——
     * 那是合规与口碑事故，不是「制造冲突」。基础资源被抢才是 B00 想要的社交起点。
     * 返回 0 而不是「不设上限保护」，是因为掠夺逻辑（B07）本来就只针对基础资源，
     * 让金币的额度显式为 0 可以避免以后有人误以为金币也在保护范围内。
     *
     * <p><b>体力同样返回 0</b>：体力不在仓库里，「被抢走一部分体力」在物理上说不通。
     * 给它算一个保护额度反而危险 —— 那会让人以为掠夺逻辑需要考虑它。
     */
    public long protectedAmountOf(String resourceId, Rates rates) {
        ResourceCfg res = configs.getResource(resourceId);
        if (res.kind() != ResourceCfg.Kind.BASE) {
            return 0L;
        }
        return ResourceProtection.protectedAmount(
                rates.cap(resourceId), configs.fixedParam("RESOURCE_PROTECT_RATIO"));
    }

    /**
     * 体力上限 = 基准 + 每级增量 × (主城等级 - 1)（B09 §5：上限随等级提升）。
     *
     * <p>用 {@code level - 1} 而不是 {@code level}，是为了让 1 级时正好等于基准值，
     * 也就是等于 resource 表里 STAMINA 行的 initCap —— 新号建档时用的就是 initCap，
     * 若公式在 1 级给出更大的值，玩家第一次打开面板就会看到容量凭空跳了一截，
     * 而「initCap 与 STAMINA_CAP_BASE 必须相等」这条一致性也就无从断言。
     *
     * <p>按主城等级而不是「玩家等级」：本项目没有独立的玩家等级轴（B00 的成长轴就是主城），
     * 再引入一个会让「上限随等级提升」这句话有两个解释。
     */
    private long staminaCap(int mainCityLevel) {
        return configs.longParam("STAMINA_CAP_BASE")
                + configs.longParam("STAMINA_CAP_PER_LEVEL") * (mainCityLevel - 1L);
    }

    /**
     * 主城（CORE 建筑）等级；找不到时按 1 级算，与 {@code PlayerSave.cityLevel} 的下限一致。
     *
     * <p><b>不跳过升级中的建筑</b>：{@code BuildingInstance.level()} 在升级期间就是
     * 「已达到的等级」，升级完成时才 +1（见 {@code CityState.collectFinished}）。
     * 所以「升级中不抬高体力上限」是由 level 字段本身的语义保证的，不需要额外判断状态 ——
     * 反过来，若在这里跳过升级中的建筑，玩家一开工升级主城，体力上限就会<b>掉回 1 级</b>，
     * 而这不会报错，只会表现为「升级期间体力上限莫名其妙变少了」。
     *
     * <p>注意这与「升级中的建筑不计产率」不冲突：那一条说的是产出暂停
     * （{@link #buildingPerHour} 里判 {@code isUpgrading}），是另一件事。
     */
    private int mainCityLevel(CityState city) {
        int level = 1;
        for (BuildingInstance b : city.buildings()) {
            if (configs.get(BuildingCfg.class, b.configId()).type() != BuildingCfg.Type.CORE) {
                continue;
            }
            if (b.level() > level) {
                level = b.level();
            }
        }
        return level;
    }

    /**
     * 分段结算并把新的产率、容量、保护量写回存档。
     *
     * <p>调用方必须在玩家锁内使用（读-改-写）。本方法会顺带收割到点的升级 ——
     * 收割是结算的前置步骤（等级变了产率才变），不是两件独立的事。
     *
     * @return 结算后的状态与本次实际入账的产量
     */
    public Settlement settle(PlayerSave player, CityState city, long now) {
        if (player == null) {
            throw new IllegalArgumentException("player 不得为 null");
        }
        Map<String, Long> credited = new LinkedHashMap<>();
        Map<String, PlayerResourceState> states = new LinkedHashMap<>();
        List<String> harvested = new ArrayList<>();
        Rates lastRates = compute(city, player.tech(), player.playerId());

        // 逐个完成时刻切段：先按「收割前」的产率结算到该时刻，再收割，再进入下一段。
        // 这样升级带来的产率阶跃只影响它真正生效之后的时间。
        for (long milestone : city.pendingFinishTimes(now)) {
            Segment seg = settleTo(player, city, milestone);
            accumulate(credited, seg.credited());
            lastRates = seg.rates();
            List<String> done = city.collectFinished(milestone);
            harvested.addAll(done);
            if (!done.isEmpty()) {
                LOG.info("分段结算触发收割 playerId={} 完成时刻={} 建筑={}",
                        player.playerId(), milestone, done);
            }
        }
        Segment tail = settleTo(player, city, now);
        accumulate(credited, tail.credited());
        lastRates = tail.rates();

        for (Map.Entry<String, PlayerResourceState> e : player.resources().entrySet()) {
            states.put(e.getKey(), e.getValue());
        }
        return new Settlement(states, credited, harvested, lastRates);
    }

    /**
     * 只读地把存档里的资源结算到 {@code now}，返回视图用的状态表。
     *
     * <p><b>与 {@link #settle} 的三点差别：不收割到点的升级、不写回存档、不加锁。</b>
     * 本方法服务于「只要一份自洽快照」的读路径 —— 典型是登录响应：它把 {@code serverNow}
     * 与资源放在同一份载荷里，若资源停留在上次结算的时刻，客户端就会拿一个旧存量配一个新时刻，
     * 而 B00 要求客户端从不结算，它没法自己补上这段时间的产出。
     *
     * <p><b>不持久化在这里没有代价</b>：结算是 (current, cap, perHour, lastSettle, now) 的纯函数，
     * 下一次真结算会从同一个 {@code lastSettle} 出发算出同样的量。
     * 反过来，若在登录这种并发重入最多的入口做读-改-写，两次重登就会互相撞乐观锁版本，
     * 玩家看到的是「登录失败」，而它本来只是一次读取。
     *
     * @param city 城建状态；<b>允许为 null</b>（新号或城还没建起来）。此时产率与容量沿用存档里的值
     *             —— 它们本来就是建档时按同一来源算出来的 —— 只结算时间轴。
     *             把退化逻辑放在这里而不是让每个调用方各写一份，是为了不让「拿不到城」
     *             变成干脆不结算的理由：那等于让新号登录返回一份停在建档时刻的存量
     */
    public Map<String, PlayerResourceState> settledView(PlayerSave player, CityState city, long now) {
        if (player == null) {
            throw new IllegalArgumentException("settledView 需要玩家存档");
        }
        Rates rates = city == null ? null : compute(city, player.tech(), player.playerId());
        Map<String, PlayerResourceState> out = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerResourceState> e : player.resources().entrySet()) {
            String id = e.getKey();
            PlayerResourceState s = e.getValue();
            long cap = rates == null ? s.cap() : rates.cap(id);
            long perHour = rates == null ? s.perHour() : rates.perHour(id);
            ResourceSettlement.Result r = ResourceSettlement.settle(
                    s.current(), cap, perHour, s.lastSettle(), now);
            if (r.heldOverCap()) {
                LOG.warn("资源持有量超过容量上限：本次停止产出且不清仓，请检查哪条路径漏了封顶"
                                + " playerId={} 资源={} 持有={} 上限={}",
                        player.playerId(), id, s.current(), cap);
            }
            out.put(id, new PlayerResourceState(r.current(), cap,
                    rates == null ? s.protectedAmount() : protectedAmountOf(id, rates), perHour, r.lastSettle()));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 一段结算的产出：入账量与这一段使用的产率。 */
    private record Segment(Map<String, Long> credited, Rates rates) {
    }

    /** 用「当前城建状态」的产率与容量，把所有资源结算到时刻 t 并写回存档。 */
    private Segment settleTo(PlayerSave player, CityState city, long t) {
        Rates rates = compute(city, player.tech(), player.playerId());
        Map<String, Long> credited = new LinkedHashMap<>();
        // player.resources() 返回快照副本，遍历期间写回不会触发并发修改
        for (Map.Entry<String, PlayerResourceState> e : player.resources().entrySet()) {
            String id = e.getKey();
            PlayerResourceState s = e.getValue();
            long perHour = rates.perHour(id);
            long cap = rates.cap(id);
            ResourceSettlement.Result r = ResourceSettlement.settle(
                    s.current(), cap, perHour, s.lastSettle(), t);
            long gain = r.current() - s.current();
            if (gain > 0L) {
                credited.put(id, gain);
            }
            player.putResource(id, new PlayerResourceState(
                    r.current(), cap, protectedAmountOf(id, rates), perHour, r.lastSettle()));
        }
        return new Segment(credited, rates);
    }

    private static void accumulate(Map<String, Long> total, Map<String, Long> part) {
        part.forEach((k, v) -> total.merge(k, v, Long::sum));
    }

    /**
     * 单个建筑实例的每小时产量。
     *
     * <p>两种情况返回 0：等级为 0（刚放置还没升过级）、正在升级（B03 §2：升级中不产资源）。
     * 正在升级的建筑在收割后会按新等级从完成时刻起计入，所以这段时间不产是设计如此，不是漏算。
     */
    private long buildingPerHour(BuildingCfg cfg, BuildingInstance b, long outputExponent) {
        if (b.level() <= 0 || b.isUpgrading()) {
            return 0L;
        }
        Long base = cfg.outputBasePerHour();
        if (base == null || base <= 0L) {
            return 0L;
        }
        return FixedPoint.round(Formula.buildingOutput(FixedPoint.of(base), b.level(), outputExponent));
    }
}
