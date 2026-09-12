package com.ironoath.core.hero;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：武将养成的全部规则参数（由调用方从 contract/config 解析后传入）。
 * 依赖：无（纯数据）。
 *
 * <p>与战斗内核、城建同一套约定：game-core 不读配置表，所有数值由外层解析好传进来。
 * 于是「满级满星武将属性 = 手算值」（B06 验收 6）这种断言可以在没有 Spring、
 * 没有数据库、没有配置文件的情况下被验证。
 *
 * <p><b>成长因子刻意分成两条曲线</b>（这是 B06 数值设计里最容易被搞错的一处）：
 * <ul>
 *   <li><b>战斗属性</b>用 {@code levelStepFixed} 线性步进：100 级 ×2.98。
 *       有界是必须的 —— 战斗属性要换算成乘区 A 的百分比，
 *       若按 curve.HERO_GROWTH 的 level^1.20（100 级 = 251 倍）放大，
 *       满级武将的攻击加成会到几千个百分点，那正是 B06 禁止项说的「堆叠爆炸」。</li>
 *   <li><b>战力数值</b>用 {@code powerExponentFixed} 幂律：100 级 = 251 倍。
 *       战力只用于展示与 B08 圈层匹配，不进战斗公式，所以可以陡峭 ——
 *       玩家需要感到「我变强了很多」，而战斗平衡仍然由兵种与克制决定。</li>
 * </ul>
 * 同一个「武将成长」用两种增长形状不是不一致，而是因为两个数字服务于两个不同的目的。
 *
 * @param levelStepFixed      每级属性步进（HERO_LEVEL_STEP，0.02 ⇒ 200）
 * @param starStepFixed       每星属性步进（HERO_STAR_STEP，0.10 ⇒ 1000）
 * @param awakenStepFixed     每阶觉醒步进（HERO_AWAKEN_STEP，0.08 ⇒ 800）
 * @param starMax             星级上限（HERO_STAR_MAX）
 * @param skillMaxLevel       技能等级上限（HERO_SKILL_MAX_LEVEL）
 * @param attrPerPercent      每多少点属性折算 +1% 战斗加成（HERO_ATTR_PER_PERCENT）
 * @param subBonusRatioFixed  副将属性计入队伍乘区的比例（HERO_SUB_BONUS_RATIO）
 * @param zoneCapFixed        武将乘区（含缘分）的加成上限（HERO_ZONE_CAP）
 * @param bondBonusFixed      每条激活的缘分带来的加成（HERO_BOND_BONUS）
 * @param troopPerCommand     每点统率折算的带兵数（TROOP_PER_COMMAND）
 * @param lineupSize          每队武将数（LINEUP_HERO_COUNT）
 * @param presetCount         编队预设套数（LINEUP_PRESET_COUNT）
 * @param expBaseFixed        升级经验曲线基数（curve.HERO_LEVEL_EXP.base）
 * @param expRatioFixed       升级经验曲线比率（curve.HERO_LEVEL_EXP.ratio）
 * @param powerExponentFixed  战力曲线指数（curve.HERO_GROWTH.exponent）
 */
public record HeroRules(
        long levelStepFixed,
        long starStepFixed,
        long awakenStepFixed,
        int starMax,
        int skillMaxLevel,
        long attrPerPercent,
        long subBonusRatioFixed,
        long zoneCapFixed,
        long bondBonusFixed,
        long troopPerCommand,
        int lineupSize,
        int presetCount,
        long expBaseFixed,
        long expRatioFixed,
        long powerExponentFixed) {

    public HeroRules {
        requirePositive(levelStepFixed, "levelStepFixed");
        requireNonNegative(starStepFixed, "starStepFixed");
        requireNonNegative(awakenStepFixed, "awakenStepFixed");
        if (starMax < 1) {
            throw new IllegalArgumentException("starMax 必须 >= 1（获得时就是 1 星），实际=" + starMax);
        }
        if (skillMaxLevel < 1) {
            throw new IllegalArgumentException("skillMaxLevel 必须 >= 1，实际=" + skillMaxLevel);
        }
        if (attrPerPercent < 1) {
            throw new IllegalArgumentException("attrPerPercent 必须 >= 1，否则除零，实际=" + attrPerPercent);
        }
        requireRatio(subBonusRatioFixed, "subBonusRatioFixed");
        requireRatio(bondBonusFixed, "bondBonusFixed");
        // 上限必须为正：0 会把整个武将乘区钉死成无加成，武将系统等于不存在
        if (zoneCapFixed <= 0L) {
            throw new IllegalArgumentException("zoneCapFixed 必须为正，实际=" + zoneCapFixed);
        }
        if (troopPerCommand < 1) {
            throw new IllegalArgumentException("troopPerCommand 必须 >= 1，否则带兵上限恒为 0，实际="
                    + troopPerCommand);
        }
        if (lineupSize < 1) {
            throw new IllegalArgumentException("lineupSize 必须 >= 1（至少要有一个主将位），实际=" + lineupSize);
        }
        if (presetCount < 1) {
            throw new IllegalArgumentException("presetCount 必须 >= 1，实际=" + presetCount);
        }
        if (expBaseFixed < 1 || expRatioFixed < 1) {
            throw new IllegalArgumentException("经验曲线的基数与比率都必须为正：base=" + expBaseFixed
                    + ", ratio=" + expRatioFixed);
        }
        if (powerExponentFixed < 1) {
            throw new IllegalArgumentException("powerExponentFixed 必须 >= 1.0，实际=" + powerExponentFixed);
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " 必须为正，实际=" + value);
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负，实际=" + value);
        }
    }

    private static void requireRatio(long fixed, String field) {
        if (fixed < 0L || fixed > FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + fixed);
        }
    }

    /** 等级因子 = 1 + (level-1) × levelStep。线性、有界，见类注释。 */
    public long levelFactor(int level) {
        requireLevel(level);
        return FixedPoint.ONE + FixedPoint.mul(FixedPoint.of(level - 1L), levelStepFixed);
    }

    /** 星级因子 = 1 + (star-1) × starStep。 */
    public long starFactor(int star) {
        if (star < 1 || star > starMax) {
            throw new IllegalArgumentException("星级必须落在 [1, " + starMax + "]，实际=" + star);
        }
        return FixedPoint.ONE + FixedPoint.mul(FixedPoint.of(star - 1L), starStepFixed);
    }

    /** 觉醒因子 = 1 + awaken × awakenStep。0 阶是初始状态，所以不减 1。 */
    public long awakenFactor(int awaken) {
        if (awaken < 0) {
            throw new IllegalArgumentException("觉醒阶数不得为负：" + awaken);
        }
        return FixedPoint.ONE + FixedPoint.mul(FixedPoint.of(awaken), awakenStepFixed);
    }

    /**
     * 升到 {@code level+1} 级所需经验 = expBase × expRatio^(level-1)（curve.HERO_LEVEL_EXP）。
     *
     * @param maxLevel 该武将的等级上限，满级时返回 0（不是抛异常：客户端要显示「已满级」）
     */
    public long expToNext(int level, int maxLevel) {
        requireLevel(level);
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel 必须 >= 1，实际=" + maxLevel);
        }
        if (level >= maxLevel) {
            return 0L;
        }
        return FixedPoint.round(FixedPoint.geometric(expBaseFixed, expRatioFixed, level - 1));
    }

    /**
     * 属性换算成战斗加成（定点）。
     *
     * <p>{@code attrPerPercent} 是「多少点属性 = +1%」，所以加成 = attr / attrPerPercent / 100，
     * 换算成定点就是 attr × SCALE / (attrPerPercent × 100)。
     * 全程整数运算，最后一步才除，避免中途取整把小数值吃掉。
     */
    public long attrToBonusFixed(long attr) {
        if (attr < 0L) {
            throw new IllegalArgumentException("属性不得为负：" + attr);
        }
        if (attr == 0L) {
            return 0L;
        }
        return attr * FixedPoint.SCALE / (attrPerPercent * 100L);
    }

    private static void requireLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("等级必须 >= 1，实际=" + level);
        }
    }
}
