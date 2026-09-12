package com.ironoath.config;

import com.ironoath.common.config.BattleParamsSource;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.config.cfg.UnitCounterCfg;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 职责：从配置表装配战斗规则参数 —— 全部数值的<b>唯一</b>一个家。
 * 依赖：本包的 ConfigRegistry 与 cfg 记录、game-common 的 {@link BattleParamsSource} 端口。
 *
 * <p><b>为什么放在 game-config 而不是 game-web 或 balance-sim</b>：
 * 战斗参数有两个消费方 —— 线上的 game-web 与调数值用的 balance-sim CLI。
 * 放在任何一方，另一方都得再抄一份，而抄的那份会与真源慢慢分叉：
 * 表现是「CLI 跑出来的胜率矩阵很漂亮，线上完全不是那个手感」，
 * 且两边都不会报错，只有玩家会觉得战斗不对劲。
 * game-config 是两者都已经依赖的层，所以它是唯一能同时被两边用的位置。
 *
 * <p><b>本类只产出数值，不产出 {@code BattleRules}</b>：BattleRules 在 game-battle 里，
 * 而配置层依赖战斗内核是反的方向。字符串兵种名到 {@code UnitType} 的翻译由
 * {@code BattleRules.from} 做一次（见 {@link BattleParamsSource} 的类注释）。
 *
 * @param maxRounds 及其余各项 与 {@link BattleParamsSource} 一一对应，来源见该接口的逐项注释
 * @param counterMatrix 兵种名 → 被它克制的兵种名。<b>四个兵种都在键里</b>，无克制关系的给空集合
 */
public record BattleParams(
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
        Map<String, Set<String>> counterMatrix) implements BattleParamsSource {

    public BattleParams {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 必须 >= 1，实际=" + maxRounds);
        }
        if (counterMatrix == null) {
            throw new IllegalArgumentException("counterMatrix 不得为 null（无克制关系请传空 Map）");
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        counterMatrix.forEach((attacker, defenders) -> {
            if (attacker == null || attacker.isBlank()) {
                throw new IllegalArgumentException("克制矩阵的兵种名不得为空");
            }
            frozen.put(attacker, Collections.unmodifiableSet(
                    new LinkedHashSet<>(defenders == null ? Set.of() : defenders)));
        });
        counterMatrix = Collections.unmodifiableMap(frozen);
    }

    /**
     * 从 global 表与 unit_counter 表装配。
     *
     * <p>每次调用都重新读表，<b>不缓存</b>：配置表支持热更（{@code ConfigRegistry.reload}），
     * 缓存一份参数会让热更在战斗这条路径上失效 —— 而战斗参数恰恰是最需要能热更的
     * （线上发现某个数值不对，等一次发版再改是不可接受的）。
     */
    public static BattleParams of(ConfigRegistry configs) {
        if (configs == null) {
            throw new IllegalArgumentException("configs 不得为 null");
        }
        return new BattleParams(
                (int) configs.longParam("BATTLE_MAX_ROUNDS"),
                configs.fixedParam("LANCHESTER_K"),
                configs.fixedParam("HP_DEFENSE_WEIGHT"),
                configs.fixedParam("BATTLE_JITTER_MIN"),
                configs.fixedParam("BATTLE_JITTER_MAX"),
                configs.fixedParam("COUNTER_ADVANCE_FRONT"),
                configs.fixedParam("COUNTER_ADVANCE_MID"),
                configs.fixedParam("COUNTER_ADVANCE_BACK"),
                configs.fixedParam("COUNTER_BONUS"),
                configs.fixedParam("COUNTER_PENALTY"),
                configs.fixedParam("BATTLE_DRAW_GAP_RATIO"),
                configs.fixedParam("WOUND_RATIO_PVE_DEAD"),
                configs.fixedParam("WOUND_RATIO_PVP_ATTACKER_DEAD"),
                configs.fixedParam("WOUND_RATIO_PVP_DEFENDER_DEAD"),
                counterMatrix(configs));
    }

    /**
     * 克制矩阵：attacker → 它克制的 defender 集合。
     *
     * <p>只收「兵种 → 兵种」的关系；unit_counter 表里 SIEGE→WALL / SIEGE→TRAP 的目标是建筑，
     * 不是兵种，它们由 unit 表的 vsBuildingBonus 字段表达（见 BattleSimulator）。
     *
     * <p>键集合取自 <b>unit 表的兵种枚举</b>而不是硬编码四个名字：
     * 硬编码就是给「有哪四个兵种」这件事开了第二个家，将来加第五个兵种时会漏。
     */
    private static Map<String, Set<String>> counterMatrix(ConfigRegistry configs) {
        Map<String, Set<String>> matrix = new LinkedHashMap<>();
        for (UnitCfg.Type type : UnitCfg.Type.values()) {
            matrix.put(type.name(), new LinkedHashSet<>());
        }
        for (UnitCounterCfg row : configs.all(UnitCounterCfg.class)) {
            String attacker = row.attacker().name();
            Set<String> defenders = matrix.get(attacker);
            if (defenders == null) {
                // unit_counter 的 attacker 枚举比 unit 表的兵种枚举多（含建筑侧），
                // 走到这里说明克制关系的攻击方不是兵种，跳过即可
                continue;
            }
            if (!matrix.containsKey(row.defender().name())) {
                // 防守方是 WALL / TRAP 这类建筑目标：不进兵种克制矩阵
                continue;
            }
            if (row.bonusFixed() != null || row.penaltyFixed() != null) {
                // 逐对覆盖目前无处可去：BattleRules 只有全局的 counterBonus/counterPenalty 两个槽位。
                // 与其让填了值的行静默失效（策划调了一对克制关系、发现毫无变化，
                // 然后开始怀疑整个克制系统），不如当场报错说清楚缺的是内核槽位
                throw new ConfigException("unit_counter 行 " + row.id() + " 填了 bonusFixed/penaltyFixed，"
                        + "但战斗内核只有全局的 COUNTER_BONUS / COUNTER_PENALTY 两个槽位，"
                        + "逐对覆盖会被静默忽略。要启用它必须先给 BattleRules 加逐对槽位（内核改动），"
                        + "在那之前请把这两列留空");
            }
            defenders.add(row.defender().name());
        }
        return matrix;
    }
}
