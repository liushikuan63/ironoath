package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotPveTargets;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;

/**
 * 职责：C1b 的 PvE 那半的落地验证（收口清单 §五 C1b / #96）—— 打野与采集真的出门，
 *       目标真的来自「看得见的候选」，看不见就安静跳过。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么直接对 {@code execute} 发决策</b>：触发条件（体力与队列、greed 分流）在 core 用例里
 * 已经钉住，这里要量的是"决策给出之后，动作有没有真的落到世界上"。用真实 tick 凑出打野
 * 会让用例依赖随机序列 —— 红了也说不清是哪一层坏了。
 *
 * <p><b>每条都断言可观察的副作用</b>：打野看得到的是一支打向那只野怪的行军、
 * 采集看到的是 action=GATHER 的行军、看不见时看到的是「没有行军且什么都没报错」。
 * 期望目标由用例<b>独立扫一遍格子</b>算出来（不是复用被测代码的选择结果）——
 * 否则选错了也会自洽地通过。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotPveTest {

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private ArmyRepository armies;
    @Autowired private MarchRepository marches;
    @Autowired private InMemorySocialStore socialStore;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotAttackLimiter limiter;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        bots.clear();
        limiter.reset();
        runtime.reset();
        adapter.resetCounters();
    }

    // ---------- 打野 ----------

    @Test
    @DisplayName("打野：真的派出一支打向「打得过的最大等级野怪」的出征，且保护期内的新号 Bot 也打得动")
    void huntSendsAMarchToTheStrongestBeatableMonster() {
        String bot = botAt(256, 256);
        giveTroops(bot, Map.of("unit_infantry_t1", 300L));
        powerRefreshService.refresh(bot);
        // 生产上由 BotRuntimeService.enrollNew 补这一步（用例在验「有视野之后打野怎么走」）
        worldAppService.ensureHomeExplored(bot);
        long power = players.findByPlayerId(bot).orElseThrow().power().matchPower();

        // 用例自己独立算期望：在家所在块里扫出所有打得过的野怪，按 (等级降序, 距离升序, 坐标序) 取第一只。
        // 与生产同一份输入（同一块、同一张 mapmonster 表），但比较逻辑是用例自己写的 ——
        // 直接复用被测选择器的结果会让「选错了」也自洽通过
        List<BotPveTargets.Monster> visible = monstersInHomeChunk(bot);
        assertThat(visible).as("世界的家附近必须有怪，否则这条用例什么都没验").isNotEmpty();
        BotPveTargets.Monster expected = visible.stream()
                .filter(m -> m.power() <= power)
                .min((a, b) -> {
                    if (a.level() != b.level()) {
                        return Integer.compare(b.level(), a.level());
                    }
                    int da = home(bot).distanceTo(a.coord());
                    int db = home(bot).distanceTo(b.coord());
                    if (da != db) {
                        return Integer.compare(da, db);
                    }
                    return coordOrder(a.coord(), b.coord());
                })
                .orElse(null);
        assertThat(expected).as("300 个 T1 兵的 Bot 必须打得过至少一只怪（战力=%s）", power).isNotNull();

        adapter.execute(bot, new BotDecisionTree.Decision(
                BotDecisionTree.Action.HUNT_MONSTER, false, 0L, "测试：直接发一个打野决策"),
                timeService.serverNow());

        assertThat(adapter.actionCounts().get("hunted"))
                .as("打野必须真的出门（目标选择 + 出征两步都成了）").isEqualTo(1L);
        assertThat(adapter.failedCount()).as("这不是一次失败").isZero();
        assertThat(marches.activeCountOf(bot)).as("世界上多了一支在外的队伍").isEqualTo(1L);
        March march = marches.findByPlayerId(bot).get(0);
        assertThat(march.to()).as("打的就是用例算出来的那一只").isEqualTo(expected.coord());
        assertThat(march.action()).isEqualTo(March.Action.ATTACK);
    }

    @Test
    @DisplayName("打野对照组：视野为空（迷雾没点亮）时安静跳过 —— 看不见就打不到，Bot 不透视迷雾")
    void huntIsQuietWithoutVision() {
        String bot = botAt(256, 256);
        giveTroops(bot, Map.of("unit_infantry_t1", 300L));
        powerRefreshService.refresh(bot);
        // 刻意不调 ensureHomeExplored：这是一座"刚落到世界上、还没看过地图"的城

        adapter.execute(bot, new BotDecisionTree.Decision(
                BotDecisionTree.Action.HUNT_MONSTER, false, 0L, "测试：视野为空"), timeService.serverNow());

        assertThat(adapter.actionCounts().get("hunted")).as("看不见就不该出门").isZero();
        assertThat(marches.activeCountOf(bot)).isZero();
        assertThat(adapter.failedCount())
                .as("「看得见的目标里没有能打的」是玩法常态，不该被计成失败").isZero();
    }

    // ---------- 采集 ----------

    @Test
    @DisplayName("采集：真的派出一支 action=GATHER 的行军，目标是可见资源点里最近的")
    void gatherSendsAMarchToTheNearestVisibleResource() {
        String bot = botAt(256, 256);
        giveTroops(bot, Map.of("unit_infantry_t1", 300L));
        powerRefreshService.refresh(bot);
        worldAppService.ensureHomeExplored(bot);

        List<BotPveTargets.Resource> visible = resourcesInHomeChunk(bot);
        assertThat(visible).as("家附近必须有资源点").isNotEmpty();
        BotPveTargets.Resource expected = visible.stream()
                .min((a, b) -> {
                    int da = home(bot).distanceTo(a.coord());
                    int db = home(bot).distanceTo(b.coord());
                    if (da != db) {
                        return Integer.compare(da, db);
                    }
                    return coordOrder(a.coord(), b.coord());
                })
                .orElseThrow();

        adapter.execute(bot, new BotDecisionTree.Decision(
                BotDecisionTree.Action.GATHER_RESOURCE, false, 0L, "测试：直接发一个采集决策"),
                timeService.serverNow());

        assertThat(adapter.actionCounts().get("gathered")).isEqualTo(1L);
        assertThat(adapter.failedCount()).isZero();
        assertThat(marches.activeCountOf(bot)).isEqualTo(1L);
        March march = marches.findByPlayerId(bot).get(0);
        assertThat(march.to()).isEqualTo(expected.coord());
        assertThat(march.action()).isEqualTo(March.Action.GATHER);
    }

    // ---------- 登记时补点灯（#35 的接线） ----------

    @Test
    @DisplayName("登记进作息表那一刻补点亮家门口：tick 之前没有视野，tick 之后家所在块已探索")
    void enrollingABotLightsItsHomeChunk() {
        String bot = botAt(256, 256);
        Coord home = home(bot);
        assertThat(world.fogOf(bot).chunks())
                .as("孵化直落位的 Bot 还没有视野（孵化器自己点不了灯，见 BotSpawnService#placeNear）")
                .isEmpty();

        runtime.tick(timeService.serverNow());

        String chunkKey = home.chunkKey((int) configs.longParam("WORLD_CHUNK_SIZE"));
        assertThat(world.fogOf(bot).chunks())
                .as("BotRuntimeService.enrollNew 在登记时调 ensureHomeExplored —— 少了这一步，"
                        + "打野的候选列表恒为空（Bot 会一直安静跳过）")
                .contains(chunkKey);
    }

    // ---------- 夹具 ----------

    /** 一个托管账号（活跃时段给满 24 小时）。注意：新号自带 72h 新手保护，本类刻意不解除 ——
     *  B08 §7 只禁「攻击玩家」，打野与采集在保护期内也必须是通的（2026-09-12 修）。 */
    private String botAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "打野测试" + UUID.randomUUID().toString().substring(0, 4), 1_700_000_000_000L)).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new com.ironoath.core.bot.BotProfile(playerId, "bot_linju",
                new com.ironoath.core.bot.BotProfile.AiProfile(FixedPoint.parse("0.30"),
                        FixedPoint.parse("0.75"), FixedPoint.parse("0.50"), FixedPoint.parse("1.0")),
                new com.ironoath.core.bot.BotProfile.Persona(42L, 7L, 99L, allHours,
                        3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        return playerId;
    }

    private Coord home(String botId) {
        return world.cityOf(botId).orElseThrow(() -> new AssertionError("Bot 应该有城"));
    }

    /** 并列裁决的最后一道（行优先坐标序），与生产规则同一条 —— 用例用它独立算期望。 */
    private static int coordOrder(Coord a, Coord b) {
        if (a.y() != b.y()) {
            return Integer.compare(a.y(), b.y());
        }
        return Integer.compare(a.x(), b.x());
    }

    /** 家所在块里的全部野怪（含打不过的），战力来自 mapmonster 表 —— 与生产同一份输入。 */
    private List<BotPveTargets.Monster> monstersInHomeChunk(String botId) {
        List<BotPveTargets.Monster> out = new ArrayList<>();
        for (Coord coord : homeChunkCells(botId)) {
            WorldGenerator.Cell cell = worldAppService.cellAt(coord);
            if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
                MapmonsterCfg monster = configs.get(MapmonsterCfg.class, cell.entityId());
                out.add(new BotPveTargets.Monster(coord, cell.entityId(), cell.level(), monster.power()));
            }
        }
        return out;
    }

    private List<BotPveTargets.Resource> resourcesInHomeChunk(String botId) {
        List<BotPveTargets.Resource> out = new ArrayList<>();
        for (Coord coord : homeChunkCells(botId)) {
            WorldGenerator.Cell cell = worldAppService.cellAt(coord);
            if (cell.entityType() == WorldGenerator.EntityType.RESOURCE) {
                out.add(new BotPveTargets.Resource(coord, cell.entityId()));
            }
        }
        return out;
    }

    /**
     * 家所在块的全部格子（只扫这一块：用例先调了 {@code ensureHomeExplored}，
     * 视野 footprint 里已探索的只有它 —— 与生产看到的候选集一致）。
     */
    private List<Coord> homeChunkCells(String botId) {
        int chunkSize = (int) configs.longParam("WORLD_CHUNK_SIZE");
        Coord origin = Coord.chunkOrigin(home(botId).chunkKey(chunkSize), chunkSize);
        List<Coord> cells = new ArrayList<>(chunkSize * chunkSize);
        for (int dy = 0; dy < chunkSize; dy++) {
            for (int dx = 0; dx < chunkSize; dx++) {
                Coord coord = Coord.of(origin.x() + dx, origin.y() + dy);
                if (coord.withinWorld((int) configs.longParam("WORLD_SIZE"))
                        && world.cityAt(coord).isEmpty()
                        && !world.isConsumed(coord)) {
                    cells.add(coord);
                }
            }
        }
        return cells;
    }

    private void giveTroops(String playerId, Map<String, Long> byUnitId) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        byUnitId.forEach(army::add);
        armies.save(playerId, army, version);
    }
}
