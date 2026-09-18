package com.ironoath.core.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：礼包弹窗在<b>玩家存档</b>上的那几位状态（B19 S3-ii）：最近一次弹出时刻、每个礼包最近的弹出时刻、
 * 三类触发各自的最近触发时刻。
 * 依赖：无（纯数据，随 {@link PlayerSave} 一起持久化）。
 *
 * <p><b>为什么住存档而不是 {@code PopupThrottle} 的堆内 Map</b>：策略对象活在一个进程里，
 * 于是「重启即清零」与「多实例各弹各的」是默认行为而不是偶发 bug —— 玩家昨天被弹满三次，
 * 今天服务重启后又能被弹三次，而频控三条规则的存在意义正是「同一个礼包别催第三遍」。
 * 与 {@link PlayerPaid} 同一条理由：写入方全都是「读档 → 改 → 写档」那一族，放存档上还白拿乐观锁。
 *
 * <p><b>触发时刻与弹出时刻必须分开记</b>：触发是「发生过一件事」（关卡失败、建筑落成、被打穿），
 * 弹出是「真的打扰过玩家一次」。合成一位就答不出「触发了但被频控压掉」这一整类事实，
 * 而运营看到「礼包转化 0」时最需要区分的就是它和「根本没弹出来」。
 *
 * <p><b>有界</b>：每个礼包只留最近 {@value #SHOWS_PER_GIFT_MAX} 次（判定窗口是 24h，
 * 上限 3 次，留 8 条已远大于需要），不封顶就是一条随会话数无限长大的存档字段。
 *
 * @param lastShowAt 最近一次弹出任意礼包的时刻；0 = 从未弹过（全局冷却 10 分钟读这一位）
 * @param showsByGift 礼包 id → 最近的弹出时刻列表（插入有序，同 {@link PlayerPaid} 的账本口径）
 * @param triggeredAt 触发类型名（{@code GiftCfg.Trigger.name()}）→ 最近一次触发时刻；
 *                    报价 TTL 从它算，过期不重写就没有弹窗
 */
public record PlayerGiftPopup(long lastShowAt, Map<String, List<Long>> showsByGift,
                              Map<String, Long> triggeredAt) {

    /** 单个礼包最多记几次弹出。见类注释的有界理由。 */
    public static final int SHOWS_PER_GIFT_MAX = 8;

    public PlayerGiftPopup {
        // 不用 Map.copyOf / List.of：它们不保证迭代顺序，而「丢最早几次」靠的正是插入顺序
        showsByGift = copyOrderedBounded(showsByGift);
        triggeredAt = triggeredAt == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(triggeredAt));
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
    }

    /** 从未弹过、也没被任何事件触发过 —— 老存档读成它（与 {@link PlayerPaid#empty()} 同一条读法）。 */
    public static PlayerGiftPopup empty() {
        return new PlayerGiftPopup(0L, Map.of(), Map.of());
    }

    /**
     * 记一次「真的弹出去了」。<b>判定与记账分开</b>（同 {@code PopupThrottle#recordShown} 的纪律）：
     * 只问过一句而没弹的路径不能走这里，否则 UI 布局阶段的试探会白白吃掉配额。
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
        return new PlayerGiftPopup(at, next, triggeredAt);
    }

    /**
     * 记一次触发（事件源唯一的动作）。<b>同一礼包重复触发只留最近那一次</b>：
     * 报价 TTL 是「从这一次触发起 60 分钟内有效」，留着旧时刻会让玩家隔几天还能翻出过期报价。
     */
    public PlayerGiftPopup withTriggered(String trigger, long at) {
        requireKey(trigger, "触发类型名");
        if (at < 0L) {
            throw new IllegalArgumentException("触发时刻不得为负，实际=" + at);
        }
        Map<String, Long> next = new LinkedHashMap<>(triggeredAt);
        next.put(trigger, at);
        return new PlayerGiftPopup(lastShowAt, showsByGift, next);
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
