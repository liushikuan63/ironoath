package com.ironoath.battle;

/**
 * 职责：单一兵种的战斗属性（由调用方从 contract/config/unit.json 解析后传入）。
 * 依赖：无（纯数据）。
 *
 * <p>内核<b>不读配置表</b>：game-battle 只能依赖 game-common，读不到 game-config。
 * 所以属性由 game-core / game-web 解析好再传进来 —— 这也是内核能脱离容器跑万局的前提。
 *
 * <p>attack/defense/hp/vsBuildingBonus 都是定点数（真实值 ×10000）。
 * speed 与 load 是整数计数，不参与定点运算。
 *
 * @param attackFixed          单位攻击（定点）
 * @param defenseFixed         单位防御（定点）
 * @param hpFixed              单位生命（定点）。<b>注意：B00 的减员公式不使用 hp</b>，见 BattleSimulator 类注释
 * @param speed                行军速度（整数），内核只用于日志与战报展示，不影响结算
 * @param load                 单位负载（整数），决定战后可掠夺的资源上限
 * @param vsBuildingBonusFixed 对建筑的额外伤害倍率（定点），仅攻城战与有城墙的防守方生效
 */
public record UnitStats(
        long attackFixed,
        long defenseFixed,
        long hpFixed,
        long speed,
        long load,
        long vsBuildingBonusFixed) {

    public UnitStats {
        requirePositive(attackFixed, "attackFixed");
        requirePositive(defenseFixed, "defenseFixed");
        requirePositive(hpFixed, "hpFixed");
        requireNonNegative(speed, "speed");
        requireNonNegative(load, "load");
        requireNonNegative(vsBuildingBonusFixed, "vsBuildingBonusFixed");
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
}
