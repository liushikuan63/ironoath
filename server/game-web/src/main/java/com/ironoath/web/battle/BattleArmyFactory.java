package com.ironoath.web.battle;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.FormationType;
import com.ironoath.battle.HeroSnapshot;
import com.ironoath.battle.TechBonus;
import com.ironoath.battle.OrgBonus;
import com.ironoath.battle.UnitStats;
import com.ironoath.battle.UnitType;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.TierSplit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把「unitId（含阶级）→ 数量」的队伍折成战斗内核要的「每兵种一份属性」（B09 战斗接线的地基）。
 * 依赖：game-config（unit 表）、game-battle 的值对象。
 *
 * <p><b>为什么加权平均是精确的，而不是近似</b>——这是 B07 把 ATTACK 推迟到 B09 时不敢拍的那个口径，
 * 现在可以拍了，依据是战斗内核的两个公式都对「数量 × 属性」<b>线性</b>：
 * <pre>
 *   有效攻击 = Σ(数量 × 单位攻击 × 乘区)
 *   有效防御 = Σ(数量 × (单位防御 + 单位生命 × HP_DEFENSE_WEIGHT) × 加成)
 * </pre>
 * 于是「100 个 T1 步兵 + 100 个 T3 步兵」与「200 个属性为两者加权平均的步兵」
 * 在内核眼里完全等价 —— 总攻击质量与总防御质量逐一相等。
 * 这不是「凑一个能跑的口径」，而是这个内核下的<b>唯一正确</b>口径；
 * 换成「按最高阶级」会让混编队伍白拿高阶属性，换成「按最低阶级」会让高阶兵白练。
 *
 * <p><b>唯一的误差来自最后那一次除法取整</b>：加权平均要把 Σ(数量×属性) 除以总数量，
 * 而 UnitStats 存的是定点整数。误差上界是「每个兵种 0.5 个定点单位」，
 * 相对于 1e5 量级的属性值可以忽略，且用四舍五入而不是截断，误差不偏向任何一方。
 *
 * <p><b>缺席的兵种也要给属性</b>：{@code BattleInput} 的构造器要求四个 UnitType 齐全，
 * 缺一个就在求和时拿到 null —— 而 NullPointerException 在战斗内核里是最难排查的一类错误
 * （看不出是哪个兵种缺了）。缺席兵种的属性取该兵种的 T1 行做占位，数量为 0，
 * 所以它不参与任何求和；占位值只为了让类型检查通过。
 *
 * <p><b>本类不负责武将</b>：{@code HeroSnapshot} 需要「per-hero 属性 → 乘区」与技能快照两件事，
 * 那是 B06 与 B05 之间的另一条接缝，混进来会让「折算是否正确」这个本该单独验证的问题
 * 与武将口径纠缠在一起。调用方把 heroes 传进来，本类只负责兵种侧。
 */
@Component
public class BattleArmyFactory {

    private final ConfigRegistry configs;

    public BattleArmyFactory(ConfigRegistry configs) {
        this.configs = configs;
    }

    /**
     * 折算结果。
     *
     * @param counts unitType → 数量，<b>四个兵种齐全</b>（缺席的为 0），可直接喂给 {@code ArmySide}
     * @param stats  unitType → 属性，四个兵种齐全，可直接喂给 {@code BattleInput}
     */
    public record Folded(Map<UnitType, Long> counts, Map<UnitType, UnitStats> stats) {

        public Folded {
            counts = freeze(counts);
            stats = freeze(stats);
            for (UnitType type : UnitType.values()) {
                if (!counts.containsKey(type) || !stats.containsKey(type)) {
                    throw new IllegalArgumentException("折算结果缺少兵种 " + type
                            + "：BattleInput 要求四个兵种齐全，缺一个就会在内核求和时拿到 null");
                }
            }
        }

        public long totalUnits() {
            long sum = 0L;
            for (long count : counts.values()) {
                sum += count;
            }
            return sum;
        }

        private static <V> Map<UnitType, V> freeze(Map<UnitType, V> source) {
            if (source == null) {
                throw new IllegalArgumentException("折算结果不得为 null");
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    /**
     * 把队伍折成「每兵种一份属性」。
     *
     * @param unitsByUnitId unitId（含阶级）→ 数量。数量为 0 的条目会被忽略
     * @throws BizException 当某个 unitId 不在 unit 表里（客户端伪造了一个不存在的兵种）
     */
    public Folded fold(Map<String, Long> unitsByUnitId) {
        if (unitsByUnitId == null) {
            throw new IllegalArgumentException("unitsByUnitId 不得为 null");
        }
        // 每个兵种累加「数量 × 属性」的质量，最后一次性除以总数量得到加权平均。
        // 先算平均再乘回去会引入两次取整误差，只除一次则只有一次
        Map<UnitType, Long> totals = new EnumMap<>(UnitType.class);
        Map<UnitType, long[]> mass = new EnumMap<>(UnitType.class);   // [攻击, 防御, 生命, 负载, 对建筑]
        Map<UnitType, Long> slowest = new EnumMap<>(UnitType.class);

        List<String> unitIds = new ArrayList<>(unitsByUnitId.keySet());
        // 排序保证「哪个兵种先报错」是确定的：漏配两个兵种时，先报哪一个决定了排查的人先看到什么
        Collections.sort(unitIds);
        for (String unitId : unitIds) {
            Long count = unitsByUnitId.get(unitId);
            if (count == null || count <= 0L) {
                continue;
            }
            UnitCfg cfg = requireUnit(unitId);
            UnitType type = UnitType.valueOf(cfg.type().name());
            totals.merge(type, count, Long::sum);
            long[] m = mass.computeIfAbsent(type, k -> new long[5]);
            m[0] += count * FixedPoint.of(cfg.attack());
            m[1] += count * FixedPoint.of(cfg.defense());
            m[2] += count * FixedPoint.of(cfg.hp());
            m[3] += count * cfg.load();
            m[4] += count * cfg.vsBuildingBonus();
            // 速度取该兵种里最慢的阶级：一支混编队伍按最慢的走，
            // 与 MarchCalculator.teamSpeed 的「取最慢兵种」是同一条口径的两个层级
            slowest.merge(type, cfg.speed(), Math::min);
        }

        Map<UnitType, Long> counts = new EnumMap<>(UnitType.class);
        Map<UnitType, UnitStats> stats = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            long total = totals.getOrDefault(type, 0L);
            counts.put(type, total);
            if (total > 0L) {
                long[] m = mass.get(type);
                stats.put(type, new UnitStats(
                        divideHalfUp(m[0], total),
                        divideHalfUp(m[1], total),
                        divideHalfUp(m[2], total),
                        slowest.getOrDefault(type, 0L),
                        divideHalfUp(m[3], total),
                        divideHalfUp(m[4], total)));
            } else {
                stats.put(type, placeholder(type));
            }
        }
        return new Folded(counts, stats);
    }

    /**
     * 组装成战斗内核的一方。
     *
     * @param sideId            这一方的标识（战报与日志用）
     * @param folded            {@link #fold} 的结果
     * @param heroes            上阵武将快照，由调用方从 B06 的武将状态构造（本类不负责）
     * @param equipBonusFixed   装备套装对攻击乘区的贡献，来自 {@code HeroCalculator.TeamBonus.equipSetAtkFixed}。
     *                          <b>必须是独立乘区</b>：合进武将乘区就违反 B06 禁止项
     *                          「不要让武将加成污染其他乘区」
     * @param hospitalCapacity  医院容量，决定伤兵能救回多少（超出的部分死亡）
     * @param techBonus         乘区 B（B05 §1.3）。<b>由调用方算好传入</b>，本类不去查联盟账本 ——
     *                          那样会让「折算兵种属性」这一件事与社交状态纠缠，也就没法单独验证。
     *                          没有科技时传 {@link TechBonus#none()}，而不是传 1.0 之类的假值：
     *                          传假值会让以后没人分得清「科技生效了」与「一直有个常数在加」
     * @param orgBonus          乘区 G（国策）与乘区 H（城墙），来自
     *                          {@code NationPolicyBonuses.combatBonusFor}。同一条分工：
     *                          <b>由调用方算好传入</b>，本类不去查国家账本 ——
     *                          「这个玩家属于哪个国家」是社交域的事，折算兵种属性不该知道它。
     *                          没有国家或没有生效国策时传 {@link OrgBonus#none()}。
     */
    public ArmySide toSide(String sideId, Folded folded, List<HeroSnapshot> heroes,
                           long equipBonusFixed, TechBonus techBonus, long hospitalCapacity) {
        return toSide(sideId, folded, heroes, equipBonusFixed, techBonus, hospitalCapacity,
                OrgBonus.none());
    }

    /** 带组织侧加成（乘区 G / H）的七参形状。<b>玩家对玩家的战斗必须走它</b>，否则国策永远不生效。 */
    public ArmySide toSide(String sideId, Folded folded, List<HeroSnapshot> heroes,
                           long equipBonusFixed, TechBonus techBonus, long hospitalCapacity,
                           OrgBonus orgBonus) {
        if (folded == null) {
            throw new IllegalArgumentException("folded 不得为 null");
        }
        // 阵型恒为 STANDARD：B05 只交付了这一种（重步兵前排、骑兵中排、弓兵与攻城器后排）
        return new ArmySide(sideId, heroes == null ? List.of() : heroes, folded.counts(),
                techBonus == null ? TechBonus.none() : techBonus,
                equipBonusFixed, FormationType.STANDARD, hospitalCapacity,
                orgBonus == null ? OrgBonus.none() : orgBonus);
    }

    /**
     * 缺席兵种的占位属性：取该兵种阶级最低的那一行。
     *
     * <p>占位值不参与任何计算（数量为 0），但必须合法 —— {@code UnitStats} 要求攻击/防御/生命为正，
     * 所以不能塞 0 进去。
     */
    private UnitStats placeholder(UnitType type) {
        UnitCfg lowest = null;
        for (UnitCfg cfg : configs.all(UnitCfg.class)) {
            if (cfg.type().name().equals(type.name())
                    && (lowest == null || cfg.tier() < lowest.tier())) {
                lowest = cfg;
            }
        }
        if (lowest == null) {
            // unit 表少了整个兵种：这不是「配置写错一行」，而是战斗内核会直接算不出结果。
            // 必须当场炸，并且说清楚缺的是哪个兵种
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "unit 表里没有任何 " + type + " 兵种的行，无法为缺席兵种构造占位属性。"
                            + "BattleInput 要求四个兵种齐全");
        }
        return new UnitStats(FixedPoint.of(lowest.attack()), FixedPoint.of(lowest.defense()),
                FixedPoint.of(lowest.hp()), lowest.speed(), lowest.load(), lowest.vsBuildingBonus());
    }

    private UnitCfg requireUnit(String unitId) {
        try {
            return configs.get(UnitCfg.class, unitId);
        } catch (ConfigException e) {
            // 客户端可以随便编一个 unitId 发过来，所以这是入口校验而不是内部错误
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "兵种配置不存在: " + unitId);
        }
    }

    /**
     * 正数的四舍五入除法。
     *
     * <p>不用 {@code FixedPoint.div}：那个是「定点 ÷ 定点」，而这里是「质量 ÷ 数量」，
     * 两个操作数的量纲不同，套定点除法会把结果再缩小一万倍。
     *
     * <p>四舍五入而不是截断：截断会让折算后的属性系统性地偏低，
     * 于是混编队伍永远比「同数量的单一阶级」弱一点点 —— 那点差别不会被察觉，
     * 但它会让「加权平均是精确等价」这个论断变成假的。
     */
    private static long divideHalfUp(long numerator, long denominator) {
        if (denominator <= 0L) {
            throw new IllegalArgumentException("除数必须为正：" + denominator);
        }
        if (numerator < 0L) {
            throw new IllegalArgumentException("被除数不得为负：" + numerator);
        }
        return (numerator + denominator / 2L) / denominator;
    }

    /**
     * 兵种 id → 兵种类型（查 unit 表，不解析字符串）。
     *
     * <p>放在这里而不是各个 service 里：折叠（{@link #fold}）与展开损失（{@link #unfoldLosses}）
     * 都要用它，而「unitId 属于哪个兵种」这件事只能有一个答案 ——
     * 两处各写一份的话，其中一份迟早会改成按字符串前缀猜，
     * 而猜错的症状是「损失被摊到另一个兵种头上」，账面上仍然守恒，看不出来。
     */
    public UnitType unitTypeOf(String unitId) {
        return UnitType.valueOf(configs.get(UnitCfg.class, unitId).type().name());
    }

    /**
     * 把内核的 per-UnitType 战果摊回 per-unitId 的损失（{@link #fold} 的逆运算）。
     *
     * <p>两步：先按兵种算出损失总量，再用 {@link TierSplit} 按各阶级的现有数量比例摊下去。
     * <b>直接按兵种扣会让「一次出征把 T5 兵降成 T1」的 bug 换个地方复活</b> ——
     * 行军与存档都是按 unitId（含阶级）记兵的，内核只认四个兵种，
     * 中间的换算必须有且只有一份实现。
     *
     * <p>野怪与玩家城两条路径共用它：守方是 {@code ArmyState.troops()}、
     * 攻方是 {@code March.units()}，形状相同（unitId → 数量），所以同一个方法两边都能用。
     *
     * @param unitsByUnitId 参战前的兵力（按 unitId）
     * @param foldedCounts  折叠后的参战数量（按兵种），即 {@link Folded#counts()}
     * @param survivors     内核算出的幸存数（按兵种）
     * @return 每个 unitId 的损失量。Σ 恰好等于 Σ(foldedCounts - survivors)
     */
    public Map<String, Long> unfoldLosses(Map<String, Long> unitsByUnitId,
                                          Map<UnitType, Long> foldedCounts,
                                          Map<UnitType, Long> survivors) {
        Map<UnitType, Long> lossByType = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            long before = foldedCounts.getOrDefault(type, 0L);
            long after = survivors == null ? 0L : survivors.getOrDefault(type, 0L);
            if (after > before) {
                throw new IllegalStateException("幸存数超过参战数：兵种=" + type
                        + ", 参战=" + before + ", 幸存=" + after);
            }
            lossByType.put(type, before - after);
        }
        Map<String, Long> losses = new LinkedHashMap<>();
        for (UnitType type : UnitType.values()) {
            long typeLoss = lossByType.get(type);
            if (typeLoss <= 0L) {
                continue;
            }
            // 只把该兵种的各阶级交给 TierSplit：跨兵种分摊会变成「步兵的损失由骑兵承担」
            Map<String, Long> ofType = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : unitsByUnitId.entrySet()) {
                if (unitTypeOf(entry.getKey()) == type && entry.getValue() > 0L) {
                    ofType.put(entry.getKey(), entry.getValue());
                }
            }
            losses.putAll(TierSplit.splitProportionally(ofType, typeLoss));
        }
        return losses;
    }
}
