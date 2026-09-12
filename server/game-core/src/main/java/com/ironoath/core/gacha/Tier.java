package com.ironoath.core.gacha;

/**
 * 职责：抽卡的稀有度档位。
 * 依赖：无（纯 Java）。
 *
 * <p><b>声明顺序即概率掷取的判定顺序</b>（SSR → SR → R → N），
 * 与 gacha 表四档基础概率的累加区间一一对应。改顺序等于改抽取算法，
 * 必须同步修改 {@link GachaEngine} 与 tools/gacha-calibrate/calibrate.py。
 *
 * <p>与 hero 表的 rarity 列、协议的 HeroRarity 枚举取值一致，
 * 三者的一致性由 {@code ContractEnumParityTest} 断言。
 */
public enum Tier {
    SSR,
    SR,
    R,
    N
}
