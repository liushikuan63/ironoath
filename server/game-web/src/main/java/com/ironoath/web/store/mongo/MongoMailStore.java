package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.ironoath.web.mail.MailStore;

/**
 * 职责：邮件存储的 MongoDB 实现（生产）。
 * 依赖：{@link MongoTemplate}、{@link MailDocument}。
 *
 * <p><b>{@link #claim} 是条件更新，不是「读出来看一眼再写回去」</b>：
 * 「同一封邮件被领两次」是重复发奖，而重复发奖在本项目里从来不是显示问题。
 * {@code updateFirst(match _id + playerId + mail.claimedAt 为空 + 未过期, set mail.claimedAt=now)}
 * 由 Mongo 在单文档级别保证原子，{@code modifiedCount == 1} 才是这一次领取的赢家。
 * 两个并发请求同时打进来时必然一个赢一个输，与节点数无关 ——
 * 这是内存版那把 {@code synchronized} 锁<b>替补不了</b>的那一半。
 *
 * <p><b>领取判定只读子文档里的字段</b>（{@code mail.claimedAt} / {@code mail.expireAt}）。
 * 文档级那两列冗余（{@code playerId} / {@code expireAt}）只为让列表与清理走索引：
 * 它们即便与子文档漂移，最坏下场是「过期的一封还被列出来」，而紧接着的领取会照子文档判 false ——
 * 方向是安全的。若把冗余列放进领取判定，漂移就会变成「重复发奖」，那才是不可接受的。
 *
 * <p>清理是显式 {@code remove} 而不是 TTL 索引：端口要返回「这次清了几条」好让 service 打日志，
 * 而 TTL 后台线程删掉的条数拿不到（与 {@code MongoBattleReportStore} 同一条取舍）。
 */
public final class MongoMailStore implements MailStore {

    private final MongoTemplate mongo;

    public MongoMailStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(MailRecord mail) {
        if (mail == null) {
            throw new IllegalArgumentException("邮件不得为 null");
        }
        try {
            mongo.insert(MailDocument.of(mail), MailDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            // 幂等：同一 mailId 已在册不覆盖（内存版 putIfAbsent 同一个语义）
        }
    }

    @Override
    public List<MailRecord> listOf(String playerId, long nowMillis) {
        if (playerId == null) {
            return List.of();
        }
        // 排序与内存版逐键一致：createdAt 倒序，同刻按 _id 定序。
        // 少第二键的话同一毫秒生成的两封（一键补发多条时真的会这样）顺序由自然序决定，
        // 症状是「刷新一次列表顺序变了」，而两端各自的测试都不会红
        List<MailDocument> docs = mongo.find(Query.query(
                        Criteria.where(MailDocument.FIELD_PLAYER_ID).is(playerId)
                                .and(MailDocument.FIELD_EXPIRE_AT).gt(nowMillis))
                        .with(Sort.by(Sort.Order.desc(MailDocument.FIELD_CREATED_AT),
                                Sort.Order.asc("_id"))),
                MailDocument.class, MailDocument.COLLECTION);
        List<MailRecord> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.toRecord()));
        return out;
    }

    @Override
    public Optional<MailRecord> findById(String playerId, String mailId) {
        if (playerId == null || mailId == null) {
            return Optional.empty();
        }
        MailDocument doc = mongo.findOne(Query.query(
                        Criteria.where("_id").is(mailId)
                                .and(MailDocument.FIELD_PLAYER_ID).is(playerId)),
                MailDocument.class, MailDocument.COLLECTION);
        return Optional.ofNullable(doc).map(MailDocument::toRecord);
    }

    @Override
    public boolean claim(String playerId, String mailId, long nowMillis) {
        if (playerId == null || mailId == null) {
            return false;
        }
        Update update = new Update().set(MailDocument.FIELD_CLAIMED_AT, nowMillis);
        // 三条判据与内存版 MailRecord#claimable 逐字对齐：没领过、没过期、**有附件**。
        // 少最后一条两侧就会分叉：等价测试实测到 Mongo 把一封纯公告标成已领而内存版拒绝 ——
        // 症状是那封公告从此显示「已领取」，而玩家从没领过任何东西
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(mailId)
                        .and(MailDocument.FIELD_PLAYER_ID).is(playerId)
                        .and(MailDocument.FIELD_CLAIMED_AT).is(null)
                        .and("mail.rewards.0").exists(true)
                        .and(MailDocument.FIELD_MAIL_EXPIRE_AT).gt(nowMillis)),
                update, MailDocument.COLLECTION).getModifiedCount() == 1L;
    }

    @Override
    public boolean releaseClaim(String playerId, String mailId) {
        if (playerId == null || mailId == null) {
            return false;
        }
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(mailId)
                        .and(MailDocument.FIELD_PLAYER_ID).is(playerId)
                        .and(MailDocument.FIELD_CLAIMED_AT).ne(null)),
                new Update().set(MailDocument.FIELD_CLAIMED_AT, null),
                MailDocument.COLLECTION).getModifiedCount() == 1L;
    }

    @Override
    public boolean markRead(String playerId, String mailId, long nowMillis) {
        if (playerId == null || mailId == null) {
            return false;
        }
        long modified = mongo.updateFirst(Query.query(Criteria.where("_id").is(mailId)
                        .and(MailDocument.FIELD_PLAYER_ID).is(playerId)
                        .and(MailDocument.FIELD_READ_AT).is(null)),
                new Update().set(MailDocument.FIELD_READ_AT, nowMillis),
                MailDocument.COLLECTION).getModifiedCount();
        if (modified == 1L) {
            return true;
        }
        // 没改动 ≠ 没命中：已读过的那一封再标一次端口承诺返回 true。
        // 过期判据在这里刻意不查 —— 过期的一封会在下一次读取/清理时消失，
        // 而"给一封已经不在列表里的邮件标了已读"没有任何下游后果
        return findById(playerId, mailId).isPresent();
    }

    @Override
    public int purgeExpired(long nowMillis) {
        // 边界与内存版同一条：expireAt <= now 算过期
        return (int) mongo.remove(Query.query(
                        Criteria.where(MailDocument.FIELD_EXPIRE_AT).lte(nowMillis)),
                MailDocument.COLLECTION).getDeletedCount();
    }

    @Override
    public int count() {
        return (int) mongo.count(new Query(), MailDocument.COLLECTION);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), MailDocument.COLLECTION);
    }
}
