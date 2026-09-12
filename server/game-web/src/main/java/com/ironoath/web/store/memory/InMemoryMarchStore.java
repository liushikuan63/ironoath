package com.ironoath.web.store.memory;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 职责：行军存档的内存实现。
 * 依赖：game-core 的端口与实体。
 *
 * <p><b>B07 §2 明写「持久化在 MongoDB」</b>：行军是跨会话的长生命周期实体，
 * 玩家杀进程重进后所有队伍必须按真实剩余时间继续（验收 1）。
 * 本实现只服务 dev/test，重启即丢，装配处会用 WARN 日志把这件事喊出来；
 * MongoDB 版由 B16 交付。
 *
 * <p>读返回深拷贝、写走乐观锁 —— 与城建/武将/军队同一套做法。
 * 深拷贝是必需的：March 可变，直接把库里那份交出去，
 * 调用方改一下就等于绕过了乐观锁（改完再 save 会「成功」，但中间状态对所有读者可见）。
 */
public final class InMemoryMarchStore implements MarchRepository {

    private static final class Entry {
        private final AtomicReference<March> march;
        private long version;

        Entry(March march) {
            this.march = new AtomicReference<>(march);
        }
    }

    /** chunk 边长，由装配处从 global.WORLD_CHUNK_SIZE 传入（铁律 1：不在代码里写死数值）。 */
    private final int chunkSize;

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    /** playerId → marchId 集合，避免「查某玩家的全部行军」时全表扫描。 */
    private final Map<String, java.util.Set<String>> byPlayer = new ConcurrentHashMap<>();

    public InMemoryMarchStore(int chunkSize) {
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂（chunk 键用位移算），实际="
                    + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    @Override
    public Optional<March> findById(String marchId) {
        Entry entry = marchId == null ? null : byId.get(marchId);
        return entry == null ? Optional.empty() : Optional.of(copyOf(entry.march.get()));
    }

    @Override
    public List<March> findByPlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        java.util.Set<String> ids = byPlayer.get(playerId);
        if (ids == null) {
            return List.of();
        }
        List<March> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Entry entry = byId.get(id);
            if (entry != null) {
                out.add(copyOf(entry.march.get()));
            }
        }
        // 按出发时刻升序：客户端要按出征顺序展示，而 ConcurrentHashMap 的迭代顺序不确定
        out.sort(Comparator.comparingLong(March::startAt).thenComparing(March::id));
        return out;
    }

    @Override
    public List<March> findByChunkKeys(List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(chunkKeys);
        List<March> out = new ArrayList<>();
        for (Entry entry : byId.values()) {
            March march = entry.march.get();
            if (wanted.contains(march.from().chunkKey(chunkSize))
                    || wanted.contains(march.to().chunkKey(chunkSize))) {
                out.add(copyOf(march));
            }
        }
        out.sort(Comparator.comparingLong(March::startAt).thenComparing(March::id));
        return out;
    }

    @Override
    public boolean insertIfAbsent(March march) {
        if (march == null) {
            throw new IllegalArgumentException("march 不得为 null");
        }
        Entry entry = new Entry(copyOf(march));
        if (byId.putIfAbsent(march.id(), entry) != null) {
            return false;
        }
        byPlayer.computeIfAbsent(march.playerId(), k -> ConcurrentHashMap.newKeySet())
                .add(march.id());
        return true;
    }

    @Override
    public long save(March march, long expectedVersion) {
        if (march == null) {
            throw new IllegalArgumentException("march 不得为 null");
        }
        Entry entry = byId.get(march.id());
        if (entry == null) {
            throw new IllegalStateException("行军不存在，无法更新：marchId=" + march.id());
        }
        synchronized (entry) {
            if (entry.version != expectedVersion) {
                throw new IllegalStateException("乐观锁冲突：marchId=" + march.id()
                        + "，存储版本=" + entry.version + "，提交版本=" + expectedVersion
                        + "。请重读行军后重试。");
            }
            entry.march.set(copyOf(march));
            return ++entry.version;
        }
    }

    @Override
    public long versionOf(String marchId) {
        Entry entry = byId.get(marchId);
        if (entry == null) {
            throw new IllegalStateException("行军不存在：marchId=" + marchId);
        }
        return entry.version;
    }

    @Override
    public void delete(String marchId) {
        if (marchId == null || marchId.isBlank()) {
            throw new IllegalArgumentException("marchId 不得为空");
        }
        Entry removed = byId.remove(marchId);
        if (removed != null) {
            java.util.Set<String> ids = byPlayer.get(removed.march.get().playerId());
            if (ids != null) {
                ids.remove(marchId);
                if (ids.isEmpty()) {
                    byPlayer.remove(removed.march.get().playerId());
                }
            }
        }
    }

    @Override
    public long activeCountOf(String playerId) {
        long count = 0L;
        for (March march : findByPlayerId(playerId)) {
            // 「在家」= 返程已到家且没有负载待卸。这里用状态判断：
            // STATIONED 且已到家（returnArriveAt 非空且已过期）的行军算已归还，
            // 但为了口径简单且不出现「明明回家了还占名额」，
            // 到家后由调用方 delete，所以这里凡是还在库里的都算在外
            count++;
        }
        return count;
    }

    /**
     * 深拷贝。March 可变，交出去必须是副本，否则乐观锁形同虚设。
     *
     * <p>实现只剩一次委托："一条行军的完整状态"（含最容易漏、且漏了整条链不报错的 {@code rallyId}）
     * 由 {@link March#snapshot()} / {@link March#fromSnapshot} 定义，内存版与 Mongo 版共用那一份。
     */
    static March copyOf(March source) {
        return March.fromSnapshot(source.snapshot());
    }

    public void clear() {
        byId.clear();
        byPlayer.clear();
    }

    public int size() {
        return byId.size();
    }
}
