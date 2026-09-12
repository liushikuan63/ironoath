package com.ironoath.battle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：战斗输入 —— simulate() 的唯一入参，战报只需存它加 seed 即可 100% 复算（铁律 4）。
 * 依赖：无（纯数据）。
 *
 * <p><b>对 B05 文档契约的一处有意偏离</b>：文档给的是单个 {@code BattleModifier modifier}，
 * 这里改成攻守各一份。原因是复仇加成(+15%)只对攻方成立、哀兵加成(+10%)只对防守集结成立，
 * 共用一份就必须在内核里判断「这个加成该给谁」，那等于把 B08 战力圈层的规则渗进战斗内核。
 * 分成两份后内核只做乘法，谁该拿到哪个加成由调用方（B08 的战力校验中间件）决定。
 *
 * @param attacker        攻方
 * @param defender        守方
 * @param terrain         地形，决定乘区 E
 * @param seed            随机种子。战报只存 seed + 本对象即可复算（铁律 4）
 * @param battleType      战斗类型，决定伤兵/死亡比例
 * @param modifierAttacker 攻方的战力圈层加成（乘区 F）
 * @param modifierDefender 守方的战力圈层加成（乘区 F）
 * @param unitStats       兵种属性表，由调用方从 unit.json 按阶级解析后传入
 * @param rules           全部规则参数，由调用方从 global.json / unit_counter.json 解析后传入
 * @param defenderStore   守方资源（用于掠夺结算）；PVE 或不可掠夺时传 null
 * @param defenderMechanic 守方的 BOSS 机制（B09 §二）。绝大多数战斗是 {@link BossMechanic#none()}
 */
public record BattleInput(
        ArmySide attacker,
        ArmySide defender,
        TerrainType terrain,
        long seed,
        BattleType battleType,
        BattleModifier modifierAttacker,
        BattleModifier modifierDefender,
        Map<UnitType, UnitStats> attackerUnitStats,
        Map<UnitType, UnitStats> defenderUnitStats,
        BattleRules rules,
        DefenderStore defenderStore,
        BossMechanic defenderMechanic) {

    /**
     * 攻守共用一份兵种属性表的便捷构造器（无 BOSS 机制）。
     *
     * <p>保留它是为了让「守方属性表」这个后加的概念不去搅动既有调用点：
     * 野怪、平衡矩阵 CLI 与全部内核测试都只需要一份表，
     * 逐个改一遍只是机械修改，而机械修改正是引入笔误的地方。
     *
     * <p><b>但共用是一份近似，PVP 不许用它</b>：内核在计算双方有效攻击与有效防御时
     * 各读自己那一份表，共用等于把守方的兵种阶级属性替换成攻方的加权平均值 ——
     * 带 T4 兵的守方会被按攻方的平均步兵属性评估，于是攻方越强守方也显得越强。
     * 打野时这个误差可以接受（野怪是同兵种同阶级，且难度曲线是按它校准的），
     * 打人时不行，所以玩家城那条路径走 {@code attackerUnitStats + defenderUnitStats} 的构造器。
     *
     * <p><b>机制挂在 input 而不是 rules 上</b>：rules 是「这一场战斗适用的全局规则」，
     * 由配置表装配、对所有战斗相同；机制是「这个守方是谁」的属性，逐场不同。
     * 混进 rules 会让「同一份配置跑出不同结果」变成可能，
     * 而复算战报时（铁律 4）就再也说不清当时用的是哪一套。
     */
    public BattleInput(ArmySide attacker, ArmySide defender, TerrainType terrain, long seed,
                       BattleType battleType, BattleModifier modifierAttacker,
                       BattleModifier modifierDefender, Map<UnitType, UnitStats> sharedUnitStats,
                       BattleRules rules, DefenderStore defenderStore) {
        this(attacker, defender, terrain, seed, battleType, modifierAttacker, modifierDefender,
                sharedUnitStats, sharedUnitStats, rules, defenderStore, BossMechanic.none());
    }

    /** 攻守共用一份属性表 + 指定 BOSS 机制。原 11 参规范构造器的形状，BOSS 那条路径继续用它。 */
    public BattleInput(ArmySide attacker, ArmySide defender, TerrainType terrain, long seed,
                       BattleType battleType, BattleModifier modifierAttacker,
                       BattleModifier modifierDefender, Map<UnitType, UnitStats> sharedUnitStats,
                       BattleRules rules, DefenderStore defenderStore, BossMechanic defenderMechanic) {
        this(attacker, defender, terrain, seed, battleType, modifierAttacker, modifierDefender,
                sharedUnitStats, sharedUnitStats, rules, defenderStore, defenderMechanic);
    }

    /**
     * 攻守各一份兵种属性表（无 BOSS 机制）。**玩家城 PVP 必须走这一个**。
     *
     * <p>与上面那个 11 参构造器的区别只在第 8、9 位：那里是「同一份表用两次」，
     * 这里是两份不同的表。两者不会歧义 —— 第 9 位一个是 {@code Map} 一个是 {@code BattleRules}。
     */
    public BattleInput(ArmySide attacker, ArmySide defender, TerrainType terrain, long seed,
                       BattleType battleType, BattleModifier modifierAttacker,
                       BattleModifier modifierDefender, Map<UnitType, UnitStats> attackerUnitStats,
                       Map<UnitType, UnitStats> defenderUnitStats,
                       BattleRules rules, DefenderStore defenderStore) {
        this(attacker, defender, terrain, seed, battleType, modifierAttacker, modifierDefender,
                attackerUnitStats, defenderUnitStats, rules, defenderStore, BossMechanic.none());
    }

    public BattleInput {
        if (attacker == null || defender == null) {
            throw new IllegalArgumentException("attacker 与 defender 都不得为 null");
        }
        if (terrain == null) {
            throw new IllegalArgumentException("terrain 不得为 null");
        }
        if (battleType == null) {
            throw new IllegalArgumentException("battleType 不得为 null");
        }
        if (modifierAttacker == null || modifierDefender == null) {
            throw new IllegalArgumentException("modifier 不得为 null，无加成请用 BattleModifier.none()");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (defenderMechanic == null) {
            throw new IllegalArgumentException("defenderMechanic 不得为 null，无机制请用 BossMechanic.none()");
        }
        // 兵种属性必须覆盖四个兵种：缺一个就会在求和时拿到 null，
        // 而 NullPointerException 在战斗内核里是最难排查的一类错误（看不出是哪个兵种缺了）。
        // 报错信息里点明是攻方还是守方 —— 现在有两份表，只说「unitStats 缺兵种」等于没说
        attackerUnitStats = complete(attackerUnitStats, "attackerUnitStats");
        defenderUnitStats = complete(defenderUnitStats, "defenderUnitStats");

        if (attacker.totalUnits() <= 0L) {
            throw new IllegalArgumentException("攻方总兵数必须为正，否则不构成一场战斗");
        }
        if (defender.totalUnits() <= 0L) {
            throw new IllegalArgumentException("守方总兵数必须为正，否则不构成一场战斗");
        }
    }

    private static Map<UnitType, UnitStats> complete(Map<UnitType, UnitStats> source, String field) {
        Map<UnitType, UnitStats> stats = new LinkedHashMap<>();
        for (UnitType type : UnitType.values()) {
            UnitStats s = source == null ? null : source.get(type);
            if (s == null) {
                throw new IllegalArgumentException(field + " 缺少兵种 " + type
                        + " 的属性。四个兵种必须齐全，未参战的兵种也要给属性（数量填 0 即可）。");
            }
            stats.put(type, s);
        }
        return Collections.unmodifiableMap(stats);
    }
}
