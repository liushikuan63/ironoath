package com.ironoath.web.store.memory;

import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 职责：背包仓储的内存实现 —— 供 dev 零依赖启动与单测使用。
 * 依赖：game-core 的 InventoryRepository 端口。
 *
 * <p>与 Mongo 实现保持相同语义：读写都是<b>副本</b>，乐观锁版本比对失败即抛异常。
 * 返回副本是必需的 —— 若返回同一个可变实例，调用方不 save 也能改到「库里」的数据，
 * 单测就会掩盖「忘记持久化」这类 bug，而它在 Mongo 上会真实丢道具。
 */
public final class InMemoryInventoryStore implements InventoryRepository {

    private static final class Entry {
        private final AtomicReference<Inventory> inventory;
        private long version;

        Entry(Inventory inventory) {
            this.inventory = new AtomicReference<>(inventory);
        }
    }

    private final Map<String, Entry> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<Inventory> findByPlayerId(String playerId) {
        Entry entry = playerId == null ? null : byPlayer.get(playerId);
        return entry == null ? Optional.empty() : Optional.of(entry.inventory.get().copy());
    }

    @Override
    public boolean insertIfAbsent(String playerId, Inventory inventory) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        // putIfAbsent 原子：并发首次登录只有一个能插入成功
        return byPlayer.putIfAbsent(playerId, new Entry(inventory.copy())) == null;
    }

    @Override
    public long save(String playerId, Inventory inventory, long expectedVersion) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("背包不存在，无法更新：playerId=" + playerId);
        }
        synchronized (entry) {
            if (entry.version != expectedVersion) {
                throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                        + "，存储版本=" + entry.version + "，提交版本=" + expectedVersion
                        + "。请重读背包后重试。");
            }
            entry.inventory.set(inventory.copy());
            return ++entry.version;
        }
    }

    @Override
    public long versionOf(String playerId) {
        Entry entry = byPlayer.get(playerId);
        if (entry == null) {
            throw new IllegalStateException("背包不存在：playerId=" + playerId);
        }
        return entry.version;
    }

    /** 测试辅助。 */
    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }
}
