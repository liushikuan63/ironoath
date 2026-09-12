package com.ironoath.core.player;

/**
 * 职责：玩家战力三元组的领域状态。
 * 依赖：无（纯数据）。
 *
 * <p>铁律 11：任何 PVP 入口必须统一经过战力校验中间件，匹配一律用 {@code matchPower}（含峰值记忆），
 * <b>不得用展示战力</b>。三个值分开存就是为了在类型层面杜绝「拿 displayPower 去匹配」这种错误。
 *
 * <p>B00 公式（B08 落地）：
 * <pre>
 *   MatchPower = max(当前匹配战力, 历史峰值 × 0.8)
 *   峰值每日衰减 2%
 * </pre>
 *
 * @param displayPower 展示战力，只用于 UI 与排行榜
 * @param matchPower   匹配战力，PVP 校验的唯一依据
 * @param peakPower    历史峰值，用于防止「战前卸兵降战力」钻空子
 */
public record PlayerPower(long displayPower, long matchPower, long peakPower) {

    public PlayerPower {
        if (displayPower < 0L || matchPower < 0L || peakPower < 0L) {
            throw new IllegalArgumentException("战力不得为负数：display=" + displayPower
                    + ", match=" + matchPower + ", peak=" + peakPower);
        }
        if (peakPower < matchPower) {
            // 峰值定义上不可能低于当前匹配战力；出现即说明衰减逻辑或写入顺序有 bug
            throw new IllegalArgumentException(
                    "历史峰值不得低于当前匹配战力：peak=" + peakPower + ", match=" + matchPower);
        }
    }

    /** 零战力。注意：新号不会用它，因为 matchPower=0 会让 [0.5x, 2.0x] 区间退化为空集（见 global.json INIT_MATCH_POWER）。 */
    public static PlayerPower zero() {
        return new PlayerPower(0L, 0L, 0L);
    }
}
