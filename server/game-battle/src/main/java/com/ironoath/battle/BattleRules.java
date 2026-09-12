package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：一场战斗所需的全部规则参数（由调用方从 contract/config 解析后传入）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架）。
 *
 * <p>内核<b>不读配置表</b>（game-battle 只能依赖 game-common，读不到 game-config），
 * 所以所有数值都从这里进来。好处是批量平衡验证可以直接构造不同参数跑对照实验，
 * 不必为每次实验改配置文件、更不必启动容器 —— 「平衡调不动，数值就永远调不好」（C00 公理四·五）。
 *
 * <p>构造期做了两条不变量校验，它们是结算正确性的前提：
 * <ul>
 *   <li>三排损失分摊之和必须恰好等于定点 1.0。少了损失凭空消失（兵越打越多），
 *       多了会把兵力算成负数</li>
 *   <li>死亡比例必须落在 [0, 1.0]。超出会让伤兵数变成负数，医院容量判断随之失效</li>
 * </ul>
 *
 * @param maxRounds            最大回合数。来源 global.BATTLE_MAX_ROUNDS
 * @param lanchesterK          防御软系数 K。来源 global.LANCHESTER_K。
 *        <p>这是「每回合损失多少」的<b>唯一</b>旋钮：势均力敌时减员系数 = 1/(1+K)。
 *        B00 给的默认值 1.0 意味着减员系数恰为 0.5，即每回合损失一半兵力 ——
 *        8 回合的战斗第 2 回合就打完，回合数、技能持续回合数、平局判定全部失去意义。
 *        CLI 跑 300 局实测确认了这个后果：12 组兵种对局里 10 组是 100%/0% 的碾压。
 *        校准到 7.0 后每回合损失约 12.5%，8 回合约损失 66%，战斗会打到最后一两回合才分胜负。
 *        <p>调 K 而不是额外加一个「每回合损耗标度」乘法项，是因为标度项会把单回合损失上限钉死，
 *        导致 400:1 的压倒性兵力也永远打不干净一小股守军（实测 8 回合后守军仍剩约 10%）；
 *        调 K 则两端都保住了：势均力敌时胶着，压倒性兵力时减员系数仍逼近 1.0，一回合即可全歼。
 * @param hpDefenseWeightFixed
 *        单位生命折算进有效防御的权重。来源 global.HP_DEFENSE_WEIGHT。
 *        <p>B00 原文的减员公式里 hp 不出现在任何一项，导致 unit 表的 hp 字段是死属性 ——
 *        而 hp 是玩家在兵种卡片上直接看得见的数字，「升阶级血量涨了但打起来没区别」会被当成数值造假。
 *        本权重让有效防御 = Σ(数量 × (防御 + 生命 × 权重) × 加成)，是 B00 原式的严格超集：
 *        权重设为 0 即精确还原原式，调错可随时回退。
 * @param jitterMinFixed       损失随机浮动下界（定点 0.95）
 * @param jitterMaxFixed       损失随机浮动上界（定点 1.05）
 * @param rowFrontFixed        前排损失分摊。来源 global.COUNTER_ADVANCE_FRONT
 * @param rowMidFixed          中排损失分摊。来源 global.COUNTER_ADVANCE_MID
 * @param rowBackFixed         后排损失分摊。来源 global.COUNTER_ADVANCE_BACK
 * @param counterBonusFixed    克制加成。来源 global.COUNTER_BONUS
 * @param counterPenaltyFixed  被克减益。来源 global.COUNTER_PENALTY
 * @param drawGapRatioFixed    平局判定的剩余兵力差距阈值。来源 global.BATTLE_DRAW_GAP_RATIO
 * @param pveDeadRatioFixed    PVE 死亡比例。来源 global.WOUND_RATIO_PVE_DEAD
 * @param pvpAttackerDeadRatioFixed PVP 攻方死亡比例。来源 global.WOUND_RATIO_PVP_ATTACKER_DEAD
 * @param pvpDefenderDeadRatioFixed PVP 守方死亡比例。来源 global.WOUND_RATIO_PVP_DEFENDER_DEAD
 * @param counterMatrix        克制矩阵：attacker → 它克制的 defender 集合。来源 unit_counter 表
 * @param terrainAttackBonus   地形对攻击的加成（乘区 E）
 * @param terrainDefenseBonus  地形对防御的加成
 */
public record BattleRules(
        int maxRounds,
        long lanchesterK,
        long hpDefenseWeightFixed,
        long jitterMinFixed,
        long jitterMaxFixed,
        long rowFrontFixed,
        long rowMidFixed,
        long rowBackFixed,
        long counterBonusFixed,
        long counterPenaltyFixed,
        long drawGapRatioFixed,
        long pveDeadRatioFixed,
        long pvpAttackerDeadRatioFixed,
        long pvpDefenderDeadRatioFixed,
        Map<UnitType, Set<UnitType>> counterMatrix,
        Map<TerrainType, Long> terrainAttackBonus,
        Map<TerrainType, Long> terrainDefenseBonus) {

    public BattleRules {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 必须 >= 1，实际=" + maxRounds);
        }
        requirePositive(lanchesterK, "lanchesterK");
        requireNonNegative(hpDefenseWeightFixed, "hpDefenseWeightFixed");
        requirePositive(jitterMinFixed, "jitterMinFixed");
        requirePositive(jitterMaxFixed, "jitterMaxFixed");
        if (jitterMaxFixed < jitterMinFixed) {
            throw new IllegalArgumentException("损失浮动区间非法：min=" + jitterMinFixed
                    + " > max=" + jitterMaxFixed);
        }
        requireNonNegative(rowFrontFixed, "rowFrontFixed");
        requireNonNegative(rowMidFixed, "rowMidFixed");
        requireNonNegative(rowBackFixed, "rowBackFixed");
        requireNonNegative(counterBonusFixed, "counterBonusFixed");
        requireNonNegative(counterPenaltyFixed, "counterPenaltyFixed");
        requireNonNegative(drawGapRatioFixed, "drawGapRatioFixed");
        requireRatio(pveDeadRatioFixed, "pveDeadRatioFixed");
        requireRatio(pvpAttackerDeadRatioFixed, "pvpAttackerDeadRatioFixed");
        requireRatio(pvpDefenderDeadRatioFixed, "pvpDefenderDeadRatioFixed");
        if (counterMatrix == null) {
            throw new IllegalArgumentException("counterMatrix 不得为 null（无克制关系请传空 Map）");
        }

        // 三排分摊之和必须恰好等于定点 1.0
        long rowSum = rowFrontFixed + rowMidFixed + rowBackFixed;
        if (rowSum != FixedPoint.SCALE) {
            throw new IllegalArgumentException("三排损失分摊之和必须等于定点 1.0（10000），实际="
                    + rowSum + "（前=" + rowFrontFixed + " 中=" + rowMidFixed + " 后=" + rowBackFixed + "）");
        }

        Map<UnitType, Set<UnitType>> matrix = new EnumMap<>(UnitType.class);
        for (Map.Entry<UnitType, Set<UnitType>> e : counterMatrix.entrySet()) {
            matrix.put(e.getKey(), e.getValue().isEmpty()
                    ? EnumSet.noneOf(UnitType.class)
                    : EnumSet.copyOf(e.getValue()));
        }
        counterMatrix = Collections.unmodifiableMap(matrix);
        terrainAttackBonus = copyTerrain(terrainAttackBonus, "terrainAttackBonus");
        terrainDefenseBonus = copyTerrain(terrainDefenseBonus, "terrainDefenseBonus");
    }

    private static Map<TerrainType, Long> copyTerrain(Map<TerrainType, Long> source, String field) {
        Map<TerrainType, Long> copy = new EnumMap<>(TerrainType.class);
        for (TerrainType t : TerrainType.values()) {
            long value = (source == null) ? 0L : source.getOrDefault(t, 0L);
            if (value < 0L) {
                throw new IllegalArgumentException(field + " 不得为负：" + t + "=" + value);
            }
            copy.put(t, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " 必须为正定点数，实际=" + value);
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负，实际=" + value);
        }
    }

    private static void requireRatio(long value, String field) {
        if (value < 0L || value > FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + value);
        }
    }

    /**
     * 从配置侧的参数端口装配（{@link com.ironoath.common.config.BattleParamsSource}）。
     *
     * <p><b>这是「配置表 → 战斗内核」唯一的映射处</b>。线上的 game-web 与调数值用的
     * balance-sim CLI 都走这里，数值本身由 game-config 的 {@code BattleParams} 提供 ——
     * 于是「调好的数值」与「线上跑的数值」在结构上不可能是两套。
     * 此前 balance-sim 里有一份自己读表的 resolver，接战斗时若在 game-web 再写一份，
     * 两边就会慢慢分叉，而表现是「CLI 的胜率矩阵很漂亮、线上完全不是那个手感」，两边都不报错。
     *
     * <p>兵种名到 {@link UnitType} 的翻译在这里做一次，未知名字立刻报错：
     * 静默跳过一个不认识的兵种名，等于让那个兵种永远不触发克制 ——
     * 不报错，只在胜率矩阵上看起来偏弱，是极难定位的一类问题。
     *
     * <p>地形加成传空 Map：地形数值表还没落地（B07 只交付了地块类型，没有加成表）。
     * 表落地后应当在 {@code BattleParamsSource} 上加两个方法，而不是在调用方各填一份。
     */
    public static BattleRules from(com.ironoath.common.config.BattleParamsSource params) {
        if (params == null) {
            throw new IllegalArgumentException("params 不得为 null");
        }
        Map<UnitType, Set<UnitType>> matrix = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            matrix.put(type, EnumSet.noneOf(UnitType.class));
        }
        params.counterMatrix().forEach((attackerName, defenderNames) -> {
            UnitType attacker = unitTypeOf(attackerName);
            for (String defenderName : defenderNames) {
                matrix.get(attacker).add(unitTypeOf(defenderName));
            }
        });
        return new BattleRules(
                params.maxRounds(),
                params.lanchesterK(),
                params.hpDefenseWeightFixed(),
                params.jitterMinFixed(),
                params.jitterMaxFixed(),
                params.rowFrontFixed(),
                params.rowMidFixed(),
                params.rowBackFixed(),
                params.counterBonusFixed(),
                params.counterPenaltyFixed(),
                params.drawGapRatioFixed(),
                params.pveDeadRatioFixed(),
                params.pvpAttackerDeadRatioFixed(),
                params.pvpDefenderDeadRatioFixed(),
                matrix,
                Map.of(),
                Map.of());
    }

    private static UnitType unitTypeOf(String name) {
        try {
            return UnitType.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("克制矩阵里的兵种名 " + name
                    + " 不是内核认识的 UnitType（合法取值见 UnitType 枚举）。"
                    + "静默跳过会让这个兵种永远不触发克制，而那是不会报错的");
        }
    }

    /** attacker 是否克制 defender。 */
    public boolean counters(UnitType attacker, UnitType defender) {
        Set<UnitType> set = counterMatrix.get(attacker);
        return set != null && set.contains(defender);
    }

    public long terrainAttack(TerrainType terrain) {
        return terrainAttackBonus.getOrDefault(terrain, 0L);
    }

    public long terrainDefense(TerrainType terrain) {
        return terrainDefenseBonus.getOrDefault(terrain, 0L);
    }

    /** 按战斗类型与攻守方取死亡比例。伤兵比例 = 1 - 死亡比例。 */
    public long deadRatio(BattleType type, boolean isAttacker) {
        if (type == BattleType.PVE) {
            return pveDeadRatioFixed;
        }
        return isAttacker ? pvpAttackerDeadRatioFixed : pvpDefenderDeadRatioFixed;
    }
}
