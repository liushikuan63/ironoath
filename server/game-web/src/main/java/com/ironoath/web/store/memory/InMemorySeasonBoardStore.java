package com.ironoath.web.store.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.season.SeasonBoardStore;

/**
 * 职责：赛季榜与快照的内存实现（dev / test）。
 * 依赖：无（两张 ConcurrentHashMap）。
 *
 * <p><b>它的丢失后果与账本不同，别混为一谈</b>：账本丢了是"重复发钱"（{@link InMemorySeasonLedger} 的注释），
 * 榜丢了是"按一张冷榜结算" —— 快照从空榜拍下来之后，账本会把这次错误的名次记成"已经付过"，
 * 于是本该拿奖的人永久拿不到。所以生产必须用 {@code mongo} 模式（见 {@code SeasonBeansConfig} 的告警）。
 *
 * <p>排序在<b>读</b>的时候做（存储只按 id 去重）：写路径是热调用，读路径一季才几次，
 * 而两个实现给出同一个顺序这件事由 {@code SeasonBoardStoreEquivalenceTest} 钉住。
 */
public final class InMemorySeasonBoardStore implements SeasonBoardStore {

    /** seasonId → board → (id → entry) */
    private final Map<String, Map<SeasonSettlement.Board, Map<String, SeasonSettlement.Entry>>> boards =
            new ConcurrentHashMap<>();
    /** seasonId:board → 快照。key 拼法与 mongo 实现的 _id 同一条，便于两边对着读 */
    private final Map<String, SeasonSettlement.Snapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void report(String seasonId, SeasonSettlement.Board board, SeasonSettlement.Entry entry) {
        SeasonBoardStore.requireKey(seasonId, board);
        if (entry == null) {
            throw new IllegalArgumentException("entry 不得为 null");
        }
        Map<String, SeasonSettlement.Entry> rows = boards
                .computeIfAbsent(seasonId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(board, k -> new ConcurrentHashMap<>());
        rows.put(entry.id(), entry);
    }

    @Override
    public List<SeasonSettlement.Entry> board(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        return sorted(boards.getOrDefault(seasonId, Map.of()).get(board));
    }

    @Override
    public int rankOf(String seasonId, SeasonSettlement.Board board, String playerId) {
        List<SeasonSettlement.Entry> entries = board(seasonId, board);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(playerId)) {
                return i + 1;
            }
        }
        return 0;
    }

    @Override
    public SeasonSettlement.Snapshot snapshot(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        return snapshots.get(keyOf(seasonId, board));
    }

    @Override
    public boolean saveSnapshotIfAbsent(String seasonId, SeasonSettlement.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不得为 null");
        }
        SeasonBoardStore.requireKey(seasonId, snapshot.board());
        return snapshots.putIfAbsent(keyOf(seasonId, snapshot.board()), snapshot) == null;
    }

    @Override
    public void clear() {
        boards.clear();
        snapshots.clear();
    }

    /** 与 {@code SeasonBoardSnapshotDocument} 的 _id 同一条拼法。 */
    static String keyOf(String seasonId, SeasonSettlement.Board board) {
        return seasonId + ":" + board;
    }

    /** 榜单排序的唯一实现（与领域层的口径逐字一致：分数降序、同分按 id 升序）。 */
    static List<SeasonSettlement.Entry> sorted(Map<String, SeasonSettlement.Entry> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<SeasonSettlement.Entry> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingLong(SeasonSettlement.Entry::score).reversed()
                .thenComparing(SeasonSettlement.Entry::id));
        return List.copyOf(out);
    }
}
