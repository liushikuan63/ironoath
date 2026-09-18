package com.ironoath.web.store.mongo;

import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 职责：战报存储的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link BattleReportDocument}。
 *
 * <p><b>补的是 #16 里"玩家失去复盘线索"那一档</b>：内存版重启即丢，而 {@code BattlePlayback}
 * 放的就是这份档 —— 丢了不会有任何报错，只是列表空了。
 *
 * <p><b>{@link #save} 的幂等方向刻意与订单相反</b>：{@code MongoPayOrderStore.insert} 撞号要抛，
 * 因为"两笔支付共用一个幂等键"是必须有人来查的事故；而同一份战报被重复写入只是重放
 * （同一次结算的两个入口、或客户端重试触发的同一天同一场），端口写的是"重复视为幂等、不覆盖"，
 * 所以这里咽掉 {@link DuplicateKeyException} 返回。静默吞掉的只是"重复"，
 * 不是"写失败"—— 真写不进去（网络、库挂了）仍然照原样抛给调用方。
 *
 * <p>清理是显式 {@code remove} 而不是 TTL 索引：端口要返回"这次清了几条"，
 * 而 TTL 后台线程删的条数拿不到（{@code BattleReportService#purgeExpired} 的日志就靠这个数）。
 * 保留时长本身也已经是每条文档上的 {@code expiresAt}（来自 {@code BATTLE_REPORT_TTL_SECONDS}），
 * 换成 TTL 索引还会碰上"改表要改索引"的第二处配置，两边都不会报错。
 */
public final class MongoBattleReportStore implements BattleReportStore {

    private final MongoTemplate mongo;

    public MongoBattleReportStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(BattleReport report) {
        if (report == null) {
            throw new IllegalArgumentException("战报不得为 null");
        }
        try {
            mongo.insert(BattleReportDocument.fromDomain(report), BattleReportDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            // 幂等：同 reportId 已在册就不覆盖（内存版的 putIfAbsent 同一个语义）
        }
    }

    @Override
    public Optional<BattleReport> findById(String reportId) {
        if (reportId == null) {
            return Optional.empty();
        }
        BattleReportDocument doc = mongo.findById(reportId, BattleReportDocument.class,
                BattleReportDocument.COLLECTION);
        return Optional.ofNullable(doc).map(BattleReportDocument::toDomain);
    }

    @Override
    public List<BattleReport> reportsOf(String ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        // 排序必须和内存版逐键一致：倒序按 createdAt，同刻再按 reportId 定序。
        // 少了第二键，同一毫秒内的两份战报顺序就由 Mongo 自然序决定 ——
        // 表现是列表页刷新一次顺序变了，而两端各自测试都不会红
        List<BattleReportDocument> docs = mongo.find(
                Query.query(Criteria.where("ownerId").is(ownerId))
                        .with(Sort.by(Sort.Order.desc(BattleReportDocument.FIELD_CREATED_AT),
                                Sort.Order.asc("_id"))),
                BattleReportDocument.class, BattleReportDocument.COLLECTION);
        List<BattleReport> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.toDomain()));
        return out;
    }

    @Override
    public int purgeExpired(long nowMillis) {
        // 边界与内存版同一条：expiresAt <= now 算过期
        return (int) mongo.remove(Query.query(
                        Criteria.where(BattleReportDocument.FIELD_EXPIRES_AT).lte(nowMillis)),
                BattleReportDocument.COLLECTION).getDeletedCount();
    }

    @Override
    public void markShared(String reportId, String channelKey) {
        if (reportId == null || channelKey == null) {
            throw new IllegalArgumentException("reportId / channelKey 不得为 null");
        }
        // 战报不在册时 updateFirst 是空操作 —— 内存版显式判了 byId 的存在性来对齐这一语义，
        // 两边不一致的表现是"单测记得住一份不存在的战报，线上记不住"
        mongo.updateFirst(Query.query(Criteria.where("_id").is(reportId)),
                new Update().addToSet(BattleReportDocument.FIELD_SHARED_CHANNELS, channelKey),
                BattleReportDocument.COLLECTION);
    }

    @Override
    public List<String> sharedChannels(String reportId) {
        if (reportId == null) {
            return List.of();
        }
        // 只取分享账这一个字段：整份战报含逐回合明细，为了读一个频道键把几十 KB 拉回来不划算
        Query query = Query.query(Criteria.where("_id").is(reportId));
        query.fields().include(BattleReportDocument.FIELD_SHARED_CHANNELS);
        BattleReportDocument doc = mongo.findOne(query, BattleReportDocument.class,
                BattleReportDocument.COLLECTION);
        if (doc == null || doc.sharedChannels() == null || doc.sharedChannels().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(doc.sharedChannels());
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), BattleReportDocument.COLLECTION);
    }
}
