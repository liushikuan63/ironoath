package com.ironoath.battle.sim;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.BattleRules;
import com.ironoath.battle.FormationType;
import com.ironoath.battle.OrgBonus;
import com.ironoath.battle.TechBonus;
import com.ironoath.battle.UnitStats;
import com.ironoath.battle.UnitType;
import com.ironoath.config.BattleParams;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.common.num.FixedPoint;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把配置表翻译成战斗内核需要的参数对象（BattleRules / UnitStats / ArmySide）。
 * 依赖：game-config、game-battle。
 *
 * <p><b>这一层是「配置驱动」与「内核纯净」之间的唯一桥</b>：
 * game-battle 按 B00 分层规则只能依赖 game-common，读不到配置表；
 * 所以所有数值都必须由外层解析好再传进去。本类就是那个解析器。
 *
 * <p><b>规则参数不再由本类读表</b>：{@code BattleParams.of(configs)} 在 game-config 里，
 * {@code BattleRules.from} 在 game-battle 里，本类只是把两者接起来。
 * 原因是战斗参数有两个消费方（线上的 game-web 与本 CLI），
 * 任何一方自己读表都会让另一方抄一份，而抄的那份会与真源慢慢分叉 ——
 * 表现是「CLI 的胜率矩阵很漂亮、线上完全不是那个手感」，两边都不报错。
 *
 * <p>本类保留的是<b>只有平衡验证才需要</b>的部分：按阶级取裸属性、构造无武将无科技的对照军队、
 * 找表里的最高阶级。这些不该进 game-web，因为线上永远带着武将与装备，
 * 而平衡矩阵要的恰恰是把它们剥掉之后的兵种本体强度。
 */
public final class BattleParamsResolver {

    private final ConfigRegistry configs;
    private final Map<UnitType, UnitStats> statsByTier = new EnumMap<>(UnitType.class);

    public BattleParamsResolver(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 规则参数。数值来自 game-config 的 BattleParams，映射由 game-battle 的 BattleRules.from 完成。 */
    public BattleRules rules() {
        return BattleRules.from(BattleParams.of(configs));
    }

    /**
     * 取某阶级的四兵种属性。
     *
     * <p><b>单位换算</b>（踩过一次坑，务必看清）：
     * <ul>
     *   <li>{@code attack / defense / hp} 在 unit.json 里声明为 LONG_POS，是<b>普通整数</b>（8、12、60），
     *       而内核要求定点数，必须用 {@code FixedPoint.of()} 放大 10000 倍</li>
     *   <li>{@code speed / load} 是整数计数，不参与定点运算，原样传入</li>
     *   <li>{@code vsBuildingBonus} 声明为 DECIMAL，已由 FixedPointDeserializer 转成定点，<b>不要再转一次</b></li>
     * </ul>
     * 漏掉第一条不报错、也不改变胜负关系（攻防同比缩放时减员系数不变），
     * 但战报里的绝对数值会小 10000 倍，与策划对表时完全对不上 —— 属于最难发现的一类 bug。
     */
    public Map<UnitType, UnitStats> unitStats(int tier) {
        Map<UnitType, UnitStats> stats = new EnumMap<>(UnitType.class);
        for (UnitCfg row : configs.all(UnitCfg.class)) {
            if (row.tier() != tier) {
                continue;
            }
            stats.put(UnitType.valueOf(row.type().name()), new UnitStats(
                    FixedPoint.of(row.attack()),
                    FixedPoint.of(row.defense()),
                    FixedPoint.of(row.hp()),
                    row.speed(),
                    row.load(),
                    row.vsBuildingBonus()));
        }
        for (UnitType type : UnitType.values()) {
            if (!stats.containsKey(type)) {
                throw new IllegalStateException("unit 表缺少兵种 " + type + " 的 T" + tier + " 行");
            }
        }
        return stats;
    }

    /** 构造一方军队。无武将、无科技、无装备 —— 平衡验证要的是裸兵种对比。 */
    public ArmySide bareArmy(String sideId, Map<UnitType, Long> units, long hospitalCapacity) {
        return new ArmySide(sideId, List.of(), units, TechBonus.none(), 0L,
                FormationType.STANDARD, hospitalCapacity);
    }

    /**
     * 带组织侧攻击加成的裸军队（#19 集结曲线用）。
     *
     * <p><b>走 {@link OrgBonus}（乘区 G）而不是在工具里另造一个加成位</b>：
     * 集结是攻方的有效攻击加成，而乘区 G 就是那一位的正式通路（A 格交付）。
     * 工具里另开一个加成位，等于平衡读数与实战结算走两套算式 ——
     * 那样量出来的幅度拿去上线就会对不上。
     *
     * <p>该加成**按兵种给**（与 A 格的形状一致），不是压成一个标量：
     * 一个标量会顺手给到编成里所有兵种，而集结现实中加的是带队那支兵。
     */
    public ArmySide bareArmy(String sideId, Map<UnitType, Long> units, long hospitalCapacity,
                              long attackBonusFixed) {
        if (attackBonusFixed == 0L) {
            return bareArmy(sideId, units, hospitalCapacity);
        }
        Map<UnitType, Long> byUnit = new EnumMap<>(UnitType.class);
        for (Map.Entry<UnitType, Long> e : units.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0L) {
                byUnit.put(e.getKey(), attackBonusFixed);
            }
        }
        return new ArmySide(sideId, List.of(), units, TechBonus.none(), 0L,
                FormationType.STANDARD, hospitalCapacity, new OrgBonus(byUnit, 0L, 0L));
    }

    /**
     * 带城墙防御加成的裸军队（乘区 H 的量具）。
     *
     * <p>与上面那个四参形状的关系是**相加**而不是替代：集结加的是攻方有效攻击（乘区 G 的
     * 攻击侧），城墙加的是守方防御（乘区 H），两者落在 {@link OrgBonus} 的不同字段上，
     * 所以一个五参形状装得下两种来源 —— 而城墙那一位**不允许为负**（破墙是归零不是取负）。
     */
    public ArmySide bareArmy(String sideId, Map<UnitType, Long> units, long hospitalCapacity,
                              long attackBonusFixed, long wallDefenseFixed) {
        if (wallDefenseFixed <= 0L) {
            return bareArmy(sideId, units, hospitalCapacity, attackBonusFixed);
        }
        return new ArmySide(sideId, List.of(), units, TechBonus.none(), 0L,
                FormationType.STANDARD, hospitalCapacity,
                new OrgBonus(Map.of(), 0L, wallDefenseFixed));
    }

    /** 单一兵种的军队，用于胜率矩阵。 */
    public ArmySide singleTypeArmy(String sideId, UnitType type, long count, long hospitalCapacity) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        for (UnitType t : UnitType.values()) {
            units.put(t, t == type ? count : 0L);
        }
        return bareArmy(sideId, units, hospitalCapacity);
    }

    /** 表中已有的最高兵种阶级。 */
    public int maxTier() {
        int max = 1;
        for (UnitCfg row : configs.all(UnitCfg.class)) {
            max = Math.max(max, (int) row.tier());
        }
        return max;
    }
}
