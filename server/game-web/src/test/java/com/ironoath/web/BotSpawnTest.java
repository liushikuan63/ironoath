package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotArchetype;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotSpawnService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ViewportReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;

/**
 * 职责：Bot 孵化（B11 §五 B 档）的落地验证 —— 世界真的有 Bot 了，而且它们经得起验收 3/11。
 * 依赖：Spring Boot Test + test profile（内存存储，不需要 MongoDB/Redis）。
 *
 * <p>本类盯的是四件「没接上也不会报错」的事：
 * <ol>
 *   <li><b>密度公式真的驱动了数量</b>：目标数 = clamp(密度 × 在线真人, 下限, 上限)，
 *       缺口补满即停，一轮不超过上限的 1%；</li>
 *   <li><b>孵出来的是世界公民而不是空 id</b>：有存档、有名字、有落点、有战力带 ——
 *       战力带还必须是「相对真人均值」而不是写死的初值（否则验收 3 恒真）；</li>
 *   <li><b>名字过验收 11</b>：不重复（含真人昵称）、没有机器感命名（不允许数字后缀）；</li>
 *   <li><b>它们是静止的</b>：没有军队、没有训练队列、最近活跃时间冻在孵化那一刻。</li>
 * </ol>
 *
 * <p><b>在线人数由用例直接给</b>（{@code topUp} 的入参）：真实连接数在单测里凑不出来，
 * 而验收 3/11 要 100 个样本。web 层的接点由 {@link #viewportDrivesTheSpawn} 单独验。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotSpawnTest {

    /** 密度曲线在测试 profile 下取 D30（未配 SERVER_OPEN_AT ⇒ 开服天数视为极大）⇒ 1.5。 */
    private static final long DENSITY_D30_FIXED = 15_000L;
    private static final long HUMAN_AVERAGE = 100_000L;

    @Autowired private BotSpawnService botSpawn;
    @Autowired private BotRegistry registry;
    @Autowired private BotRulesAssembler assembler;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private CityRepository cities;
    @Autowired private ArmyRepository armies;

    @BeforeEach
    void resetStores() {
        // BotRegistry / 节流预算都是进程内状态：不复位的话，上一条用例孵出来的 Bot 会被算进
        // 下一条的「已有数量」，而节流会把时间轴顶到未来 —— 两种症状都长得像「孵化没接上」
        registry.clear();
        botSpawn.clearThrottle();
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryWorldStore) world).clear();
    }

    // ---------- 装配：哪些数从表里读、哪些是派生 ----------

    /**
     * C0（2026-09-12）把 {@code greedOf(playStyle)} 里硬编码的五个数搬进了 {@code bot_archetype.greed} 列。
     *
     * <p><b>这条用例钉的是"搬"而不是"顺手改了数值"</b>：六个期望值就是原映射的一位不差。
     * 同时钉住 {@code activeness} <b>仍然是派生</b> —— 它与 {@code activeHoursPattern} 是同一个事实，
     * 加一列就是把一份真相存两处（收口清单 #90 记了这个判断）。
     */
    @Test
    @DisplayName("C0：greed 读表列（六个值一字未改），activeness 仍由活跃小时派生")
    void greedComesFromTheTableWhileActivenessStaysDerived() {
        java.util.Map<String, Long> greed = new java.util.HashMap<>();
        BotArchetype paoyao = null;
        for (BotArchetype archetype : assembler.archetypes()) {
            greed.put(archetype.id(), archetype.greedFixed());
            if ("bot_paoyao".equals(archetype.id())) {
                paoyao = archetype;
            }
        }

        assertThat(greed).hasSize(6);
        assertThat(greed.get("bot_paoyao")).as("FARMER 只打野采集").isEqualTo(FixedPoint.parse("0.75"));
        assertThat(greed.get("bot_junfa")).as("BUILDER 偏造建筑但仍要资源").isEqualTo(FixedPoint.parse("0.60"));
        assertThat(greed.get("bot_linju")).isEqualTo(FixedPoint.parse("0.50"));
        assertThat(greed.get("bot_yingzi")).isEqualTo(FixedPoint.parse("0.50"));
        assertThat(greed.get("bot_mengyou")).as("SOCIAL 重心在联盟互动不在野区").isEqualTo(FixedPoint.parse("0.35"));
        assertThat(greed.get("bot_jielue")).as("RAIDER 重心是侦查与掠夺").isEqualTo(FixedPoint.parse("0.20"));

        assertThat(paoyao.activenessFixed())
                .as("陪跑者活跃 5 小时 ⇒ 5/24，派生而不是列（列了就有两个家）")
                .isEqualTo(FixedPoint.div(FixedPoint.of(5L), FixedPoint.of(24L)));
    }

    // ---------- 数量：密度公式 ----------

    @Test
    @DisplayName("按密度补员：一轮最多补单服上限的 1%，补满即停（不会超发、也不会一次造满）")
    void topUpFillsToTheDensityTargetThenStops() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        long t0 = freshTime();
        int online = 50;
        int expectedTarget = (int) (online * DENSITY_D30_FIXED / 10_000L);

        int first = botSpawn.topUp(t0, human, anchor, worldAppService.rules(), online);
        assertThat(first).as("一轮最多补 5000/100 = 50 个，而不是一次补满").isEqualTo(50);
        assertThat(registry.size()).isEqualTo(50);

        int second = botSpawn.topUp(t0 + 61_000L, human, anchor, worldAppService.rules(), online);
        assertThat(second).as("剩余缺口继续补").isEqualTo(expectedTarget - 50);
        assertThat(registry.size()).as("补满到目标数").isEqualTo(expectedTarget);

        int third = botSpawn.topUp(t0 + 122_000L, human, anchor, worldAppService.rules(), online);
        assertThat(third).as("已经达标：再补一个就是超发").isZero();
        assertThat(registry.size()).isEqualTo(expectedTarget);
    }

    @Test
    @DisplayName("没有真人在线时退回下限（1 个），而不是 0 —— 新服第一张地图不能空着")
    void emptyServerStillGetsTheFloor() {
        long t0 = freshTime();
        int spawned = botSpawn.topUp(t0, null, Coord.of(100, 100), worldAppService.rules(), 0);
        assertThat(spawned).isEqualTo(1);
    }

    // ---------- 质量：验收 3 与验收 11 ----------

    @Test
    @DisplayName("验收3：随机抽 100 个 Bot，matchPower 与真人均值之比落在 [0.7, 1.0]，且档位有参差")
    void sampleHundredBotsPassThePowerCheck() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        List<String> botIds = spawnBots(100, human, anchor);

        Set<Long> powers = new HashSet<>();
        for (String botId : botIds) {
            PlayerSave save = players.findByPlayerId(botId).orElseThrow(() ->
                    new AssertionError("孵化的 Bot 没有存档：" + botId));
            long ratio = save.power().matchPower() * 10_000L / HUMAN_AVERAGE;
            assertThat(ratio)
                    .as("验收 3：Bot %s 的 matchPower=%d 与真人均值 %d 之比必须落在 [0.7, 1.0]",
                            botId, save.power().matchPower(), HUMAN_AVERAGE)
                    .isBetween(7_000L, 10_000L);
            assertThat(save.power().matchPower())
                    .as("战力不能是建档时的初值 100 —— 那是「没赋值」的样子")
                    .isNotEqualTo(100L);
            powers.add(save.power().matchPower());
        }
        assertThat(powers.size())
                .as("六个原型的档位必须体现出差异（§一：军阀高、陪跑者低）").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("验收11：100 个 Bot 的名字互不重复、不撞真人、没有数字后缀")
    void sampleHundredNamesAreUniqueAndHumanLike() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        String humanName = players.findByPlayerId(human).orElseThrow().nickName();
        Coord anchor = world.cityOf(human).orElseThrow();
        List<String> botIds = spawnBots(100, human, anchor);

        Set<String> names = new HashSet<>();
        for (String botId : botIds) {
            String name = players.findByPlayerId(botId).orElseThrow().nickName();
            assertThat(name)
                    .as("机器感命名：禁止数字/字母后缀（B11 §四 禁止「玩家12345」式名字），实际=%s", name)
                    .doesNotContainPattern("[0-9A-Za-z]");
            assertThat(names.add(name)).as("名字重复：%s", name).isTrue();
            assertThat(name).as("不能与真人昵称撞名").isNotEqualTo(humanName);
        }
        assertThat(names).hasSize(100);
    }

    @Test
    @DisplayName("落点在锚的 5~15 格环带内（§四：一出城就有邻居）")
    void placementLandsInTheRingAroundTheAnchor() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        List<String> botIds = spawnBots(30, human, anchor);

        for (String botId : botIds) {
            Coord coord = world.cityOf(botId).orElseThrow(() ->
                    new AssertionError("孵化的 Bot 没有落点：" + botId));
            assertThat(coord.distanceTo(anchor))
                    .as("Bot 落点距锚 %d 格，必须在 [5,15] 内", coord.distanceTo(anchor))
                    .isBetween(5, 15);
        }
    }

    @Test
    @DisplayName("静止：孵出来的 Bot 不建军队、不排队列，最近活跃冻在孵化那一刻")
    void spawnedBotsAreStatic() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        List<String> botIds = spawnBots(5, human, anchor);

        for (String botId : botIds) {
            BotProfile profile = registry.profileOf(botId);
            assertThat(profile).as("必须在注册表里（合规判定的唯一入口）").isNotNull();
            assertThat(armies.findByPlayerId(botId))
                    .as("静止的 Bot 不该有军队（没有训练队列、没有伤兵）").isEmpty();
            assertThat(cities.findByPlayerId(botId))
                    .as("城与真人一样是惰性的：没打开过城内界面就没有 CityState 对象（可见性靠落点 + 存档等级）")
                    .isEmpty();
            assertThat(players.findByPlayerId(botId).orElseThrow().lastLoginAt())
                    .as("静止 ⇒ 只有孵化那一刻算「登录过」").isPositive();
        }
    }

    // ---------- 校准：真人均值不能被 Bot 自己带偏 ----------

    @Test
    @DisplayName("战力带取自真人均值：Bot 不计入均值（否则每孵一轮均值就被自己拖低一次）")
    void powerBandFollowsTheHumanAverageOnly() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        long t0 = freshTime();

        List<Long> lastRound = List.of();
        for (int round = 0; round < 3; round++) {
            botSpawn.topUp(t0 + round * 61_000L, human, anchor, worldAppService.rules(), 100);
            lastRound = registry.botIds().stream()
                    .map(id -> players.findByPlayerId(id).orElseThrow().power().matchPower())
                    .toList();
        }
        assertThat(lastRound).as("三轮之后应当有 150 个 Bot").hasSize(150);
        for (long power : lastRound) {
            // Bot 自己被算进均值时，均值会一轮比一轮低（Bot 只有真人均值的 0.7~1.0 倍），
            // 三轮之后就会跌破下界 —— 那条正反馈正是这条断言要钉住的东西
            assertThat(power).as("战力带漂移了：出现 %d（真人均值 %d 的下界是 %d）",
                            power, HUMAN_AVERAGE, HUMAN_AVERAGE * 7 / 10)
                    .isBetween(HUMAN_AVERAGE * 7 / 10, HUMAN_AVERAGE);
        }
    }

    // ---------- 接点与节流 ----------

    @Test
    @DisplayName("节流：同一个时刻连读 20 次图只补一轮，越过预算才继续补")
    void sweepIsThrottledWithinTheBudget() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();
        long t0 = freshTime();
        int online = 100;

        // 第一次用 sweep（带节流），后面 20 次用同一时刻重复调 —— 都不该再建档
        botSpawn.sweep(t0, human, anchor, worldAppService.rules(), online);
        assertThat(registry.size()).as("一轮最多 50 个").isEqualTo(50);
        for (int i = 0; i < 20; i++) {
            botSpawn.sweep(t0, human, anchor, worldAppService.rules(), online);
        }
        assertThat(registry.size())
                .as("节流预算内又补了：一次拖图风暴会变成一轮批量建档（缺口还有 100 个）")
                .isEqualTo(50);

        botSpawn.sweep(t0 + 61_000L, human, anchor, worldAppService.rules(), online);
        assertThat(registry.size()).as("越过预算后继续补").isEqualTo(100);
    }

    @Test
    @DisplayName("视野下发驱动补员（接点），且 100 次请求里只补下限那 1 个")
    void viewportDrivesTheSpawn() {
        String human = newPlacedHuman(HUMAN_AVERAGE);
        Coord anchor = world.cityOf(human).orElseThrow();

        worldAppService.viewport(human, new ViewportReq(anchor.x(), anchor.y(), 0, List.of()));
        assertThat(registry.size()).as("读图就把世界补起来（在线 0 ⇒ 下限 1 个）").isEqualTo(1);

        for (int i = 0; i < 10; i++) {
            worldAppService.viewport(human, new ViewportReq(anchor.x(), anchor.y(), 0, List.of()));
        }
        assertThat(registry.size()).as("已经达标：读图再多也不该继续建档").isEqualTo(1);
    }

    // ---------- 辅助 ----------

    /**
     * 造一个已经在地图上的真人。
     *
     * <p>战力<b>刻意不是建档初值</b>：验收 3 判的是「与真人均值之比」，
     * 用初值 100 当均值时，「写死 100」与「按均值校准」两种实现都会通过 ——
     * 那样这条用例就白测了。
     *
     * <p>昵称取<b>名字池里真实存在的组合</b>（bot_name 的姓氏 + 双字名，无称号）：
     * 用池外的名字（例如「真人甲」）时，「Bot 不与真人撞名」那条断言永远不可能失败，
     * 是一条空断言。生成器对 {@code taken} 的强制重试由 game-core 的
     * {@code BotSystemTest}（小池子耗尽用例）确定性覆盖，这里验的是「建档时真的带上了这份名单」。
     */
    private String newPlacedHuman(long matchPower) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "test-" + UUID.randomUUID(), "human-" + UUID.randomUUID(), "裴惊澜", freshTime()))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPower(new PlayerPower(matchPower, matchPower, matchPower));
        players.save(save);
        worldAppService.homeOf(playerId);
        return playerId;
    }

    /** 连补几轮直到凑够 {@code want} 个 Bot（一轮的批量上限是 50）。 */
    private List<String> spawnBots(int want, String human, Coord anchor) {
        long t0 = freshTime();
        int online = 200;   // 目标数 = 200 × 1.5 = 300，够 100 个样本还有余
        for (int round = 0; registry.size() < want; round++) {
            botSpawn.topUp(t0 + round * 61_000L, human, anchor, worldAppService.rules(), online);
        }
        List<String> ids = new ArrayList<>(registry.botIds());
        return ids.subList(0, Math.min(want, ids.size()));
    }

    /**
     * 一个不与其它用例重叠的合成时间轴。
     *
     * <p>与公敌广播那条用例同一个理由：进程内节流挡的是「比上次 sweep 更早的时刻」，
     * 用例之间只要有一个把时间轴推到未来，别的用例就会无辜早退。
     */
    private static long freshTime() {
        return System.currentTimeMillis() + 60L * 60_000L;
    }
}
