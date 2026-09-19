package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.BizException;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SeasonPhase;
import com.ironoath.web.dto.generated.SeasonStatusResp;
import com.ironoath.web.service.AttackGuardService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.season.SeasonAppService;
import com.ironoath.web.season.SeasonLedgerStore;
import com.ironoath.web.store.memory.InMemorySeasonLedger;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.season.SeasonSettlementService;

/**
 * 职责：B14 §一 赛季时间轴的两条对外口径 —— 状态查询的诚实性，以及禁战期真的挡住玩家间攻击。
 * 依赖：Spring Boot Test（取 TimeService 与真实配置表），但<b>不动上下文里那份 ConfigRegistry</b>。
 *
 * <p><b>为什么每个用例自己装配一份配置</b>：赛季锚点是全服状态。一旦在共享上下文里配上
 * 「今天开季」，同一上下文里所有攻击类用例都会落进备战期被拒 —— 那种失败看起来像
 * 「PVP 功能坏了」，实际是配置串台。跑的是同一个 {@link SeasonAppService}，只是换了注入的表。
 *
 * <p><b>「赛季第 N 天」怎么造出来</b>：状态与判定都只读服务端当前时刻（铁律 5），
 * 所以做法是把锚点摆到真实时刻之前 N 天，于是「现在」正好落进要验的那一天。
 * 不给生产代码开注入时钟的后门。
 */
@SpringBootTest
@ActiveProfiles("test")
class SeasonStatusTest {

    /** 结算里会触发战令的赛季末补发（B24）：这里的用例不关心补发，但构造要真的传进去 —— 传 null 会让"结算顺手补发"这条路径在测试里被静默跳过。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ironoath.web.battlepass.BattlePassService battlePass;

    private static final long DAY = 86_400_000L;

    @Autowired private TimeService timeService;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private PowerService powerService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private RewardService rewardService;
    @Autowired private IdempotencyStore idempotency;
    /** 下面那条用例要手工构造攻击闸门，这两个是它新增的两颗依赖。 */
    @Autowired private com.ironoath.web.social.SocialStore socialStore;
    @Autowired private com.ironoath.web.nation.NationStore nationStore;
    @Autowired private com.ironoath.web.bot.BotAttackLimiter botAttackLimiter;

    private static ConfigRegistry plain;
    private static String globalJson;
    private static String seasonJson;

    @BeforeAll
    static void loadConfigs() throws Exception {
        Path dir = locateConfigDir();
        plain = ConfigRegistry.loadFromDirectory(dir);
        globalJson = Files.readString(dir.resolve("global.json"), StandardCharsets.UTF_8);
        seasonJson = Files.readString(dir.resolve("season.json"), StandardCharsets.UTF_8);
    }

    // ---------- 未启用 ----------

    @Test
    @DisplayName("未配置 SEASON_START_AT：赛季字段一律 null 而不是 0，PVP 也不被赛季挡住")
    void unconfiguredSeasonReportsNullNotZero() {
        SeasonAppService seasons = wiringOn(plain).status();
        SeasonStatusResp resp = seasons.status(null);

        assertThat(resp.phase()).as("没配锚点就是「没有赛季」，回个 EXPAND 等于编一个假赛季").isNull();
        assertThat(resp.seasonStartAt()).isNull();
        assertThat(resp.dayIndex()).as("0 会被客户端读成「赛季第 1 天」").isNull();
        assertThat(resp.phaseEndAt()).isNull();
        assertThat(wiringOn(plain).status().status("P-whatever").myRank())
                .as("赛季未启用时连带了身份也不给名次 —— 0 会被读成「第 0 名」").isNull();
        assertThat(resp.allowsPvp()).as("未启用 ⇒ 不新增任何限制").isTrue();
        assertThat(resp.seasonId()).isEmpty();
        assertThat(resp.totalDays())
                .as("总天数是表本身的属性，与锚点无关，所以它是真值而不是 0").isEqualTo(45L);
        seasons.requirePvpAllowed(timeService.serverNow());
    }

    // ---------- 各阶段的读数 ----------

    @Test
    @DisplayName("备战期：报 PREPARE 并禁 PVP，提示还得说清第几天起开放")
    void preparePhaseLocksPvpWithAnActionableMessage() {
        SeasonStatusResp atDayOne = statusAtDay(0);

        assertThat(atDayOne.phase()).isEqualTo(SeasonPhase.PREPARE);
        assertThat(atDayOne.dayIndex()).isZero();
        assertThat(atDayOne.seasonStartAt()).isNotNull();
        assertThat(atDayOne.allowsPvp()).isFalse();
        assertThat(atDayOne.allowsCapitalWar()).isFalse();
        assertThat(atDayOne.readOnly()).isFalse();

        long start = timeService.serverNow();
        assertThatThrownBy(() -> seasonAtDay(0).requirePvpAllowed(start))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("备战期")
                .hasMessageContaining("第 8 天起开放")
                .hasMessageContaining("打野不受限制")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SEASON_PVP_LOCKED);
    }

    @Test
    @DisplayName("立盟期放行 PVP 但不开王城，问鼎期才开王城：三个阶段的读数各自不同")
    void expandAllowsPvpWhileCapitalWarNeedsItsOwnPhase() {
        SeasonStatusResp dayTen = statusAtDay(10);
        assertThat(dayTen.phase()).isEqualTo(SeasonPhase.EXPAND);
        assertThat(dayTen.allowsPvp()).as("第 11 天起可以打人").isTrue();
        assertThat(dayTen.allowsCapitalWar()).as("但王城还没开").isFalse();

        SeasonStatusResp dayThirty = statusAtDay(30);
        assertThat(dayThirty.phase()).isEqualTo(SeasonPhase.CAPITAL_WAR);
        assertThat(dayThirty.allowsCapitalWar()).isTrue();
        assertThat(dayThirty.allowsPvp()).isTrue();
    }

    @Test
    @DisplayName("第 45 天之后是休赛期：又回到禁战，且这条分支不是死代码")
    void afterTheSeasonEndsPvpIsLockedAgain() {
        SeasonStatusResp rest = statusAtDay(46);

        assertThat(rest.phase()).as("越界即休赛，不需要往表里补第六行").isEqualTo(SeasonPhase.REST);
        assertThat(rest.readOnly()).isTrue();
        assertThat(rest.allowsPvp()).isFalse();
        assertThat(rest.dayIndex()).as("天数继续如实往前数，赛季结束不等于时间停住").isEqualTo(46);
        assertThat(rest.phaseEndAt()).as("休赛期的倒计时指向赛季终点")
                .isEqualTo(DayKey.startOfDayPlusDays(rest.seasonStartAt(), 45));

        assertThatThrownBy(() -> seasonAtDay(46).requirePvpAllowed(timeService.serverNow()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("休赛期")
                .hasMessageContaining("等待下一赛季开启");
    }

    @Test
    @DisplayName("阶段切换精确落在整日边界：边界之前仍禁战，第 8 天 00:00 才解除")
    void phaseBoundaryIsExact() {
        // 末位是 C2 接进来的频控：本用例的两个玩家都不在托管名册里，它一路放行 ——
        // 这条用例验的仍然只是「赛季禁战有没有接在统一漏斗上」
        long beforeMidnight = java.time.Instant.parse("2026-09-13T15:59:59.999Z").toEpochMilli();
        long afterMidnight = java.time.Instant.parse("2026-09-13T16:00:00Z").toEpochMilli();
        long seasonStart = java.time.Instant.parse("2026-09-07T12:00:00Z").toEpochMilli();
        SeasonAppService beforeBoundary = wiringAnchoredAt(seasonStart,
                new com.ironoath.common.time.TimeService(() -> beforeMidnight)).status();
        SeasonAppService atBoundary = wiringAnchoredAt(seasonStart,
                new com.ironoath.common.time.TimeService(() -> afterMidnight)).status();

        assertThat(beforeBoundary.status(null).phase()).isEqualTo(SeasonPhase.PREPARE);
        assertThat(atBoundary.status(null).phase())
                .as("第 8 天 00:00 起进立盟期").isEqualTo(SeasonPhase.EXPAND);
        assertThatThrownBy(() -> beforeBoundary.requirePvpAllowed(beforeMidnight))
                .as("边界前一毫秒解除禁战，就是给备战期最后一天开了个洞")
                .isInstanceOf(BizException.class);
        atBoundary.requirePvpAllowed(afterMidnight);
    }

    @Test
    @DisplayName("myRank 的三种取值必须分得清：null 是没问、0 是未上榜、>=1 才是名次")
    void myRankDistinguishesAbsentFromUnranked() {
        String leader = newPlayer("榜一");
        String mate = newPlayer("榜二");
        String nobody = newPlayer("没打过仗");
        Wiring wiring = wiringAtDay(10);   // 立盟期，赛季在跑
        wiring.settlements().report(leader, "榜一", 10_000L);
        wiring.settlements().report(mate, "榜二", 5_000L);

        assertThat(wiring.status().status(leader).myRank()).as("战力高的在前").isEqualTo(1);
        assertThat(wiring.status().status(mate).myRank()).isEqualTo(2);
        assertThat(wiring.status().status(nobody).myRank())
                .as("带身份但从未上报过战力 ⇒ 0 = 未上榜，不能是 null（那会被面板画成「赛季没开」）")
                .isZero();
        assertThat(wiring.status().status(null).myRank())
                .as("不带身份就没有「谁的名次」这件事，null 而不是 0").isNull();
    }

    // ---------- 接线 ----------

    @Test
    @DisplayName("判定确实接在统一攻击漏斗里：备战期打玩家城得到赛季错，打野与空地不会得到这个错")
    void guardActuallyConsultsTheSeason() {
        String attacker = newPlayer("赛季攻方");
        String victim = newPlayer("赛季守方");
        Coord victimHome = worldAppService.homeOf(victim);
        // 一个确定不是玩家城的坐标：用来证明这条判定只在 PVP 上生效
        Coord notACity = victimHome.x() + 9 < 512 ? Coord.of(victimHome.x() + 9, victimHome.y() + 9)
                : Coord.of(victimHome.x() - 9, victimHome.y() - 9);
        assertThat(world.cityAt(notACity)).as("夹具前提：这个格子上不能有城").isEmpty();

        // 后两个参数是本次接外交判定带进来的：这条用例里的两个玩家都没有联盟，也就没有国籍，
        // 国家那一层直接放行 —— 它验的仍然只是"赛季禁战有没有接在统一漏斗上"
        AttackGuardService guard = new AttackGuardService(players, world, powerService,
                powerRefreshService, seasonAtDay(0), socialStore, nationStore,
                botAttackLimiter);   // 锚点=今天 ⇒ 现在处于备战期
        long now = timeService.serverNow();

        assertThatThrownBy(() -> guard.guard(attacker, victimHome, now))
                .as("禁战判定写在 SeasonAppService 里不等于生效，接在唯一漏斗上才算")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SEASON_PVP_LOCKED);

        try {
            guard.guard(attacker, notACity, now);
        } catch (BizException e) {
            // 这条断言只关心一件事：它没被赛季挡住。会不会被别的校验（护盾、圈层）拒掉与本题无关
            assertThat(e.errorCode())
                    .as("非玩家城的目标不该拿到赛季错（打野不受禁战期限制）")
                    .isNotEqualTo(ErrorCode.SEASON_PVP_LOCKED);
        }
    }

    // ---------- 表说了算 ----------

    @Test
    @DisplayName("改表就是改规则：把第一段的 rulePhase 改成 EXPAND，禁战期立刻消失")
    void rulePhaseColumnDrivesThePhases() {
        String tweaked = seasonJson.replace("\"rulePhase\": \"PREPARE\"", "\"rulePhase\": \"EXPAND\"");
        assertThat(tweaked).as("夹具必须真的改到了那一行").isNotEqualTo(seasonJson);

        ConfigRegistry reloaded = ConfigRegistry.loadFromDirectory(locateConfigDir());
        reloaded.reload("season", com.ironoath.config.cfg.SeasonCfg.class, tweaked);
        reloaded.reload("global", GlobalCfg.class,
                withSeasonStart(timeService.serverNow() - 60_000L));
        SeasonAppService seasons = wiringOn(reloaded).status();

        SeasonStatusResp resp = seasons.status(null);
        assertThat(resp.phase())
                .as("规则阶段来自表里那一列，代码里没有分支能把它掰回 PREPARE")
                .isEqualTo(SeasonPhase.EXPAND);
        assertThat(resp.allowsPvp()).isTrue();
    }

    // ---------- 夹具 ----------

    private SeasonStatusResp statusAtDay(long day) {
        return seasonAtDay(day).status(null);
    }

    /**
     * 一对配好的服务：状态查询要读实时榜，而榜在结算服务那里。
     *
     * <p>必须同源（同一个 registry、同一个结算器），否则测试读到的是另一份榜，
     * 「myRank 到底来自哪张榜」这件事就什么都没验证。
     */
    private record Wiring(SeasonAppService status, SeasonSettlementService settlements) {
    }

    private Wiring wiringAtDay(long day) {
        return wiringAnchoredAt(timeService.serverNow() - day * DAY);
    }

    private Wiring wiringAnchoredAt(long seasonStartAt) {
        return wiringAnchoredAt(seasonStartAt, timeService);
    }

    private Wiring wiringAnchoredAt(long seasonStartAt, com.ironoath.common.time.TimeService clock) {
        ConfigRegistry reloaded = ConfigRegistry.loadFromDirectory(locateConfigDir());
        reloaded.reload("global", GlobalCfg.class, withSeasonStart(seasonStartAt));
        return wiringOn(reloaded, clock);
    }

    /** 给定一份配置，装配一对同源的「状态查询 + 结算」服务。 */
    private Wiring wiringOn(ConfigRegistry registry) {
        return wiringOn(registry, timeService);
    }

    private Wiring wiringOn(ConfigRegistry registry, com.ironoath.common.time.TimeService clock) {
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(registry);
        // 红线判定（Bot 不进榜）需要一份注册表；本类不注册任何 Bot，它只是一个必填依赖
        com.ironoath.web.bot.BotRegistry bots = new com.ironoath.web.bot.BotRegistry(
                new com.ironoath.web.bot.BotRulesAssembler(registry));
        SeasonSettlementService settlements = new SeasonSettlementService(registry, clock,
                assembler, players, rewardService, new InMemorySeasonLedger(),
                new com.ironoath.web.store.memory.InMemorySeasonBoardStore(), idempotency, bots, battlePass);
        return new Wiring(new SeasonAppService(registry, clock, assembler, settlements),
                settlements);
    }

    /** 只关心状态查询的场合用这两个便捷方法。 */
    private SeasonAppService seasonAtDay(long day) {
        return wiringAtDay(day).status();
    }

    /** 把赛季锚点摆到 {@code seasonStartAt}，其余参数一律用真实配置表。 */
    private SeasonAppService seasonAnchoredAt(long seasonStartAt) {
        return wiringAnchoredAt(seasonStartAt).status();
    }

    /**
     * 往 global 表追加一行 {@code SEASON_START_AT}。
     *
     * <p>它平时<b>不在表里</b>：赛季锚点是部署参数而不是游戏数值（与 SERVER_OPEN_AT 同一口径，
     * 那张表放的是策划要调的数）。所以「配置它」在测试里就是加一行 —— 也顺带证明了
     * 没有这一行时赛季规则确实不启用（见 {@link #unconfiguredSeasonReportsNullNotZero}）。
     */
    private static String withSeasonStart(long seasonStartAt) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(globalJson);
            ArrayNode rows = (ArrayNode) table.get("rows");
            ObjectNode row = mapper.createObjectNode();
            row.put("id", "SEASON_START_AT");
            row.put("valueType", "LONG");
            row.put("value", seasonStartAt);
            row.put("unit", "毫秒时间戳");
            row.put("source", "B14 §一（部署参数，不进表）");
            row.put("why", "测试注入的赛季锚点：真实环境由部署时配置");
            rows.add(row);
            return mapper.writeValueAsString(table);
        } catch (Exception e) {
            throw new IllegalStateException("无法构造带 SEASON_START_AT 的 global 表", e);
        }
    }

    /** 建一个号并把主城落到世界里 —— 攻击漏斗是按坐标查城的。 */
    private String newPlayer(String nickName) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + java.util.UUID.randomUUID(), "dev-" + java.util.UUID.randomUUID(),
                nickName, 1_700_000_000_000L, "")).playerId();
        worldAppService.homeOf(playerId);
        return playerId;
    }

    /** surefire 的 cwd 是被测模块目录，所以向上找仓库根。 */
    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录，cwd=" + Path.of("").toAbsolutePath());
    }
}
