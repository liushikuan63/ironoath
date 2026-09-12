package com.ironoath.web.store.mongo;

import com.ironoath.core.stage.StageProgress;
import com.ironoath.core.stage.StageProgressRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

/**
 * 职责：章节进度仓储的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link StageProgressDocument}。
 *
 * <p><b>这一类状态丢不起的原因在 {@code StageAppService} 里</b>：首通判定读的就是这份进度
 * （{@code firstClear = attempt.won() && !clearedBefore}），而首通奖励只在这个判定为真时发。
 * 进度留在内存里，等于每次重启都给全服玩家重新发一遍首通奖 —— 那是可以直接把资源刷爆的口子，
 * 比"玩家星级没了"更严重（后者是客诉，前者是经济崩）。
 *
 * <p>四条契约（读返回副本、保存推进版本、过期版本被拒且不留半个写入、并发插入只有一个赢家）
 * 由 {@code StageStoreContractTest}（内存）与 {@code MongoStageProgressStoreContractTest}
 * （真实 Mongo）跑同一份断言保证。写入用「按 version 条件的 update」而不是整档 replace，
 * 理由与 {@link MongoCityStore} 同一条：{@code versionOf} 这种只读版本的调用不该付整档传输的代价。
 *
 * <p><b>不动调用方的对象</b>：{@code save} 只推进落库那份的版本，传进来的 {@code progress}
 * 仍停在它被读到的版本。于是按 {@code StageAppService.saveProgress} 的写法
 * （{@code save(playerId, p, p.version())}）连写两次，第二次会撞锁 —— 双写在这条路径上
 * 从来不是业务动作，而是忘记重读的 bug 症状，让它响比让它静默覆盖好。
 * 内存版原先会把入参的版本一起推进（那样第二次会静默成功），本轮已改成同一口径，
 * 两侧各有一条同名配对用例钉住这件事。
 */
public final class MongoStageProgressStore implements StageProgressRepository {

    private final MongoTemplate mongo;

    public MongoStageProgressStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<StageProgress> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        StageProgressDocument doc = mongo.findById(playerId, StageProgressDocument.class,
                StageProgressDocument.COLLECTION);
        return Optional.ofNullable(doc).map(StageProgressDocument::toDomain);
    }

    @Override
    public boolean insertIfAbsent(String playerId, StageProgress progress) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (progress == null) {
            throw new IllegalArgumentException("进度不得为 null");
        }
        // 靠 _id 唯一约束原子地"有则不动、无则插入"。写成先查后插会在并发首通时插出两份，
        // 而两份存档意味着同一个玩家的首通奖励可以被发两次
        try {
            mongo.insert(StageProgressDocument.fromDomain(playerId, progress),
                    StageProgressDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(String playerId, StageProgress progress, long expectedVersion) {
        if (progress == null) {
            throw new IllegalArgumentException("进度不得为 null");
        }
        Query query = Query.query(Criteria.where("_id").is(playerId).and("version").is(expectedVersion));
        Update update = new Update()
                .set("stages", StageProgressDocument.fromDomain(playerId, progress).stages())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, StageProgressDocument.class,
                StageProgressDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            Long stored = storedVersionOrNull(playerId);
            if (stored == null) {
                throw new IllegalStateException("进度不存在，无法更新：playerId=" + playerId
                        + "。先 insertIfAbsent 再 save");
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，存储版本=" + stored + "，提交版本=" + expectedVersion + "。请重读后重试。");
        }
        return expectedVersion + 1L;
    }

    /**
     * 内存版在缺档时回 0（"没写过就是第 0 版"），这里照它 —— 不抛。
     *
     * <p>刻意与 {@code MongoInventoryStore.versionOf}（缺档抛）不同：那处偏离是它的实现先写的，
     * 而本端口的方法契约就是"当前版本，缺档为 0"。跨存储真正要对齐的是内存版，
     * 因为契约测试拿内存版当参照。
     */
    @Override
    public long versionOf(String playerId) {
        Long stored = storedVersionOrNull(playerId);
        return stored == null ? 0L : stored;
    }

    /** 只投影 version 一列：冲突消息要报真实版本，而为此拖回整档不值。 */
    private Long storedVersionOrNull(String playerId) {
        Query query = Query.query(Criteria.where("_id").is(playerId));
        query.fields().include("version");
        StageProgressDocument doc = mongo.findOne(query,
                StageProgressDocument.class, StageProgressDocument.COLLECTION);
        return doc == null ? null : doc.version();
    }
}
