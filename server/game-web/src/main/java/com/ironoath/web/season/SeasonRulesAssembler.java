package com.ironoath.web.season;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.SeasonCfg;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.service.ServerCalendar;

/**
 * 职责：把 global 表的赛季参数装配成 game-core 的规则对象（B14 §2 段位、§4 结算）。
 * 依赖：game-config、game-core。
 *
 * <p><b>本类补的是一个铁律 1 的缺口</b>：B14 交付时领域层是干净的（{@code SeasonTier.Rules} 与
 * {@code SeasonSettlement.Rules} 都由外部传入），但那些数字<b>只活在单测夹具里</b> ——
 * 配置表里一个赛季参数都没有。那样一来，写 web 层的人只能在代码里手填一组数字，
 * 而手填的数字既躲过了策划评审，也没法在赛季中途调整。
 *
 * <p><b>时间轴也在本类</b>（{@link #timelineRules()}）：2026-09-08 已裁决「时长以 season 表为准
 * （45 天五阶段），B14 §一改写」，而「目标阶段 → 规则阶段」的映射也已经进表（{@code rulePhase} 列）。
 * 于是装配器只读表，Java 里没有任何阶段名、天数或映射分支 —— 这是 B14 开工提示词
 * 「换赛季只改配置 + 换表，不改代码」的可执行形态。
 *
 * <p><b>赛季 id 从行 id 的前缀反推</b>（{@code season_01_phase_3} ⇒ {@code season_01}），
 * 而不是再加一个全局参数：一行的前缀就是这一季，两处定义迟早漂移成
 * 「归档集合叫 season_01 而时间轴读的是 season_02」，那种数据错乱没有任何测试会红。
 *
 * <p><b>每次调用都重新装配，不缓存</b>：与其它装配器同一条理由 ——
 * 配置支持热更，而赛季参数恰恰是「赛季中途发现奖励太抠要调」的那一类。
 */
@Component
public class SeasonRulesAssembler {

    private final ConfigRegistry configs;
    private final TimeService timeService;

    /**
     * 单参构造器（保留给直接 new 的测试）：时间源退化成系统时钟。
     *
     * <p><b>为什么不把它删掉</b>：仓库里有 11 处 {@code new SeasonRulesAssembler(configs)}
     * （{@code RankEndpointTest} / {@code SeasonRulesAssemblerTest} / {@code SeasonSettlementTest} /
     * {@code SeasonStatusTest}），而它们要的只是「赛季未启用时恒取第一季」这个读数 ——
     * 那条读数与时钟无关。删掉它等于让 4 个测试类为了一个与它们无关的构造参数而改。
     */
    public SeasonRulesAssembler(ConfigRegistry configs) {
        this(configs, new TimeService(System::currentTimeMillis));
    }

    /** 生产路径：时间源是 {@link TimeService}（唯一时间基准，测试可完全控制）。 */
    @Autowired
    public SeasonRulesAssembler(ConfigRegistry configs, TimeService timeService) {
        this.configs = configs;
        this.timeService = timeService;
    }

    /**
     * 段位规则（B14 §2）。
     *
     * <p>{@code SEASON_TIER_PROGRESS_KEEP} 是 DECIMAL，已由 FixedPointDeserializer 转成定点 long
     * （0.5 → 5000），<b>不要再转一次</b>：多转一次不报错，只会让「保留一半进度」变成
     * 「保留 5000 倍进度」，而 {@code SeasonTier.Rules} 的构造期校验不一定拦得住
     * （它校验的是门槛数组与降档步数，进度保留率只是一个定点数）。
     */
    public SeasonTier.Rules tierRules() {
        return new SeasonTier.Rules(
                parseThresholds(configs.stringParam("SEASON_TIER_THRESHOLDS")),
                (int) configs.longParam("SEASON_DEMOTE_STEPS"),
                configs.fixedParam("SEASON_TIER_PROGRESS_KEEP"));
    }

    /**
     * 赛季时间轴（B14 §一）。逐行读 {@code season} 表，本方法里没有任何阶段名、天数或映射分支。
     *
     * <p><b>规则阶段取自 {@code rulePhase} 列</b>：表描述的是「这阶段要玩家做什么」（目标阶段），
     * 而 {@code SeasonTimeline.Phase} 决定「这阶段允许什么」（能不能 PVP、王城是否开放、是否结算）。
     * 两者不是一回事（立盟期与争锋期是两个目标、同一条扩张规则），所以映射必须进表；
     * 写进 Java 的 if-else 之后，「换赛季只改配置」就成了一句空话。
     *
     * <p><b>表是空的就抛</b>：没有时间轴的赛季连「现在第几天该干什么」都答不出来，
     * 带着空阶段列表启动的症状是所有 PVP 被无声放行。
     */
    public SeasonTimeline.Rules timelineRules() {
        return timelineRules(timeService.serverNow());
    }

    /**
     * 赛季时间轴，带显式「现在」（毫秒）。
     *
     * <p><b>为什么要这个重载</b>：赛季号会随天数推进，于是 {@link #timelineRules()} 的读数依赖墙上时钟 ——
     * 而「今天是第 50 天 ⇒ season_02」这类断言只有把 now 钉住才写得出来（时间夹具 discipline）。
     * 生产走无参版（时间源是 {@link TimeService}），单测走这个。
     *
     * <p><b>为什么必须按赛季筛行</b>（2026-10-03 裁决）：season 表现在有 {@code season_01..season_05}
     * 共 25 行，而 {@code SeasonTimeline.Rules} 的构造期校验要求 {@code phaseNo} 从 1 起连续、
     * 且后一阶段起点恰好等于前一阶段终点 —— 把 25 行一起塞进去会直接抛
     * {@code IllegalArgument: 阶段序号必须从 1 起连续，实际缺了 2}。
     * 所以要先取出「当前这一季」那 5 行，别的季的行不进这条时间轴。
     */
    public SeasonTimeline.Rules timelineRules(long now) {
        List<SeasonCfg> allRows = new ArrayList<>(configs.all(SeasonCfg.class));
        if (allRows.isEmpty()) {
            throw new com.ironoath.config.ConfigException(
                    "season 表没有任何阶段行：赛季时间轴为空，无法判定当前处于哪个阶段");
        }
        String currentId = currentSeasonIdOf(allRows, now);
        List<SeasonCfg> rows = new ArrayList<>();
        for (SeasonCfg row : allRows) {
            if (seasonIdOf(row.id()).equals(currentId)) {
                rows.add(row);
            }
        }
        rows.sort((a, b) -> Long.compare(a.phaseNo(), b.phaseNo()));
        if (rows.isEmpty()) {
            // 没有这条守卫的话，症状是下面 rows.get(0) 抛 IndexOutOfBounds（0/0）——
            // 那句话既不说是哪个赛季号对不上，也不说表里有几个赛季，等于把排查留给下一个人。
            throw new com.ironoath.config.ConfigException(
                    "season 表里找不到当前赛季 " + currentId + " 的阶段行：表内共 " + allRows.size()
                            + " 行、赛季前缀 " + new TreeSet<>(seasonIdsOf(allRows)));
        }
        List<SeasonTimeline.Stage> stages = new ArrayList<>(rows.size());
        for (var row : rows) {
            stages.add(new SeasonTimeline.Stage((int) row.phaseNo(),
                    SeasonTimeline.Phase.valueOf(row.rulePhase().name()),
                    row.startDayOffset(), row.durationDays()));
        }
        return new SeasonTimeline.Rules(stages, seasonIdOf(rows.get(0).id()));
    }

    /**
     * 当前是第几季（返回行 id 前缀，如 {@code season_02}）。
     *
     * <p><b>三条口径，每条都有出处</b>：
     * ① <b>赛季未启用（没配 {@code SEASON_START_AT}）⇒ 恒取第一季</b>。
     * 沿用 {@link ServerCalendar#seasonStartOrZero} 的既有语义（返回 0 = 未启用）；
     * 若这里改成「按开服天数硬推」，会出现「开服 0 天就在 season_02」这种没开季的服。
     * ② <b>启用后按天数推进</b>：{@code 天数 / 单季总天数} 即第几季（0 基）。
     * ③ <b>天数越过表里最后一季 ⇒ 取最后一季</b>，不报错：表里只写到 season_05，
     * 而 seasonId 补零到两位（{@code SeasonSettlementService} 注释：字典序 = 时间序），
     * 越界时取最大前缀是唯一不制造「新 id」的选择 —— 编一个 season_06 会让
     * {@code purgeArchivedSeasons} 的归档比较拿到一个表里不存在的季号。
     *
     * <p><b>「各季等长」是当前表的事实而非假设</b>：#751 裁决「复用 season_01 的目标与奖励」，
     * 5 季的 {@code durationDays} 完全相同（7/7/14/14/3 = 45 天），所以用第一季的长度算索引是准的。
     * 若将来各季不等长，本方法要改成「逐季累加到目标天数落在哪一季」—— 届时
     * {@code SeasonTimeline.Rules} 的无空洞校验会先把它逼出来。
     */
    private String currentSeasonIdOf(List<SeasonCfg> allRows, long now) {
        List<String> ids = new ArrayList<>(new TreeSet<>(seasonIdsOf(allRows)));
        long start = ServerCalendar.seasonStartOrZero(configs);
        if (start <= 0L) {
            return ids.get(0);
        }
        long days = Math.max(0L, DayKey.daysBetween(start, now));
        long seasonLength = seasonLengthDays(allRows, ids.get(0));
        if (seasonLength <= 0L) {
            return ids.get(0);
        }
        long index = days / seasonLength;
        return ids.get((int) Math.min(index, ids.size() - 1L));
    }

    /** 表里出现过��赛季 id（去重、字典序升序）。季号补零两位 ⇒ 字典序 = 时间序。 */
    private static List<String> seasonIdsOf(List<SeasonCfg> rows) {
        List<String> ids = new ArrayList<>();
        for (SeasonCfg row : rows) {
            ids.add(seasonIdOf(row.id()));
        }
        return ids;
    }

    /** 某一季的总天数（各阶段 {@code durationDays} 之和）。 */
    private static long seasonLengthDays(List<SeasonCfg> rows, String seasonId) {
        long total = 0L;
        for (SeasonCfg row : rows) {
            if (seasonIdOf(row.id()).equals(seasonId)) {
                total += row.durationDays();
            }
        }
        return total;
    }

    /** {@code season_01_phase_3} ⇒ {@code season_01}。为什么不再加一个全局参数见类注释。 */
    private static String seasonIdOf(String rowId) {
        int cut = rowId.indexOf("_phase_");
        return cut > 0 ? rowId.substring(0, cut) : rowId;
    }

    /**
     * 结算规则（B14 §4）。
     *
     * <p><b>结算依据哪张榜是配置项而不是常量</b>：按战力结算与按击杀结算会导致完全不同的
     * 赛季策略（前者鼓励养成，后者鼓励开战），那是运营决策，不该由代码替运营做。
     */
    public SeasonSettlement.Rules settlementRules() {
        return new SeasonSettlement.Rules(
                boardOf(configs.stringParam("SEASON_SETTLE_BOARD")),
                (int) configs.longParam("SEASON_REWARDED_TOP_N"),
                configs.longParam("SEASON_COIN_PER_RANK"),
                (int) configs.longParam("SEASON_ARCHIVE_RETENTION"));
    }

    /**
     * 解析段位门槛。
     *
     * <p><b>在这里校验个数而不是只靠领域层</b>：{@code SeasonTier.Rules} 也会校验，
     * 但它报的是「段位门槛必须有 6 个」，而配置写错的人需要听到的是
     * 「global.SEASON_TIER_THRESHOLDS 里有 5 个数」。同一个校验在两层各说一次不是重复 ——
     * 领域层守的是不变量，这里守的是「哪一行配置填错了」。
     */
    private static long[] parseThresholds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("global.SEASON_TIER_THRESHOLDS 为空：算不出任何段位");
        }
        List<Long> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("global.SEASON_TIER_THRESHOLDS 里有非数字项「" + trimmed
                        + "」，完整值=" + raw, e);
            }
        }
        int expected = SeasonTier.Tier.values().length;
        if (values.size() != expected) {
            throw new IllegalStateException("global.SEASON_TIER_THRESHOLDS 必须有 " + expected
                    + " 个数（每个段位一个门槛），实际 " + values.size() + " 个，完整值=" + raw);
        }
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * 国战赛季分（V18，B13 承载 3b）：把 global 的 {@code WAR_SEASON_*} 那<b>五</b>行装配成一份规则。
     *
     * <p><b>为什么放在赛季装配器而不是 {@code WarRulesAssembler}</b>：这些分写的是
     * {@code SeasonBoardStore} 那张赛季榜，而读它的人（{@code RankBoardService#reportWarSeasonPoints}）
     * 手里已经握着本类；放过去会把「国战积分板的规则」与「国战发的赛季分」混成一份 Rules ——
     * 前者的语义是<b>不进战事存档、每次现取</b>（见 {@code WarRulesAssembler} 的类注释），
     * 后者只在结算那一刻用一次，两者的生命周期不是一件事。
     *
     * <p><b>0 是合法值</b>：门槛给 0 = 不设门槛，每杀给 0 = 这一场不发分（运营旋钮的两种"关掉"），
     * 而 3b-2 那三条 bonus（{@code WINNER}／{@code PARTICIPANT}／{@code INITIATOR_BONUS}）
     * 的<b>出厂值就是 0</b> —— 含义是「机制已接通、档位待 #756 拍板」，改表即生效、不改代码。
     * 所以校验只拦负数；<b>这里刻意不替调用方判"0 就跳过"</b>（那是省读的判据，属 {@code RankBoardService}）。
     */
    public WarSeasonPoints warSeasonPoints() {
        return new WarSeasonPoints(
                configs.longParam("WAR_SEASON_POINT_PER_KILL"),
                configs.longParam("WAR_SEASON_POINT_MIN_KILLS"),
                configs.longParam("WAR_SEASON_POINT_WINNER"),
                configs.longParam("WAR_SEASON_POINT_PARTICIPANT"),
                configs.longParam("WAR_SEASON_INITIATOR_BONUS"));
    }

    /**
     * @param pointPerKill   每消灭一个单位给的赛季分。来源 global.WAR_SEASON_POINT_PER_KILL
     * @param minKills       挂机门槛（<b>只管击杀分</b>，三条 bonus 不看个人击杀数，
     *                       见 {@code WAR_SEASON_POINT_PARTICIPANT} 的 why）。来源 global.WAR_SEASON_POINT_MIN_KILLS
     * @param winner         胜国成员每人一份。来源 global.WAR_SEASON_POINT_WINNER
     * @param participant    参战国成员每人一份（不分胜负）。来源 global.WAR_SEASON_POINT_PARTICIPANT
     * @param initiatorBonus 发起国成员在参与分之上再加一份。来源 global.WAR_SEASON_INITIATOR_BONUS
     */
    public record WarSeasonPoints(long pointPerKill, long minKills, long winner,
                                  long participant, long initiatorBonus) {

        public WarSeasonPoints {
            if (pointPerKill < 0) {
                throw new IllegalArgumentException("WAR_SEASON_POINT_PER_KILL 不得为负，实际=" + pointPerKill
                        + "。负分意味着「打国战反而扣赛季分」，那是另一条要产品口径的设计，"
                        + "不是这个旋钮的一个档位");
            }
            if (minKills < 0) {
                throw new IllegalArgumentException("WAR_SEASON_POINT_MIN_KILLS 不得为负，实际=" + minKills);
            }
            // 三条 bonus 各写一条而不是共用一段：报错时要能直接看出是哪一行表被改坏了，
            // 而这三档的语义并不相同（胜方分 / 参与分 / 发起加成），将来很可能只有其中一条被调成别的形状。
            if (winner < 0) {
                throw new IllegalArgumentException("WAR_SEASON_POINT_WINNER 不得为负，实际=" + winner
                        + "。负的胜方分等于「打赢了扣赛季分」，那会把这一格唯一的正反馈反接成惩罚");
            }
            if (participant < 0) {
                throw new IllegalArgumentException("WAR_SEASON_POINT_PARTICIPANT 不得为负，实际=" + participant
                        + "。负的参与分直接反接 V18 §六 防刷第 2 条（空转仗也要给参与分）那条设计");
            }
            if (initiatorBonus < 0) {
                throw new IllegalArgumentException("WAR_SEASON_INITIATOR_BONUS 不得为负，实际=" + initiatorBonus
                        + "。它是 V18 那节明写的<b>主钩子</b>（发动成本在发起方），"
                        + "给它负数就是在罚第一个动手的人，理性群体会永远不宣战");
            }
        }

        /**
         * 这名玩家这一场的分。<b>门槛不过返回 0 而不是负数或异常</b> —— 调用方按「0 就不写那一行」处理，
         * 于是"不建行"和"给 0 分"这两种形状里，实现只保留前者（见那行参数的 {@code why}）。
         */
        public long pointsOf(long kills) {
            if (kills <= 0L || kills < minKills || pointPerKill <= 0L) {
                return 0L;
            }
            return kills * pointPerKill;
        }

        /**
         * 这个国家本轮该给的 <b>bonus 合计</b>：参与分 + （打赢了才有的）胜方分 + （发起才有的）发起加成。
         *
         * <p><b>为什么给合计而不是三个各自的方法</b>：调用方要回答的是「这一国的成员因为国家身份进账多少」，
         * 而三种身份在同一个人身上是<b>叠加</b>的 —— 发起国打赢了，它的成员同时是参与者、胜者、发起者。
         * 分三次写进榜就是三次 {@code accumulate}（三次原子写、三份条目名字），收在一处只写一次；
         * 榜上那一行的读法也仍然是「合计」，不需要玩家自己加三遍。
         *
         * <p><b>平分时不发胜方分</b>：{@code winnerId} 为 null 是内核 {@code WarScoreBoard#settle} 的
         * 原样输出（两国同分刻意不按 id 字典序硬挑赢家），这里 {@code nationId.equals(null)} 恒不成立，
         * 于是那一条判定只长在内核一处，没有被抄第二遍。
         *
         * <p><b>三条全为 0 时返回 0，调用方据此连花名册都不查</b> —— 这是「0 = 不发」在代码里的唯一落点，
         * 也是那一条省读判据（别让一次为 0 的发奖付两次读）的根据。
         *
         * @param nationId    被问的那个参战国 id；null 时给 0（没有国家就没有成员）
         * @param winnerId    内核结算给的胜者，平分时为 null
         * @param initiatorId 发起这一场的国家 id，历史档可能为 null
         */
        public long bonusOf(String nationId, String winnerId, String initiatorId) {
            if (nationId == null) {
                return 0L;
            }
            long total = participant;
            if (nationId.equals(winnerId)) {
                total += winner;
            }
            if (nationId.equals(initiatorId)) {
                total += initiatorBonus;
            }
            return total;
        }
    }

    private static SeasonSettlement.Board boardOf(String raw) {
        try {
            return SeasonSettlement.Board.valueOf(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("global.SEASON_SETTLE_BOARD 的值「" + raw
                    + "」不是合法榜单类型，可选=" + java.util.Arrays.toString(SeasonSettlement.Board.values()), e);
        }
    }
}
