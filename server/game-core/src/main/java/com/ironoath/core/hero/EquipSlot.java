package com.ironoath.core.hero;

/**
 * 职责：装备槽位（B06 §2.5：4 槽位 + 套装效果）。
 * 依赖：无（纯 Java）。
 *
 * <p><b>声明顺序即 {@code HeroView.equips} 数组的下标顺序</b>，也与 equip 表的 slot 列取值一致。
 * 用定长数组而不是 Map 下发是为了让客户端不必猜键名顺序（B06 契约里的 equips 字段）。
 */
public enum EquipSlot {
    WEAPON,
    ARMOR,
    MOUNT,
    ACCESSORY;

    /** 槽位数量。装备表若新增槽位，这里与协议里的 equips 定长数组必须同步改。 */
    public static final int COUNT = 4;

    static {
        // 静态断言：COUNT 写死成 4 而枚举只有 3 个（或多于 4 个）是典型的复制粘贴事故，
        // 而它的表现是客户端读到 undefined，很难追到这里
        if (values().length != COUNT) {
            throw new IllegalStateException("EquipSlot 的枚举数量(" + values().length
                    + ")与 COUNT(" + COUNT + ")不一致，协议里的 equips 定长数组也要同步改");
        }
    }
}
