package com.ironoath.battle;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：一方军队的战前快照（不可变）。
 * 依赖：无（纯数据）。
 *
 * <p><b>units 用 EnumMap 而不是 HashMap</b>（B05 §1.7 确定性保障）：
 * HashMap 的迭代顺序依赖 hash 与桶容量，同一份数据在不同 JVM 上可能给出不同顺序。
 * 求和本身满足交换律所以看不出问题，但损失分摊要决定「先扣哪个兵种」，
 * 顺序不同结果就不同 —— 同 seed 的战报将无法复现，而这正是本项目最不能接受的事。
 * EnumMap 严格按枚举声明顺序迭代，构造时就把顺序钉死。
 *
 * @param sideId           这一方的标识（玩家 id 或野怪 id），仅用于战报与日志
 * @param heroes           上阵武将。顺序影响技能触发（B05 §1.4），用 {@link #heroesBySlot()} 取有序视图
 * @param units            各兵种数量。允许某兵种为 0，不允许负数
 * @param techBonus        科技加成（乘区 B）
 * @param equipBonusFixed  装备加成（乘区 C）。装备系统交付前恒为 0
 * @param formation        阵型，决定兵种落在哪一排（V1 只有 STANDARD）
 * @param hospitalCapacity 医院容量。伤兵超出容量的部分直接死亡（B00 伤兵规则）
 * @param orgBonus         组织侧加成（乘区 G 国策 / 乘区 H 城墙）。无国家、无城墙时传 {@link OrgBonus#none()}
 */
public record ArmySide(
        String sideId,
        List<HeroSnapshot> heroes,
        Map<UnitType, Long> units,
        TechBonus techBonus,
        long equipBonusFixed,
        FormationType formation,
        long hospitalCapacity,
        OrgBonus orgBonus) {

    /**
     * 七参构造器（组织侧加成恒为 0）。
     *
     * <p>打野、PVE、平衡 CLI 与全部「无国策」的内核测试都走它 ——
     * 这些场景本来就不该有国策加成，让它们数第八个参数只会增加笔误面。
     * <b>玩家城 PVP 那条路径必须走八参版本</b>，否则国策与城墙永远不生效；
     * {@code BattleArmyFactory} 是它唯一的生产方。
     */
    public ArmySide(String sideId, List<HeroSnapshot> heroes, Map<UnitType, Long> units,
                    TechBonus techBonus, long equipBonusFixed, FormationType formation,
                    long hospitalCapacity) {
        this(sideId, heroes, units, techBonus, equipBonusFixed, formation, hospitalCapacity,
                OrgBonus.none());
    }

    public ArmySide {
        if (sideId == null || sideId.isBlank()) {
            throw new IllegalArgumentException("sideId 不得为空");
        }
        if (techBonus == null) {
            throw new IllegalArgumentException("techBonus 不得为 null，sideId=" + sideId);
        }
        if (formation == null) {
            throw new IllegalArgumentException("formation 不得为 null，sideId=" + sideId);
        }
        if (equipBonusFixed < 0L) {
            throw new IllegalArgumentException("equipBonusFixed 不得为负，sideId=" + sideId);
        }
        if (hospitalCapacity < 0L) {
            throw new IllegalArgumentException("hospitalCapacity 不得为负，sideId=" + sideId);
        }
        if (orgBonus == null) {
            throw new IllegalArgumentException("orgBonus 不得为 null，无加成请用 OrgBonus.none()，sideId=" + sideId);
        }
        heroes = List.copyOf(heroes);
        units = toEnumMap(units, sideId);
        validateSlots(heroes, sideId);
    }

    /** 复制成 EnumMap，顺带把四个兵种补全（缺省的按 0 处理）并校验非负。 */
    private static Map<UnitType, Long> toEnumMap(Map<UnitType, Long> source, String sideId) {
        if (source == null) {
            throw new IllegalArgumentException("units 不得为 null，sideId=" + sideId);
        }
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            long count = source.getOrDefault(type, 0L);
            if (count < 0L) {
                throw new IllegalArgumentException(
                        "兵种数量不得为负，sideId=" + sideId + ", " + type + "=" + count);
            }
            copy.put(type, count);
        }
        return copy;
    }

    /** 站位不得重复：两个武将同 slot 会让技能触发顺序不确定。 */
    private static void validateSlots(List<HeroSnapshot> heroes, String sideId) {
        for (int i = 0; i < heroes.size(); i++) {
            for (int j = i + 1; j < heroes.size(); j++) {
                if (heroes.get(i).slot() == heroes.get(j).slot()) {
                    throw new IllegalArgumentException("武将站位重复，sideId=" + sideId
                            + ", slot=" + heroes.get(i).slot()
                            + "（" + heroes.get(i).heroId() + " 与 " + heroes.get(j).heroId() + "）");
                }
            }
        }
    }

    /** 按 slot 升序返回武将（技能触发顺序）。 */
    public List<HeroSnapshot> heroesBySlot() {
        return heroes.stream()
                .sorted((a, b) -> Integer.compare(a.slot(), b.slot()))
                .toList();
    }

    /** 总兵数。按枚举声明顺序累加，结果可复现。 */
    public long totalUnits() {
        return totalOf(units);
    }

    /** 对一份兵力表求总兵数，供内核在逐回合结算中复用。 */
    public static long totalOf(Map<UnitType, Long> counts) {
        long total = 0L;
        for (UnitType type : UnitType.values()) {
            total += counts.getOrDefault(type, 0L);
        }
        return total;
    }
}
