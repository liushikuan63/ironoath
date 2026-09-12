package com.ironoath.config;

import com.ironoath.config.cfg.BotArchetypeCfg;
import com.ironoath.config.cfg.BotChatCfg;
import com.ironoath.config.cfg.BotNameCfg;
import com.ironoath.config.cfg.BotScheduleCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：把 B11 文档里的 Bot 数值与合规红线钉成会失败的断言。
 * 依赖：JUnit 5 + AssertJ；读仓库里的真实表，不启动容器。
 *
 * <p><b>为什么这些必须写成测试</b>：bot_* 四张表加起来 379 行，每一行都是手可以改的数字，
 * 而它们改坏的后果都不是崩溃，是「Bot 一眼假」—— 验收 2 的盲测正确率会从 <70% 涨到 95%，
 * 而这个变化不会让任何一条现有测试变红。
 *
 * <p><b>本类还盯两条合规红线</b>（§七，违反即视为任务失败）：
 * 交易语料里不得出现金币/充值字样（禁止 Bot 诱导付费），
 * 以及每个原型都必须能在配置里被找到「它不许当盟主」这条约束的落点
 * （那条落点在 game-core 的 BotTuning.mayHoldOffice，本类只保证原型表本身完整）。
 */
class BotConfigTest {

    private static ConfigRegistry registry;
    private static List<BotArchetypeCfg> archetypes;
    private static List<BotNameCfg> names;
    private static List<BotChatCfg> chats;
    private static List<BotScheduleCfg> schedule;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        archetypes = registry.all(BotArchetypeCfg.class);
        names = registry.all(BotNameCfg.class);
        chats = registry.all(BotChatCfg.class);
        schedule = registry.all(BotScheduleCfg.class);
    }

    // ---------- 原型（§一） ----------

    @Test
    @DisplayName("六种原型齐全，且 global 里的占比合计恰为 100")
    void sixArchetypesWithSharesSummingTo100() {
        assertThat(archetypes).hasSize(6);
        Set<String> ids = new HashSet<>();
        for (BotArchetypeCfg row : archetypes) {
            ids.add(row.id());
        }
        assertThat(ids).containsExactlyInAnyOrder(
                "bot_paoyao", "bot_linju", "bot_mengyou", "bot_jielue", "bot_junfa", "bot_yingzi");

        long total = 0;
        for (String share : List.of("BOT_SHARE_PAOYAO", "BOT_SHARE_LINJU", "BOT_SHARE_MENGYOU",
                "BOT_SHARE_JIELUE", "BOT_SHARE_JUNFA", "BOT_SHARE_YINGZI")) {
            total += registry.longParam(share);
        }
        assertThat(total).as("六档占比合计必须恰为 100，否则孵化时会有 Bot 抽不到原型").isEqualTo(100L);
        // §一 给的分布：30 / 30 / 15 / 10 / 4 / 11
        assertThat(registry.longParam("BOT_SHARE_PAOYAO")).isEqualTo(30L);
        assertThat(registry.longParam("BOT_SHARE_LINJU")).isEqualTo(30L);
        assertThat(registry.longParam("BOT_SHARE_MENGYOU")).isEqualTo(15L);
        assertThat(registry.longParam("BOT_SHARE_JIELUE")).isEqualTo(10L);
        assertThat(registry.longParam("BOT_SHARE_JUNFA")).isEqualTo(4L);
        assertThat(registry.longParam("BOT_SHARE_YINGZI")).isEqualTo(11L);
    }

    @Test
    @DisplayName("验收9：每个原型的反应延迟都落在 [3, 30] 秒内，禁止 0ms 反应")
    void reactionDelaysStayInsideAcceptance9() {
        long min = registry.longParam("BOT_REACTION_DELAY_MIN_SEC");
        long max = registry.longParam("BOT_REACTION_DELAY_MAX_SEC");
        assertThat(min).as("验收 9 的区间下界").isEqualTo(3L);
        assertThat(max).as("验收 9 的区间上界").isEqualTo(30L);
        for (BotArchetypeCfg row : archetypes) {
            assertThat(row.reactionDelayMinSec())
                    .as("%s 的反应延迟下界不得低于 3 秒：0ms 反应是 Bot 最容易暴露的一处", row.id())
                    .isGreaterThanOrEqualTo(min);
            assertThat(row.reactionDelayMaxSec())
                    .as("%s 的反应延迟上界不得超过 30 秒", row.id())
                    .isLessThanOrEqualTo(max);
            assertThat(row.reactionDelayMinSec()).isLessThanOrEqualTo(row.reactionDelayMaxSec());
        }
    }

    @Test
    @DisplayName("联盟求助延迟落在 [10, 120] 秒（§四）")
    void helpDelaysStayInsideDocumentedRange() {
        assertThat(registry.longParam("BOT_HELP_DELAY_MIN_SEC")).isEqualTo(10L);
        assertThat(registry.longParam("BOT_HELP_DELAY_MAX_SEC")).isEqualTo(120L);
        for (BotArchetypeCfg row : archetypes) {
            assertThat(row.helpDelayMinSec()).isBetween(10L, 120L);
            assertThat(row.helpDelayMaxSec()).isBetween(10L, 120L);
            assertThat(row.helpDelayMinSec()).isLessThanOrEqualTo(row.helpDelayMaxSec());
        }
    }

    @Test
    @DisplayName("失误率落在 §三 的 5%~20%，且每个原型都有失误（永远最优的 Bot 一眼假）")
    void mistakeRatesStayInsideDocumentedRange() {
        long min = registry.fixedParam("BOT_MISTAKE_RATE_MIN");
        long max = registry.fixedParam("BOT_MISTAKE_RATE_MAX");
        assertThat(min).isEqualTo(500L);
        assertThat(max).isEqualTo(2000L);
        for (BotArchetypeCfg row : archetypes) {
            assertThat(row.suboptimalChanceMin())
                    .as("%s 必须有失误：0 失误率的 Bot 永远做最优决策，一眼假", row.id())
                    .isPositive()
                    .isGreaterThanOrEqualTo(min);
            assertThat(row.suboptimalChanceMax()).isLessThanOrEqualTo(max);
            assertThat(row.suboptimalChanceMin()).isLessThanOrEqualTo(row.suboptimalChanceMax());
        }
    }

    @Test
    @DisplayName("成长系数区间落在 [0.7, 1.3]，且上界不得突破 global 的天花板（否则 Bot 会打穿 B08 圈层）")
    void growthFactorsStayInsideCeiling() {
        long floor = registry.fixedParam("BOT_GROWTH_FACTOR_MIN");
        long ceiling = registry.fixedParam("BOT_GROWTH_FACTOR_MAX");
        assertThat(floor).isEqualTo(7000L);
        assertThat(ceiling).isEqualTo(13000L);
        for (BotArchetypeCfg row : archetypes) {
            assertThat(row.growthFactorMin()).isGreaterThanOrEqualTo(floor);
            assertThat(row.growthFactorMax())
                    .as("%s 的成长系数上界不得突破天花板：再高就会让个别 Bot 超出 B08 的圈层上限", row.id())
                    .isLessThanOrEqualTo(ceiling);
            assertThat(row.growthFactorMin()).isLessThanOrEqualTo(row.growthFactorMax());
        }
        // §四 禁止项：不要让 Bot 战力整齐划一。区间宽度为 0 就是整齐划一
        for (BotArchetypeCfg row : archetypes) {
            assertThat(row.growthFactorMax() - row.growthFactorMin())
                    .as("%s 的成长系数必须有散布", row.id())
                    .isPositive();
        }
    }

    @Test
    @DisplayName("aggression 与 socialness 都在 [0,1]，且陪跑者最弱、劫掠者最凶、盟友最合群")
    void aiTendenciesMatchArchetypeRoles() {
        Map<String, BotArchetypeCfg> byId = new java.util.HashMap<>();
        for (BotArchetypeCfg row : archetypes) {
            byId.put(row.id(), row);
            assertThat(row.aggression()).isBetween(0L, 10000L);
            assertThat(row.socialness()).isBetween(0L, 10000L);
        }
        // §一 的行为倾向：陪跑者「被打不还手」、劫掠者「会侦查会掠夺」、盟友「会捐献会帮助会聊天」
        assertThat(byId.get("bot_paoyao").aggression())
                .isLessThan(byId.get("bot_linju").aggression());
        assertThat(byId.get("bot_jielue").aggression())
                .as("劫掠者必须是最凶的一档").isGreaterThan(byId.get("bot_junfa").aggression());
        assertThat(byId.get("bot_mengyou").socialness())
                .as("盟友必须是最合群的一档").isGreaterThan(byId.get("bot_linju").socialness());
        assertThat(byId.get("bot_junfa").socialness())
                .as("军阀是孤立据点：它若会叫盟友，「需要集结才能打下」就不成立了")
                .isLessThan(byId.get("bot_mengyou").socialness());
    }

    // ---------- 名字（§四、验收 11） ----------

    @Test
    @DisplayName("名字池三档齐全，组合数足以撑住单服 Bot 上限而不撞名")
    void namePoolsAreLargeEnough() {
        assertThat(registry.stringParam("BOT_NAME_SURNAME_POOL")).isEqualTo("bot_name_surname");
        assertThat(registry.stringParam("BOT_NAME_GIVEN_POOL")).isEqualTo("bot_name_given");
        assertThat(registry.stringParam("BOT_NAME_TITLE_POOL")).isEqualTo("bot_name_title");

        Map<String, BotNameCfg> byId = new java.util.HashMap<>();
        for (BotNameCfg row : names) {
            byId.put(row.id(), row);
        }
        assertThat(byId).containsKeys("bot_name_surname", "bot_name_given", "bot_name_title");

        long surnames = byId.get("bot_name_surname").namePool().size();
        long givens = byId.get("bot_name_given").namePool().size();
        long titles = byId.get("bot_name_title").namePool().size();
        assertThat(surnames).as("§十一 建议 100，本次先落 30").isGreaterThanOrEqualTo(20L);
        assertThat(givens).isGreaterThanOrEqualTo(20L);
        assertThat(titles).isPositive();

        long combinations = surnames * givens * (1 + titles);
        long cap = registry.longParam("BOT_MAX_PER_SERVER");
        assertThat(combinations)
                .as("组合数必须显著大于单服上限，否则重抽会退化成死循环：%d 组合 vs %d 上限",
                        combinations, cap)
                .isGreaterThan(cap * 3L);
    }

    @Test
    @DisplayName("验收11：名字池里没有机器感命名（禁止「玩家12345」式）")
    void namePoolsContainNoMachineNames() {
        for (BotNameCfg row : names) {
            for (com.fasterxml.jackson.databind.JsonNode name : row.namePool()) {
                String text = name.asText();
                assertThat(text).as("名字不得为空").isNotBlank();
                assertThat(text).as("禁止纯数字或「玩家/测试/bot」式机器名：%s", text)
                        .doesNotMatch(".*\\d+.*")
                        .doesNotContain("玩家", "测试", "bot", "Bot", "BOT", "user", "test");
            }
        }
        // 池内不得有重复：重复项会直接降低有效组合数
        for (BotNameCfg row : names) {
            Set<String> seen = new HashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode name : row.namePool()) {
                assertThat(seen.add(name.asText()))
                        .as("%s 池内有重复项 %s", row.id(), name.asText()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("称号使用概率在 (0,1) 之间：全都带称号会显得像武侠小说角色表")
    void titleChanceIsPartial() {
        long chance = registry.fixedParam("BOT_NAME_TITLE_CHANCE");
        assertThat(chance).isPositive().isLessThan(10000L);
    }

    // ---------- 聊天语料（§四、§七 红线） ----------

    @Test
    @DisplayName("八种 scene 每种都有话可说：缺场景比句子少更容易被察觉")
    void everyChatSceneHasAtLeastOneLine() {
        Set<String> scenes = new HashSet<>();
        for (BotChatCfg row : chats) {
            scenes.add(String.valueOf(row.scene()));
        }
        assertThat(scenes).contains("HELP_REQUEST", "RALLY_CALL", "ATTACKED", "VICTORY",
                "DEFEAT", "CHAT_IDLE", "ALLIANCE_JOIN", "TRADE");
    }

    @Test
    @DisplayName("§七 红线：交易语料里不得出现金币/充值/付费字样（禁止 Bot 诱导付费）")
    void tradeChatNeverMentionsPayment() {
        for (BotChatCfg row : chats) {
            String text = row.text();
            assertThat(text).as("语料 %s 含付费诱导词，违反 B11 §七 红线", row.id())
                    .doesNotContain("充值", "付费", "礼包", "钻石", "首充", "打折", "限时")
                    .doesNotContain("客服", "官方", "GM", "管理员");
            if (row.scene() == BotChatCfg.Scene.TRADE) {
                assertThat(text).as("交易语料只谈资源互换：%s", row.id()).doesNotContain("金币");
            }
        }
    }

    @Test
    @DisplayName("语料的等级门槛自洽：minCityLevel <= maxCityLevel，且低门槛句不得提到高级玩法")
    void chatLevelGatesAreSelfConsistent() {
        for (BotChatCfg row : chats) {
            if (row.maxCityLevel() != null) {
                assertThat(row.minCityLevel())
                        .as("%s 的等级区间反了", row.id())
                        .isLessThanOrEqualTo(row.maxCityLevel());
            }
            assertThat(row.text()).as("%s 不得为空", row.id()).isNotBlank();
            assertThat(row.weight()).as("%s 的权重必须为正", row.id()).isPositive();
        }
        // 说错话的 Bot 比不说话的 Bot 更容易被认出来（验收 2 的盲测就是测这个）
        Map<String, BotChatCfg> byId = new java.util.HashMap<>();
        for (BotChatCfg row : chats) {
            byId.put(row.id(), row);
        }
        assertThat(byId.get("chat_help_train").minCityLevel())
                .as("3 级才有兵营，1 级的 Bot 说「练兵中」会露馅").isEqualTo(3L);
        assertThat(byId.get("chat_help_heal").minCityLevel())
                .as("5 级才有医院").isEqualTo(5L);
        assertThat(byId.get("chat_attacked_help").minCityLevel())
                .as("被打了谁都会喊，不该有门槛").isEqualTo(1L);
    }

    // ---------- 作息（§四、验收 10） ----------

    @Test
    @DisplayName("验收10：凌晨 2-5 点的 tick 权重 < 晚 20-22 点的 20%")
    void nightTicksAreFarBelowEveningPeak() {
        Map<Integer, Long> loginByHour = new java.util.TreeMap<>();
        for (BotScheduleCfg row : schedule) {
            if (row.actionType() == BotScheduleCfg.ActionType.LOGIN) {
                loginByHour.put((int) row.hourOfDay(), row.weight());
            }
        }
        assertThat(loginByHour).as("LOGIN 必须覆盖 24 小时，否则某些小时的 Bot 永远不会 tick")
                .hasSize(24);

        long night = loginByHour.getOrDefault(2, 0L) + loginByHour.getOrDefault(3, 0L)
                + loginByHour.getOrDefault(4, 0L) + loginByHour.getOrDefault(5, 0L);
        long peak = loginByHour.getOrDefault(20, 0L) + loginByHour.getOrDefault(21, 0L)
                + loginByHour.getOrDefault(22, 0L);
        assertThat(peak).as("晚高峰权重不得为 0，否则验收 10 的分母不存在").isPositive();
        assertThat(night * 100L)
                .as("凌晨 2-5 点 = %d，晚 20-22 点 = %d，比值必须 < 20%%", night, peak)
                .isLessThan(peak * 20L);
    }

    @Test
    @DisplayName("作息曲线有午休与晚间两个高峰（§四：按真人曲线分布）")
    void activityCurveHasLunchAndEveningPeaks() {
        Map<Integer, Long> loginByHour = new java.util.TreeMap<>();
        for (BotScheduleCfg row : schedule) {
            if (row.actionType() == BotScheduleCfg.ActionType.LOGIN) {
                loginByHour.put((int) row.hourOfDay(), row.weight());
            }
        }
        long morning = loginByHour.getOrDefault(9, 0L) + loginByHour.getOrDefault(10, 0L);
        long lunch = loginByHour.getOrDefault(12, 0L) + loginByHour.getOrDefault(13, 0L);
        long evening = loginByHour.getOrDefault(20, 0L) + loginByHour.getOrDefault(21, 0L)
                + loginByHour.getOrDefault(22, 0L);
        assertThat(lunch).as("午休 12-13 点必须是高峰").isGreaterThan(morning);
        assertThat(evening).as("晚 20-22 点必须是全天最高").isGreaterThan(lunch);
    }

    @Test
    @DisplayName("响应集结与捐献只在有人在线的时段：凌晨做这两件事的 Bot 一眼就是脚本")
    void socialActionsAvoidDeadHours() {
        for (BotScheduleCfg row : schedule) {
            if (row.actionType() == BotScheduleCfg.ActionType.JOIN_RALLY
                    || row.actionType() == BotScheduleCfg.ActionType.DONATE) {
                assertThat(row.hourOfDay() >= 0L && row.hourOfDay() <= 7L)
                        .as("%s 不得出现在凌晨 %d 点：响应集结与捐献都需要别人在线才有意义，"
                                + "凌晨做这两件事的 Bot 一眼就是脚本", row.actionType(), row.hourOfDay())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("每个 (小时, 行为) 组合至多一行：重复行会让权重被算两遍")
    void scheduleHasNoDuplicateHourActionPairs() {
        Map<BotScheduleCfg.ActionType, Set<Long>> seen = new EnumMap<>(BotScheduleCfg.ActionType.class);
        List<String> duplicates = new ArrayList<>();
        for (BotScheduleCfg row : schedule) {
            Set<Long> hours = seen.computeIfAbsent(row.actionType(), k -> new HashSet<>());
            if (!hours.add(row.hourOfDay())) {
                duplicates.add(row.actionType() + "@" + row.hourOfDay());
            }
        }
        assertThat(duplicates).as("重复的 (行为, 小时) 组合").isEmpty();
    }

    // ---------- 密度与频控（§五、验收 4） ----------

    @Test
    @DisplayName("密度曲线随时间递减：Bot 是给真人让位的，越到后期越少")
    void densityCurveDecreases() {
        long d1 = registry.fixedParam("BOT_DENSITY_RATIO_D1");
        long d7 = registry.fixedParam("BOT_DENSITY_RATIO_D7");
        long d30 = registry.fixedParam("BOT_DENSITY_RATIO_D30");
        assertThat(d1).isEqualTo(200000L);
        assertThat(d7).isEqualTo(50000L);
        assertThat(d30).isEqualTo(15000L);
        assertThat(d1).isGreaterThan(d7);
        assertThat(d7).isGreaterThan(d30);
    }

    @Test
    @DisplayName("验收4：同一真人 24h 内被 Bot 攻击 ≤ 3 次")
    void attackFrequencyIsCapped() {
        assertThat(registry.longParam("BOT_ATTACK_LIMIT_PER_24H")).isEqualTo(3L);
    }

    @Test
    @DisplayName("验收3 的容忍带必须覆盖校准目标带，否则按目标校准出的 Bot 通不过自己的验收")
    void powerCheckBandCoversCalibrationBand() {
        assertThat(registry.fixedParam("BOT_POWER_CHECK_MIN")).isEqualTo(7000L);
        assertThat(registry.fixedParam("BOT_POWER_CHECK_MAX")).isEqualTo(10000L);
        assertThat(registry.fixedParam("BOT_POWER_CHECK_MIN"))
                .isLessThanOrEqualTo(registry.fixedParam("BOT_POWER_RATIO_MIN"));
        assertThat(registry.fixedParam("BOT_POWER_CHECK_MAX"))
                .isGreaterThanOrEqualTo(registry.fixedParam("BOT_POWER_RATIO_MAX"));
        assertThat(registry.fixedParam("BOT_POWER_RATIO_MAX"))
                .as("Bot 战力系数上界不得超过真人均值，否则圈层规则失去意义")
                .isLessThanOrEqualTo(10000L);
    }

    @Test
    @DisplayName("性能预算：5000 × 单 tick 5ms 必须落在 30s 的全量预算内（验收 6）")
    void performanceBudgetIsSelfConsistent() {
        long cap = registry.longParam("BOT_MAX_PER_SERVER");
        long perTick = registry.longParam("BOT_TICK_BUDGET_MS");
        long round = registry.longParam("BOT_FULL_ROUND_BUDGET_MS");
        assertThat(cap).isEqualTo(5000L);
        assertThat(perTick).isEqualTo(5L);
        assertThat(round).isEqualTo(30000L);
        assertThat(cap * perTick)
                .as("全量一轮 = 5000 × 5ms = 25s，必须留得出余量给调度本身的开销")
                .isLessThanOrEqualTo(round);
    }

    /**
     * C0（2026-09-12）拆口径之后新增的一条自洽不变量。<b>它钉住的是"分钟级 tick 与驱动频率必须匹配"</b>：
     * 一旦有人只调 {@code BOT_TICKS_PER_DAY_MAX}（或只调每轮预算/批上限）而不重算另一头，
     * 表现是队列积压、受击反应永远迟到，而没有任何一处代码会报错 —— 正是这条用例存在的理由。
     */
    @Test
    @DisplayName("C0 的算术：每轮能清的条数 ÷ 峰值 tick 需求 = 允许的驱动间隔，必须罩得住 3~30s 的反应延迟")
    void tickDemandAndDriveBudgetAreSelfConsistent() {
        long cap = registry.longParam("BOT_MAX_PER_SERVER");
        long ticksPerDayMax = registry.longParam("BOT_TICKS_PER_DAY_MAX");
        long perTick = registry.longParam("BOT_TICK_BUDGET_MS");
        long roundBudget = registry.longParam("BOT_ROUND_BUDGET_MS");
        long batchLimit = registry.longParam("BOT_DUE_BATCH_LIMIT");

        // 一天里 Bot 真正活跃的小时数（bot_schedule 覆盖 7~23 点，取 10 小时做保守值）
        long activeSeconds = 10 * 3600L;
        double peakTicksPerSecond = (double) cap * ticksPerDayMax / activeSeconds;
        // 一轮既受墙钟预算限制、也受条数限制
        double perRoundByBudget = (double) roundBudget / perTick;
        double perRound = Math.min(perRoundByBudget, batchLimit);
        double maxDriveIntervalSeconds = perRound / peakTicksPerSecond;

        assertThat(maxDriveIntervalSeconds)
                .as("峰值 {} tick/秒、每轮清 {} 条 ⇒ 最疏也只能每 {} 秒打一次驱动端点。"
                        + "小于 0.2 秒 = 这套表在真实部署里跑不动（调度打不到那么密，每轮还要吃 round 预算）；"
                        + "大于 30 秒 = 验收 9 的反应延迟上界根本罩不住。两头都得拦",
                        peakTicksPerSecond, perRound, maxDriveIntervalSeconds)
                .isBetween(0.2, 30.0);
        assertThat(perRoundByBudget)
                .as("条数上限不该比预算允许的还宽：否则 dueBatchLimit 形同虚设")
                .isGreaterThanOrEqualTo((double) batchLimit / 4);
    }

    @Test
    @DisplayName("落点距离 5~15 格：保证「一出城就有邻居」又不至于挤在家门口")
    void spawnDistanceRange() {
        assertThat(registry.longParam("BOT_SPAWN_DISTANCE_MIN")).isEqualTo(5L);
        assertThat(registry.longParam("BOT_SPAWN_DISTANCE_MAX")).isEqualTo(15L);
        assertThat(registry.longParam("BOT_SPAWN_DISTANCE_MIN"))
                .isLessThan(registry.longParam("BOT_SPAWN_DISTANCE_MAX"));
    }

    @Test
    @DisplayName("标识策略：前 7 天不做任何标识（§四）")
    void noMarkingDuringFirstWeek() {
        assertThat(registry.longParam("BOT_MARK_AFTER_DAYS")).isEqualTo(7L);
    }
}
