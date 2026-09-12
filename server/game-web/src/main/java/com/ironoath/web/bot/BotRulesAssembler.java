package com.ironoath.web.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BotArchetypeCfg;
import com.ironoath.config.cfg.BotChatCfg;
import com.ironoath.config.cfg.BotNameCfg;
import com.ironoath.config.cfg.BotScheduleCfg;
import com.ironoath.core.bot.BotArchetype;
import com.ironoath.core.bot.BotChatBook;
import com.ironoath.core.bot.BotNameGenerator;
import com.ironoath.core.bot.BotSchedule;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.core.bot.BotTuning;

/**
 * 职责：把 bot_archetype / bot_name 两张表与 global 的 BOT_* 参数装配成孵化要用的 core 类型
 * （铁律 1：数值零硬编码）。依赖：game-config、game-core。
 *
 * <p>与 {@code NationRulesAssembler}、{@code SocialRulesAssembler} 是同一种东西：
 * game-core 按 B00 分层规则读不到配置表，所以所有数值都必须由外层解析好再传进去。
 *
 * <p><b>每次调用都重新装配、不缓存</b>：配置表支持热更，缓存会让热更在这条路径上失效。
 *
 * <p><b>AiProfile 的四个维度里，两个直接来自表列，一个派生，理由各不相同</b>：
 * <ol>
 *   <li>{@code aggression} / {@code sociability(=表里的 socialness)} —— 读表，无派生</li>
 *   <li>{@code greed} —— <b>读表</b>（bot_archetype.greed 列，2026-09-12 的 C0 加的）。
 *       它此前是 {@code greedOf(playStyle)} 里五个硬编码的数，而"哪个原型偏采集"是策划数值，
 *       住在 Java 里就等于改一个数值要发一次版（铁律 1）</li>
 *   <li>{@code activeness} —— <b>派生自 {@code activeHoursPattern} 的覆盖度</b>（活跃几小时 / 24）。
 *       这一条<b>刻意不补列</b>：它的全部含义就是那份小时列表，加一列就把同一个事实存两处，
 *       早晚一份改了另一份没改（收口清单 #84 的欠账丁就是这么了结的：一列进表、
 *       一列判定为不该进表）。activeness 的消费者是 tick 频率（运行时那一档，收口清单 §五 C1），
 *       在那之前它只影响画像完整性，不影响任何行为。</li>
 * </ol>
 */
@Component
public class BotRulesAssembler {

    /** bot_archetype 主键前缀与占比参数的映射：{@code bot_paoyao → BOT_SHARE_PAOYAO}。 */
    private static final String ARCHETYPE_ID_PREFIX = "bot_";
    private static final String SHARE_PARAM_PREFIX = "BOT_SHARE_";
    /** 占比参数的单位是「百分比」（30 = 30%），转定点前要除它。 */
    private static final long PERCENT_SCALE = 100L;

    private final ConfigRegistry configs;

    public BotRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 数量/强度/频控规则（B11 §五）。来源见 {@link BotTuning.Rules} 的字段注释。 */
    public BotTuning.Rules tuningRules() {
        return new BotTuning.Rules(
                configs.fixedParam("BOT_POWER_RATIO_MIN"),
                configs.fixedParam("BOT_POWER_RATIO_MAX"),
                configs.fixedParam("BOT_POWER_CHECK_MIN"),
                configs.fixedParam("BOT_POWER_CHECK_MAX"),
                (int) configs.longParam("BOT_ATTACK_LIMIT_PER_24H"),
                (int) configs.longParam("BOT_MAX_PER_SERVER"),
                configs.fixedParam("BOT_DENSITY_RATIO_D1"),
                configs.fixedParam("BOT_DENSITY_RATIO_D7"),
                configs.fixedParam("BOT_DENSITY_RATIO_D30"));
    }

    /**
     * 六种原型的孵化模板。占比合计必须正好是 100% —— 少于 100 意味着有一部分孵化请求
     * 抽不到任何原型（表配漏了一行），多于 100 意味着有个原型按比例永远抽不到。
     * 两种都不该静默发生，所以在这里当场拒绝。
     */
    public List<BotArchetype> archetypes() {
        List<BotArchetypeCfg> rows = configs.all(BotArchetypeCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("bot_archetype 表为空：算不出任何原型，Bot 孵化无从谈起");
        }
        long bandMin = configs.fixedParam("BOT_GROWTH_FACTOR_MIN");
        long bandMax = configs.fixedParam("BOT_GROWTH_FACTOR_MAX");
        List<BotArchetype> out = new ArrayList<>(rows.size());
        long shareSum = 0L;
        for (BotArchetypeCfg row : rows) {
            long share = shareOf(row.id());
            shareSum += share;
            // 成长系数是「参差」而不是「失控」：表里的区间必须落在 global 的 [0.7, 1.3] 内。
            // 超出上界会让个别 Bot 强过真人均值（B08 圈层规则的失效），
            // 超出下界则让 Bot 弱到白给（§五 明写「不会白给」）
            if (row.growthFactorMin() < bandMin || row.growthFactorMax() > bandMax) {
                throw new IllegalStateException("原型 " + row.id() + " 的成长系数区间 ["
                        + FixedPoint.format(row.growthFactorMin()) + ","
                        + FixedPoint.format(row.growthFactorMax()) + "] 超出了 global 的 ["
                        + FixedPoint.format(bandMin) + "," + FixedPoint.format(bandMax)
                        + "]，请对齐 bot_archetype 与 BOT_GROWTH_FACTOR_MIN/MAX");
            }
            List<Integer> hours = parseHours(row.id(), row.activeHoursPattern());
            // greed 的区间在 core（BotArchetype 的 requireRatio）也校验一次 —— 两层各说一次不是重复：
            // core 报的是「greed 越界」，这里报的是「**哪个原型**的 greed 越界」。
            // 少这一句，配置填错的人要先去猜六行里是哪一行（与 SeasonRulesAssembler 同一条理由）
            if (row.greed() < 0L || row.greed() > FixedPoint.ONE) {
                throw new IllegalStateException("原型 " + row.id() + " 的 greed="
                        + FixedPoint.format(row.greed()) + " 不在 [0, 1.0] 区间内（bot_archetype.greed 列）");
            }
            out.add(new BotArchetype(row.id(), row.name(), share,
                    row.aggression(), row.socialness(), row.powerFactor(),
                    row.greed(), activenessOf(hours),
                    row.growthFactorMin(), row.growthFactorMax(),
                    row.reactionDelayMinSec(), row.reactionDelayMaxSec(),
                    row.suboptimalChanceMin(), row.suboptimalChanceMax(),
                    hours));
        }
        if (shareSum != FixedPoint.SCALE) {
            throw new IllegalStateException("六种原型的占比合计必须为 100%，实际="
                    + FixedPoint.format(shareSum) + "（检查 global 里六行 BOT_SHARE_* 与 bot_archetype 是否一一对应）");
        }
        return List.copyOf(out);
    }

    /**
     * 作息规则（B11 §四「按真人曲线分布 tick」）。
     *
     * <p>来源是 {@code bot_schedule} 的 142 行 (hour, action, weight) 与 global 的
     * {@code BOT_TICKS_PER_DAY_MIN/MAX}。<b>权重按 (action, hour) 收进两层 map</b>，
     * 因为 {@code BotSchedule.Rules} 要的就是这个形状 —— 装配器负责把表的"行"折成"索引"，
     * core 侧不需要知道表长什么样（分层规则）。
     *
     * <p><b>{@code isWeekendOnly} 今天一律是 false</b>（表里 142 行全是），而 {@code BotSchedule}
     * 也没有"周末"这个输入。所以这里<b>不静默跳过 true 的行</b>：出现了就抛 ——
     * 静默忽略会让"我配了周末权重为什么不生效"变成一个查不出来的问题。
     */
    public BotSchedule.Rules scheduleRules() {
        List<BotScheduleCfg> rows = configs.all(BotScheduleCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("bot_schedule 表为空：算不出作息分布，Bot 的 tick 无从排期");
        }
        Map<BotSchedule.Action, Map<Integer, Integer>> weights =
                new java.util.EnumMap<>(BotSchedule.Action.class);
        for (BotScheduleCfg row : rows) {
            if (row.isWeekendOnly()) {
                throw new IllegalStateException("bot_schedule 行 " + row.id()
                        + " 配了 isWeekendOnly=true，但作息模型里没有「周末」这个输入 —— "
                        + "要么删掉这行的标记，要么先给 BotSchedule 加周末维度（收口清单 §五 C1b）");
            }
            int hour = (int) row.hourOfDay();
            if (hour < 0 || hour > 23) {
                throw new IllegalStateException("bot_schedule 行 " + row.id()
                        + " 的 hourOfDay=" + hour + " 越界（必须落在 0~23）");
            }
            weights.computeIfAbsent(toScheduleAction(row.actionType()), key -> new HashMap<>())
                    .put(hour, (int) row.weight());
        }
        return new BotSchedule.Rules((int) configs.longParam("BOT_TICKS_PER_DAY_MIN"),
                (int) configs.longParam("BOT_TICKS_PER_DAY_MAX"), weights);
    }

    /**
     * 调度器的两条闸（收口清单 #90 拆开的口径）：一轮的墙钟预算 + 一轮最多取多少条。
     *
     * <p><b>注意读的是 {@code BOT_ROUND_BUDGET_MS} 而不是 {@code BOT_TICK_BUDGET_MS}</b>：
     * 后者是"单个 Bot 一次 tick 的耗时目标"（验收 6 拿去对日志的量具），不是闸。
     * 这两个数在 #90 之前被同一个字段混用过，名字也骗人 —— 别再改回去。
     */
    public BotScheduler.Rules schedulerRules() {
        return new BotScheduler.Rules(configs.longParam("BOT_ROUND_BUDGET_MS"),
                (int) configs.longParam("BOT_DUE_BATCH_LIMIT"));
    }

    /** 生成物枚举 → core 枚举。两个枚举各有一份"同名不同类"是分层规则的代价，映射只能有一个家。 */
    private static BotSchedule.Action toScheduleAction(BotScheduleCfg.ActionType type) {
        return switch (type) {
            case LOGIN -> BotSchedule.Action.LOGIN;
            case LOGOUT -> BotSchedule.Action.LOGOUT;
            case BUILD -> BotSchedule.Action.BUILD;
            case TRAIN -> BotSchedule.Action.TRAIN;
            case GATHER -> BotSchedule.Action.GATHER;
            case ATTACK_MONSTER -> BotSchedule.Action.ATTACK_MONSTER;
            case JOIN_RALLY -> BotSchedule.Action.JOIN_RALLY;
            case CHAT -> BotSchedule.Action.CHAT;
            case DONATE -> BotSchedule.Action.DONATE;
        };
    }

    /**
     * 聊天句库（B11 §四「事件触发模板句库」）。来源 {@code bot_chat} 表（18 行、8 个场景）。
     *
     * <p><b>生成物枚举 → core 枚举的映射只有这一个家</b>（与作息权重同一条纪律）：
     * 两侧各写一份 switch 的那一天，表里新增一个场景就会在一边报警、在另一边静默漏掉。
     *
     * <p><b>等级门槛照原样带进 core</b>（不在这里过滤）：筛选发生在 {@code BotChatBook.pick}
     * —— 因为"这个等级该说哪几句"是句库的不变量，而装配器的职责只是把表搬过来。
     */
    public BotChatBook chatBook() {
        List<BotChatCfg> rows = configs.all(BotChatCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("bot_chat 表为空：Bot 没有可说的话，聊天通道等于没接");
        }
        List<BotChatBook.Line> lines = new ArrayList<>(rows.size());
        for (BotChatCfg row : rows) {
            lines.add(new BotChatBook.Line(row.id(), toChatScene(row.scene()), row.text(),
                    (int) row.weight(), row.minCityLevel(), row.maxCityLevel()));
        }
        return new BotChatBook(new BotChatBook.Rules(lines));
    }

    private static BotChatBook.Scene toChatScene(BotChatCfg.Scene scene) {
        return switch (scene) {
            case HELP_REQUEST -> BotChatBook.Scene.HELP_REQUEST;
            case RALLY_CALL -> BotChatBook.Scene.RALLY_CALL;
            case ATTACKED -> BotChatBook.Scene.ATTACKED;
            case VICTORY -> BotChatBook.Scene.VICTORY;
            case DEFEAT -> BotChatBook.Scene.DEFEAT;
            case CHAT_IDLE -> BotChatBook.Scene.CHAT_IDLE;
            case ALLIANCE_JOIN -> BotChatBook.Scene.ALLIANCE_JOIN;
            case TRADE -> BotChatBook.Scene.TRADE;
        };
    }

    /**
     * 名字生成器（B11 §四：姓表 × 名表 × 称号表，服务器内唯一，禁止机器名）。
     *
     * <p>三个池子的行 id 来自 global（{@code BOT_NAME_*_POOL}）而不是写死在代码里：
     * 换一套名字库是运营动作，不该需要改代码。
     */
    public BotNameGenerator nameGenerator() {
        return new BotNameGenerator(
                poolOf("BOT_NAME_SURNAME_POOL", "姓"),
                poolOf("BOT_NAME_GIVEN_POOL", "名"),
                poolOf("BOT_NAME_TITLE_POOL", "称号"),
                configs.fixedParam("BOT_NAME_TITLE_CHANCE"));
    }

    // ---------- 内部 ----------

    /**
     * 占比：global 的 BOT_SHARE_&lt;原型后缀大写&gt;。
     *
     * <p>按 id 派生参数名（{@code bot_paoyao → BOT_SHARE_PAOYAO}）而不是在表里再存一列参数名：
     * 两个家会让「加了原型却忘了配占比」静默漏掉一种原型，而这里会当场抛出来。
     */
    private long shareOf(String archetypeId) {
        if (!archetypeId.startsWith(ARCHETYPE_ID_PREFIX)) {
            throw new IllegalStateException("bot_archetype 的 id 必须以 " + ARCHETYPE_ID_PREFIX
                    + " 开头（占比参数按它派生）：" + archetypeId);
        }
        String param = SHARE_PARAM_PREFIX
                + archetypeId.substring(ARCHETYPE_ID_PREFIX.length()).toUpperCase(Locale.ROOT);
        if (!configs.hasParam(param)) {
            throw new IllegalStateException("缺少占比参数 " + param + "（对应原型 " + archetypeId
                    + "）：新增原型时必须一起配占比，否则这次孵化会漏掉它");
        }
        return FixedPoint.div(FixedPoint.of(configs.longParam(param)), FixedPoint.of(PERCENT_SCALE));
    }

    /**
     * activeness 由活跃小时的覆盖度派生：每天活跃几小时 / 24。
     *
     * <p>原 {@code greedOf(playStyle)} 在这里把五个数值硬编码在 Java 里，2026-09-12 的 C0 把它们
     * 逐原型搬进了 {@code bot_archetype.greed} 列（值一字未改）—— 所以本文件不再读 {@code playStyle}。
     * <b>{@code playStyle} 现在没有代码读者</b>：它的数值含义已经搬走，剩下的是「这个原型属于 B11 §一
     * 哪一种定位」这句话本身。计划由 §五 C3 的掠袭分支用它做资格判定（RAIDER 才有掠袭倾向）；
     * 那一档若不做，这列就该在表里标作废，而不是留成"改了不生效"的装饰。
     */
    private static long activenessOf(List<Integer> hours) {
        if (hours.size() > 24) {
            throw new IllegalStateException("活跃小时数不可能超过 24，实际=" + hours.size());
        }
        return FixedPoint.div(FixedPoint.of(hours.size()), FixedPoint.of(24L));
    }

    private static List<Integer> parseHours(String archetypeId, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException("原型 " + archetypeId + " 的 activeHoursPattern 为空");
        }
        String[] parts = pattern.split(",");
        List<Integer> hours = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                hours.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("原型 " + archetypeId + " 的 activeHoursPattern 含非数字："
                        + part, e);
            }
        }
        return hours;
    }

    private List<String> poolOf(String globalId, String what) {
        String rowId = configs.stringParam(globalId);
        BotNameCfg row = configs.get(BotNameCfg.class, rowId);
        JsonNode pool = row.namePool();
        if (pool == null || !pool.isArray() || pool.isEmpty()) {
            throw new IllegalStateException(globalId + " 指向的 " + rowId + " 的 namePool 不是非空数组，"
                    + "无法作为" + what + "池使用");
        }
        List<String> values = new ArrayList<>(pool.size());
        pool.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
