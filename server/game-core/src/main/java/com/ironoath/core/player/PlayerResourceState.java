package com.ironoath.core.player;

/**
 * 职责：玩家单一资源的领域状态（惰性结算模型）。
 * 依赖：无（纯数据，game-core 只依赖 game-common）。
 *
 * <p>B00 Java 五大技术陷阱第 2 条：禁止 {@code @Scheduled} 每秒扫全表结算产出，改用惰性结算 ——
 * 存 {@code lastSettle}，读取时按时间差算。本 record 就是这个模型的最小载体。
 *
 * <p>领域对象与线上 DTO 刻意分开：本类是内部状态（可含未落地为整数的中间量），
 * {@code com.ironoath.web.dto.generated.ResourceState} 是下发契约。分开之后，
 * 内部模型可以自由演进（B03 会加入建筑加成明细），不必每次都改协议与客户端。
 *
 * @param current         已结算的资源量
 * @param cap             容量上限
 * @param protectedAmount 受保护不可掠夺<b>额度</b> = cap × 保护比例（B04 §1）。
 *                        它是按容量算出的额度而不是当前持有量的子集，所以可以大于 current
 *                        ——那只是意味着「手上的全部都在保护范围内」
 * @param perHour         每小时产量
 * @param lastSettle      上次结算的服务端时间戳（毫秒）
 */
public record PlayerResourceState(
        long current,
        long cap,
        long protectedAmount,
        long perHour,
        long lastSettle) {

    public PlayerResourceState {
        requireNonNegative(current, "current");
        requireNonNegative(cap, "cap");
        requireNonNegative(protectedAmount, "protectedAmount");
        requireNonNegative(perHour, "perHour");
        requireNonNegative(lastSettle, "lastSettle");
        if (current > cap) {
            // 允许 current == cap（满仓），但不允许超出：超出说明某处结算漏了封顶，必须当场暴露
            throw new IllegalArgumentException(
                    "资源量不得超过容量上限：current=" + current + ", cap=" + cap);
        }
        if (protectedAmount > cap) {
            // protectedAmount 是「按容量算出的不可掠夺额度」（B04 §1：cap × 保护比例），
            // 不是「当前持有量的一部分」。新号只有 200 金币而额度是 20 万完全合法，
            // 含义就是「手上的全都在保护范围内」；掠夺时的截断由 ResourceProtection.plunderable 负责。
            throw new IllegalArgumentException(
                    "受保护额度不得超过容量上限：protectedAmount=" + protectedAmount + ", cap=" + cap);
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负数，实际=" + value);
        }
    }
}
