package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：战斗结果 —— 客户端播放战报所需的全部内容，自包含、不可变。
 * 依赖：无（纯数据）。
 *
 * <p>客户端<b>只播放不重算</b>：拿着本对象就能完整重现一场战斗，
 * 不需要 seed、不需要配置表、不需要 simulate()（跨语言一致性策略第 1 条）。
 * seed 仍然保留在结果里，但它的用途是<b>服务端</b>自查、问题复现与反外挂校验，
 * 不是给客户端复算用的。
 *
 * @param winner          胜方
 * @param rounds          逐回合快照，客户端按序播放
 * @param totalRounds     实际进行的回合数
 * @param atkSurvivors    攻方各兵种存活数量
 * @param defSurvivors    守方各兵种存活数量
 * @param atkDead         攻方死亡兵数（不可恢复）
 * @param atkWounded      攻方伤兵数（进医院，可治疗）
 * @param atkOverflowDead 攻方因医院溢出而额外死亡的数量。B00：医院溢出部分直接死亡
 * @param defDead         守方死亡兵数
 * @param defWounded      守方伤兵数
 * @param defOverflowDead 守方医院溢出死亡数
 * @param loot            掠夺所得，键为资源 id。未破墙或 PVE 时为空
 * @param lootCapacity    攻方剩余负载（掠夺上限）。B00：掠夺 = min(对方非保护资源, 我方剩余负载)
 * @param seed            本场战斗种子，服务端复算与反外挂用
 * @param attritionLog    每回合的减员系数（定点），排查数值异常时直接看这条曲线
 */
public record BattleResult(
        Winner winner,
        List<RoundSnapshot> rounds,
        int totalRounds,
        Map<UnitType, Long> atkSurvivors,
        Map<UnitType, Long> defSurvivors,
        long atkDead,
        long atkWounded,
        long atkOverflowDead,
        long defDead,
        long defWounded,
        long defOverflowDead,
        Map<String, Long> loot,
        long lootCapacity,
        long seed,
        List<Long> attritionLog) {

    public BattleResult {
        if (winner == null) {
            throw new IllegalArgumentException("winner 不得为 null");
        }
        rounds = List.copyOf(rounds);
        attritionLog = List.copyOf(attritionLog);
        atkSurvivors = freezeUnits(atkSurvivors);
        defSurvivors = freezeUnits(defSurvivors);
        loot = freezeLoot(loot);
        if (totalRounds < 0 || totalRounds != rounds.size()) {
            throw new IllegalArgumentException("totalRounds 必须等于快照数量：totalRounds="
                    + totalRounds + ", rounds=" + rounds.size());
        }
        requireNonNegative(atkDead, "atkDead");
        requireNonNegative(atkWounded, "atkWounded");
        requireNonNegative(atkOverflowDead, "atkOverflowDead");
        requireNonNegative(defDead, "defDead");
        requireNonNegative(defWounded, "defWounded");
        requireNonNegative(defOverflowDead, "defOverflowDead");
        requireNonNegative(lootCapacity, "lootCapacity");
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负，实际=" + value);
        }
    }

    private static Map<UnitType, Long> freezeUnits(Map<UnitType, Long> source) {
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            copy.put(type, source == null ? 0L : source.getOrDefault(type, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Long> freezeLoot(Map<String, Long> source) {
        Map<String, Long> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((k, v) -> {
                if (v == null || v < 0L) {
                    throw new IllegalArgumentException("loot 中资源 " + k + " 的数量非法：" + v);
                }
                copy.put(k, v);
            });
        }
        return Collections.unmodifiableMap(new java.util.TreeMap<>(copy));
    }

    /** 攻方总损失（死亡 + 伤兵，含溢出死亡）。 */
    public long atkTotalLoss() {
        return atkDead + atkWounded;
    }

    public long defTotalLoss() {
        return defDead + defWounded;
    }

    /** 掠夺到的资源总量。 */
    public long lootTotal() {
        long total = 0L;
        for (long v : loot.values()) {
            total += v;
        }
        return total;
    }
}
