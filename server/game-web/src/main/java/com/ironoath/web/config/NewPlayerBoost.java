package com.ironoath.web.config;

/**
 * 职责：新号开局数值的**可覆盖口**（只在 dev profile 有一个实现，见 {@link DevNewPlayerBoost}）。
 * 依赖：无框架类型。
 *
 * <p><b>为什么需要它</b>：国家系统（B13）的建国前置是「主城 16 级 + 在某联盟中」，
 * 而新号开局主城 1 级、四种资源加起来不到两万 —— 靠正常升级打到 16 级要跑几十分钟的真实时长，
 * 于是 V13-S1 与 V13-S2 的正向链路（建国 / 入籍 / 国库支出）在本地永远验不到。
 *
 * <p><b>为什么不是改配置表</b>：{@code INIT_CITY_LEVEL} 与 {@code resource.initAmount} 是
 * {@code contract/config} 里的真源，被 {@code ConfigRegistryTest} /
 * {@code PlayerInitTest} 钉着，而且 prod 与 dev 读的是同一份表 ——
 * 为了让本地好跑而把 prod 的开局数值一起改了，是拿验收环境换一条测试。
 *
 * <p><b>为什么接口 + ObjectProvider</b>：prod 上下文里**没有实现 bean**（实现是 {@code @Profile("dev")}），
 * 所以覆盖值在 prod 结构上就取不到，而不是"读了环境变量恰好没设"。
 * {@link #cityLevel} / {@link #startAmount} 把"没设"这件事定义成 {@code <= 0}，
 * 于是「环境变量写错」与「没写」是同一种结果：走配置表原值。
 */
public interface NewPlayerBoost {

    /**
     * 覆盖值。{@code <= 0} 表示"没设"，调用方一律退回配置表。
     *
     * <p>默认实现就是"没设" —— 它是接口常量而不是抽象方法刻意如此：
     * 这样没装配任何覆盖的上下文（单测、prod）拿到的是一个安全的空实现，
     * 不必在每个测试里塞一个 mock。
     */
    NewPlayerBoost NONE = new NewPlayerBoost() {
        @Override
        public int cityLevel() {
            return 0;
        }

        @Override
        public long startAmount() {
            return 0L;
        }
    };

    /** 覆盖新号的主城等级（0 = 不覆盖）。 */
    int cityLevel();

    /** 覆盖新号每一种资源的初始数量（0 = 不覆盖）。 */
    long startAmount();

    /**
     * 主城等级最终取哪个。
     *
     * <p>{@code override <= 0} 时**必须**原样退回配置值 —— 退回 1 而不是退回 0，
     * 因为 0 级主城会让新号一进内城就撞上一堆"主城 1 级才能建"的空态，而那与覆盖口无关。
     */
    static int cityLevel(long fromConfig, int override) {
        return override > 0 ? override : (int) fromConfig;
    }

    /** 资源初始数量最终取哪个（同样只有 {@code > 0} 才覆盖）。 */
    static long startAmount(long fromConfig, long override) {
        return override > 0 ? override : fromConfig;
    }
}
