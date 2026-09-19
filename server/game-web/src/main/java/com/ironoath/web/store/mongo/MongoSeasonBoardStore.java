package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.season.SeasonBoardStore;

/**
 * 职责：赛季榜与快照的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、两个文档类。
 *
 * <p><b>这一档补的是「按冷榜结算」</b>：榜与快照原先都在进程里，重启之后
 * {@code POST /season/settle}（换一个 requestId 就能再跑）会从一张空榜拍快照，
 * 而快照不可重拍 + 账本把这次发奖记成"已经付过" ⇒ 名次算错的那批人永久拿不到。
 * 落库之后同样的输入重跑得到同样的结果 —— 这才是 B14 §3「结算按快照」的完整含义。
 *
 * <p><b>{@code saveSnapshotIfAbsent} 的原子性靠 {@code _id} 唯一约束</b>：撞号即"已经拍过"。
 * 方向与 {@code MongoSeasonLedger.recordIfAbsent} 一致（都是"重复 = 有人先做了这件事"），
 * 与 {@code MongoPayOrderStore.insert} 相反（订单号重复是必须有人来查的事故）。
 */
public final class MongoSeasonBoardStore implements SeasonBoardStore {

    private final MongoTemplate mongo;

    public MongoSeasonBoardStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void report(String seasonId, SeasonSettlement.Board board, SeasonSettlement.Entry entry) {
        SeasonBoardStore.requireKey(seasonId, board);
        if (entry == null) {
            throw new IllegalArgumentException("entry 不得为 null");
        }
        // save 走 _id upsert：同一个人重复上报只改自己那一行（写路径是热调用，不做整榜重写）
        mongo.save(SeasonBoardDocument.of(seasonId, board, entry), SeasonBoardDocument.COLLECTION);
    }

    @Override
    public void accumulate(String seasonId, SeasonSettlement.Board board,
                           SeasonSettlement.Entry entry, long delta) {
        SeasonBoardStore.requireKey(seasonId, board);
        if (entry == null) {
            throw new IllegalArgumentException("entry 不得为 null");
        }
        if (delta < 0) {
            throw new IllegalArgumentException("累加型上报的增量不得为负，实际=" + delta);
        }
        // upsert + $inc：一次原子操作同时覆盖"已有这一行"与"还没有这一行"，
        // 分两步写会在两个人同时结算时插出两条同一 _id 的文档（第二个人报 DuplicateKey）。
        //
        // $setOnInsert 那三个字段是必需的，不是冗余：查询只按 _id 匹配，而 Mongo 插入新文档时
        // 只从查询的等值条件里取材 —— 少了它们，新插入的这一行只有 _id/score/name，
        // 而 board() 是按 seasonId+board 查的，于是"第一次击杀的人永远不上榜"。
        // 用 $setOnInsert 而不是 $set：那是这三个字段**只该在插入时写**（已有行上写同样值是白写）。
        mongo.upsert(Query.query(Criteria.where("_id")
                        .is(SeasonBoardDocument.keyOf(seasonId, board, entry.id()))),
                new Update().inc("score", delta).set("name", entry.name())
                        .setOnInsert("seasonId", seasonId)
                        .setOnInsert("board", board.name())
                        .setOnInsert("playerId", entry.id()),
                SeasonBoardDocument.class, SeasonBoardDocument.COLLECTION);
    }

    @Override
    public List<SeasonSettlement.Entry> board(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        Query query = Query.query(Criteria.where("seasonId").is(seasonId)
                        .and("board").is(board.name()))
                .with(Sort.by(Sort.Order.desc("score"), Sort.Order.asc("playerId")));
        return mongo.find(query, SeasonBoardDocument.class, SeasonBoardDocument.COLLECTION)
                .stream().map(SeasonBoardDocument::toEntry).toList();
    }

    @Override
    public int rankOf(String seasonId, SeasonSettlement.Board board, String playerId) {
        SeasonBoardStore.requireKey(seasonId, board);
        if (playerId == null || playerId.isBlank()) {
            return 0;
        }
        SeasonBoardDocument self = mongo.findById(
                SeasonBoardDocument.keyOf(seasonId, board, playerId),
                SeasonBoardDocument.class, SeasonBoardDocument.COLLECTION);
        if (self == null) {
            return 0;
        }
        // 名次 = 比我分数高的人数 + 1。同分按 id 升序算名次，所以同分里 id 比我小的也要算在头上 ——
        // 与内存版的"排序后找下标"必须是同一个答案，否则两个实现会给出差一位的名次。
        // 注意 Criteria.orOperator 会**替换**它前面那条链，所以必须用 andOperator 把
        // 「本季本榜」与「$or(分数更高 / 同分且 id 更小)」分开写 —— 写成链式会静默丢掉前两个条件，
        // 变成跨赛季、跨榜一起数（名次偏大，且没有任何东西会红）
        Criteria scope = Criteria.where("seasonId").is(seasonId).and("board").is(board.name());
        Criteria aheadOfMe = new Criteria().orOperator(
                Criteria.where("score").gt(self.score()),
                Criteria.where("score").is(self.score()).and("playerId").lt(playerId));
        Query ahead = Query.query(new Criteria().andOperator(scope, aheadOfMe));
        return (int) mongo.count(ahead, SeasonBoardDocument.COLLECTION) + 1;
    }

    @Override
    public SeasonSettlement.Snapshot snapshot(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        SeasonBoardSnapshotDocument doc = mongo.findById(
                SeasonBoardSnapshotDocument.keyOf(seasonId, board),
                SeasonBoardSnapshotDocument.class, SeasonBoardSnapshotDocument.COLLECTION);
        return doc == null ? null : doc.toSnapshot();
    }

    @Override
    public boolean saveSnapshotIfAbsent(String seasonId, SeasonSettlement.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不得为 null");
        }
        SeasonBoardStore.requireKey(seasonId, snapshot.board());
        try {
            mongo.insert(SeasonBoardSnapshotDocument.of(seasonId, snapshot),
                    SeasonBoardSnapshotDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean saveDailyIfAbsent(String seasonId, SeasonSettlement.Board board, String dayKey,
                                     SeasonSettlement.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不得为 null");
        }
        SeasonBoardStore.requireKey(seasonId, board);
        SeasonBoardStore.requireDayKey(dayKey);
        try {
            mongo.insert(SeasonDailyBoardDocument.of(seasonId, board, dayKey, snapshot),
                    SeasonDailyBoardDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            // 撞号 = 今天已经有人拍过了。与 saveSnapshotIfAbsent 同一条语义：
            // 并发下的两个读请求只会有一份留下，另一份丢掉即可（内容本该一致）
            return false;
        }
    }

    @Override
    public SeasonSettlement.Snapshot daily(String seasonId, SeasonSettlement.Board board,
                                           String dayKey) {
        SeasonBoardStore.requireKey(seasonId, board);
        SeasonBoardStore.requireDayKey(dayKey);
        SeasonDailyBoardDocument doc = mongo.findById(
                SeasonDailyBoardDocument.keyOf(seasonId, board, dayKey),
                SeasonDailyBoardDocument.class, SeasonDailyBoardDocument.COLLECTION);
        return doc == null ? null : doc.toSnapshot();
    }

    @Override
    public List<String> dailyDays(String seasonId, SeasonSettlement.Board board) {
        SeasonBoardStore.requireKey(seasonId, board);
        // 只取 dayKey 一列（每天一份文档，每份里嵌着整榜，读回来再丢是白花带宽）。
        //
        // **这里刻意用原始 Document 而不是映射回 SeasonDailyBoardDocument**：record 的构造器
        // 要全部参数，字段裁剪之后映射器会拿 null 去凑，直接抛 MappingInstantiationException
        // （本实现的第一版就是这么炸的 —— 而它只在"库里真有数据"时才炸，等价用例正是因为这个才值钱）。
        Query query = Query.query(Criteria.where("seasonId").is(seasonId)
                        .and("board").is(board.name()))
                .with(Sort.by(Sort.Order.asc("dayKey")));
        query.fields().include("dayKey");
        return mongo.find(query, org.bson.Document.class, SeasonDailyBoardDocument.COLLECTION)
                .stream().map(doc -> doc.getString("dayKey")).toList();
    }

    @Override
    public int purgeSeason(String seasonId) {
        // 三个集合都要删：只删榜不删快照，表现是旧季的名次查不到了但"那一季最终榜"还能被读出来，
        // 一个说不清自己保留了什么的库比一个明确没清的库更难查；每日快照是第四个面（B23 裁决②），
        // 漏掉它的症状是"申诉期早过了，时间线还占着存储"
        long entries = mongo.remove(Query.query(Criteria.where("seasonId").is(seasonId)),
                SeasonBoardDocument.COLLECTION).getDeletedCount();
        long snapshots = mongo.remove(Query.query(Criteria.where("seasonId").is(seasonId)),
                SeasonBoardSnapshotDocument.COLLECTION).getDeletedCount();
        long dailies = mongo.remove(Query.query(Criteria.where("seasonId").is(seasonId)),
                SeasonDailyBoardDocument.COLLECTION).getDeletedCount();
        return Math.toIntExact(entries + snapshots + dailies);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), SeasonBoardDocument.COLLECTION);
        mongo.remove(new Query(), SeasonBoardSnapshotDocument.COLLECTION);
        mongo.remove(new Query(), SeasonDailyBoardDocument.COLLECTION);
    }
}
