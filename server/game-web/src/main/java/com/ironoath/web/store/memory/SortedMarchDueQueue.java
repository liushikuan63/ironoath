package com.ironoath.web.store.memory;

import com.ironoath.core.march.MarchDueQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：行军到期队列的内存实现 —— 按到期时刻排序、被问到时扫出前面的若干个。
 * 依赖：无框架（纯 JDK 并发容器）。
 *
 * <p><b>这里一个定时器都没有，这是刻意的</b>。B07 的头号禁止项是
 * 「不要用 per-march Timer / ScheduledExecutorService —— 1000 支行军 = 1000 个定时器，必崩」，
 * 而 CI 的 check-layering.sh 里有一条静态检查扫全服务端运行期模块，
 * 出现 {@code new Timer(} / {@code newScheduledThreadPool} / {@code scheduleAtFixedRate} 就直接失败。
 * 所以本实现只提供「问我要 now 之前到期的 id」，由请求驱动地推进世界
 * （与产出结算的惰性推进是同一套纪律，见 B03 验收 9）。
 *
 * <p>生产环境换成 Redisson {@code RDelayedQueue} 或 Redis ZSET：
 * 两者都是<b>一个</b>队列 + <b>一个</b>消费线程，不是每支行军一个定时器，
 * 因此不违反红线。接口不变，只换实现。
 *
 * <p><b>数据结构是 {@code TreeMap<到期时刻, 该时刻的 id 列表>} + 一张 id → 时刻的反查表</b>：
 * <ul>
 *   <li>TreeMap 让 {@code dueBefore} 是 O(log n + k) 而不是全表扫描 ——
 *       1000 支队伍同时到期时（B07 验收 2）不能每次都遍历全队列</li>
 *   <li>反查表让 {@code reschedule}/{@code cancel} 是 O(log n)：
 *       没有它就得遍历所有时刻去找那个 id，加速一次行军要扫全队列</li>
 *   <li>同一时刻可能有多支队伍（整点集结），所以值是列表而不是单个 id</li>
 * </ul>
 */
public final class SortedMarchDueQueue implements MarchDueQueue {

    /** 到期时刻 → 该时刻到期的 marchId 列表（同一时刻可能有多支，例如整点集结）。 */
    private final TreeMap<Long, List<String>> byDueAt = new TreeMap<>();
    /** marchId → 到期时刻。反查表，让改期与撤销不必扫全队列。 */
    private final Map<String, Long> dueAtOf = new ConcurrentHashMap<>();

    @Override
    public void schedule(String marchId, long dueAtMillis) {
        requireId(marchId);
        if (dueAtMillis <= 0L) {
            throw new IllegalArgumentException("到期时刻必须为正的服务端时间戳：" + dueAtMillis);
        }
        synchronized (this) {
            Long previous = dueAtOf.put(marchId, dueAtMillis);
            if (previous != null) {
                // 响应丢失后的同事件重放须保持唯一，不能只让反查表唯一而桶里重复。
                if (previous == dueAtMillis) { return; }
                removeFromBucket(previous, marchId);
            }
            byDueAt.computeIfAbsent(dueAtMillis, k -> new ArrayList<>()).add(marchId);
        }
    }

    @Override
    public void reschedule(String marchId, long dueAtMillis) {
        // 改期与「取消 + 重新登记」的区别在于原子性：后者在两步之间有一个窗口，
        // 此时若正好有一次到期扫描，这支行军就会被漏掉（B07 验收 2：无漏触发）
        schedule(marchId, dueAtMillis);
    }

    @Override
    public void cancel(String marchId) {
        requireId(marchId);
        synchronized (this) {
            Long dueAt = dueAtOf.remove(marchId);
            if (dueAt != null) {
                removeFromBucket(dueAt, marchId);
            }
        }
    }

    @Override
    public List<String> dueBefore(long nowMillis, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正，否则一次都取不出来：" + limit);
        }
        List<String> out = new ArrayList<>();
        synchronized (this) {
            // headMap(true) 含等于 now 的时刻：到期时刻 == now 就算已到期，
            // 差一毫秒会让「刚好到点」的行军要等下一次轮询才被处理
            for (Map.Entry<Long, List<String>> entry : byDueAt.headMap(nowMillis, true).entrySet()) {
                for (String marchId : entry.getValue()) {
                    if (out.size() >= limit) {
                        return out;
                    }
                    out.add(marchId);
                }
            }
        }
        return out;
    }

    @Override
    public int size() {
        return dueAtOf.size();
    }

    /** 某个时刻的待触发数量，供单测断言「同一时刻的多支行军都被登记了」。 */
    public int sizeAt(long dueAtMillis) {
        synchronized (this) {
            List<String> bucket = byDueAt.get(dueAtMillis);
            return bucket == null ? 0 : bucket.size();
        }
    }

    private void removeFromBucket(long dueAt, String marchId) {
        List<String> bucket = byDueAt.get(dueAt);
        if (bucket == null) {
            return;
        }
        bucket.remove(marchId);
        if (bucket.isEmpty()) {
            // 空桶必须移除：否则 TreeMap 会越积越多的空列表，
            // headMap 遍历时会白走这些桶，队列长度看起来正常但扫描越来越慢
            byDueAt.remove(dueAt);
        }
    }

    private static void requireId(String marchId) {
        if (marchId == null || marchId.isBlank()) {
            throw new IllegalArgumentException("marchId 不得为空");
        }
    }

    public void clear() {
        synchronized (this) {
            byDueAt.clear();
            dueAtOf.clear();
        }
    }
}
