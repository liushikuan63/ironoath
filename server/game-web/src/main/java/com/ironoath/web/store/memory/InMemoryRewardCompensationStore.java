package com.ironoath.web.store.memory;

import com.ironoath.web.reward.RewardCompensationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 职责：补偿台账的内存实现（dev / 测试）。
 * 依赖：{@link RewardCompensationStore}。
 *
 * <p>整个类的方法都是 {@code synchronized}：{@link #resolve} 的「只有第一次翻成已处理才算成功」
 * 是一句原子声明，用监视器锁在进程内兑现它。跨实例那一道由 Mongo 版的<b>条件更新</b>负责，
 * 两把闸各管自己那一层（与 {@code InMemoryMailStore} 同一分工）。
 *
 * <p>本实现<b>重启即丢</b>，这正是它只用于 dev 的理由：装配处会为此打一条 WARN。
 */
public final class InMemoryRewardCompensationStore implements RewardCompensationStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryRewardCompensationStore.class);

    /** compensationId → 记录。LinkedHashMap 只为让「同一毫秒记的两笔」相对顺序稳定，不参与判定。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized void save(Entry entry) {
        Entry previous = entries.putIfAbsent(entry.compensationId(), entry);
        if (previous != null) {
            LOG.warn("重复写入同一条补偿记录，已按幂等忽略 compensationId={} —— "
                    + "已处理的那一条被一次重投盖回未处理，是台账最不该发生的事", entry.compensationId());
        }
    }

    @Override
    public synchronized List<Entry> pending(int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.pending()) {
                out.add(entry);
            }
        }
        // 与 Mongo 版逐字同一条排序：createdAt 升序（最旧的在前），同刻按 compensationId 升序。
        // 两侧排序不同会让运维在 dev 与 prod 看到不同的「第一页」，那是最容易漏测的差异。
        out.sort(Comparator.comparingLong(Entry::createdAt).thenComparing(Entry::compensationId));
        return List.copyOf(out.size() > Math.max(0, limit) ? out.subList(0, Math.max(0, limit)) : out);
    }

    @Override
    public synchronized int countPending() {
        int n = 0;
        for (Entry entry : entries.values()) {
            if (entry.pending()) {
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized Optional<Entry> findById(String compensationId) {
        return Optional.ofNullable(entries.get(compensationId));
    }

    @Override
    public synchronized boolean resolve(String compensationId, String resolvedBy, String resolution,
                                        long nowMillis) {
        if (resolvedBy == null || resolvedBy.isBlank()) {
            // 与 Mongo 版逐字同一句话（等价测试比对文本）：空处理人写进库会让那条记录
            // 读回来时撞上 Entry 的构造校验，从此谁也查不到它 —— 那不是"没处理"，是"处理完就消失"
            throw new IllegalArgumentException("resolve 必须写明处理人：「谁把这笔债销掉的」与「谁欠的」同样重要");
        }
        Entry entry = entries.get(compensationId);
        if (entry == null || !entry.pending()) {
            return false;
        }
        entries.put(compensationId, new Entry(entry.compensationId(), entry.playerId(), entry.failed(),
                entry.source(), entry.sourceRef(), entry.traceId(), entry.reason(), entry.createdAt(),
                nowMillis, resolvedBy, resolution));
        return true;
    }

    @Override
    public synchronized int count() {
        return entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }
}
