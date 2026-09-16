package com.ironoath.web.store.mongo;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 职责：玩家仓储的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、game-core 的 PlayerRepository 端口。
 *
 * <p>用 {@link MongoTemplate} 而不是 Spring Data Repository（B01 开放问题 3 的决定）：
 * 本项目需要「按版本条件更新」的乐观锁、按 deviceId 唯一索引做原子插入、
 * 后续批次还要用 MongoDB 事务配合 Redisson 锁扣资源（B00 陷阱 3）。
 * 派生查询方法名那套抽象在这些场景下反而要绕回 Template，不如一开始就用 Template。
 *
 * <p>{@link #insertIfAbsent} 依赖 deviceId 的唯一索引来保证原子性 —— 绝不使用「先查后插」，
 * 那样两个并发请求会同时查到不存在然后都插入，产生一个设备两份存档。
 */
public final class MongoPlayerStore implements PlayerRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MongoPlayerStore.class);

    private final MongoTemplate mongo;

    public MongoPlayerStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<PlayerSave> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        PlayerDocument doc = mongo.findById(playerId, PlayerDocument.class, PlayerDocument.COLLECTION);
        return Optional.ofNullable(doc).map(PlayerDocumentMapper::toDomain);
    }

    @Override
    public Optional<PlayerSave> findByDeviceId(String deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        PlayerDocument doc = mongo.findOne(
                Query.query(Criteria.where("deviceId").is(deviceId)),
                PlayerDocument.class,
                PlayerDocument.COLLECTION);
        return Optional.ofNullable(doc).map(PlayerDocumentMapper::toDomain);
    }

    @Override
    public Map<String, PlayerSave> findByPlayerIds(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Map.of();
        }
        // 一次 $in 查询而不是 N 次 findById：目标搜索的候选池可能上千，
        // N 次往返的延迟会直接变成玩家点一次「搜索」的等待时间
        Set<String> ids = new LinkedHashSet<>();
        for (String id : playerIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<PlayerDocument> docs = mongo.find(
                Query.query(Criteria.where("_id").in(ids)),
                PlayerDocument.class,
                PlayerDocument.COLLECTION);
        Map<String, PlayerSave> out = new LinkedHashMap<>(docs.size());
        for (PlayerDocument doc : docs) {
            out.put(doc.playerId(), PlayerDocumentMapper.toDomain(doc));
        }
        return out;
    }

    @Override
    public boolean insertIfAbsent(PlayerSave save) {
        if (save == null) {
            throw new IllegalArgumentException("待插入的存档不得为 null");
        }
        try {
            mongo.insert(PlayerDocumentMapper.toDocument(save), PlayerDocument.COLLECTION);
            LOG.info("新建玩家存档成功 playerId={} deviceId={} nickName={}",
                    save.playerId(), save.deviceId(), save.nickName());
            return true;
        } catch (DuplicateKeyException e) {
            // deviceId 唯一索引冲突：说明并发请求已经建过号，交给调用方改读现有存档
            LOG.info("deviceId 已存在，放弃重复建号 deviceId={} playerId={}", save.deviceId(), save.playerId());
            return false;
        }
    }

    @Override
    public void save(PlayerSave save) {
        if (save == null) {
            throw new IllegalArgumentException("待保存的存档不得为 null");
        }
        PlayerDocument doc = PlayerDocumentMapper.toDocument(save);

        // 版本条件写入：只有存储中的 version 与提交的一致才更新，否则说明有并发写入
        Query query = Query.query(Criteria.where("_id").is(save.playerId())
                .and("version").is(save.version()));
        Update update = new Update()
                .set("nickName", doc.nickName())
                .set("avatarId", doc.avatarId())
                .set("lastLoginAt", doc.lastLoginAt())
                .set("cityLevel", doc.cityLevel())
                .set("resources", doc.resources())
                .set("power", doc.power())
                .set("pvp", doc.pvp())
                .set("protectUntil", doc.protectUntil())
                // 少这一行的症状不是报错，而是「内存开发一切正常、生产每次重启荣耀都清零」——
                // 两侧写同一批字段这条纪律由 PlayerStoreContractTest 的那条新用例守着
                .set("glory", doc.glory())
                // 引导进度同理：漏这一行的症状是「内存 dev 一切正常、生产每次重启把玩家弹回第 1 步」
                .set("guide", doc.guide())
                // 付费权益同理，而且这一位的代价是钱：漏写的症状是「买过月卡的号每次重启都变回没买过」，
                // 而 insertIfAbsent 走的是整篇文档，所以新号看起来一切正常 —— 只有更新路径在丢
                .set("paid", doc.paid())
                .inc("version", 1);

        UpdateResult result = mongo.updateFirst(query, update, PlayerDocument.class, PlayerDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(
                    Query.query(Criteria.where("_id").is(save.playerId())),
                    PlayerDocument.class,
                    PlayerDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("存档不存在，无法更新：playerId=" + save.playerId());
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + save.playerId()
                    + "，提交版本=" + save.version() + "。请重读存档后重试。");
        }
        save.incrementVersion();
    }

    @Override
    public void touchLogin(String playerId, long now) {
        // 条件里带 lastLoginAt < now：让「单调前进」由存储层原子保证。
        // 不读文档、不带 version 条件，因此并发登录之间不会互相冲突（见端口方法注释）。
        Query query = Query.query(Criteria.where("_id").is(playerId).and("lastLoginAt").lt(now));
        Update update = new Update().set("lastLoginAt", now);
        mongo.updateFirst(query, update, PlayerDocument.class, PlayerDocument.COLLECTION);
    }
}
