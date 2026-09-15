package com.ironoath.web.store.memory;

import com.ironoath.web.season.SeasonLedgerStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：赛季账本的内存实现 —— dev / 单测零依赖启动。
 * 依赖：{@link SeasonLedgerStore} 端口。
 *
 * <p><b>它带着一个生产上不可接受的性质</b>：重启即清空，而账本是"这一季已经付过钱"的唯一凭据。
 * 所以 {@code ironoath.storage=mongo} 时换 {@code MongoSeasonLedger}，两侧跑同一份
 * {@code SeasonLedgerStoreEquivalenceTest}（含一条"换一个实例等于重启，第二次记不上"的用例）。
 *
 * <p>这里保留 {@link ConcurrentHashMap} 而不是 {@code LinkedHashMap}：结算可以分页并发跑，
 * 而"同一季同一人只记一次"这件事必须由 {@code putIfAbsent} 的原子性保证，不能靠外层锁。
 */
public class InMemorySeasonLedger implements SeasonLedgerStore {

    /** seasonId → playerId → 记录。 */
    private final Map<String, Map<String, Record>> bySeason = new ConcurrentHashMap<>();

    /**
     * 两个写方法都先过端口上那份校验（{@link SeasonLedgerStore#requireWriteKey}）：
     * 文案只有一份，两侧不可能各说各话；而 null 键在 Mongo 侧会被拼进 {@code _id} 留下垃圾档。
     */
    @Override
    public boolean recordIfAbsent(String seasonId, Record record) {
        SeasonLedgerStore.requireWriteKey(seasonId, record);
        Map<String, Record> page = bySeason.computeIfAbsent(seasonId, k -> new ConcurrentHashMap<>());
        return page.putIfAbsent(record.playerId(), record) == null;
    }

    @Override
    public void overwrite(String seasonId, Record record) {
        SeasonLedgerStore.requireWriteKey(seasonId, record);
        bySeason.computeIfAbsent(seasonId, k -> new ConcurrentHashMap<>())
                .put(record.playerId(), record);
    }

    @Override
    public Record find(String seasonId, String playerId) {
        if (seasonId == null || playerId == null) {
            return null;   // 读侧照旧宽容：查不到就是 null，与 Mongo 版同一条
        }
        Map<String, Record> page = bySeason.get(seasonId);
        return page == null ? null : page.get(playerId);
    }

    @Override
    public Map<String, Record> seasonRecords(String seasonId) {
        Map<String, Record> page = seasonId == null ? null : bySeason.get(seasonId);
        if (page == null) {
            return Map.of();
        }
        // 按 playerId 排序后返回：并发 map 的迭代顺序不定，不排序会让"同一份数据两次归档
        // 得到不同顺序"这种无关问题混进比对（Mongo 侧同一条口径）
        List<String> ids = new ArrayList<>(page.keySet());
        Collections.sort(ids);
        Map<String, Record> out = new LinkedHashMap<>();
        ids.forEach(id -> out.put(id, page.get(id)));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public Set<String> seasonIds() {
        return Set.copyOf(bySeason.keySet());
    }

    @Override
    public int purgeSeason(String seasonId) {
        Map<String, Record> removed = bySeason.remove(seasonId);
        return removed == null ? 0 : removed.size();
    }

    @Override
    public void clear() {
        bySeason.clear();
    }
}
