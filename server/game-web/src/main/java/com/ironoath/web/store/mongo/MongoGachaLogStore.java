package com.ironoath.web.store.mongo;

import com.ironoath.core.gacha.GachaLogStore;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 职责：抽卡日志的 MongoDB 实现（B06 §6 合规凭证）。
 * 依赖：Spring Data MongoDB、{@link GachaLogBatchDocument}。
 *
 * <p>三条语义必须与内存实现逐条对齐（{@code GachaLogStoreContractTest} 跑同一份断言）：
 * <ol>
 *   <li><b>一批全写或全不写</b>：一个批次一个文档，原子性来自单文档写入，不依赖事务与副本集；</li>
 *   <li><b>按时间升序返回</b>：合规核查与客服回放都要按发生顺序看，顺序错了就等于
 *       "他先出的 SSR 还是先出的 UP"这种问题答错；</li>
 *   <li><b>清理要返回删掉的条数</b>：审计要的是"删了多少条、删的是哪段时间"，
 *       这个数不能是猜的（内存版用"删前总数 - 删后总数"求差，这里用逐批计数）。</li>
 * </ol>
 *
 * <p>批次时间两端字段只用来裁剪候选集：查询取 {@code lastDrawnAt >= since}、清理取
 * {@code firstDrawnAt < cutoff}，两种都不会漏掉半新半旧批次里的条目；逐条判定仍以每条自己的
 * {@code drawnAt} 为准（那才是 {@code Entry} 里的权威值）。
 */
public final class MongoGachaLogStore implements GachaLogStore {

    private final MongoTemplate mongo;

    public MongoGachaLogStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void appendAll(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String playerId = entries.get(0).playerId();
        for (Entry entry : entries) {
            if (!entry.playerId().equals(playerId)) {
                // 必须在写入之前拒绝：一批混了两个玩家时若已经落库，就是有一方的日志凭空消失了一半
                throw new IllegalArgumentException("一批日志必须属于同一个玩家，混入了 "
                        + entry.playerId() + " 与 " + playerId);
            }
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Entry entry : entries) {
            first = Math.min(first, entry.drawnAt());
            last = Math.max(last, entry.drawnAt());
        }
        mongo.insert(new GachaLogBatchDocument(UUID.randomUUID().toString(), playerId, first, last,
                List.copyOf(entries)), GachaLogBatchDocument.COLLECTION);
    }

    @Override
    public List<Entry> query(String playerId, long sinceMillis) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        List<GachaLogBatchDocument> batches = mongo.find(
                Query.query(Criteria.where("playerId").is(playerId)
                        .and("lastDrawnAt").gte(sinceMillis))
                        .with(Sort.by(Sort.Direction.ASC, "firstDrawnAt")),
                GachaLogBatchDocument.class, GachaLogBatchDocument.COLLECTION);
        List<Entry> out = new ArrayList<>();
        for (GachaLogBatchDocument batch : batches) {
            for (Entry entry : batch.entries()) {
                if (entry.drawnAt() >= sinceMillis) {
                    out.add(entry);
                }
            }
        }
        // 再排一次：批次之间的顺序不等于条目顺序（同一批里时间也可能不同）
        out.sort(Comparator.comparingLong(Entry::drawnAt));
        return List.copyOf(out);
    }

    @Override
    public int purgeBefore(long cutoffMillis) {
        List<GachaLogBatchDocument> candidates = mongo.find(
                Query.query(Criteria.where("firstDrawnAt").lt(cutoffMillis)),
                GachaLogBatchDocument.class, GachaLogBatchDocument.COLLECTION);
        int removed = 0;
        for (GachaLogBatchDocument batch : candidates) {
            List<Entry> kept = new ArrayList<>();
            for (Entry entry : batch.entries()) {
                if (entry.drawnAt() >= cutoffMillis) {
                    kept.add(entry);
                }
            }
            removed += batch.entries().size() - kept.size();
            if (kept.isEmpty()) {
                // 条件必须写 _id：@Id 字段在服务端文档里就叫 _id。写 "batchId" 不报错，
                // 只是什么都删不掉 —— 保留期清理会静默失效，而它正是"日志会不会涨爆"的那道闸
                mongo.remove(Query.query(Criteria.where("_id").is(batch.batchId())),
                        GachaLogBatchDocument.class, GachaLogBatchDocument.COLLECTION);
            } else if (kept.size() != batch.entries().size()) {
                long first = Long.MAX_VALUE;
                long last = Long.MIN_VALUE;
                for (Entry entry : kept) {
                    first = Math.min(first, entry.drawnAt());
                    last = Math.max(last, entry.drawnAt());
                }
                mongo.save(new GachaLogBatchDocument(batch.batchId(), batch.playerId(), first, last,
                        List.copyOf(kept)), GachaLogBatchDocument.COLLECTION);
            }
        }
        return removed;
    }

    /** 观测用（测试与运维）：当前存了多少批。条数要看 query，不从这里推。 */
    public long batchCount() {
        return mongo.count(new Query(), GachaLogBatchDocument.class, GachaLogBatchDocument.COLLECTION);
    }
}
