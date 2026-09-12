package com.ironoath.core.world;

import com.ironoath.core.march.March;
import com.ironoath.core.scout.ScoutReport;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 职责：世界状态的仓储端口 —— 玩家城位置、被改动的格子、chunk 版本号、迷雾、侦查报告。
 * 依赖：无。
 *
 * <p><b>只存「玩家造成的变化」，不存整张地图</b>：野怪与资源点由
 * {@link WorldGenerator} 按 {@code WORLD_SEED} 确定性生成，
 * 所以 262144 格里只有被采空的资源点、被打掉的野怪、以及玩家城需要落库。
 * 存储量从「格数」降到「玩家数 + 被改动数」，这是 512×512 世界能跑起来的前提。
 *
 * <p><b>chunk 版本号是增量下发的核心</b>（B07 验收 6：无变化时返回 304、二次请求实体数为 0）。
 * 客户端上报它手里每个 chunk 的版本，服务端只回版本更高的那些。
 * 没有版本号就只能每次全量下发 9 个 chunk，而 B07 的 payload 上限是 20KB。
 */
public interface WorldRepository {

    // ---------- 玩家城位置 ----------

    /** 某玩家的世界坐标；未落位返回 empty。 */
    Optional<Coord> cityOf(String playerId);

    /** 某坐标上的玩家城；空地返回 empty。 */
    Optional<String> cityAt(Coord coord);

    /**
     * 给玩家落位。
     *
     * @return true 表示落位成功；false 表示该坐标已被占用（调用方应向外螺旋找空位）
     * @throws IllegalStateException 该玩家已经落位。重复落位会在坐标索引里留下第二座指向
     *         同一个人的格，旧的那座就变成"点进去找不到人"的幽灵城；换位置请走 {@link #moveCity}。
     *         （内存版原先不报这个错，是静默造幽灵城的那一侧，2026-09-10 与 Mongo 版对齐）
     */
    boolean placeCity(String playerId, Coord coord);

    /**
     * 整城迁移（B08 §5 流亡迁城）。与 {@link #placeCity} 的区别正是这条能力的全部难度所在：
     * <b>旧格必须解绑，而且新旧两个块的版本号都要变新</b>。
     *
     * <p>漏掉解绑会留下「一座不存在的城」—— 旧邻居的地图上仍然有他的城，点进去却搜不到人；
     * 漏掉旧块版本号则那些客户端根本不会重新拉这一块（视野是按版本号增量的）。
     *
     * <p>旧格的解绑是<b>条件删除</b>（仅当它确实还指向这个人）：并发下可能已经有别人的城
     * 落到了同一格，此时无条件删除会把别人的城从坐标索引上抹掉，而那个人自己的存档里
     * 仍然记着这座城在他的格子上 —— 一座从此谁也打不到、却占着落位索引的幽灵城。
     *
     * @param playerId 迁移谁的城
     * @param to       新坐标
     * @return true 迁移成功；false 表示新坐标已被占用，或该玩家本来就在这一格（调用方另找一格）
     */
    boolean moveCity(String playerId, Coord to);

    /**
     * 全部已落位的玩家城，playerId -> 坐标。
     *
     * <p>B08 §8 的目标搜索要在这个池子里按「半径 + 战力圈层 + 非护盾 + 非同盟 + 活跃」筛选，
     * 其中后四项横跨玩家档案与联盟数据，仓储自己无法独立完成过滤，所以端口只能给全量。
     *
     * <p>TODO(B16)：玩家规模上千后这里是热点，应下推成
     * {@code citiesWithin(Coord center, int radius)}，用空间索引（GeoHash / 网格桶）只捞半径内的。
     * 现在 512×512 世界、候选池就是活跃玩家数量级，全量扫描可接受。
     */
    Map<String, Coord> allCities();

    // ---------- 格子覆盖 ----------

    /**
     * 标记一格已被消耗（资源点采空 / 野怪被打掉）。
     *
     * <p>消耗后该格回到 {@link WorldGenerator} 的生成结果之外的「空」状态，
     * 并且<b>不会重新生成</b> —— 否则玩家会发现「刚打掉的野怪又回来了」，
     * 而刷新应当是显式的运营行为（B14 赛季重置），不是仓储的副作用。
     */
    void markConsumed(Coord coord, long atMillis);

    boolean isConsumed(Coord coord);

    /** 某 chunk 内已被消耗的格子，用于组装 chunk 数据时挖掉它们。 */
    List<Coord> consumedInChunk(String chunkKey, int chunkSize);

    // ---------- chunk 版本 ----------

    /** 取某 chunk 的当前版本号；从未变动过返回 0。 */
    long chunkVersion(String chunkKey);

    /**
     * 推进某 chunk 的版本号并返回新值。
     *
     * <p>任何会改变 chunk 内容的操作（玩家落位、行军进入/离开、资源点被采空）都必须调用它。
     * 漏调用的后果是客户端拿着旧版本号请求、服务端认为「无变化」而不回数据，
     * 玩家看到的是一个静止的地图 —— 而这种 bug 只在多端/重进时出现，极难排查。
     */
    long bumpChunkVersion(String chunkKey);

    // ---------- 迷雾 ----------

    /** 某玩家的迷雾状态；没有则返回一个空的（新号什么都没见过），版本为 0。 */
    com.ironoath.core.world.FogOfWar fogOf(String playerId);

    /**
     * 带乐观锁写回迷雾（与 {@code NationStore#save} 同一条契约，见收口清单 #61 与 #62）。
     *
     * <p>迷雾是<b>整份覆盖</b>的：不加版本，两条"读 → 探索几块 → 写回"的请求就会让后写的
     * 把先写的那几块重新盖黑 —— 玩家看到"我刚走过的地方又黑了"，而服务端一行日志都没有。
     * {@code PlayerLock} 只挡得住同一实例内的同玩家并发，多实例部署时只剩版本号。
     *
     * @param expectedVersion 调用方从 {@link #fogOf} 拿到的版本；<b>0 表示建档</b>
     *                        （新号第一次探索就是这种）
     * @return 写入后的新版本
     * @throws IllegalStateException 版本不匹配（有人先写了，调用方应重读重试），
     *                               或 {@code expectedVersion=0} 而档已存在
     */
    long saveFog(String playerId, com.ironoath.core.world.FogOfWar fog, long expectedVersion);

    // ---------- 侦查报告 ----------

    void saveReport(ScoutReport.Report report);

    Optional<ScoutReport.Report> findReport(String reportId);

    /** 某玩家的全部报告，按生成时刻倒序（最新的在前）。 */
    List<ScoutReport.Report> reportsOf(String scoutPlayerId);

    /** 删除已过期的报告。保留期由 global.SCOUT_REPORT_TTL_SECONDS 决定。 */
    int purgeExpiredReports(long cutoffMillis);

    // ---------- 行军的地图投影 ----------

    /**
     * 起点或终点落在给定 chunk 集合内的行军。
     *
     * <p>行军是地图上的可见实体（B07 的 WorldEntity 里有 MARCH 类型），
     * 所以 viewport 组装时必须把它们一并下发。放在世界仓储而不是行军仓储，
     * 是因为查询条件是「地理范围」而不是「归属玩家」。
     */
    List<March> marchesInChunks(List<String> chunkKeys);
}
