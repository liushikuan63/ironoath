package com.ironoath.web.store.mongo;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.scout.ScoutReport;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 职责：世界状态的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link MarchRepository}、五个文档类。
 *
 * <p><b>这是 #16 里最后那一档"重启后玩家的东西还在不在"</b>：城位置丢了，玩家上线看到的是一句
 * "找不到你的城"然后被随机重新落位（世界坐标变了，邻居、行军目标、集结点全部对不上）；
 * 消耗格丢了，被采空的资源点与被打掉的野怪会原地复活，而 B07 明写刷新必须是显式运营行为。
 *
 * <p><b>与内存版最不一样的是"谁占哪一格"的表示</b>：内存版有两份索引（玩家→格、格→玩家），
 * 迁城要记得改两处并做条件解绑；这里一份文档同时是"这个玩家的城"和"这格被这个人占着"
 * （{@code _id=playerId} + {@code coordKey} 唯一索引），于是迁城是一次原子改档，
 * <b>结构上不存在"改了这边忘了那边"的幽灵城</b>。内存版保留两份索引（它没有唯一索引可用），
 * 但两处必须给出的答案由 {@code WorldStoreEquivalenceTest} 逐条对齐。
 *
 * <p>行军不在这里存：{@link #marchesInChunks} 与内存版一样是转发给 {@link MarchRepository}，
 * 那边的 chunk 键列已经建好索引 —— 在两个集合里各存一份行军就是两份真相，
 * 而其中一份会先过期。
 */
public final class MongoWorldStore implements WorldRepository {

    private final MongoTemplate mongo;
    private final MarchRepository marches;
    private final int chunkSize;

    public MongoWorldStore(MongoTemplate mongo, MarchRepository marches, int chunkSize) {
        if (mongo == null) {
            throw new IllegalArgumentException("mongo 不得为 null");
        }
        if (marches == null) {
            throw new IllegalArgumentException("marches 不得为 null");
        }
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂，实际=" + chunkSize
                    + "。chunk 键用位移计算，非 2 的幂会让相邻格子被算进不同的块");
        }
        this.mongo = mongo;
        this.marches = marches;
        this.chunkSize = chunkSize;
    }

    // ---------- 玩家城位置 ----------

    @Override
    public Optional<Coord> cityOf(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findById(playerId, WorldCityDocument.class,
                WorldCityDocument.COLLECTION)).map(MongoWorldStore::toCoord);
    }

    @Override
    public Optional<String> cityAt(Coord coord) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        WorldCityDocument doc = mongo.findOne(Query.query(
                Criteria.where("coordKey").is(coord.storageKey())),
                WorldCityDocument.class, WorldCityDocument.COLLECTION);
        return Optional.ofNullable(doc).map(WorldCityDocument::playerId);
    }

    @Override
    public boolean placeCity(String playerId, Coord coord) {
        requirePlayer(playerId);
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        if (mongo.findById(playerId, WorldCityDocument.class, WorldCityDocument.COLLECTION) != null) {
            throw new IllegalStateException("玩家已落位，不能重复 placeCity：playerId=" + playerId
                    + "。要换位置请用 moveCity");
        }
        try {
            mongo.insert(cityDoc(playerId, coord), WorldCityDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            // 走到这里只可能是 coordKey 撞了（_id 上面已经查过）：那一格已被别人占着，
            // 调用方会向外螺旋再试一格 —— 与内存版 putIfAbsent 返回 false 同一条语义
            return false;
        }
        bumpChunkVersion(coord.chunkKey(chunkSize));
        return true;
    }

    @Override
    public boolean moveCity(String playerId, Coord to) {
        requirePlayer(playerId);
        if (to == null) {
            throw new IllegalArgumentException("to 不得为 null");
        }
        WorldCityDocument mine = mongo.findById(playerId, WorldCityDocument.class,
                WorldCityDocument.COLLECTION);
        if (mine == null) {
            // 与内存版同一条：没落位过的人"迁"过来等价于落位（占不到格就 false）
            return placeCity(playerId, to);
        }
        Coord from = toCoord(mine);
        if (from.equals(to)) {
            return false;   // 原地迁城没有意义，且下面的唯一索引会把"自己占自己"判成冲突
        }
        try {
            mongo.findAndModify(Query.query(Criteria.where("_id").is(playerId)),
                    new Update().set("coordKey", to.storageKey()).set("x", to.x()).set("y", to.y()),
                    WorldCityDocument.class, WorldCityDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            // 目标格已被占：这一次改档整体失败，文档还停在旧格上（内存版是先占新格再解旧格，
            // 中间有一个"两个格都指着我"的窗口；这里没有）
            return false;
        }
        bumpChunkVersion(to.chunkKey(chunkSize));
        bumpChunkVersion(from.chunkKey(chunkSize));
        return true;
    }

    @Override
    public Map<String, Coord> allCities() {
        Map<String, Coord> out = new LinkedHashMap<>();
        mongo.findAll(WorldCityDocument.class, WorldCityDocument.COLLECTION)
                .forEach(doc -> out.put(doc.playerId(), toCoord(doc)));
        return Map.copyOf(out);
    }

    // ---------- 格子消耗 ----------

    @Override
    public void markConsumed(Coord coord, long atMillis) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        if (atMillis <= 0L) {
            throw new IllegalArgumentException("atMillis 必须为正的服务端时间戳：" + atMillis);
        }
        mongo.upsert(Query.query(Criteria.where("_id").is(coord.storageKey())),
                new Update().setOnInsert("coordKey", coord.storageKey())
                        .set("chunkKey", coord.chunkKey(chunkSize))
                        .set("consumedAt", atMillis),
                WorldCellDocument.COLLECTION);
        bumpChunkVersion(coord.chunkKey(chunkSize));
    }

    @Override
    public boolean isConsumed(Coord coord) {
        return coord != null && mongo.exists(Query.query(Criteria.where("_id").is(coord.storageKey())),
                WorldCellDocument.COLLECTION);
    }

    @Override
    public List<Coord> consumedInChunk(String chunkKey, int size) {
        requireKey(chunkKey);
        if (size != chunkSize) {
            // 落库时用的是装配处给的 WORLD_CHUNK_SIZE，这里若被问成别的尺寸，索引里的
            // chunkKey 就对不上，会静默回空表（表现是"采空的资源点又出现了"）—— 宁可响
            throw new IllegalStateException("consumedInChunk 的尺寸=" + size
                    + " 与存储装配用的 chunkSize=" + chunkSize + " 不一致，结果不可信");
        }
        List<WorldCellDocument> docs = mongo.find(Query.query(Criteria.where("chunkKey").is(chunkKey)),
                WorldCellDocument.class, WorldCellDocument.COLLECTION);
        List<Coord> out = new ArrayList<>(docs.size());
        docs.forEach(doc -> out.add(Coord.parseStorageKey(doc.coordKey())));
        // 与内存版同一条稳定顺序：_id 的字典序会把 "10:5" 排在 "2:3" 前面
        out.sort(Comparator.comparingInt(Coord::x).thenComparingInt(Coord::y));
        return out;
    }

    // ---------- chunk 版本 ----------

    @Override
    public long chunkVersion(String chunkKey) {
        requireKey(chunkKey);
        WorldChunkVersionDocument doc = mongo.findById(chunkKey, WorldChunkVersionDocument.class,
                WorldChunkVersionDocument.COLLECTION);
        return doc == null ? 0L : doc.version();
    }

    @Override
    public long bumpChunkVersion(String chunkKey) {
        requireKey(chunkKey);
        // 原子自增并取回新值（upsert 覆盖"第一次被改动"那种文档还不存在的情况）。
        // 读-加-写在这里是错的：两个实例同时改同一块会有一次自增被吃掉，
        // 而那次变化的实体客户端就永远不会再来拉（增量下发只看版本号）
        WorldChunkVersionDocument after = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(chunkKey)),
                new Update().inc("version", 1L),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                WorldChunkVersionDocument.class, WorldChunkVersionDocument.COLLECTION);
        return after == null ? chunkVersion(chunkKey) : after.version();
    }

    // ---------- 迷雾 ----------

    @Override
    public FogOfWar fogOf(String playerId) {
        requirePlayer(playerId);
        WorldFogDocument doc = mongo.findById(playerId, WorldFogDocument.class,
                WorldFogDocument.COLLECTION);
        FogOfWar fog = new FogOfWar();
        if (doc != null && doc.chunks() != null) {
            fog.restore(new LinkedHashSet<>(doc.chunks()), doc.version());
        }
        return fog;
    }

    @Override
    public long saveFog(String playerId, FogOfWar fog, long expectedVersion) {
        requirePlayer(playerId);
        if (fog == null) {
            throw new IllegalArgumentException("fog 不得为 null");
        }
        List<String> chunks = List.copyOf(fog.chunks());
        if (expectedVersion == 0L) {
            try {
                mongo.insert(new WorldFogDocument(playerId, chunks, 1L), WorldFogDocument.COLLECTION);
                return 1L;
            } catch (DuplicateKeyException e) {
                // 0 是"建档"语义，撞号说明有人已经建过：不能当成"写成功"，否则这份
                // 探索记录会被静默丢掉，而玩家那边的表现是"我探过的块没保存上"
                throw new IllegalStateException("迷雾档已存在，不能按建档写：playerId=" + playerId
                        + "。请重读迷雾后带着它的版本号写回", e);
            }
        }
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(playerId)
                        .and("version").is(expectedVersion)),
                new Update().set("chunks", chunks).set("version", expectedVersion + 1L),
                WorldFogDocument.class, WorldFogDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            WorldFogDocument current = mongo.findById(playerId, WorldFogDocument.class,
                    WorldFogDocument.COLLECTION);
            if (current == null) {
                throw new IllegalStateException("迷雾档不存在，无法按版本更新：playerId=" + playerId
                        + "。建档请用 expectedVersion=0");
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，存储版本=" + current.version() + "，提交版本=" + expectedVersion
                    + "。请重读迷雾后重试：不挡的话后写的那份会把先写的块重新盖黑");
        }
        return expectedVersion + 1L;
    }

    // ---------- 侦查报告 ----------

    @Override
    public void saveReport(ScoutReport.Report report) {
        if (report == null) {
            throw new IllegalArgumentException("report 不得为 null");
        }
        // 覆盖而不是追加：与内存版同一条（同一份报告存两次，列表里只许出现一次）
        mongo.save(new ScoutReportDocument(report.reportId(), report.scoutPlayerId(), report),
                ScoutReportDocument.COLLECTION);
    }

    @Override
    public Optional<ScoutReport.Report> findReport(String reportId) {
        if (reportId == null) {
            return Optional.empty();
        }
        ScoutReportDocument doc = mongo.findById(reportId, ScoutReportDocument.class,
                ScoutReportDocument.COLLECTION);
        return Optional.ofNullable(doc).map(ScoutReportDocument::report);
    }

    @Override
    public List<ScoutReport.Report> reportsOf(String scoutPlayerId) {
        if (scoutPlayerId == null || scoutPlayerId.isBlank()) {
            throw new IllegalArgumentException("scoutPlayerId 不得为空");
        }
        List<ScoutReportDocument> docs = mongo.find(Query.query(
                        Criteria.where("scoutPlayerId").is(scoutPlayerId))
                        .with(Sort.by(Sort.Order.desc(ScoutReportDocument.FIELD_CREATED_AT),
                                Sort.Order.asc("_id"))),
                ScoutReportDocument.class, ScoutReportDocument.COLLECTION);
        List<ScoutReport.Report> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.report()));
        return out;
    }

    @Override
    public int purgeExpiredReports(long cutoffMillis) {
        // 严格小于：与内存版同一条边界（等于截止时刻的那一份还算没过期）
        return (int) mongo.remove(Query.query(
                        Criteria.where(ScoutReportDocument.FIELD_EXPIRES_AT).lt(cutoffMillis)),
                ScoutReportDocument.COLLECTION).getDeletedCount();
    }

    // ---------- 行军的地图投影 ----------

    @Override
    public List<March> marchesInChunks(List<String> chunkKeys) {
        return marches.findByChunkKeys(chunkKeys);
    }

    /** 测试与运维用：只清世界自己的五个集合，绝不动行军（那是 {@link MarchRepository} 的档）。 */
    public void clear() {
        mongo.remove(new Query(), WorldCityDocument.COLLECTION);
        mongo.remove(new Query(), WorldCellDocument.COLLECTION);
        mongo.remove(new Query(), WorldChunkVersionDocument.COLLECTION);
        mongo.remove(new Query(), WorldFogDocument.COLLECTION);
        mongo.remove(new Query(), ScoutReportDocument.COLLECTION);
    }

    /** 观测用：已落位的城数。 */
    public long cityCount() {
        return mongo.count(new Query(), WorldCityDocument.COLLECTION);
    }

    private static WorldCityDocument cityDoc(String playerId, Coord coord) {
        return new WorldCityDocument(playerId, coord.storageKey(), coord.x(), coord.y());
    }

    private static Coord toCoord(WorldCityDocument doc) {
        return Coord.of((int) doc.x(), (int) doc.y());
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
    }

    private static void requireKey(String chunkKey) {
        if (chunkKey == null || chunkKey.isBlank()) {
            throw new IllegalArgumentException("chunkKey 不得为空");
        }
    }
}
