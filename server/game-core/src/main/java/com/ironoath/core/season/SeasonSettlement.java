package com.ironoath.core.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：赛季结算 —— 快照榜、幂等发奖、分页处理、归档记录（B14 §3/§4/§5，验收 2/3/4/7）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>结算按快照榜，不按实时榜</b>（§3、验收 4、禁止项）。理由写在文档里也写在代码里：
 * 实时榜的最后一秒可以被操纵 —— 攒了一整赛季的资源在结算前一刻全部换成战力，
 * 就能挤掉一个从头打到尾的人。快照榜把「结算依据」固定在某个时刻，
 * 之后无论怎么刷分都不影响结果。所以本类<b>同时</b>持有两份数据：
 * 实时榜给 UI 看（玩家要看到自己现在第几名），快照榜给结算用。
 * 两者混用是这一节最容易犯的错，所以它们在类型上就是两个不同的方法。
 *
 * <p><b>结算幂等</b>（§5、验收 2、禁止项）：幂等键是 {@code seasonId + playerId}。
 * 重复触发结算脚本时奖励只发一次 —— 而「重复触发」不是假设：
 * 结算是一个跑几十分钟的分页批处理，中途失败重跑是常规操作，
 * 没有幂等键的话重跑一次就等于全服多发一次奖励。
 *
 * <p><b>分页而不是全表扫描</b>（禁止项）：单服几千个玩家，一次载入全部再发奖
 * 会让结算期间内存峰值翻倍，而结算恰好是服务器最忙的时刻（王城战刚结束）。
 * 所以本类的接口是「给我一页玩家，我返回这一页的结算结果」。
 *
 * <p><b>赛季数据不碰玩家主存档</b>（§4、验收 3、禁止项）：本类不持有任何玩家存档引用，
 * 它只输出「该给谁发多少」的清单，由 game-web 的适配层写进 {@code season_<id>} 集合。
 * 主存档只保留荣耀等级、历史最高段位、赛季徽章三项 —— 那三项由适配层在归档时回写。
 */
public final class SeasonSettlement {

    /** 排行榜类型（B14 §二）。 */
    public enum Board {
        /** 战力榜 */
        POWER,
        /** 击杀榜 */
        KILL,
        /** 联盟榜 */
        ALLIANCE,
        /** 国家榜 */
        NATION
    }

    /** 榜上的一条。 */
    public record Entry(String id, String name, long score) {
        public Entry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("榜单条目的 id 不得为空");
            }
            if (score < 0) {
                throw new IllegalArgumentException("分数不得为负，实际=" + score);
            }
        }
    }

    /** 一份快照。snapshotAt 必须一起存：申诉时要能证明「结算依据是哪一刻的数据」。 */
    public record Snapshot(Board board, long snapshotAt, List<Entry> entries) {
        public Snapshot {
            entries = List.copyOf(entries);
        }

        /** 某个 id 的名次（1 起）；不在榜上返回 0。 */
        public int rankOf(String id) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id().equals(id)) {
                    return i + 1;
                }
            }
            return 0;
        }
    }

    /** 一个玩家的结算结果。 */
    public record Award(String playerId, int rank, SeasonTier.Tier tier, long seasonCoin,
                        long gold, boolean firstTime) {
    }

    /**
     * @param snapshotBoard      结算依据哪个榜
     * @param rewardedTopN       前 N 名有奖
     * @param coinPerRank        每名次的基础赛季币（第 1 名拿 coinPerRank × 1，第 2 名 × 2 递减）
     * @param archiveCollections 归档保留几个赛季。来源 global.SEASON_ARCHIVE_RETENTION
     */
    public record Rules(Board snapshotBoard, int rewardedTopN, long coinPerRank, int archiveCollections) {
        public Rules {
            if (snapshotBoard == null) {
                throw new IllegalArgumentException("snapshotBoard 不得为 null：结算必须有明确的依据榜");
            }
            if (rewardedTopN < 1) {
                throw new IllegalArgumentException("rewardedTopN 必须 >= 1，否则没人有奖，实际=" + rewardedTopN);
            }
            if (coinPerRank < 0) {
                throw new IllegalArgumentException("coinPerRank 不得为负，实际=" + coinPerRank);
            }
            if (archiveCollections < 1) {
                throw new IllegalArgumentException("archiveCollections 必须 >= 1，实际=" + archiveCollections
                        + "。归档保留 0 个赛季等于没有归档，而 §五 开放问题 3 说明申诉需要历史数据");
            }
        }
    }

    private final Rules rules;
    /** 实时榜：给 UI 看。可随时更新，不影响结算 */
    private final Map<Board, List<Entry>> live = new LinkedHashMap<>();
    /** 快照榜：结算依据。一旦拍下就不再改动 */
    private final Map<Board, Snapshot> snapshots = new LinkedHashMap<>();
    /** 幂等键（seasonId:playerId）→ 已发放的奖励。这是验收 2 的全部机制 */
    private final Map<String, Award> settled = new LinkedHashMap<>();
    /** 已归档的赛季 id，按归档顺序。超出保留数就丢最旧的 */
    private final Set<String> archived = new LinkedHashSet<>();
    private final String seasonId;

    public SeasonSettlement(String seasonId, Rules rules) {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId 不得为空：幂等键与归档集合名都靠它");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.seasonId = seasonId;
        this.rules = rules;
        for (Board board : Board.values()) {
            live.put(board, new ArrayList<>());
        }
    }

    // ---------- 实时榜 ----------

    /** 更新实时榜的一条（分数只增不减：榜是累计量，掉分意味着数据被回滚了）。 */
    public void updateLive(Board board, String id, String name, long score) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null");
        }
        List<Entry> entries = live.get(board);
        Entry updated = new Entry(id, name == null ? id : name, score);
        entries.removeIf(entry -> entry.id().equals(id));
        entries.add(updated);
        entries.sort(Comparator.comparingLong(Entry::score).reversed()
                .thenComparing(Entry::id));
    }

    /** 实时榜（给 UI）。返回的是排好序的副本，改它不会影响内部状态。 */
    public List<Entry> liveBoard(Board board) {
        return Collections.unmodifiableList(new ArrayList<>(live.getOrDefault(board, List.of())));
    }

    /** 实时榜上的名次（1 起）；不在榜上返回 0。 */
    public int liveRank(Board board, String id) {
        List<Entry> entries = live.getOrDefault(board, List.of());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    // ---------- 快照榜（验收 4） ----------

    /**
     * 拍下快照。
     *
     * <p><b>同一赛季同一榜只能拍一次</b>：拍两次意味着有两个「结算依据」，
     * 而申诉时无法说明用的是哪一个。要重拍必须先显式作废（本类不提供作废 ——
     * 快照的意义就在于不可更改）。
     */
    public Snapshot takeSnapshot(Board board, long snapshotAt) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null");
        }
        if (snapshots.containsKey(board)) {
            throw new IllegalStateException("榜 " + board + " 的快照已经拍过了（拍于 "
                    + snapshots.get(board).snapshotAt() + "）。快照不可更改是它存在的意义："
                    + "可以重拍的快照无法作为申诉依据");
        }
        Snapshot snapshot = new Snapshot(board, snapshotAt, liveBoard(board));
        snapshots.put(board, snapshot);
        return snapshot;
    }

    /** 某个榜的快照；未拍返回 null。 */
    public Snapshot snapshot(Board board) {
        return snapshots.get(board);
    }

    /**
     * 从存储恢复一份<b>已经拍过并落库</b>的快照（适配层在用，不在结算流程里）。
     *
     * <p>为什么需要它：快照拍下之后就落库了（它是申诉依据），而本类是进程内的。
     * 重启后若不能把库里那份恢复回来，{@code snapshotReady()} 会是 false ⇒ 结算会<b>重拍</b>一张，
     * 于是"结算依据"有了两个版本 —— 而快照存在的全部意义就是"有一刻被冻住了"。
     *
     * <p>约束与 {@link #takeSnapshot} 相同：同一个榜只能有一份。已经持有快照时再恢复会抛，
     * 因为那说明调用方没有先问 {@code snapshotReady()}（多实例并发时先落库的那一份才算数，
     * 后到的实例应当重建工作集再恢复，而不是在这里覆盖）。
     *
     * @throws IllegalStateException 该榜已经有快照（内存里已有一份，或调用方顺序错了）
     */
    public Snapshot restoreSnapshot(Snapshot stored) {
        if (stored == null) {
            throw new IllegalArgumentException("stored 不得为 null（没有快照时不要调用恢复）");
        }
        if (snapshots.containsKey(stored.board())) {
            throw new IllegalStateException("榜 " + stored.board() + " 已有快照（拍于 "
                    + snapshots.get(stored.board()).snapshotAt() + "），不能再从存储恢复一份："
                    + "一季一榜只能有一个结算依据");
        }
        snapshots.put(stored.board(), stored);
        return stored;
    }

    /** 结算依据的快照是否已就绪。没就绪就结算等于按实时榜结算（禁止项）。 */
    public boolean snapshotReady() {
        return snapshots.containsKey(rules.snapshotBoard());
    }

    // ---------- 结算（验收 2） ----------

    /**
     * 结算一页玩家。
     *
     * <p><b>幂等</b>：已经结算过的 playerId 会带着 {@code firstTime=false} 原样返回，
     * 而不是重新计算 —— 重新计算的话，若期间实时榜变了，同一个玩家两次结算会得到不同结果，
     * 那比多发一次奖励更难查。
     *
     * @param page       这一页的玩家 id（分页由调用方控制，本类不做全表扫描）
     * @param tierOf     玩家 → 赛季结束时的段位。用函数而不是 Map，
     *                   因为段位要在结算时按最新 matchPower 现算
     * @return 这一页的结算结果，顺序与入参一致
     * @throws IllegalStateException 快照未拍。结算必须按快照（禁止项）
     */
    public List<Award> settlePage(List<String> page, java.util.function.Function<String, SeasonTier.Tier> tierOf) {
        if (!snapshotReady()) {
            throw new IllegalStateException("结算前必须先拍下 " + rules.snapshotBoard()
                    + " 榜的快照：按实时榜结算会让最后一秒刷分的人挤掉从头打到尾的人（B14 禁止项）");
        }
        if (page == null) {
            throw new IllegalArgumentException("page 不得为 null");
        }
        if (tierOf == null) {
            throw new IllegalArgumentException("tierOf 不得为 null");
        }
        Snapshot snapshot = snapshots.get(rules.snapshotBoard());
        List<Award> out = new ArrayList<>(page.size());
        for (String playerId : page) {
            String key = seasonId + ":" + playerId;
            Award existing = settled.get(key);
            if (existing != null) {
                out.add(new Award(existing.playerId(), existing.rank(), existing.tier(),
                        existing.seasonCoin(), existing.gold(), false));
                continue;
            }
            int rank = snapshot.rankOf(playerId);
            SeasonTier.Tier tier = tierOf.apply(playerId);
            long coin = 0L;
            long gold = 0L;
            if (rank > 0 && rank <= rules.rewardedTopN()) {
                // 名次越靠前奖励越高：coinPerRank × (rewardedTopN - rank + 1)
                coin = rules.coinPerRank() * (rules.rewardedTopN() - rank + 1L);
                gold = coin / 2L;
            }
            Award award = new Award(playerId, rank, tier, coin, gold, true);
            settled.put(key, award);
            out.add(award);
        }
        return Collections.unmodifiableList(out);
    }

    /** 已结算的玩家数。 */
    public int settledCount() {
        return settled.size();
    }

    /** 已发放的赛季币总额。供 SeasonSettleResp.distributedRewards 使用。 */
    public long distributedRewards() {
        long total = 0L;
        for (Award award : settled.values()) {
            total += award.seasonCoin() + award.gold();
        }
        return total;
    }

    // ---------- 归档（验收 3/7） ----------

    /**
     * 归档一个赛季。
     *
     * <p>归档集合名是 {@code season_<id>}（§4）。超出保留数量时丢最旧的 ——
     * 但「丢」只是从本类的记录里移除，真正的删除由存储层按保留策略执行，
     * 本类只负责告诉调用方「哪些赛季已经超出保留期」。
     *
     * @return 本次归档后超出保留期、可以被存储层清理的赛季 id
     */
    public List<String> archive(String archivedSeasonId) {
        if (archivedSeasonId == null || archivedSeasonId.isBlank()) {
            throw new IllegalArgumentException("archivedSeasonId 不得为空");
        }
        archived.add(archivedSeasonId);
        List<String> all = new ArrayList<>(archived);
        if (all.size() <= rules.archiveCollections()) {
            return List.of();
        }
        return Collections.unmodifiableList(
                new ArrayList<>(all.subList(0, all.size() - rules.archiveCollections())));
    }

    /** 当前记录的已归档赛季，按归档顺序。 */
    public List<String> archivedSeasons() {
        return Collections.unmodifiableList(new ArrayList<>(archived));
    }

    /**
     * 「荣耀三件套」的类型不在这里 —— 见 {@link com.ironoath.core.player.PlayerGlory}。
     *
     * <p>原先 core 的 {@code GloryRecord} 与 web 端口的 {@code Glory} 是两个字段一样的记录类型，
     * 转换靠手写，而且"哪一个才主存档要存的那份"说不清。收成一个类型之后，
     * 派生（账本 → 荣耀）与缓存（荣耀 → 主存档）共用同一个值对象。
     */
    public String seasonId() {
        return seasonId;
    }

    public Rules rules() {
        return rules;
    }

    /** 归档集合名。 */
    public String archiveCollection() {
        return "season_" + seasonId;
    }
}
