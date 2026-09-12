package com.ironoath.battle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：守方的资源存量 —— 掠夺结算的输入（B00：掠夺 = min(对方非保护资源, 我方剩余负载)）。
 * 依赖：无（纯数据）。
 *
 * <p>键是资源 id 字符串（对应 contract/config/resource.json 的 rows[].id，如 WOOD）。
 * 用字符串而不是枚举：game-battle 只能依赖 game-common，拿不到 game-web 生成的
 * ResourceType 枚举，也不该为此在 game-common 再造一份重复的枚举。
 * 字符串 id 是跨层的最小公约数，转换由调用方（game-core / game-web）负责。
 *
 * <p>破墙后才掠夺仓库（B00）：城墙是否被破由 {@code wallBroken} 表达，
 * 内核不自己判断 —— 城墙耐久属于城防系统（B03/B07），不在战斗内核的职责内。
 *
 * @param unprotected   非保护资源量，可被掠夺
 * @param protectedAmount 受保护资源量，不可被掠夺（仓库等级与护盾决定，见 B04）
 * @param wallBroken    城墙是否已被攻破。false 时 loot 恒为空
 */
public record DefenderStore(
        Map<String, Long> unprotected,
        Map<String, Long> protectedAmount,
        boolean wallBroken) {

    public DefenderStore {
        unprotected = freeze(unprotected, "unprotected");
        protectedAmount = freeze(protectedAmount, "protectedAmount");
    }

    /** 不可掠夺（PVE、或城墙未破）。 */
    public static DefenderStore none() {
        return new DefenderStore(Map.of(), Map.of(), false);
    }

    private static Map<String, Long> freeze(Map<String, Long> source, String field) {
        Map<String, Long> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Long> e : source.entrySet()) {
                if (e.getValue() == null || e.getValue() < 0L) {
                    throw new IllegalArgumentException(field + " 中资源 " + e.getKey()
                            + " 的数量不得为 null 或负数，实际=" + e.getValue());
                }
                copy.put(e.getKey(), e.getValue());
            }
        }
        // 按资源 id 排序：掠夺是贪心填充的，遍历顺序不同会导致掠到的资源组合不同
        return Collections.unmodifiableMap(new java.util.TreeMap<>(copy));
    }

    /** 非保护资源总量。 */
    public long totalUnprotected() {
        long total = 0L;
        for (long v : unprotected.values()) {
            total += v;
        }
        return total;
    }
}
