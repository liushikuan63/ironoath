package com.ironoath.battle;

/**
 * 职责：战力圈层相关加成 —— 乘区 F 的来源（B08 传入）。
 * 依赖：无（纯数据）。
 *
 * <p>四个字段全部独立存储，<b>禁止合并成一个「总加成」</b>（B05 §1.3）。
 * 理由不是洁癖：这四个数来自四个不同的系统（复仇来自战报历史、哀兵来自集结状态、
 * 围剿来自暴虐值、地形战术来自大地图），合并之后任何一方调数值都会影响其他三方的表现，
 * 而且出了 bug 无法定位是哪条规则算错了。
 *
 * <p>铁律 11：这些加成是「弱者的反击工具」，绝不是「弱者的自动补偿」。
 * 每一项都要求玩家做了某个具体动作才生效（24 小时内反打同一目标 / 参与防守集结 / 围剿公敌），
 * 没有任何一项是「因为你弱所以给你加」。<b>禁止在此新增基于战力差的收益衰减或补偿字段。</b>
 *
 * @param revenge   复仇加成（定点）。+15% ⇒ 1500。来源 global.BONUS_REVENGE
 * @param aggrieved 哀兵加成（定点）。+10% ⇒ 1000。来源 global.BONUS_MOURNING
 * @param crusade   围剿公敌加成（定点）。+15% ⇒ 1500。来源 global.BONUS_SIEGE_PUBLIC_ENEMY
 * @param terrainAdv 地形战术加成（定点）。由 B07 大地图按地块给出
 */
public record BattleModifier(long revenge, long aggrieved, long crusade, long terrainAdv) {

    public BattleModifier {
        requireNonNegative(revenge, "revenge");
        requireNonNegative(aggrieved, "aggrieved");
        requireNonNegative(crusade, "crusade");
        requireNonNegative(terrainAdv, "terrainAdv");
    }

    /** 无任何圈层加成。 */
    public static BattleModifier none() {
        return new BattleModifier(0L, 0L, 0L, 0L);
    }

    /** 四项之和（定点）。仅用于乘区 F 的合成，不用于对外暴露「总加成」。 */
    public long totalFixed() {
        return revenge + aggrieved + crusade + terrainAdv;
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负，实际=" + value);
        }
    }
}
