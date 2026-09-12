package com.ironoath.web.season;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.core.season.SeasonTier;

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

    public SeasonRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
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
        List<com.ironoath.config.cfg.SeasonCfg> rows =
                new ArrayList<>(configs.all(com.ironoath.config.cfg.SeasonCfg.class));
        if (rows.isEmpty()) {
            throw new com.ironoath.config.ConfigException(
                    "season 表没有任何阶段行：赛季时间轴为空，无法判定当前处于哪个阶段");
        }
        rows.sort((a, b) -> Long.compare(a.phaseNo(), b.phaseNo()));
        List<SeasonTimeline.Stage> stages = new ArrayList<>(rows.size());
        for (var row : rows) {
            stages.add(new SeasonTimeline.Stage((int) row.phaseNo(),
                    SeasonTimeline.Phase.valueOf(row.rulePhase().name()),
                    row.startDayOffset(), row.durationDays()));
        }
        return new SeasonTimeline.Rules(stages, seasonIdOf(rows.get(0).id()));
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

    private static SeasonSettlement.Board boardOf(String raw) {
        try {
            return SeasonSettlement.Board.valueOf(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("global.SEASON_SETTLE_BOARD 的值「" + raw
                    + "」不是合法榜单类型，可选=" + java.util.Arrays.toString(SeasonSettlement.Board.values()), e);
        }
    }
}
