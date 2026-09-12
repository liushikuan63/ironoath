package com.ironoath.core.social;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：聊天防刷屏限流（B10 §5、验收 9：同内容 10 秒内发 4 次，第 4 次被拦截）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>限的是「同内容」而不是「同发送者」</b>：一个人正常聊天 10 秒发 3 条<b>不同</b>内容
 * 不该被拦（小队集结时刷屏报坐标是正常行为），
 * 而复读机 —— 招人广告、辱骂、刷屏表情 —— 正是靠重复同一句话实现的。
 * 按发送者限流会误伤前者、放过换个标点就绕开的后者。
 *
 * <p><b>滑动窗口而不是固定窗口</b>：固定窗口（每 10 秒重置计数）允许在窗口交界处
 * 连着发 6 条（上个窗口末尾 3 条 + 新窗口开头 3 条），
 * 而刷屏者恰恰会本能地卡在重置点上发。滑动窗口没有这个缝。
 *
 * <p><b>时间由调用方传入</b>（铁律 5：不用本地时钟、不自己读 System.currentTimeMillis）：
 * 服务端权威时间才能被单测控制，也才能在压测里复现「10 秒内 4 次」这个精确场景。
 *
 * <p><b>内存有上界</b>：每个玩家最多跟踪 {@code maxKeysPerPlayer} 个不同的内容，
 * 超出按最久未用淘汰。不设上界的话，一个玩家发一万条不同的消息就能让这张表涨到一万项 ——
 * 那是用聊天功能打内存的现成路径。
 */
public final class ChatRateLimiter {

    /**
     * @param windowMillis       滑动窗口长度（毫秒）。来源 global.CHAT_RATE_LIMIT_WINDOW_SECONDS
     * @param maxPerWindow       窗口内同内容允许的次数。来源 global.CHAT_RATE_LIMIT_COUNT
     * @param maxKeysPerPlayer   每个玩家最多跟踪多少个不同内容
     */
    public record Rules(long windowMillis, int maxPerWindow, int maxKeysPerPlayer) {
        public Rules {
            if (windowMillis <= 0) {
                throw new IllegalArgumentException("windowMillis 必须为正，实际=" + windowMillis);
            }
            if (maxPerWindow < 1) {
                throw new IllegalArgumentException("maxPerWindow 必须 >= 1，否则任何消息都发不出去，实际="
                        + maxPerWindow);
            }
            if (maxKeysPerPlayer < 1) {
                throw new IllegalArgumentException("maxKeysPerPlayer 必须 >= 1，实际=" + maxKeysPerPlayer);
            }
        }
    }

    /** 一次发送的判定结果。 */
    public record Verdict(boolean allowed, int hitsInWindow, long retryAfterMillis) {
    }

    private final Rules rules;
    /** playerId → (内容 → 发送时刻队列)。用 LinkedHashMap 以便按访问顺序淘汰。 */
    private final Map<String, LinkedHashMap<String, Deque<Long>>> byPlayer = new HashMap<>();

    public ChatRateLimiter(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 判定一次发送并记账。
     *
     * <p><b>判定与记账是同一步</b>：分成「先查能不能发」和「发了再记」两步的话，
     * 两步之间的并发窗口里同一个人可以发出超过上限的条数（先查后改的经典漏洞，
     * 与 B00 禁止的「先查后改扣资源」是同一类问题）。
     *
     * @param playerId 发送者
     * @param content  正文原样。<b>不做 trim 与大小写归一</b>：归一化会让「AA」与「aa」算同一条，
     *                 看起来更严，但真正的刷屏者会在每句末尾加一个随机字符，
     *                 归一化对他毫无作用，只会误伤正常输入。内容层面的滥用属 B15 合规范畴。
     * @param now      服务端当前时刻（毫秒）
     */
    public Verdict check(String playerId, String content, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不得为 null");
        }
        LinkedHashMap<String, Deque<Long>> keys = byPlayer.computeIfAbsent(playerId,
                k -> new LinkedHashMap<>(4, 0.75f, true));
        evict(keys);

        Deque<Long> hits = keys.computeIfAbsent(content, k -> new ArrayDeque<>(rules.maxPerWindow()));
        long windowStart = now - rules.windowMillis();
        // 先丢掉窗口外的旧记录：不丢的话队列会无限增长，而且判定用的是过期数据
        while (!hits.isEmpty() && hits.peekFirst() <= windowStart) {
            hits.pollFirst();
        }

        if (hits.size() >= rules.maxPerWindow()) {
            Long oldest = hits.peekFirst();
            // 还能重试的时刻 = 最早那条滑出窗口的时刻。给 0 会让客户端立刻重发并再次被拦
            long retryAfter = (oldest == null) ? rules.windowMillis() : Math.max(1L, oldest + rules.windowMillis() - now);
            return new Verdict(false, hits.size(), retryAfter);
        }
        hits.addLast(now);
        if (hits.isEmpty()) {
            keys.remove(content);
        }
        return new Verdict(true, hits.size(), 0L);
    }

    /**
     * 玩家下线或长期不发言后清理其记录。
     *
     * <p>不清理的话这张表只增不减 —— 每个登录过的玩家都会留下几十条内容记录，
     * 一个月后就是几十万个 Deque。这是内存泄漏，不是缓存。
     */
    public void forget(String playerId) {
        byPlayer.remove(playerId);
    }

    /** 当前跟踪的玩家数。用于断言内存有上界。 */
    public int trackedPlayers() {
        return byPlayer.size();
    }

    /** 某个玩家当前跟踪的不同内容数。恒定 <= maxKeysPerPlayer。 */
    public int trackedKeys(String playerId) {
        LinkedHashMap<String, Deque<Long>> keys = byPlayer.get(playerId);
        return keys == null ? 0 : keys.size();
    }

    /** 按访问顺序淘汰最久未用的内容键，把跟踪数压回上限内。 */
    private void evict(LinkedHashMap<String, Deque<Long>> keys) {
        if (keys.size() < rules.maxKeysPerPlayer()) {
            return;
        }
        Iterator<Map.Entry<String, Deque<Long>>> iterator = keys.entrySet().iterator();
        while (iterator.hasNext() && keys.size() >= rules.maxKeysPerPlayer()) {
            iterator.next();
            iterator.remove();
        }
    }
}
