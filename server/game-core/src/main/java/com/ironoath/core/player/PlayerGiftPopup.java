package com.ironoath.core.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ironoath.common.time.DayKey;

/**
 * 职责：礼包弹窗与限购在<b>玩家存档</b>上的那几位状态（B19 S3-ii/S3-iii）：
 * 最近一次弹出时刻、每个礼包最近的弹出时刻、三类触发各自的最近触发时刻、当日购买账本。
 * 依赖：无（纯数据，随 {@link PlayerSave} 一起持久化）。
 *
 * <p><b>为什么住存档而不是 {@code PopupThrottle} 的堆内 Map</b>：策略对象活在一个进程里，
 * 于是「重启即清零」与「多实例各弹各的」是默认行为而不是偶发 bug。与 {@link PlayerPaid} 同一条理由。
 *
 * <p><b>触发时刻与弹出时刻必须分开记</b>：触发是"发生过一件事"，弹出是"真的打扰过玩家一次"。
 * 合成一位就答不出"触发了但被频控压掉"这一整类事实。**报价有效期也从触发时刻算**（S3-iii）。
 *
 * <p><b>购买账本为什么不放订单表</b>：礼包限购问的是"今天买过几次"，而订单表只有按金额聚合的读法
 * （{@code paidCentsSince}）—— 为它新开一条按 productId 的索引，不如把这一天的事实写在本来就要读的这一位上
 * （弹窗判定与限购读的是同一位，少一次跨集合查询）。
 *
 * @param lastShowAt 最近一次弹出任意礼包的时刻；0 = 从未弹过（全局冷却 10 分钟读这一位）
 * @param showsByGift 礼包 id → 最近的弹出时刻列表（有界，见 {@link #SHOWS_PER_GIFT_MAX}）
 * @param triggeredAt 触发类型名（{@code GiftCfg.Trigger.name()}）→ 最近一次触发时刻
 * @param purchaseDayKey 购买账本所属的自然日（UTC+8 的 {@link DayKey}）；null = 还没买过任何礼包
 * @param purchasedCountByGift 礼包 id → **当日**已购次数；换日时整体清空（见 {@link #withPurchased}）
 */
public record PlayerGiftPopup(long lastShowAt, Map<String, List<Long>> showsByGift,
                              Map<String, Long> triggeredAt, String purchaseDayKey,
                              Map<String, Long> purchasedCountByGift) {

    /** 单个礼包最多记几次弹出。见类注释的有界理由。 */
    public static final int SHOWS_PER_GIFT_MAX = 8;

    public PlayerGiftPopup {
        showsByGift = copyOrderedBounded(showsByGift);
        triggeredAt = triggeredAt == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(triggeredAt));
        purchasedCountByGift = purchasedCountByGift == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(purchasedCountByGift));
        if (lastShowAt < 0L) {
            throw new IllegalArgumentException("lastShowAt 不得为负，实际=" + lastShowAt);
        }
        showsByGift.values().forEach(times -> times.forEach(t -> {
            if (t == null || t < 0L) {
                throw new IllegalArgumentException("弹出时刻不得为负或 null");
            }
        }));
        triggeredAt.forEach((k, v) -> {
            if (k == null || k.isBlank()) {
                throw new IllegalArgumentException("触发类型名不得为空白");
            }
            if (v == null || v < 0L) {
                throw new IllegalArgumentException("触发时刻不得为负或 null，trigger=" + k);
            }
        });
        if (purchaseDayKey != null && purchaseDayKey.isBlank()) {
            throw new IllegalArgumentException("购买账本的自然日不得为空白字符串（没买过请传 null）");
        }
        purchasedCountByGift.forEach((k, v) -> {
            if (k == null || k.isBlank()) {
                throw new IllegalArgumentException("礼包 id 不得为空白");
            }
            if (v == null || v < 1L) {
                throw new IllegalArgumentException("已购次数必须为正（0 次的行不该留在这里），gift=" + k);
            }
        });
    }

    /** 一次都没弹、没触发、没买过 —— 老存档读成它（与 {@link PlayerPaid#empty()} 同一条读法）。 */
    public static PlayerGiftPopup empty() {
        return new PlayerGiftPopup(0L, Map.of(), Map.of(), null, Map.of());
    }

    /**
     * 记一次"真的弹出去了"。<b>判定与记账分开</b>：只问过一句而没弹的路径不能走这里，
     * 否则 UI 布局阶段的试探会白白吃掉配额。
     */
    public PlayerGiftPopup withShown(String giftId, long at) {
        requireKey(giftId, "礼包 id");
        if (at < 0L) {
            throw new IllegalArgumentException("弹出时刻不得为负，实际=" + at);
        }
        List<Long> times = new ArrayList<>(showsByGift.getOrDefault(giftId, List.of()));
        times.add(at);
        while (times.size() > SHOWS_PER_GIFT_MAX) {
            times.remove(0);
        }
        Map<String, List<Long>> next = new LinkedHashMap<>(showsByGift);
        next.put(giftId, times);
        return new PlayerGiftPopup(at, next, triggeredAt, purchaseDayKey, purchasedCountByGift);
    }

    /**
     * 记一次触发（事件源唯一的动作）。同一礼包重复触发只留最近那一次：
     * 报价 TTL 是"从这一次触发起 60 分钟内有效"，留着旧时刻会让玩家隔几天还能翻出过期报价。
     */
    public PlayerGiftPopup withTriggered(String trigger, long at) {
        requireKey(trigger, "触发类型名");
        if (at < 0L) {
            throw new IllegalArgumentException("触发时刻不得为负，实际=" + at);
        }
        Map<String, Long> next = new LinkedHashMap<>(triggeredAt);
        next.put(trigger, at);
        return new PlayerGiftPopup(lastShowAt, showsByGift, next, purchaseDayKey, purchasedCountByGift);
    }

    /**
     * 记一次礼包购买（下单那一刻记账，见 S3-iii 的下单侧闸门）。
     *
     * <p><b>换日就整体清空</b>：限购的口径是"每自然日"，而不是"距上次购买 24 小时"——
     * 后者会让玩家在每天固定时间点越买越晚，最后滑出当天。清空按 {@link DayKey}（UTC+8 零点）。
     */
    public PlayerGiftPopup withPurchased(String giftId, String dayKey) {
        requireKey(giftId, "礼包 id");
        requireKey(dayKey, "购买账本的自然日");
        Map<String, Long> next = new LinkedHashMap<>(
                dayKey.equals(purchaseDayKey) ? purchasedCountByGift : Map.of());
        next.merge(giftId, 1L, Long::sum);
        return new PlayerGiftPopup(lastShowAt, showsByGift, triggeredAt, dayKey, next);
    }

    /** 某个礼包最近的弹出时刻列表；没弹过是空表。 */
    public List<Long> showsOf(String giftId) {
        return showsByGift.getOrDefault(giftId, List.of());
    }

    /** 某类触发最近一次发生的时刻；没触发过为 0。 */
    public long triggeredAtOf(String trigger) {
        Long at = triggeredAt.get(trigger);
        return at == null ? 0L : at;
    }

    /** 某个礼包**今天**已买几次。账本属于别的自然日时读作 0（这就是"跨天恢复可买"的那一步）。 */
    public long purchasedTodayOf(String giftId, String todayKey) {
        if (todayKey == null || !todayKey.equals(purchaseDayKey)) {
            return 0L;
        }
        return purchasedCountByGift.getOrDefault(giftId, 0L);
    }

    private static Map<String, List<Long>> copyOrderedBounded(Map<String, List<Long>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> copy = new LinkedHashMap<>();
        source.forEach((giftId, times) -> {
            requireKey(giftId, "礼包 id");
            List<Long> kept = new ArrayList<>(times == null ? List.of() : times);
            while (kept.size() > SHOWS_PER_GIFT_MAX) {
                kept.remove(0);
            }
            copy.put(giftId, Collections.unmodifiableList(kept));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void requireKey(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不得为空白");
        }
    }
}
