package com.ironoath.web.rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.dto.generated.OpsRankSnapshotResp;
import com.ironoath.web.dto.generated.RankEntryView;
import com.ironoath.web.dto.generated.RankListResp;
import com.ironoath.web.dto.generated.RankSnapshotResp;
import com.ironoath.web.dto.generated.RankType;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：四类榜的读路径与击杀上报（B23 §一 1）。
 * 依赖：{@link SeasonBoardStore}（POWER / KILL 的账）、社交与国家存储（组织榜要按成员聚合）、Bot 注册表（排除）。
 *
 * <p><b>四类榜不是四套存储</b>：POWER 与 KILL 走赛季榜那张表（各自一个 board），
 * ALLIANCE / NATION 是**按成员把赛季分加起来**算出来的投影 —— §五 裁决①明写"与赛季结算同源、不造第二本账"。
 * 组织榜因此没有自己的行：成员的分变了，它下一次读就跟着变，不存在"先算好再同步"的那一步。
 *
 * <p><b>Bot 排除在两侧都有</b>（B13 禁止项 + B23 验收 3）：写入侧 {@link #reportKills} 在入口挡，
 * 读侧 {@link #list} 再摘一遍 —— 只做写入侧的话，规则生效之前写进库的 Bot 条目会在重启之后重新上榜
 * （与赛季榜恢复时那处兜底同一条理由）。
 *
 * <p><b>实时榜只用于展示</b>：结算依据永远是快照（B14 禁止项）。本类不参与任何结算路径。
 */
@Service
public class RankBoardService {

    /** Bot 不进榜前 3 的那个"前 3"：与赛季榜奖励坑位同一个宽度（B13 的口径，不是这个类自己定的）。 */
    private static final int REWARDED_TOP_N = 3;

    private final SeasonBoardStore boards;
    private final SocialStore social;
    private final NationStore nations;
    private final PlayerRepository players;
    private final BotRegistry bots;
    private final ConfigRegistry configs;
    private final SeasonRulesAssembler assembler;
    private final TimeService time;

    public RankBoardService(SeasonBoardStore boards, SocialStore social, NationStore nations,
                            PlayerRepository players, BotRegistry bots, ConfigRegistry configs,
                            SeasonRulesAssembler assembler, TimeService time) {
        this.boards = boards;
        this.social = social;
        this.nations = nations;
        this.players = players;
        this.bots = bots;
        this.configs = configs;
        this.assembler = assembler;
        this.time = time;
    }

    /**
     * 击杀上报（KILL 榜，累加型）。战斗结算之后由战报域调用 —— 那一处是所有战斗的唯一漏斗。
     *
     * <p>赛季没开（时间轴还没锚定）时**直接返回**：没有赛季就没有"赛季内击杀"，硬记会记到一份
     * 谁也不认的榜上（与赛季榜上报同一条判断）。
     */
    public void reportKills(String playerId, String name, long kills) {
        if (kills <= 0L || playerId == null || playerId.isBlank()) {
            return;
        }
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            return;
        }
        if (bots.humanOnly(playerId, "击杀榜") == null) {
            return;
        }
        boards.accumulate(seasonId, SeasonSettlement.Board.KILL,
                new SeasonSettlement.Entry(playerId, name == null ? playerId : name, kills), kills);
    }

    /** 一页榜 + 我的名次（未上榜时名次为 null，不用 0 冒充 —— 验收 2）。 */
    public RankListResp list(String playerId, RankType type, int page) {
        captureTodayIfAbsent(type);
        List<SeasonSettlement.Entry> rows = rankedEntries(type);
        int pageSize = (int) configs.longParam("RANK_PAGE_SIZE_MAX");
        int totalPages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int safePage = Math.min(Math.max(1, page), totalPages);
        int from = (safePage - 1) * pageSize;
        int to = Math.min(rows.size(), from + pageSize);

        List<RankEntryView> entries = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            entries.add(toView(i + 1, rows.get(i), type));
        }
        MyRow mine = myRowOf(playerId, type, rows);
        return new RankListResp(type, entries, mine.rank(), mine.value(),
                safePage, pageSize, to < rows.size());
    }

    /**
     * 我的名次（验收 2）。条目带我所在的那一页：面板要把"我的名次恒在顶部"画出来，
     * 而只回一个数字的话，那一行还得客户端自己拼一份（本仓库禁止客户端拼榜）。
     */
    public RankListResp me(String playerId, RankType type) {
        List<SeasonSettlement.Entry> rows = rankedEntries(type);
        MyRow mine = myRowOf(playerId, type, rows);
        if (mine.rank() == null) {
            return list(playerId, type, 1);
        }
        int pageSize = (int) configs.longParam("RANK_PAGE_SIZE_MAX");
        return list(playerId, type, (mine.rank() - 1) / pageSize + 1);
    }

    /**
     * 某一天的每日快照里**我的那一行**（B23 §一 2 的申诉读取）。
     *
     * <p><b>只回自己</b>（裁决③）：全服历史名次是情报（与"不下发精确距离"同一条思路），
     * 运营要全量走 {@link #opsSnapshot}。所以这个返回值里刻意**没有 entries** ——
     * 一个"有 entries 字段但恒为空"的响应会让人以为"那天榜上没人"，那是句假话。
     *
     * @param dayKey 日期键 {@code yyyyMMdd}（UTC+8）。查今天会顺手把今天的补拍上
     *               （与 {@link #list} 同一条惰性先例）
     */
    public RankSnapshotResp snapshot(String playerId, RankType type, String dayKey) {
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            throw snapshotMissing("本赛季还没开始，暂时没有快照", dayKey);
        }
        String day = requireDayKey(dayKey);
        SeasonSettlement.Board board = boardOf(type);
        if (day.equals(todayKey())) {
            captureTodayIfAbsent(type);
        }
        SeasonSettlement.Snapshot taken = boards.daily(seasonId, board, day);
        if (taken == null) {
            throw snapshotMissing(detailForMissingDay(seasonId, board, day), day);
        }
        SeasonSettlement.Entry mine = entryOf(taken, type, playerId);
        return new RankSnapshotResp(type, day, taken.snapshotAt(),
                mine == null ? null : taken.rankOf(mine.id()),
                mine == null ? null : mine.score());
    }

    /**
     * 某一天某张榜的**全量**（运营只读，裁决③：运营侧走 ops 全量）。
     *
     * <p>申诉时要能回答"那天第 37 名是多少分"，所以这里给整榜而不是某一个人；
     * 分页沿用实时榜那套（同一张表里的每页条数），免得出现"榜上一页 20、快照一页 50"两套口径。
     */
    public OpsRankSnapshotResp opsSnapshot(RankType type, String dayKey, int page) {
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            throw snapshotMissing("本赛季还没开始，暂时没有快照", dayKey);
        }
        String day = requireDayKey(dayKey);
        SeasonSettlement.Board board = boardOf(type);
        SeasonSettlement.Snapshot taken = boards.daily(seasonId, board, day);
        if (taken == null) {
            throw snapshotMissing(detailForMissingDay(seasonId, board, day), day);
        }
        List<SeasonSettlement.Entry> rows = taken.entries();
        int pageSize = (int) configs.longParam("RANK_PAGE_SIZE_MAX");
        int totalPages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int safePage = Math.min(Math.max(1, page), totalPages);
        int from = (safePage - 1) * pageSize;
        int to = Math.min(rows.size(), from + pageSize);
        List<RankEntryView> entries = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            entries.add(toView(i + 1, rows.get(i), type));
        }
        return new OpsRankSnapshotResp(type, day, taken.snapshotAt(), entries, rows.size(),
                safePage, pageSize, to < rows.size());
    }

    /**
     * 惰性补拍今天的快照（B23 §一 2 的拍摄时机）。<b>不跑定时器</b>（B14 禁止项）：
     * 判"该榜今天还没拍过就补一份"，与战报过期清理、邮件过期同一形状。
     *
     * <p><b>拍的是读侧同一份内容</b>：Bot 已被摘掉（读侧兜底那一段），组织榜是当时的投影。
     * 若拍的是"库里的原始行"，玩家会看到"榜上没有某个名字，但快照里有" —— 而快照是给申诉用的，
     * 它必须与玩家当时看到的一致。
     *
     * <p><b>先查再写不是竞态</b>：查只是省下一次全榜写；两个并发读即使都走到写，落库的也只有一份
     * （{@code saveDailyIfAbsent} 按 _id 撞号判断），所以"同一天只拍一份"不依赖这段查询。
     */
    private void captureTodayIfAbsent(RankType type) {
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            return;
        }
        SeasonSettlement.Board board = boardOf(type);
        String day = todayKey();
        if (boards.daily(seasonId, board, day) != null) {
            return;
        }
        List<SeasonSettlement.Entry> rows = rankedEntries(type);
        boards.saveDailyIfAbsent(seasonId, board, day,
                new SeasonSettlement.Snapshot(board, time.serverNow(), rows));
    }

    /** 榜类型 → 存储里的 board。名字逐字对应（契约里刻意与领域枚举同形，见 rank.schema.json 的说明）。 */
    private static SeasonSettlement.Board boardOf(RankType type) {
        return SeasonSettlement.Board.valueOf(type.name());
    }

    /**
     * 协议里的榜类型字符串 → 枚举。<b>解析只有这一处实现</b>（{@code RankController} 与
     * {@code OpsController} 都调它）：两个入口各写一份的话，某个入口迟早会开始接受
     * 一种另一个入口不认的写法（多一个空格、小写、别名），而那种不一致只在客户端换入口时才暴露。
     *
     * <p>认不出来回**参数错误**而不是空榜：一个拼错的 type 给一张空榜，客户端会把它画成
     * "这个榜还没有人"，而实际是名字写错了。
     */
    public static RankType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "type 不得为空");
        }
        try {
            return RankType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "不认识的榜类型：" + raw + "（可选 POWER / KILL / ALLIANCE / NATION）");
        }
    }

    private String todayKey() {
        return DayKey.of(time.serverNow());
    }

    /**
     * 日期键校验。格式不对是**参数问题**，不是"那天没有快照" —— 后者会误导玩家去翻别的日期，
     * 而这里他该做的是看看自己传了什么。
     */
    private static String requireDayKey(String dayKey) {
        if (dayKey == null || dayKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "dayKey 不得为空（形如 20260919）");
        }
        String trimmed = dayKey.trim();
        if (!trimmed.matches("\\d{8}")) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "dayKey 必须是 8 位日期（形如 20260919），实际=" + dayKey);
        }
        return trimmed;
    }

    /** 那一天没有快照时的 detail：把"可选的最早一天"带上（没有就明说还没拍过任何一天）。 */
    private String detailForMissingDay(String seasonId, SeasonSettlement.Board board, String day) {
        List<String> days = boards.dailyDays(seasonId, board);
        if (days.isEmpty()) {
            return "这一天没有快照，而且本赛季还没有任何一天拍过快照（快照是在有人读榜时惰性补拍的）";
        }
        return "这一天没有快照；可查的最早一天是 " + days.get(0) + "，最近一天是 "
                + days.get(days.size() - 1);
    }

    private static BizException snapshotMissing(String detail, String dayKey) {
        return new BizException(ErrorCode.RANK_SNAPSHOT_EMPTY, detail + "（dayKey=" + dayKey + "）");
    }

    /** 我在某份快照里的那一行；组织榜要找的是我所在组织。 */
    private SeasonSettlement.Entry entryOf(SeasonSettlement.Snapshot taken, RankType type,
                                           String playerId) {
        String myId = type == RankType.ALLIANCE || type == RankType.NATION
                ? orgIdOf(playerId, type)
                : playerId;
        if (myId == null) {
            return null;
        }
        for (SeasonSettlement.Entry entry : taken.entries()) {
            if (entry.id().equals(myId)) {
                return entry;
            }
        }
        return null;
    }

    /** 我在这张榜上的行（不排序、不分页）：组织榜回的是我所在组织那一行。 */
    private MyRow myRowOf(String playerId, RankType type, List<SeasonSettlement.Entry> rows) {
        String myId = type == RankType.ALLIANCE || type == RankType.NATION
                ? orgIdOf(playerId, type)
                : playerId;
        if (myId == null) {
            return new MyRow(null, null);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(myId)) {
                return new MyRow(i + 1, rows.get(i).score());
            }
        }
        return new MyRow(null, null);
    }

    private record MyRow(Integer rank, Long value) {
    }

    /**
     * 这张榜的全部行（已排序、已摘掉 Bot）。组织榜是投影：把 POWER 榜按组织分组求和。
     *
     * <p>顺序由存储层保证（分数降序、同分按 id 升序 —— 与赛季榜同一条排序口径），
     * 投影出来的组织榜在这里再排一次同样的规则，两处不能各排一套。
     */
    private List<SeasonSettlement.Entry> rankedEntries(RankType type) {
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            return List.of();
        }
        if (type == RankType.ALLIANCE || type == RankType.NATION) {
            return projectOrgBoard(seasonId, type);
        }
        List<SeasonSettlement.Entry> rows = boards.board(seasonId,
                SeasonSettlement.Board.valueOf(type.name()));
        List<SeasonSettlement.Entry> out = new ArrayList<>(rows.size());
        for (SeasonSettlement.Entry entry : rows) {
            // 读侧兜底：写入侧已经拦过，但库里可能存着规则生效前的条目（赛季榜恢复时同一条理由）
            if (bots.humanOnly(entry.id(), "榜前 " + REWARDED_TOP_N + " 奖励坑位") == null) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * 组织榜 = 成员的赛季分合计（裁决①）。
     *
     * <p>只有**在榜上的成员**会被计入：一个人还没上报过战力就等于还没有分数，
     * 把他算成 0 分会把"没打过仗的联盟"和"分数为 0 的联盟"混成同一件事。
     */
    private List<SeasonSettlement.Entry> projectOrgBoard(String seasonId, RankType type) {
        Map<String, Long> scoreByOrg = new LinkedHashMap<>();
        Map<String, String> nameByOrg = new LinkedHashMap<>();
        for (SeasonSettlement.Entry entry : rankedEntries(RankType.POWER)) {
            String orgId = orgIdOf(entry.id(), type);
            if (orgId == null) {
                continue;
            }
            scoreByOrg.merge(orgId, entry.score(), Long::sum);
            if (type == RankType.ALLIANCE) {
                nameByOrg.computeIfAbsent(orgId, id -> social.allianceById(id)
                        .map(value -> value.name() + "[" + value.tag() + "]").orElse(id));
            } else {
                nameByOrg.computeIfAbsent(orgId, id -> nations.findById(id)
                        .map(value -> value.name()).orElse(id));
            }
        }
        List<SeasonSettlement.Entry> out = new ArrayList<>(scoreByOrg.size());
        scoreByOrg.forEach((orgId, score) ->
                out.add(new SeasonSettlement.Entry(orgId, nameByOrg.get(orgId), score)));
        out.sort(Comparator.comparingLong(SeasonSettlement.Entry::score).reversed()
                .thenComparing(SeasonSettlement.Entry::id));
        return out;
    }

    /** 玩家此刻所属的组织 id（联盟榜回联盟，国家榜回国家）；不在任何组织里就是 null。 */
    private String orgIdOf(String playerId, RankType type) {
        Optional<com.ironoath.core.social.Alliance> alliance = social.allianceOf(playerId);
        if (alliance.isEmpty()) {
            return null;
        }
        if (type == RankType.ALLIANCE) {
            return alliance.get().id();
        }
        return nations.findByAlliance(alliance.get().id()).map(value -> value.id()).orElse(null);
    }

    private RankEntryView toView(int rank, SeasonSettlement.Entry entry, RankType type) {
        boolean org = type == RankType.ALLIANCE || type == RankType.NATION;
        return new RankEntryView(rank, entry.id(), displayNameOf(entry, type), entry.score(),
                org ? tagOf(entry.id(), type) : null);
    }

    /**
     * 显示名。个人榜用**当下**的昵称（榜上存的是上报那一刻的名字：
     * 改过名的人不该在榜上永远挂着旧名），组织榜名字里已经带了缩写，原样回。
     */
    private String displayNameOf(SeasonSettlement.Entry entry, RankType type) {
        if (type == RankType.ALLIANCE || type == RankType.NATION) {
            return entry.name() == null ? entry.id() : entry.name();
        }
        return players.findByPlayerId(entry.id())
                .map(PlayerSave::nickName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(entry.name() == null ? entry.id() : entry.name());
    }

    /** 组织缩写：个人榜没有；联盟榜从联盟表取，国家榜回 null（国家的标识是名字，没有缩写列）。 */
    private String tagOf(String orgId, RankType type) {
        if (type != RankType.ALLIANCE) {
            return null;
        }
        return social.allianceById(orgId).map(value -> value.tag()).orElse(null);
    }

    private String seasonIdOrNull() {
        String seasonId = assembler.timelineRules().seasonId();
        return seasonId == null || seasonId.isBlank() ? null : seasonId;
    }
}
