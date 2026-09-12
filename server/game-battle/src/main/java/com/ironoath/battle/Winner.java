package com.ironoath.battle;

/**
 * 职责：战斗胜方。
 * 依赖：无。
 */
public enum Winner {
    ATTACKER,
    DEFENDER,
    /**
     * 平局：8 回合未分胜负且双方剩余兵力百分比差距小于阈值
     * （global.json 的 BATTLE_DRAW_GAP_RATIO，默认 5%）。
     */
    DRAW
}
