package com.ironoath.web.store.memory;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.ArmyVersionConflictException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 职责：军队存档的内存实现（{@code ironoath.storage=memory}，缺省即此）。
 * 依赖：game-core 的端口与聚合。
 *
 * <p>与 {@code InMemoryCityStore}/{@code InMemoryHeroStore} 同一套做法：
 * 读返回副本、写走乐观锁、首次插入用 {@code putIfAbsent} 保证并发下只有一个成功。
 *
 * <p><b>副本必须是深拷贝</b>：ArmyState 里有三张可变的 Map，
 * 浅拷贝会让调用方直接改到库里那一份 —— 那样乐观锁版本号就形同虚设，
 * 因为「改完再 save」永远成功，而中间状态对所有读者可见。
 */
public final class InMemoryArmyStore implements ArmyRepository {

    private static final class Entry {
        private final AtomicReference<ArmyState> army;
        private volatile long version;

        Entry(ArmyState army) {
            this.army = new AtomicReference<>(army);
        }
    }

    private final Map<String, Entry> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<ArmyState> findByPlayerId(String playerId) {
        Entry entry = playerId == null ? null : byPlayer.get(playerId);
        if (entry == null) { return Optional.empty(); }
        synchronized (entry) {
            ArmyState copy = copyOf(entry.army.get());
            copy.bindRepositoryVersion(entry.version);
            return Optional.of(copy);
        }
    }

    @Override
    public boolean insertIfAbsent(String playerId, ArmyState army) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (army == null) {
            throw new IllegalArgumentException("army 不得为 null");
        }
        if (byPlayer.putIfAbsent(playerId, new Entry(copyOf(army))) != null) { return false; }
        army.bindRepositoryVersion(0L);
        return true;
    }

    @Override
    public long save(String playerId, ArmyState army, long expectedVersion) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("军队存档不存在，无法更新：playerId=" + playerId);
        }
        if (army == null) {
            throw new IllegalArgumentException("army 不得为 null");
        }
        synchronized (entry) {
            army.requireRepositoryVersion(expectedVersion);
            if (entry.version != expectedVersion) {
                throw new ArmyVersionConflictException("乐观锁冲突：playerId=" + playerId
                        + "，存储版本=" + entry.version + "，提交版本=" + expectedVersion
                        + "。请重读军队存档后重试。");
            }
            entry.army.set(copyOf(army));
            army.bindRepositoryVersion(++entry.version);
            return entry.version;
        }
    }

    @Override
    public long versionOf(String playerId) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("军队存档不存在：playerId=" + playerId);
        }
        return entry.version;
    }

    /**
     * 深拷贝。实现只做一件事：走领域里的 {@link ArmyState#snapshot()} / {@code fromSnapshot}，
     * 于是"什么是一份完整的军队存档"只有一份定义（这里曾经自己用八个 getter 现拼）。
     */
    private static ArmyState copyOf(ArmyState source) {
        return ArmyState.fromSnapshot(source.snapshot());
    }

    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }
}
