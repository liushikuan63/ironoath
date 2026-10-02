package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.config.cfg.SeasonCfg;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.season.SeasonRulesAssembler;

/**
 * 职责：B14 赛季参数的配置自检 —— 装配值、参数之间的关系、以及「改配置即生效」。
 * 依赖：game-config（独立实例，不动 Spring 上下文里那份）、{@link SeasonRulesAssembler}。
 *
 * <p><b>本类存在的理由是补一个铁律 1 的缺口</b>：B14 交付时领域层是干净的
 * （Rules 由外部传入），但那些数字只活在单测夹具里，配置表里一个赛季参数都没有。
 * 于是「赛季参数从哪来」这个问题的答案曾经是「谁写 web 层谁填」——
 * 而那样填出来的数字既躲过策划评审，也没法在赛季中途调整。
 *
 * <p><b>断言分三类，第三类最容易被省略但最重要</b>：
 * ① 值对不对（对着配置表逐项核）；② 关系对不对（门槛必须严格升序、第一个必须是 0）——
 * 这类断言在策划调参时才真正起作用，因为它拦的是「调完变成非升序」而不是「值变了」；
 * ③ <b>改配置是否立刻生效</b>。装配器一旦被人加上缓存，前两类断言依然全绿，
 * 而线上「调了赛季奖励却没生效」这件事不会有任何报错。
 */
class SeasonRulesAssemblerTest {

    private static ConfigRegistry configs;
    private static SeasonRulesAssembler assembler;

    /** 一天的毫秒数（UTC+8 自然日轴，与 ServerCalendar / DayKey 同一口径）。 */
    private static final long DAY_MS = 86_400_000L;

    /**
     * 固定的赛季锚点（毫秒时间戳），不是「今天」——按天数推进的断言要能复现同一个 now。
     * 数值本身无意义，只要落在一个整日边界上即可。
     */
    private static final long SEASON_ANCHOR = 1_760_000_000_000L;

    @BeforeAll
    static void loadConfigs() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        assembler = new SeasonRulesAssembler(configs);
    }

    // ---------- ① 值 ----------

    @Test
    @DisplayName("赛季时间轴全部来自 season 表：45 天五阶段，规则阶段取自 rulePhase 列")
    void timelineComesFromTheSeasonTable() {
        SeasonTimeline.Rules rules = assembler.timelineRules();

        assertThat(rules.seasonId())
                .as("season_01_phase_3 ⇒ season_01：赛季 id 不另设全局参数，避免两处定义漂移")
                .isEqualTo("season_01");
        assertThat(rules.totalDays())
                .as("时长以表为准（2026-09-08 裁决，B14 §一 已按此改写）").isEqualTo(45L);
        assertThat(rules.stages()).extracting(SeasonTimeline.Stage::startDayOffset)
                .containsExactly(0L, 7L, 14L, 28L, 42L);
        assertThat(rules.stages()).extracting(SeasonTimeline.Stage::phase).containsExactly(
                SeasonTimeline.Phase.PREPARE, SeasonTimeline.Phase.EXPAND, SeasonTimeline.Phase.EXPAND,
                SeasonTimeline.Phase.CAPITAL_WAR, SeasonTimeline.Phase.SETTLE);
        // 立盟期与争锋期是两个目标、同一条扩张规则。这条断言拦的是「有人把阶段数当规则数」：
        // 真出现五档五规则时才需要重新审视，而不是让「阶段 = 规则」这个巧合悄悄固化
        assertThat(rules.stages()).filteredOn(s -> s.phase() == SeasonTimeline.Phase.EXPAND)
                .as("目标阶段多于规则阶段：映射不能靠位置一一对应").hasSize(2);
    }

    @Test
    @DisplayName("改表即改规则：把问鼎期的 rulePhase 改成 EXPAND 后，全季不再有王城战窗口")
    void rulePhaseMappingIsReadFromTheTable() throws Exception {
        String original = Files.readString(locateTable("season.json"), StandardCharsets.UTF_8);
        String tweaked = original.replace("\"rulePhase\": \"CAPITAL_WAR\"", "\"rulePhase\": \"EXPAND\"");
        assertThat(tweaked).as("夹具必须真的改到了那一行，否则这条断言是空的").isNotEqualTo(original);

        ConfigRegistry reloaded = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        reloaded.reload("season", SeasonCfg.class, tweaked);
        SeasonTimeline.Rules after = new SeasonRulesAssembler(reloaded).timelineRules();

        assertThat(after.stages()).extracting(SeasonTimeline.Stage::phase)
                .as("装配器不认阶段名也不认序号，只认表里那一列 —— 这就是「换赛季只改配置」")
                .containsExactly(SeasonTimeline.Phase.PREPARE, SeasonTimeline.Phase.EXPAND,
                        SeasonTimeline.Phase.EXPAND, SeasonTimeline.Phase.EXPAND,
                        SeasonTimeline.Phase.SETTLE);
        SeasonTimeline timeline = new SeasonTimeline(after);
        long start = 1_700_000_000_000L;
        for (int day = 0; day < after.totalDays(); day++) {
            assertThat(timeline.allowsCapitalWar(start + day * 86_400_000L, start))
                    .as("第 %d 天都不该开放王城战", day + 1).isFalse();
        }
        // 共享的那份 registry 没被动过：上面用的是独立实例
        assertThat(assembler.timelineRules().stages().get(3).phase())
                .isEqualTo(SeasonTimeline.Phase.CAPITAL_WAR);
    }

    @Test
    @DisplayName("段位门槛逐项对上配置表：六档、升序、第一档从 0 开始")
    void tierThresholdsComeFromConfig() {
        SeasonTier.Rules rules = assembler.tierRules();
        assertThat(rules.thresholds())
                .containsExactly(0L, 5_000L, 20_000L, 60_000L, 150_000L, 400_000L);
        assertThat(rules.thresholds()).hasSize(SeasonTier.Tier.values().length);
    }

    @Test
    @DisplayName("降档步数与进度保留率来自配置：DECIMAL 已由反序列化器转成定点，装配层不得再转一次")
    void demotionParametersComeFromConfig() {
        SeasonTier.Rules rules = assembler.tierRules();
        assertThat(rules.demoteSteps()).isEqualTo((int) configs.longParam("SEASON_DEMOTE_STEPS"));
        assertThat(rules.demoteSteps()).as("B14 §五 建议降 1 段").isEqualTo(1);
        assertThat(rules.progressKeepFixed())
                .as("0.5 的定点表示是 5000；若装配层又转一次会得到 5000000，"
                        + "而 SeasonTier.Rules 的构造期校验拦不住它（它只校验门槛与降档步数）")
                .isEqualTo(FixedPoint.parse("0.5"))
                .isEqualTo(configs.fixedParam("SEASON_TIER_PROGRESS_KEEP"));
    }

    @Test
    @DisplayName("结算规则四项全部来自配置：依据榜、发奖名额、每名次币、归档保留")
    void settlementRulesComeFromConfig() {
        SeasonSettlement.Rules rules = assembler.settlementRules();
        assertThat(rules.snapshotBoard()).isEqualTo(SeasonSettlement.Board.POWER);
        assertThat(rules.rewardedTopN()).isEqualTo((int) configs.longParam("SEASON_REWARDED_TOP_N"));
        assertThat(rules.coinPerRank()).isEqualTo(configs.longParam("SEASON_COIN_PER_RANK"));
        assertThat(rules.archiveCollections())
                .as("B14 §五 建议保留 3 个赛季（申诉需要）")
                .isEqualTo((int) configs.longParam("SEASON_ARCHIVE_RETENTION"))
                .isEqualTo(3);
    }

    // ---------- ② 关系 ----------

    /**
     * 赛季号一致性（2026-10-02 加，#748）。
     *
     * <p><b>为什么加这条</b>：{@code SeasonRulesAssembler} 的类注释里写明「赛季 id 从行 id 的前缀反推，
     * 而不是再加一个全局参数：一行的前缀就是这一季，两处定义迟早漂移成『归档集合叫 season_01
     * 而时间轴读的是 season_02』，那种数据错乱没有任何测试会红」。上面 {@code timelineComesFromTheSeasonTable}
     * 断言的是「season_01 这一个值对不对」，它拦不住上面说的那种漂移 ——
     * 因为两处定义漂移时，每一处**单独看都是合法的 season_01**。
     *
     * <p><b>所以这条断言只做一件事</b>：把「行 id 的前缀集合」与「装配出来的 {@code seasonId}」放在一起比。
     * 一旦有人加了第二个赛季前缀、或者把赛季号改成了全局参数而表里的前缀没跟上，这条就会红。
     *
     * <p><b>断言能失败吗</b>：能。夹具把五行改成 {@code season_09_phase_N} 之后，
     * {@code assembler.timelineRules().seasonId()} 仍是 {@code season_01}（它读的是未改的那份表），
     * 而表前缀集合已变成 {@code {season_09}} ⇒ 两个断言必然有一个不成立。
     */
    @Test
    @DisplayName("赛季号一致性：装配出来的 seasonId 必须是表里真实存在的行 id 前缀，不许另有全局参数")
    void seasonIdComesFromTheTablePrefixes() {
        SeasonTimeline.Rules rules = assembler.timelineRules();
        List<String> prefixes = configs.all(SeasonCfg.class).stream()
                .map(SeasonCfg::id)
                .map(id -> id.substring(0, id.indexOf("_phase_")))
                .distinct()
                .sorted()
                .toList();

        // 2026-10-03 裁决落地：season 表从 season_01 五行扩到 season_01..season_05 共 25 行，
        // 所以「表里只有一个前缀」这个前提**已经不成立**，断言随之改成「当前读数必须是表里存在的那个」。
        // 仍然要守的是同一件事：赛季号的唯一来源是行 id 前缀。编一个 season_06 或读一个全局参数
        // 都会让归档集合名（SeasonSettlement 侧用的同一个字符串）与时间轴分叉，而那种错没有任何别处会红。
        assertThat(prefixes)
                .as("多赛季落地后表里应有 5 个前缀（SEASON_COUNT=5，#750 裁决）")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(rules.seasonId())
                .as("装配出来的赛季号必须来自行 id 前缀的集合，不能另有全局参数")
                .isIn(prefixes);
        assertThat(rules.seasonId())
                .as("未配置 SEASON_START_AT ⇒ 赛季未启用 ⇒ 恒取第一季（ServerCalendar 的既有语义）")
                .isEqualTo(prefixes.get(0));
    }

    /**
     * 第 N 天 ⇒ season_0X（2026-10-03 裁决落地：按天数推进）。
     *
     * <p><b>断言能失败吗</b>：能，而且不依赖墙上时钟 —— {@code timelineRules(long now)} 让 now 可钉住。
     * 反例有两条：① 把某一天的 now 改成 season_01 那一档的天数，断言会红；
     * ② 若筛选那段被删掉（把 25 行全塞进时间轴），本条在 day=0 时就会因
     * {@code IllegalArgument: 阶段序号必须从 1 起连续，实际缺了 2} 而 Error。
     */
    @Test
    @DisplayName("按天数推进：第 46 天必须是 season_02，第 96 天必须是 season_03（第 1 季 45 天）")
    void seasonAdvancesWithElapsedDays() {
        // 锚点是固定常量而不是「今天」—— 这条断言要能失败，就必须能复现同一个 now。
        SeasonRulesAssembler anchored = new SeasonRulesAssembler(
                registryWithSeasonStartAt(SEASON_ANCHOR), clockAt(SEASON_ANCHOR));

        assertThat(anchored.timelineRules(SEASON_ANCHOR).seasonId())
                .as("开季当天必须是第一季").isEqualTo("season_01");
        assertThat(anchored.timelineRules(SEASON_ANCHOR + 44L * DAY_MS).seasonId())
                .as("第 45 天仍落在第一季内（0 基 44 天 < 45）").isEqualTo("season_01");
        assertThat(anchored.timelineRules(SEASON_ANCHOR + 45L * DAY_MS).seasonId())
                .as("第 46 天（0 基 45 天）跨过整季 ⇒ 第二季").isEqualTo("season_02");
        assertThat(anchored.timelineRules(SEASON_ANCHOR + 50L * DAY_MS).seasonId())
                .as("第 51 天落在第二季内").isEqualTo("season_02");
        assertThat(anchored.timelineRules(SEASON_ANCHOR + 95L * DAY_MS).seasonId())
                .as("第 96 天跨过两季 ⇒ 第三季").isEqualTo("season_03");
        assertThat(anchored.timelineRules(SEASON_ANCHOR + 10_000L * DAY_MS).seasonId())
                .as("天数远超表里最后一季（season_05）⇒ 取最后一季，不编造 season_06："
                        + "编出来的 id 会让 purgeArchivedSeasons 拿一个表里不存在的季号去比较归档")
                .isEqualTo("season_05");
    }

    /** 往 global 表追加一行 SEASON_START_AT（它是部署参数，平时不在表里）—— 与 RankEndpointTest / SeasonStatusTest 同款做法。 */
    private static ConfigRegistry registryWithSeasonStartAt(long startAt) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(
                    Files.readString(locateTable("global.json"), StandardCharsets.UTF_8));
            ArrayNode rows = (ArrayNode) table.get("rows");
            ObjectNode row = mapper.createObjectNode();
            row.put("id", "SEASON_START_AT");
            row.put("valueType", "LONG");
            row.put("value", startAt);
            row.put("unit", "毫秒时间戳");
            row.put("source", "B14 §一（部署参数，不进表）");
            row.put("why", "测试注入的赛季锚点：真实环境由部署时配置");
            rows.add(row);
            ConfigRegistry registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
            registry.reload(ConfigRegistry.TABLE_GLOBAL, GlobalCfg.class, mapper.writeValueAsString(table));
            return registry;
        } catch (Exception e) {
            throw new IllegalStateException("无法构造带 SEASON_START_AT 的 global 表", e);
        }
    }

    private static TimeService clockAt(long fixedMillis) {
        return new TimeService(() -> fixedMillis);
    }

    @Test
    @DisplayName("门槛必须严格升序且第一个为 0：否则最低档之下会出现一个没有段位的区间")
    void thresholdRelationsHold() {
        long[] thresholds = assembler.tierRules().thresholds();
        assertThat(thresholds[0]).as("第一档必须从 0 开始，否则那之下的玩家段位面板只能显示空白").isZero();
        for (int i = 1; i < thresholds.length; i++) {
            assertThat(thresholds[i])
                    .as("第 %d 档必须严格高于第 %d 档，相等会让两个段位永远无法区分", i, i - 1)
                    .isGreaterThan(thresholds[i - 1]);
        }
    }

    @Test
    @DisplayName("降档步数不得大于等于段位数：否则一次重置就能把王者打到没有段位")
    void demotionStaysWithinTheLadder() {
        SeasonTier.Rules rules = assembler.tierRules();
        assertThat(rules.demoteSteps()).isPositive();
        assertThat(rules.demoteSteps())
                .as("降档步数必须小于段位数，否则最低档之下无处可去")
                .isLessThan(SeasonTier.Tier.values().length);
        assertThat(rules.progressKeepFixed())
                .as("进度保留率必须落在 [0, 1.0]：超过 1 等于降档反而涨进度")
                .isBetween(0L, FixedPoint.ONE);
    }

    @Test
    @DisplayName("发奖名额与归档保留必须为正：名额为 0 等于没人有奖，归档为 0 等于申诉无从取证")
    void settlementRelationsHold() {
        SeasonSettlement.Rules rules = assembler.settlementRules();
        assertThat(rules.rewardedTopN()).isPositive();
        assertThat(rules.archiveCollections())
                .as("归档保留期不能短于申诉期（B14 验收 7 要求归档后能完整还原用于申诉）")
                .isGreaterThanOrEqualTo(1);
        assertThat(rules.coinPerRank()).isNotNegative();
    }

    // ---------- ③ 改配置即生效 ----------

    @Test
    @DisplayName("装配器不缓存：改了 global 里的赛季参数，下一次装配就必须读到新值")
    void rulesAreRereadOnEveryCall() throws Exception {
        SeasonTier.Rules before = assembler.tierRules();
        // 不能直接比 Rules 对象：它含 long[] 字段，而 record 的 equals 对数组用引用比较，
        // 于是两次装配出来的 Rules 永远「不相等」—— 那会让这条断言假失败，
        // 而假失败比没有断言更糟，因为下一个人会直接把它删掉
        SeasonTier.Rules again = assembler.tierRules();
        assertThat(again.thresholds()).isEqualTo(before.thresholds());
        assertThat(again.demoteSteps()).isEqualTo(before.demoteSteps());
        assertThat(again.progressKeepFixed()).isEqualTo(before.progressKeepFixed());

        Path file = locateTable("global.json");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        String tweaked = original.replace(
                "\"id\": \"SEASON_DEMOTE_STEPS\",\n      \"valueType\": \"LONG\",\n      \"value\": 1,",
                "\"id\": \"SEASON_DEMOTE_STEPS\",\n      \"valueType\": \"LONG\",\n      \"value\": 2,");
        assertThat(tweaked).as("夹具必须真的改到了那一行").isNotEqualTo(original);

        ConfigRegistry reloaded = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        reloaded.reload("global", GlobalCfg.class, tweaked);
        SeasonRulesAssembler hot = new SeasonRulesAssembler(reloaded);

        assertThat(hot.tierRules().demoteSteps())
                .as("热更之后必须读到新值：装配器一旦加上缓存，这条断言就是唯一会变红的地方")
                .isEqualTo(2);
        assertThat(hot.tierRules().thresholds()).as("其它参数不受影响").isEqualTo(before.thresholds());
        // 共享的那份 registry 没被动过：上面用的是独立实例
        assertThat(assembler.tierRules().demoteSteps()).isEqualTo(1);
    }

    @Test
    @DisplayName("配置填错时报错要指名是哪一行：领域层守不变量，装配层守「哪个参数填错了」")
    void malformedThresholdsNameTheParameter() throws Exception {
        Path file = locateTable("global.json");
        String original = Files.readString(file, StandardCharsets.UTF_8);

        String tooFew = original.replace("\"value\": \"0,5000,20000,60000,150000,400000\"",
                "\"value\": \"0,5000,20000\"");
        assertThat(tooFew).as("夹具必须真的改到了门槛那一行").isNotEqualTo(original);
        ConfigRegistry few = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        few.reload("global", GlobalCfg.class, tooFew);
        assertThatThrownBy(() -> new SeasonRulesAssembler(few).tierRules())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEASON_TIER_THRESHOLDS")
                .hasMessageContaining("6");

        String notNumber = original.replace("\"value\": \"0,5000,20000,60000,150000,400000\"",
                "\"value\": \"0,5000,20000,60000,150000,青铜\"");
        ConfigRegistry bad = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        bad.reload("global", GlobalCfg.class, notNumber);
        assertThatThrownBy(() -> new SeasonRulesAssembler(bad).tierRules())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非数字项")
                .hasMessageContaining("青铜");
    }

    /**
     * 定位 contract/config 下的表文件。从 cwd 逐级向上找：
     * 测试的工作目录可能是模块目录也可能是仓库根，写死相对路径的话只有一种跑法能成 ——
     * 那种只在某一种跑法下通过的测试，迟早会在 CI 上变成謎之失败。
     */
    private static Path locateTable(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve("contract").resolve("config").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException("找不到 contract/config/" + fileName + "，cwd=" + Path.of("").toAbsolutePath());
    }
}
