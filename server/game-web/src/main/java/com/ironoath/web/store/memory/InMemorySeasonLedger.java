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
     * seasonId:playerId → 已花掉的赛季币（B24 裁决① 的赛季商店）。
     *
     * <p>与记录分开一张表、但**键同构**：记录那边 putIfAbsent 之后不可改，花掉的部分只增不减，
     * 于是「余额 = 记录里发的 − 这里花的」在任何时刻都只有一个答案。
     * 两件事都放进同一个文档（Mongo 侧）或同一把 compute（内存侧），"校验 + 扣减"才是原子的。
     */
    private final Map<String, Long> spent = new ConcurrentHashMap<>();

    /** 与 Mongo 文档的 {@code _id} 同一条拼法：两边对着读时不用做心算映射。 */
    static String keyOf(String seasonId, String playerId) {
        return seasonId + ":" + playerId;
    }

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
    public long spentOf(String seasonId, String playerId) {
        requireSpendArgs(seasonId, playerId, 1L);
        return spent.getOrDefault(keyOf(seasonId, playerId), 0L);
    }

    @Override
    public boolean spend(String seasonId, String playerId, long amount) {
        requireSpendArgs(seasonId, playerId, amount);
        Record record = find(seasonId, playerId);
        if (record == null) {
            return false;   // 没结算过 = 没有可花的币（不是错误，是余额为 0）
        }
        // compute 的原子性由 ConcurrentHashMap 保证：两个人同时买不会双花
        long[] leftAfter = { -1L };
        spent.compute(keyOf(seasonId, playerId), (key, old) -> {
            long used = old == null ? 0L : old;
            if (used + amount > record.seasonCoin()) {
                return old;   // 余额不足：原样保留，交给下面的判定
            }
            leftAfter[0] = record.seasonCoin() - used - amount;
            return used + amount;
        });
        return leftAfter[0] >= 0L;
    }

    private static void requireSpendArgs(String seasonId, String playerId, long amount) {
        if (seasonId == null || seasonId.isBlank() || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("赛季币的季与人都不得为空");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("扣减金额必须为正，实际=" + amount);
        }
    }

    @Override
    public int purgeSeason(String seasonId) {
        Map<String, Record> removed = bySeason.remove(seasonId);
        // 花掉的那本账也要一起删：留着它，下一季若复用同一个 seasonId（重开季）就会带着上一轮的消费记录
        String prefix = seasonId + ":";
        List<String> staleKeys = new ArrayList<>();
        for (String key : spent.keySet()) {
            if (key.startsWith(prefix)) {
                staleKeys.add(key);
            }
        }
        staleKeys.forEach(spent::remove);
        return removed == null ? 0 : removed.size();
    }

    @Override
    public void clear() {
        bySeason.clear();
        spent.clear();
    }
}
