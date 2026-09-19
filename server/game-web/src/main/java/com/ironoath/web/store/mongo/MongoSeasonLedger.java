package com.ironoath.web.store.mongo;

import com.ironoath.web.season.SeasonLedgerStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：赛季账本的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link SeasonLedgerDocument}。
 *
 * <p><b>这一档补的是一个重复发钱的洞</b>：账本记的就是"这一季这个人已经付过"，
 * 而它原先只在进程里 —— 重启之后账本空了，运维再点一次结算（用的是新 requestId，
 * 幂等键只挡同一个请求的重放）就会给同一批人再发一遍金币，发出去的收不回来。
 *
 * <p>{@link #recordIfAbsent} 的原子性靠 {@code _id} 唯一约束：撞号就是"已经记过"，返回 false。
 * <b>这里的方向与 {@code MongoBattleReportStore.save} 相同、与 {@code MongoPayOrderStore.insert}
 * 相反</b> —— 账本重复写入只可能是同一季同一个人的重放，咽掉即可；而订单号重复是必须有人来查的事故。
 */
public final class MongoSeasonLedger implements SeasonLedgerStore {

    private final MongoTemplate mongo;

    public MongoSeasonLedger(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean recordIfAbsent(String seasonId, Record record) {
        SeasonLedgerStore.requireWriteKey(seasonId, record);
        try {
            mongo.insert(SeasonLedgerDocument.of(seasonId, record), SeasonLedgerDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void overwrite(String seasonId, Record record) {
        SeasonLedgerStore.requireWriteKey(seasonId, record);
        mongo.save(SeasonLedgerDocument.of(seasonId, record), SeasonLedgerDocument.COLLECTION);
    }

    @Override
    public Record find(String seasonId, String playerId) {
        if (seasonId == null || playerId == null) {
            return null;   // 与内存版同一条：读侧宽容，null 键就是"没有这条记录"
        }
        SeasonLedgerDocument doc = mongo.findById(SeasonLedgerDocument.keyOf(seasonId, playerId),
                SeasonLedgerDocument.class, SeasonLedgerDocument.COLLECTION);
        return doc == null ? null : doc.record();
    }

    @Override
    public Map<String, Record> seasonRecords(String seasonId) {
        if (seasonId == null) {
            return Map.of();
        }
        List<SeasonLedgerDocument> docs = mongo.find(
                Query.query(Criteria.where("seasonId").is(seasonId))
                        .with(Sort.by(Sort.Direction.ASC, "playerId")),
                SeasonLedgerDocument.class, SeasonLedgerDocument.COLLECTION);
        Map<String, Record> out = new LinkedHashMap<>();
        docs.forEach(d -> out.put(d.playerId(), d.record()));
        return java.util.Collections.unmodifiableMap(out);
    }

    @Override
    public Set<String> seasonIds() {
        return Set.copyOf(mongo.findDistinct(new Query(), "seasonId",
                SeasonLedgerDocument.COLLECTION, String.class));
    }

    @Override
    public long spentOf(String seasonId, String playerId) {
        if (seasonId == null || playerId == null) {
            return 0L;
        }
        SeasonLedgerDocument doc = mongo.findById(SeasonLedgerDocument.keyOf(seasonId, playerId),
                SeasonLedgerDocument.class, SeasonLedgerDocument.COLLECTION);
        return doc == null ? 0L : doc.spentOrZero();
    }

    @Override
    public boolean spend(String seasonId, String playerId, long amount) {
        if (seasonId == null || seasonId.isBlank() || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("赛季币的季与人都不得为空");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("扣减金额必须为正，实际=" + amount);
        }
        SeasonLedgerDocument doc = mongo.findById(SeasonLedgerDocument.keyOf(seasonId, playerId),
                SeasonLedgerDocument.class, SeasonLedgerDocument.COLLECTION);
        if (doc == null) {
            return false;   // 没结算过 ⇒ 没有可花的币
        }
        long cap = doc.record().seasonCoin() - amount;   // 扣完这一笔之后 spent 的上界
        if (cap < 0L) {
            return false;
        }
        // 一条带守卫的原子自增：条件里**必须显式包含"spent 字段不存在"那一支** ——
        // 这一列是后加的，老文档没有它，而 Mongo 的比较运算符对缺失字段一律不匹配，
        // 漏了那一支的症状是"老号永远买不成"，而且只有老号会中招（新号有字段，测不出来）
        Query guarded = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(SeasonLedgerDocument.keyOf(seasonId, playerId)),
                new Criteria().orOperator(
                        Criteria.where("spent").exists(false),
                        Criteria.where("spent").lte(cap))));
        var result = mongo.findAndModify(guarded,
                new org.springframework.data.mongodb.core.query.Update().inc("spent", amount),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                SeasonLedgerDocument.class, SeasonLedgerDocument.COLLECTION);
        // 有返回 = 守卫通过、这笔扣成了；没有 = 余额不够（或这一刻被另一个人先扣走了）
        return result != null;
    }

    @Override
    public int purgeSeason(String seasonId) {
        // 按 seasonId 整季删：保留策略的口径是"几个赛季"，不是"多少天"
        long deleted = mongo.remove(Query.query(Criteria.where("seasonId").is(seasonId)),
                SeasonLedgerDocument.COLLECTION).getDeletedCount();
        return Math.toIntExact(deleted);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), SeasonLedgerDocument.COLLECTION);
    }
}
