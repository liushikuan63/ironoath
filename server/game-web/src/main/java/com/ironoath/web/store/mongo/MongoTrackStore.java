package com.ironoath.web.store.mongo;

import com.ironoath.web.ops.TrackEventStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 职责：埋点与崩溃上报的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link TrackEventDocument}、{@link TrackCrashDocument}。
 *
 * <p><b>补的是 B16 §3 那句"上线后没有埋点数据等于运营侧瞎了"</b>：内存版重启即清空，
 * 而 D1/D3/D7/D30 留存与卡点流失率全部依赖这份数据；崩溃记录丢了就更没人能解释
 * "某个玩家为什么再也进不来"。
 *
 * <p><b>刻意没有条数上限</b>：{@code TRACK_STORE_MAX_EVENTS} 的 {@code why} 自己写着
 * "那是 dev 与单测的兜底而不是生产口径，生产按时间保留"。这里的 {@code serverTs} 索引
 * 就是为 {@link #purgeOlderThan} 准备的，保留期由调用方按
 * {@code DASHBOARD_RETENTION_DAYS} 的最大值换算（见 {@code TrackFlusher}）。
 *
 * <p><b>同一毫秒内并列的事件不承诺顺序</b>：内存版的顺序是 deque 的 LIFO，那是实现副产品
 * 而不是契约；这里按 {@code serverTs} 倒序 + {@code _id} 定序只是为了分页稳定。
 * 谁要真的排序规则，得先给事件加一个单调序号 —— 那是要定的口径，不在存储层偷偷决定。
 */
public final class MongoTrackStore implements TrackEventStore {

    private final MongoTemplate mongo;

    public MongoTrackStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public int saveBatch(List<TrackRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        List<TrackEventDocument> docs = new ArrayList<>(records.size());
        for (TrackRecord record : records) {
            if (record == null) {
                continue;   // 与内存版同一条：null 槽位跳过，不占写入数也不抛
            }
            docs.add(TrackEventDocument.of(record));
        }
        if (docs.isEmpty()) {
            return 0;
        }
        // 一次 insert 多文档 = 一次往返（端口存在的理由就是不许逐条 IO）。
        // 不用 insert(list, Class) 那个重载：它会把 record 里的 @Id 反填回实体，而 record 不可变
        mongo.insert(docs, TrackEventDocument.COLLECTION);
        return docs.size();
    }

    @Override
    public List<TrackRecord> recentOf(String playerId, int limit) {
        if (playerId == null || playerId.isBlank() || limit < 1) {
            return List.of();
        }
        List<TrackEventDocument> docs = mongo.find(Query.query(Criteria.where("playerId").is(playerId))
                        .with(Sort.by(Sort.Order.desc("serverTs"), Sort.Order.asc("_id")))
                        .limit(limit),
                TrackEventDocument.class, TrackEventDocument.COLLECTION);
        List<TrackRecord> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.event()));
        return out;
    }

    @Override
    public int eventCount() {
        return (int) mongo.count(new Query(), TrackEventDocument.COLLECTION);
    }

    @Override
    public int purgeOlderThan(long cutoffMillis) {
        return (int) mongo.remove(Query.query(Criteria.where("serverTs").lt(cutoffMillis)),
                TrackEventDocument.COLLECTION).getDeletedCount();
    }

    @Override
    public boolean saveCrash(CrashRecord crash) {
        if (crash == null) {
            throw new IllegalArgumentException("crash 不得为 null");
        }
        try {
            mongo.insert(TrackCrashDocument.of(crash), TrackCrashDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            // _id 就是 traceId：撞号即"这条证据已经在了"，与内存版 putIfAbsent 同一条
            return false;
        }
    }

    @Override
    public Optional<CrashRecord> findCrash(String traceId) {
        if (traceId == null) {
            return Optional.empty();
        }
        TrackCrashDocument doc = mongo.findById(traceId, TrackCrashDocument.class,
                TrackCrashDocument.COLLECTION);
        return Optional.ofNullable(doc).map(TrackCrashDocument::crash);
    }

    @Override
    public int purgeCrashesOlderThan(long cutoffMillis) {
        return (int) mongo.remove(Query.query(Criteria.where("serverTs").lt(cutoffMillis)),
                TrackCrashDocument.COLLECTION).getDeletedCount();
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), TrackEventDocument.COLLECTION);
        mongo.remove(new Query(), TrackCrashDocument.COLLECTION);
    }
}
