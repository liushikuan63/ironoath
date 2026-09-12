package com.ironoath.web.store.memory;

import com.ironoath.core.stage.StageProgress;
import com.ironoath.core.stage.StageProgressRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：章节进度仓储的内存实现 —— 供 dev 零依赖启动与全部单测使用。
 * 依赖：game-core 的 {@link StageProgressRepository} 端口。
 *
 * <p>与其它内存存储同一套语义：读写都返回<b>副本</b>，
 * 调用方改了不 save 就不生效 —— 这能提前暴露「忘记持久化」的 bug（Mongo 天然是这个语义）。
 */
public final class InMemoryStageProgressStore implements StageProgressRepository {

    private final Map<String, StageProgress> byPlayerId = new ConcurrentHashMap<>();

    @Override
    public Optional<StageProgress> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPlayerId.get(playerId)).map(StageProgress::copy);
    }

    @Override
    public boolean insertIfAbsent(String playerId, StageProgress progress) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (progress == null) {
            throw new IllegalArgumentException("progress 不得为 null");
        }
        return byPlayerId.putIfAbsent(playerId, progress.copy()) == null;
    }

    @Override
    public long save(String playerId, StageProgress progress, long expectedVersion) {
        if (progress == null) {
            throw new IllegalArgumentException("progress 不得为 null");
        }
        StageProgress stored = byPlayerId.get(playerId);
        if (stored == null) {
            throw new IllegalStateException("进度不存在，无法更新：playerId=" + playerId);
        }
        synchronized (stored) {
            if (stored.version() != expectedVersion) {
                throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                        + "，存储版本=" + stored.version() + "，提交版本=" + expectedVersion
                        + "。请重读后重试。");
            }
            // 版本在"要放进表的那份副本"上推进，不动调用方的对象：
            // 本类自己声明的就是"读写都返回副本"，而 MongoStageProgressStore 也不动入参 ——
            // 推进入参会让 save(p, p.version()) 连写两次静默成功，那正是乐观锁要拦的形状
            StageProgress next = progress.copy();
            next.incrementVersion();
            byPlayerId.put(playerId, next);
            return next.version();
        }
    }

    @Override
    public long versionOf(String playerId) {
        // 与 Mongo 版同一条：null 不是"库里第 0 版"的合法键，但也不该抛 NPE ——
        // ConcurrentHashMap.get(null) 会炸，而 findByPlayerId(null) 却好好返回 empty，
        // 同一个类里两种脾气，调用方按哪一处写都不对
        if (playerId == null) {
            return 0L;
        }
        StageProgress stored = byPlayerId.get(playerId);
        return stored == null ? 0L : stored.version();
    }

    /** 测试辅助：清空。 */
    public void clear() {
        byPlayerId.clear();
    }

    public int size() {
        return byPlayerId.size();
    }
}
