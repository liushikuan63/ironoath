package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.ironoath.core.march.MarchDueQueue;

/**
 * 职责：行军到期队列的 MongoDB 实现。
 * 依赖：Spring Data MongoDB。
 *
 * <p><b>与内存版逐条同语义</b>：
 * <ul>
 *   <li>{@code schedule}/{@code reschedule} 都是"按 marchId 覆盖到期时刻"的一次原子 upsert ——
 *       改期不是"取消 + 重新登记"，两步之间被扫描会漏掉这支队伍（B07 验收 2）</li>
 *   <li>{@code cancel} 幂等：撤销不存在的 id 不报错</li>
 *   <li>{@code dueBefore} 只读不删，删除由调用方处理完之后显式 cancel：
 *       取的时候就删会让处理途中失败的行军永远没人再处理</li>
 *   <li>没有任何定时器：队列只回答"now 之前到期的 id"，推进由请求驱动（B07 头号红线）</li>
 * </ul>
 *
 * <p><b>为什么不在行军本体集合上直接查</b>：行军文档是按玩家组织的（march 集合的索引是
 * playerId + state.startAt），"全服谁该到点了"需要另一条轴。到期集合的 dueAt 索引就是这条轴；
 * 把它放在同一集合上要么全表扫，要么给行军文档加一个只为扫描存在的第二入口。
 */
public final class MongoMarchDueQueue implements MarchDueQueue {

    private final MongoTemplate mongo;

    public MongoMarchDueQueue(MongoTemplate mongo) {
        this.mongo = Objects.requireNonNull(mongo, "mongo 不得为 null");
    }

    @Override
    public void schedule(String marchId, long dueAtMillis) {
        requireId(marchId);
        if (dueAtMillis <= 0L) {
            throw new IllegalArgumentException("到期时刻必须为正的服务端时间戳：" + dueAtMillis);
        }
        mongo.upsert(Query.query(Criteria.where("_id").is(marchId)),
                new Update().set("dueAt", dueAtMillis),
                MarchDueDocument.class, MarchDueDocument.COLLECTION);
    }

    @Override
    public void reschedule(String marchId, long dueAtMillis) {
        schedule(marchId, dueAtMillis);
    }

    @Override
    public void cancel(String marchId) {
        requireId(marchId);
        mongo.remove(Query.query(Criteria.where("_id").is(marchId)), MarchDueDocument.COLLECTION);
    }

    @Override
    public List<String> dueBefore(long nowMillis, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正，否则一次都取不出来：" + limit);
        }
        Query query = Query.query(Criteria.where("dueAt").lte(nowMillis))
                .with(Sort.by(Sort.Direction.ASC, "dueAt"))
                .limit(limit);
        List<String> out = new ArrayList<>();
        for (MarchDueDocument document : mongo.find(query, MarchDueDocument.class,
                MarchDueDocument.COLLECTION)) {
            out.add(document.marchId());
        }
        return List.copyOf(out);
    }

    @Override
    public int size() {
        return (int) mongo.count(new Query(), MarchDueDocument.COLLECTION);
    }

    private static void requireId(String marchId) {
        if (marchId == null || marchId.isBlank()) {
            throw new IllegalArgumentException("marchId 不得为空");
        }
    }
}