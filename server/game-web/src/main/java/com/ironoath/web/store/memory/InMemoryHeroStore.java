package com.ironoath.web.store.memory;

import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 职责：武将存档的内存实现（{@code ironoath.storage=memory}，缺省即此）。
 * 依赖：game-core 的端口与聚合。
 *
 * <p>与 {@code InMemoryInventoryStore} 同一套做法：
 * 读返回深拷贝、写走乐观锁、首次插入用 {@code putIfAbsent} 保证并发下只有一个成功。
 * MongoDB 版在 B16 补，届时本类继续服务 dev/test（零依赖启动）。
 */
public final class InMemoryHeroStore implements HeroRepository {

    private static final class Entry {
        private final AtomicReference<HeroRoster> roster;
        private long version;

        Entry(HeroRoster roster) {
            this.roster = new AtomicReference<>(roster);
        }
    }

    private final Map<String, Entry> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<HeroRoster> findByPlayerId(String playerId) {
        Entry entry = playerId == null ? null : byPlayer.get(playerId);
        return entry == null ? Optional.empty() : Optional.of(entry.roster.get().copy());
    }

    @Override
    public boolean insertIfAbsent(String playerId, HeroRoster roster) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (roster == null) {
            throw new IllegalArgumentException("roster 不得为 null");
        }
        return byPlayer.putIfAbsent(playerId, new Entry(roster.copy())) == null;
    }

    @Override
    public long save(String playerId, HeroRoster roster, long expectedVersion) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("武将存档不存在，无法更新：playerId=" + playerId);
        }
        if (roster == null) {
            throw new IllegalArgumentException("roster 不得为 null");
        }
        synchronized (entry) {
            if (entry.version != expectedVersion) {
                throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                        + "，存储版本=" + entry.version + "，提交版本=" + expectedVersion
                        + "。请重读武将存档后重试。");
            }
            entry.roster.set(roster.copy());
            return ++entry.version;
        }
    }

    @Override
    public long versionOf(String playerId) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("武将存档不存在：playerId=" + playerId);
        }
        return entry.version;
    }

    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }
}
