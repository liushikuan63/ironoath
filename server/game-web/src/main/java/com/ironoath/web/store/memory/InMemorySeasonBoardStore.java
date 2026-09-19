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
    /**
     * seasonId:board:dayKey → 每日快照。
     *
     * <p><b>为什么不与上面那张表共用</b>：裁决②明写每日快照与结算快照**不同集合**。
     * 共用一张表时两者的清理策略会互相牵制（结算快照只该在归档时消失，而每日快照还有保留期这回事），
     * 而且 {@code snapshot()} 会开始能把每日快照读成结算快照 —— 那是把"付钱依据"与"申诉时间线"
     * 混成一份数据，属于本档最不该出现的一种错。
     */
    private final Map<String, SeasonSettlement.Snapshot> dailies = new ConcurrentHashMap<>();

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
    public void accumulate(String seasonId, SeasonSettlement.Board board,
                           SeasonSettlement.Entry entry, long delta) {
        SeasonBoardStore.requireKey(seasonId, board);
        if (entry == null) {
            throw new IllegalArgumentException("entry 不得为 null");
        }
        if (delta < 0) {
            throw new IllegalArgumentException("累加型上报的增量不得为负，实际=" + delta);
        }
        Map<String, SeasonSettlement.Entry> rows = boards
                .computeIfAbsent(seasonId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(board, k -> new ConcurrentHashMap<>());
        // compute 的原子性由 ConcurrentHashMap 保证：同一格上的并发累加不会丢一笔
        rows.compute(entry.id(), (id, old) -> old == null
                ? new SeasonSettlement.Entry(id, entry.name(), delta)
                : new SeasonSettlement.Entry(id, entry.name(), old.score() + delta));
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
    public boolean saveDailyIfAbsent(String seasonId, SeasonSettlement.Board board, String dayKey,
                                     SeasonSettlement.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不得为 null");
        }
        SeasonBoardStore.requireKey(seasonId, board);
        SeasonBoardStore.requireDayKey(dayKey);
        return dailies.putIfAbsent(dailyKeyOf(seasonId, board, dayKey), snapshot) == null;
    }

    @Override
    public SeasonSettlement.Snapshot daily(String seasonId, SeasonSettlement.Board board,
                                           String dayKey) {
        SeasonBoardStore.requireKey(seasonId, board);
        SeasonBoardStore.requireDayKey(dayKey);
        return dailies.get(dailyKeyOf(seasonId, board, dayKey));
    }

    @Override
    public List<String> dailyDays(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        String prefix = seasonId + ":" + board + ":";
        List<String> days = new ArrayList<>();
        for (String key : dailies.keySet()) {
            if (key.startsWith(prefix)) {
                days.add(key.substring(prefix.length()));
            }
        }
        // 升序：调用方（错误详情的"最早一天"）要的就是最早那天，让它自己排序就等于把这个口径复制到第二处
        days.sort(Comparator.naturalOrder());
        return List.copyOf(days);
    }

    @Override
    public int purgeSeason(String seasonId) {
        int removed = 0;
        Map<SeasonSettlement.Board, Map<String, SeasonSettlement.Entry>> boardsOfSeason =
                boards.remove(seasonId);
        if (boardsOfSeason != null) {
            for (Map<String, SeasonSettlement.Entry> rows : boardsOfSeason.values()) {
                removed += rows.size();
            }
        }
        // 前缀必须带 : 这个终止符 —— 少了它，season_1 会把 season_10 的快照一起删掉，
        // 而那是删掉一个还在保留期内的赛季，不可恢复
        String prefix = seasonId + ":";
        List<String> keys = new ArrayList<>(snapshots.keySet());
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                snapshots.remove(key);
                removed++;
            }
        }
        // 每日快照是第四处：它不在 snapshots 那张表里（裁决②：与结算快照不同集合），
        // 所以上面那段前缀清理扫不到它 —— 漏掉这一段的表现是"申诉期早过了，时间线还占着存储"
        List<String> dailyKeys = new ArrayList<>(dailies.keySet());
        for (String key : dailyKeys) {
            if (key.startsWith(prefix)) {
                dailies.remove(key);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void clear() {
        boards.clear();
        snapshots.clear();
        dailies.clear();
    }

    /** 与 {@code SeasonBoardSnapshotDocument} 的 _id 同一条拼法。 */
    static String keyOf(String seasonId, SeasonSettlement.Board board) {
        return seasonId + ":" + board;
    }

    /** 与 {@code SeasonDailyBoardDocument} 的 _id 同一条拼法（第三段是日期键）。 */
    static String dailyKeyOf(String seasonId, SeasonSettlement.Board board, String dayKey) {
        return seasonId + ":" + board + ":" + dayKey;
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
