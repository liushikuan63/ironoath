package com.ironoath.core.hero;

/**
 * 职责：武将三维属性（B06 §2）。
 * 依赖：无（纯数据）。
 *
 * <p>三个值都是<b>已取整的整数</b>而不是定点数：属性是玩家直接读到的数字，
 * 「武力 620」必须就是 620，不能是 619.9997 取整后偶尔变 619。
 * 定点精度只保留在 {@link HeroCalculator} 的内部乘法链里，最后一步才落到这里。
 *
 * @param might   武力 → 攻击乘区（B05 的 heroBonusFixed）
 * @param command 统率 → 防御乘区 + 带兵上限（B05 的 defBonusFixed 与 B05 §二的人口上限）
 * @param wisdom  智力 → 技能强度
 */
public record HeroAttrs(long might, long command, long wisdom) {

    public HeroAttrs {
        requireNonNegative(might, "might");
        requireNonNegative(command, "command");
        requireNonNegative(wisdom, "wisdom");
    }

    public static HeroAttrs zero() {
        return new HeroAttrs(0L, 0L, 0L);
    }

    public static HeroAttrs of(long might, long command, long wisdom) {
        return new HeroAttrs(might, command, wisdom);
    }

    /** 三维之和，用于战力与稀有度比值校验（B06 验收 7）。 */
    public long total() {
        return might + command + wisdom;
    }

    /** 逐维相加，用于叠加装备的固定值加成。 */
    public HeroAttrs plus(HeroAttrs other) {
        if (other == null) {
            throw new IllegalArgumentException("other 不得为 null，无加成请用 HeroAttrs.zero()");
        }
        return new HeroAttrs(might + other.might, command + other.command, wisdom + other.wisdom);
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负：" + value);
        }
    }
}
