package com.ironoath.web.store.memory;

import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：城建仓储的内存实现 —— 供 dev 零依赖启动与集成测试使用。
 * 依赖：game-core 的 CityRepository 端口。
 *
 * <p>与 Mongo 实现保持相同语义：读写都是<b>深拷贝</b>，乐观锁版本比对失败即抛异常。
 * 深拷贝是必需的 —— 如果返回同一个可变实例，调用方不 save 也能改到「库里」的数据，
 * 单测就会掩盖「忘记持久化」这类 bug，而它在 Mongo 上会真实丢数据。
 */
public final class InMemoryCityStore implements CityRepository {

    private static final class Entry {
        private final CityState state;
        private final long version;

        Entry(CityState state, long version) {
            this.state = state;
            this.version = version;
        }
    }

    private final Map<String, Entry> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<CityState> findByPlayerId(String playerId) {
        Entry entry = playerId == null ? null : byPlayer.get(playerId);
        return entry == null ? Optional.empty() : Optional.of(copyOf(entry.state));
    }

    @Override
    public boolean insertIfAbsent(String playerId, CityState state) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        // putIfAbsent 是原子的：并发首次登录只有一个能插入成功
        return byPlayer.putIfAbsent(playerId, new Entry(copyOf(state), 0L)) == null;
    }

    @Override
    public long save(String playerId, CityState state, long expectedVersion) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("城建存档不存在，无法更新：playerId=" + playerId);
        }
        synchronized (entry) {
            // 重新读一次：拿到 entry 引用之后、进入临界区之前，可能已有别的请求保存过
            Entry current = byPlayer.get(playerId);
            if (current.version != expectedVersion) {
                throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                        + "，存储版本=" + current.version + "，提交版本=" + expectedVersion
                        + "。请重读存档后重试。");
            }
            // 存副本并递增版本：调用方后续改动不能影响已落库的数据
            long next = current.version + 1L;
            byPlayer.put(playerId, new Entry(copyOf(state), next));
            return next;
        }
    }

    @Override
    public long versionOf(String playerId) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("城建存档不存在：playerId=" + playerId);
        }
        return entry.version;
    }

    /**
     * 深拷贝城建存档。
     *
     * <p>实现只做一件事：调用领域里的 {@link CityState#snapshot()} / {@code fromSnapshot}。
     * 曾经这里是逐字段手写的重建代码 —— 那种私有副本在补第二种存储时会变成"两份什么算完整"，
     * 少抄一个字段的表现不是报错而是"换一种存储后某个字段静默不持久化"。
     */
    private static CityState copyOf(CityState source) {
        return CityState.fromSnapshot(source.snapshot());
    }

    /** 测试辅助：清空全部数据。 */
    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }

}
