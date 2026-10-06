package com.ironoath.web.rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.dto.generated.OpsRankSnapshotResp;
import com.ironoath.web.dto.generated.RankEntryView;
import com.ironoath.web.dto.generated.RankListResp;
import com.ironoath.web.dto.generated.RankSnapshotResp;
import com.ironoath.web.dto.generated.RankType;
import com.ironoath.web.nation.NationMembership;
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

    /**
     * 榜类型提示<b>从枚举现推</b>，不写死名单：V18 加第五张榜（{@code WAR}）那一天，
     * 原先硬编码那句「可选 POWER / KILL / ALLIANCE / NATION」就变成一句<b>教玩家怎么写错</b>的假话 ——
     * 而这条错误路径恰好只验了"拼错会被拒"，验不到"名单与枚举不一致"。
     */
    private static final String TYPE_OPTIONS = java.util.Arrays.stream(RankType.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(" / "));

    private final SeasonBoardStore boards;
    private final SocialStore social;
    private final NationStore nations;
    /**
     * 国战赛季分的三条 bonus 要按<b>国家花名册</b>给（胜国／参战国／发起国的<b>全体成员</b>，
     * 不是只有打了人的那几个），而那一跳的唯一真源在这里 —— 不在本类自己写第二份
     * （{@code NationMembership} 的类注释就是为这件事存在的：两个读者各写一遍，
     * 先分叉的总是「没有联盟」与「有联盟但没入籍」这两种缺席怎么算）。
     */
    private final NationMembership membership;
    private final PlayerRepository players;
    private final BotRegistry bots;
    private final ConfigRegistry configs;
    private final SeasonRulesAssembler assembler;
    private final TimeService time;

    public RankBoardService(SeasonBoardStore boards, SocialStore social, NationStore nations,
                            NationMembership membership, PlayerRepository players, BotRegistry bots,
                            ConfigRegistry configs, SeasonRulesAssembler assembler, TimeService time) {
        this.boards = boards;
        this.social = social;
        this.nations = nations;
        this.membership = membership;
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

    /**
     * 国战赛季分上报（V18，B13 承载 3b / 3b-2）：把一场国战<b>被结算的那一次</b>按人换成赛季分，
     * 进 {@link SeasonSettlement.Board#WAR}。分数由两部分组成：
     * <ul>
     *   <li><b>击杀分</b>：这一场里他的消灭数 × {@code WAR_SEASON_POINT_PER_KILLS}，
     *       低于 {@code WAR_SEASON_POINT_MIN_KILLS} 的人<b>不建行</b>（3b-1 那一条）；</li>
     *   <li><b>三条 bonus</b>：参战国成员每人 {@code PARTICIPANT}，胜国成员再加 {@code WINNER}，
     *       发起国成员再加 {@code INITIATOR_BONUS} —— 这三条<b>按国家花名册发</b>，
     *       所以没打的人只要在这个国里也拿得到（V18 §六 防刷第 2 条要的就是这个效果）。</li>
     * </ul>
     * 同一个人的两种分<b>合成一次 {@code accumulate}</b>（{@code accumulate} 是累加语义，
     * 分三次写就是三次原子写，而榜上那一行读起来仍然是合计 —— 见
     * {@code SeasonRulesAssembler.WarSeasonPoints#bonusOf}）。
     *
     * <p><b>只有一个调用点，而且它必须保证"每场仗只调一次"</b>（{@code WarStore.Settlement#settledNow}）。
     * 本方法走的是 {@code accumulate}（累加语义），调两次就是发两倍分。为什么不在这里再设一道幂等旗标：
     * 那要把旗标写进战事存档，而存档的"少带一项就复活出假状态"这条刚在 2c 被钉过；
     * 现在「结算转换只发生一次」是存储层临界区保证的、并且有用例钉住的更强事实，
     * 就不该为它再造第二份真相（同 B23 §五「不造第二本账」那条裁决的形状）。
     *
     * <p><b>胜负的唯一来源是 {@code result}</b>（内核 {@code settle()} 那一次的返回值）：
     * 平分时 {@code winnerId} 为 null（内核刻意不按 id 字典序硬挑赢家），此时<b>只发参与分与发起加成、
     * 不发胜方分</b> —— 这条判定只长在内核一处，本类不重算第二遍。
     * ⚠️ {@code result} <b>不许为 null</b>（{@code WarStore.Settlement} 的构造已经把「结掉了却没带结果」
     * 挡成一次抛）：{@code settledNow=false} 那一次压根不该走到这里。参战方全集也取自它，
     * 所以胜负与"这一场有哪些国家"是<b>同一次遍历</b>的产物，不存在两者对不上的窗口。
     *
     * <p><b>三条 bonus 全为 0 时连花名册都不查</b>：那是一次为 0 的发奖要付两次读（国家档 + 整国成员），
     * 而这三条参数的<b>出厂值就是 0</b>（档位等 #756 拍板）。所以「为 0 就整段跳过」是这一格的一条判据，
     * 用例把它钉住了 —— 删掉那句短路，读数就会红。
     *
     * <p><b>名字一次批量读回，且只读投影</b>（{@code PlayerRepository#findBriefs}）：加了 bonus 之后
     * 这条路一发奖就是<b>整国成员</b>，每人一次 {@code findByPlayerId} 会把"花名册那一跳已经批量"
     * 这件事在下一跳原样还回去。用 {@code findBriefs} 而不是 {@code findByPlayerIds}：这里要的只有昵称，
     * 而整档里的资源表 / PVP 账本 / 科技 / 权益都是白搬白反序列化的字节
     * （那两条口的分工写在 {@code PlayerRepository} 的注释里）。读档为 null 时给 null 名字，
     * 由 {@link #displayNameOf} 那一份回退兜住 —— <b>不要在这里填 playerId</b>（红线：屏上不出现裸 id）。
     *
     * @return 实际进账的人数（0 = 没发：赛季没开、没人打够门槛而且 bonus 又都是 0、或全是 Bot）
     */
    public int reportWarSeasonPoints(WarScoreBoard board, WarScoreBoard.Result result) {
        String seasonId = seasonIdOrNull();
        if (seasonId == null) {
            return 0;
        }
        SeasonRulesAssembler.WarSeasonPoints rules = assembler.warSeasonPoints();
        // 个人 → 这一场该进账的总分。LinkedHashMap：同一个人在击杀账和花名册里都出现时只留一行，
        // 而插入顺序让"发奖日志"和用例读到的顺序一致（不依赖 map 的哈希顺序）。
        Map<String, Long> pending = new LinkedHashMap<>();
        for (Map.Entry<String, Long> row : board.playerKillLedger().entrySet()) {
            long score = rules.pointsOf(row.getValue());
            if (score > 0L) {
                pending.merge(row.getKey(), score, Long::sum);
            }
        }

        // 胜负来源只有一份：内核 settle() 那一次的返回值（第二次调它直接抛，积分也已定格）。
        // 这里要求它非空而不是"没有就少发一档"—— 传 null 的调用方以为自己发了奖而实际没发，
        // 那是一次静默少发；抛出来才能在下一次探针里被看见。
        java.util.Objects.requireNonNull(result,
                "国战赛季分必须带结算结果：winnerId 与参战方都只能从 WarScoreBoard#settle 那一份拿");
        String winnerId = result.winnerId();
        String initiatorId = board.initiatorNationId();
        // result.scores() 的键集就是参战方全集，且行序与 winnerId 出自同一次遍历（两者不可能对不上）
        for (String nationId : result.scores().keySet()) {
            long bonus = rules.bonusOf(nationId, winnerId, initiatorId);
            if (bonus <= 0L) {
                continue;
            }
            // 只在真的要发的时候才去捞这一国的花名册 —— 上面那句短路是一条判据，不是一句优化
            Nation nation = nations.findById(nationId).orElse(null);
            for (String playerId : membership.playerIdsOf(nation)) {
                pending.merge(playerId, bonus, Long::sum);
            }
        }

        List<String> humans = new ArrayList<>();
        for (Map.Entry<String, Long> row : pending.entrySet()) {
            // bonus 全为 0 而击杀又没过门槛的人不会出现在 pending 里（两条路都给了 0 才被挡在门外），
            // 所以"不建行"这一形状在击杀与 bonus 之间是同一条（见 pointsOf 与那行门槛参数的 why）。
            if (row.getValue() <= 0L || bots.humanOnly(row.getKey(), "国战赛季分") == null) {
                continue;
            }
            humans.add(row.getKey());
        }
        if (humans.isEmpty()) {
            return 0;
        }
        // 名字一次批量读回（只取投影，不搬整档）：这条路一发奖就是整国成员，
        // 每人一次 findByPlayerId 会把"花名册那一跳已经批量"这件事在下一跳原样还回去。
        Map<String, PlayerBrief> briefs = players.findBriefs(humans);
        int applied = 0;
        for (String playerId : humans) {
            long score = pending.get(playerId);
            boards.accumulate(seasonId, SeasonSettlement.Board.WAR,
                    new SeasonSettlement.Entry(playerId,
                            briefs.get(playerId) == null ? null : briefs.get(playerId).nickName(),
                            score),
                    score);
            applied++;
        }
        return applied;
    }

    /**
     * 一页榜 + 我的名次（未上榜时名次为 null，不用 0 冒充 —— 验收 2）。
     *
     * <p>{@code size} 是**客户端按自己一屏能画几条**来要的（面板放不下 20 行时，
     * 显示 20 行里的前 8 行会让第 9~20 名永远看不到，翻页又会跳过它们）。0 表示"用上限"。
     * 上限仍是 {@code RANK_PAGE_SIZE_MAX} 且**由服务端夹**：客户端要 200 条只会拿到 20 条 ——
     * 于是验收 6 的体积预算不会因为客户端乱填而失效。
     */
    public RankListResp list(String playerId, RankType type, int page, int size) {
        captureTodayIfAbsent(type);
        List<SeasonSettlement.Entry> rows = rankedEntries(type);
        int pageSize = effectivePageSize(size);
        int totalPages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int safePage = Math.min(Math.max(1, page), totalPages);
        int from = (safePage - 1) * pageSize;
        int to = Math.min(rows.size(), from + pageSize);

        List<RankEntryView> entries = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            entries.add(toView(i + 1, rows.get(i), type));
        }
        MyRow mine = myRowOf(playerId, type, rows);
        // dayKey 与 `captureTodayIfAbsent` 用的是**同一个源**（todayKey() → DayKey.of(serverNow)）：
        // 客户端拿着它去查 /rank/snapshot，就不必自己算一个日期（那会造出第二条日切轴）。
        return new RankListResp(type, entries, mine.rank(), mine.value(),
                safePage, pageSize, to < rows.size(), todayKey());
    }

    /**
     * 我的名次（验收 2）。条目带我所在的那一页：面板要把"我的名次恒在顶部"画出来，
     * 而只回一个数字的话，那一行还得客户端自己拼一份（本仓库禁止客户端拼榜）。
     */
    public RankListResp me(String playerId, RankType type) {
        List<SeasonSettlement.Entry> rows = rankedEntries(type);
        MyRow mine = myRowOf(playerId, type, rows);
        if (mine.rank() == null) {
            return list(playerId, type, 1, 0);
        }
        int pageSize = effectivePageSize(0);
        return list(playerId, type, (mine.rank() - 1) / pageSize + 1, 0);
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
        int pageSize = effectivePageSize(0);
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

    /**
     * 实际生效的每页条数：0（或不传）用上限，其余**夹**在 [1, 上限] 之内。
     *
     * <p>客户端要几条是**显示需要**（一屏能画几行），上限是**体积预算**（验收 6）——
     * 两者不是同一个约束，所以夹取必须在服务端做，而且只有这一处实现。
     */
    private int effectivePageSize(int requested) {
        int cap = (int) configs.longParam("RANK_PAGE_SIZE_MAX");
        if (requested <= 0) {
            return cap;
        }
        return Math.min(requested, cap);
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
                    "不认识的榜类型：" + raw + "（可选 " + TYPE_OPTIONS + "）");
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
            //
            // 那句 "榜前 N 奖励坑位" 命名的是它服务的**红线条款**（B13 §七：Bot 不得占据需真人竞争的
            // 前 3 名奖励坑位；`RankEndpointTest` 验收 3 用同一措辞），**不是这里的过滤宽度** ——
            // 实际执行更严：Bot 从整张榜摘掉。别把这句改成"整榜排除"，那会切断代码与红线的对应；
            // 也别以为改 `BotTuning.mayEnterRankTop` 能改变这里（那个方法在生产里没有调用点，理由见它的注释）。
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
     *
     * <p><b>整榜的组织归属按批量读，不逐个问</b>：这里的循环长度是<b>整张 POWER 榜</b>（那张榜不
     * 截断），改之前每个榜上成员一次 {@code allianceOf}、国家榜再加一次 {@code findByAlliance}，
     * 而它挂在 {@code /rank/list} 这条人人都要拉的读路径上 —— 本仓现存最重的 N+1。名字（盟名带缩写、
     * 国名）顺手从同一批档里取，所以按 id 的那趟点查也一起没了。
     * 判据是往返计数而不是结果：{@code RankOrgBoardQueryCountTest}。
     */
    private List<SeasonSettlement.Entry> projectOrgBoard(String seasonId, RankType type) {
        List<SeasonSettlement.Entry> members = rankedEntries(RankType.POWER);
        Map<String, Alliance> allianceByPlayer = social.alliancesOf(
                members.stream().map(SeasonSettlement.Entry::id).toList());
        Map<String, Nation> nationByAlliance = type == RankType.NATION
                ? nations.nationsByAlliance(allianceByPlayer.values().stream()
                        .map(Alliance::id).distinct().toList())
                : Map.of();

        Map<String, Long> scoreByOrg = new LinkedHashMap<>();
        Map<String, String> nameByOrg = new LinkedHashMap<>();
        for (SeasonSettlement.Entry entry : members) {
            Alliance alliance = allianceByPlayer.get(entry.id());
            if (alliance == null) {
                continue;   // 不在任何联盟：与改之前 orgIdOf 回 null 同一条，直接跳过
            }
            if (type == RankType.ALLIANCE) {
                scoreByOrg.merge(alliance.id(), entry.score(), Long::sum);
                nameByOrg.putIfAbsent(alliance.id(), alliance.name() + "[" + alliance.tag() + "]");
                continue;
            }
            Nation nation = nationByAlliance.get(alliance.id());
            if (nation == null) {
                continue;   // 盟没入籍：它的人不进国家榜
            }
            scoreByOrg.merge(nation.id(), entry.score(), Long::sum);
            nameByOrg.putIfAbsent(nation.id(), nation.name());
        }
        List<SeasonSettlement.Entry> out = new ArrayList<>(scoreByOrg.size());
        scoreByOrg.forEach((orgId, score) ->
                out.add(new SeasonSettlement.Entry(orgId, nameByOrg.get(orgId), score)));
        out.sort(Comparator.comparingLong(SeasonSettlement.Entry::score).reversed()
                .thenComparing(SeasonSettlement.Entry::id));
        return out;
    }

    /**
     * 玩家此刻所属的组织 id（联盟榜回联盟，国家榜回国家）；不在任何组织里就是 null。
     *
     * <p><b>这里也走批量口</b>（单人批量）：省的不是往返 —— 一次与一次一样 —— 而是让"本类的读路径
     * 上不存在按人点查"成为一条能写成 {@code isZero()} 的判据。留着这一趟"我的名次"的点查，
     * 计数判据就只能写成"不随榜长增长"那种要采样两次才成立的弱断言。Mongo 侧单人 {@code $in} 与
     * {@code findOne} 打在同一个索引字段上，代价相同。
     */
    private String orgIdOf(String playerId, RankType type) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        Alliance alliance = social.alliancesOf(List.of(playerId)).get(playerId);
        if (alliance == null) {
            return null;
        }
        if (type == RankType.ALLIANCE) {
            return alliance.id();
        }
        Nation nation = nations.nationsByAlliance(List.of(alliance.id())).get(alliance.id());
        return nation == null ? null : nation.id();
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
