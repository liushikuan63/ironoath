package com.ironoath.web.store.memory;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.scout.ScoutReport;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 职责：世界状态的内存实现 —— 玩家城位置、被消耗的格子、chunk 版本、迷雾、侦查报告。
 * 依赖：game-core 的端口与领域对象。
 *
 * <p><b>只存「玩家造成的变化」</b>：野怪与资源点由 {@code WorldGenerator} 按种子确定性生成，
 * 262144 格里只有被采空的资源点、被打掉的野怪与玩家城需要落库。
 *
 * <p>内存实现重启即丢，而世界状态是跨会话的（玩家的城不会因为他下线就消失），
 * 所以生产必须用 MongoDB 版（B16）。装配处用 WARN 日志把这件事喊出来。
 */
public final class InMemoryWorldStore implements WorldRepository {

    private final Map<String, Coord> cityByPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> playerByCity = new ConcurrentHashMap<>();
    /**
     * 消耗格键 → 消耗时刻。
     *
     * <p>原先这里是一个 Set，把端口传进来的 {@code atMillis} 直接丢掉 —— 那意味着
     * "内存版没有这个事实、Mongo 版有"，而将来做刷新/归档（B14 赛季重置要按时刻清）时，
     * 内存版会连"我们从来没有记过时刻"都查不出来。存着不贵，丢掉是不可逆的。
     */
    private final Map<String, Long> consumedAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> chunkVersions = new ConcurrentHashMap<>();
    private final Map<String, FogRow> fogByPlayer = new ConcurrentHashMap<>();
    private final Map<String, ScoutReport.Report> reports = new ConcurrentHashMap<>();
    private final MarchRepository marches;
    private final int chunkSize;

    /**
     * @param marches   行军仓储。viewport 要把行军作为地图实体下发，
     *                  而查询条件是「地理范围」，所以由世界仓储代为转发（B07 的 WorldEntity 含 MARCH 类型）
     * @param chunkSize chunk 边长，来自 global.WORLD_CHUNK_SIZE（铁律 1：不写死）
     */
    public InMemoryWorldStore(MarchRepository marches, int chunkSize) {
        if (marches == null) {
            throw new IllegalArgumentException("marches 不得为 null");
        }
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂，实际=" + chunkSize);
        }
        this.marches = marches;
        this.chunkSize = chunkSize;
    }

    // ---------- 玩家城 ----------

    @Override
    public Optional<Coord> cityOf(String playerId) {
        return Optional.ofNullable(playerId == null ? null : cityByPlayer.get(playerId));
    }

    @Override
    public Optional<String> cityAt(Coord coord) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        return Optional.ofNullable(playerByCity.get(keyOf(coord)));
    }

    @Override
    public boolean placeCity(String playerId, Coord coord) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        // 已落位的人不能再 placeCity：原先这里会把新格绑上去、却不会解绑旧格，
        // 于是坐标索引里出现"两个格子都指向同一个人的城"—— 旧格那座就是点进去找不到人的幽灵城。
        // Mongo 版一份文档一个玩家，结构上就做不到这件事，所以内存版也必须拒绝（#16 的等价要求）
        if (cityByPlayer.containsKey(playerId)) {
            throw new IllegalStateException("玩家已落位，不能重复 placeCity：playerId=" + playerId
                    + "。那会在坐标索引里留下第二座指向同一个人的格（旧的那座从此点不到人）；"
                    + "要换位置请用 moveCity");
        }
        // putIfAbsent 保证并发落位时只有一个成功：两个玩家同时被分到同一格是必须挡住的，
        // 否则地图上会出现两重叠在一起的城，而 viewport 只会下发其中一个
        String previous = playerByCity.putIfAbsent(keyOf(coord), playerId);
        if (previous != null) {
            return false;
        }
        cityByPlayer.put(playerId, coord);
        bumpChunkVersion(coord.chunkKey(chunkSize));
        return true;
    }

    @Override
    public boolean moveCity(String playerId, Coord to) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (to == null) {
            throw new IllegalArgumentException("to 不得为 null");
        }
        Coord from = cityByPlayer.get(playerId);
        if (from != null && from.equals(to)) {
            return false;   // 原地迁城没有意义，且下面的占格会把"自己占了自己"误判成冲突
        }
        // 与新落位同一条纪律：占不到格就返回 false，由调用方另找一格，绝不把两座城叠在同一格上
        if (playerByCity.putIfAbsent(keyOf(to), playerId) != null) {
            return false;
        }
        cityByPlayer.put(playerId, to);
        bumpChunkVersion(to.chunkKey(chunkSize));
        if (from != null) {
            // 条件删除：只删「确实还指向这个人」的那份。并发下旧格可能已被别人的城接管，
            // 无条件删除会把别人的城从坐标索引上抹掉，留下一座谁也找不到的幽灵城
            playerByCity.remove(keyOf(from), playerId);
            bumpChunkVersion(from.chunkKey(chunkSize));
        }
        return true;
    }

    @Override
    public Map<String, Coord> allCities() {
        return Map.copyOf(cityByPlayer);
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
        consumedAt.put(keyOf(coord), atMillis);
        bumpChunkVersion(coord.chunkKey(chunkSize));
    }

    @Override
    public boolean isConsumed(Coord coord) {
        return coord != null && consumedAt.containsKey(keyOf(coord));
    }

    @Override
    public List<Coord> consumedInChunk(String chunkKey, int size) {
        requireKey(chunkKey);
        // 与 Mongo 版同一条拒绝：那边的 chunkKey 是一列派生值，按装配时的尺寸算出来的，
        // 换个尺寸问它就答不出（也追不到索引）。内存版"能"用任意尺寸算出范围，
        // 但那意味着 dev 全绿、生产第一次跨尺寸调用就抛 —— 两侧必须同一条行为
        if (size != chunkSize) {
            throw new IllegalStateException("consumedInChunk 的尺寸=" + size
                    + " 与存储装配用的 chunkSize=" + chunkSize + " 不一致，结果不可信");
        }
        List<Coord> out = new ArrayList<>();
        // 直接比 chunk 键，而不是"解析回坐标再做范围判断"：后者要依赖坐标键的格式，
        // 键格式一改就是静默漏格子（表现是采空的资源点又出现了）
        consumedAt.keySet().stream().map(InMemoryWorldStore::parse)
                .filter(coord -> chunkKey.equals(coord.chunkKey(size)))
                .forEach(out::add);
        // 稳定顺序：Mongo 侧按 _id（字符串）排序得不到同一个序列（"10:5" 字典序在 "2:3" 前），
        // 所以两侧都显式按 x、y 排，viewport 组装与跨实现比对才是可复现的
        out.sort(Comparator.comparingInt(Coord::x).thenComparingInt(Coord::y));
        return out;
    }

    // ---------- chunk 版本 ----------

    @Override
    public long chunkVersion(String chunkKey) {
        requireKey(chunkKey);
        AtomicLong version = chunkVersions.get(chunkKey);
        return version == null ? 0L : version.get();
    }

    @Override
    public long bumpChunkVersion(String chunkKey) {
        requireKey(chunkKey);
        return chunkVersions.computeIfAbsent(chunkKey, k -> new AtomicLong(0L)).incrementAndGet();
    }

    // ---------- 迷雾 ----------

    /** 一行的迷雾存档：探索过的块 + 库里第几版。 */
    private record FogRow(Set<String> chunks, long version) {
    }

    @Override
    public FogOfWar fogOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        FogOfWar fog = new FogOfWar();
        FogRow row = fogByPlayer.get(playerId);
        if (row != null) {
            // 拷一份再 restore：直接传内部集合会让 FogOfWar 与仓储共用同一个 Set，
            // 调用方改一下就绕过 saveFog 的写回
            fog.restore(new LinkedHashSet<>(row.chunks()), row.version());
        }
        return fog;
    }

    @Override
    public synchronized long saveFog(String playerId, FogOfWar fog, long expectedVersion) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (fog == null) {
            throw new IllegalArgumentException("fog 不得为 null");
        }
        FogRow current = fogByPlayer.get(playerId);
        if (current != null && current.version() != expectedVersion) {
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，存储版本=" + current.version() + "，提交版本=" + expectedVersion
                    + "。请重读迷雾后重试：不挡的话后写的那份会把先写的块重新盖黑");
        }
        if (current == null && expectedVersion != 0L) {
            throw new IllegalStateException("迷雾档不存在，无法按版本更新：playerId=" + playerId
                    + "。建档请用 expectedVersion=0");
        }
        Set<String> stored = ConcurrentHashMap.newKeySet();
        stored.addAll(fog.chunks());
        fogByPlayer.put(playerId, new FogRow(stored, expectedVersion + 1L));
        return expectedVersion + 1L;
    }

    // ---------- 侦查报告 ----------

    @Override
    public void saveReport(ScoutReport.Report report) {
        if (report == null) {
            throw new IllegalArgumentException("report 不得为 null");
        }
        // 只留一份真相：原先这里还往 reportIdsByPlayer 追加一次 id，
        // 于是同一份报告存两次就会在玩家的列表里出现两次，而清理时要记得改两个地方
        reports.put(report.reportId(), report);
    }

    @Override
    public Optional<ScoutReport.Report> findReport(String reportId) {
        return Optional.ofNullable(reportId == null ? null : reports.get(reportId));
    }

    @Override
    public List<ScoutReport.Report> reportsOf(String scoutPlayerId) {
        if (scoutPlayerId == null || scoutPlayerId.isBlank()) {
            throw new IllegalArgumentException("scoutPlayerId 不得为空");
        }
        List<ScoutReport.Report> out = new ArrayList<>();
        for (ScoutReport.Report report : reports.values()) {
            if (scoutPlayerId.equals(report.scoutPlayerId())) {
                out.add(report);
            }
        }
        // 最新的在前：玩家打开情报列表时要先看到刚拿到的那份。
        // 同刻再按 reportId 定序，与 Mongo 侧的排序键逐条一致（少第二键就是"刷新一次顺序变了"）
        out.sort(Comparator.comparingLong(ScoutReport.Report::createdAt).reversed()
                .thenComparing(ScoutReport.Report::reportId));
        return out;
    }

    @Override
    public int purgeExpiredReports(long cutoffMillis) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, ScoutReport.Report> entry : reports.entrySet()) {
            if (entry.getValue().expiresAt() < cutoffMillis) {
                expired.add(entry.getKey());
            }
        }
        // 边界是严格小于（与 Mongo 侧同一条查询），等于截止时刻的那一份还算没过期
        expired.forEach(reports::remove);
        return expired.size();
    }

    // ---------- 行军的地图投影 ----------

    @Override
    public List<March> marchesInChunks(List<String> chunkKeys) {
        return marches.findByChunkKeys(chunkKeys);
    }

    /**
     * 坐标的稳定存储键 —— 转发给 {@link Coord#storageKey()}。
     *
     * <p>编码规则必须在领域层只有一份：两套存储各自编一遍，就会出现"同一格在两边是两个键"，
     * 而那种不一致不报错，只是格子对不上。
     */
    private static String keyOf(Coord coord) {
        return coord.storageKey();
    }

    private static Coord parse(String key) {
        return Coord.parseStorageKey(key);
    }

    private static void requireKey(String chunkKey) {
        if (chunkKey == null || chunkKey.isBlank()) {
            throw new IllegalArgumentException("chunkKey 不得为空");
        }
    }

    public void clear() {
        cityByPlayer.clear();
        playerByCity.clear();
        consumedAt.clear();
        chunkVersions.clear();
        fogByPlayer.clear();
        reports.clear();
    }

    public int cityCount() {
        return cityByPlayer.size();
    }
}
