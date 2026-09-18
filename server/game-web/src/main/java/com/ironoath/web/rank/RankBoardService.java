package com.ironoath.web.rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.dto.generated.RankEntryView;
import com.ironoath.web.dto.generated.RankListResp;
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

    public RankBoardService(SeasonBoardStore boards, SocialStore social, NationStore nations,
                            PlayerRepository players, BotRegistry bots, ConfigRegistry configs,
                            SeasonRulesAssembler assembler) {
        this.boards = boards;
        this.social = social;
        this.nations = nations;
        this.players = players;
        this.bots = bots;
        this.configs = configs;
        this.assembler = assembler;
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
