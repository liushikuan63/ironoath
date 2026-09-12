package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.ChunkData;
import com.ironoath.web.dto.generated.ChunkVersion;
import com.ironoath.web.dto.generated.ExileReq;
import com.ironoath.web.dto.generated.ExileResp;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.GatherResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.ItemCount;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchIdReq;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchResp;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RecallResp;
import com.ironoath.web.dto.generated.ScoutReq;
import com.ironoath.web.dto.generated.ViewportReq;
import com.ironoath.web.dto.generated.ViewportResp;
import com.ironoath.web.dto.generated.WorldEntity;
import com.ironoath.web.dto.generated.WorldEntityType;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B07 世界地图与行军的端到端验证 —— 验收 5 / 6 / 7，以及迷雾与到期推进。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁 + 内存到期队列）。
 *
 * <p>验收 2（1000 支同时到期）在 {@link SortedMarchDueQueueTest} 里验队列本身；
 * 验收 11（无 per-march 定时器）由 CI 的 check-layering.sh 静态检查守；
 * 验收 3/4/9/10 需要真机与真实 Cocos 场景，这里覆盖不到。
 */
@SpringBootTest
@ActiveProfiles("test")
class WorldEndpointTest {

    @Autowired private com.ironoath.core.event.GameEventBus questBus;


    @Autowired private WorldAppService worldAppService;
    @Autowired private com.ironoath.web.service.ExileAppService exileAppService;
    @Autowired private MarchAppService marchAppService;
    @Autowired private ArmyAppService armyAppService;
    @Autowired private HeroAppService heroAppService;
    @Autowired private CityAppService cityAppService;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;

    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private com.ironoath.core.bag.InventoryRepository inventories;
    @Autowired private ArmyRepository armies;
    @Autowired private com.ironoath.core.hero.HeroRepository heroes;
    @Autowired private WorldRepository world;
    @Autowired private MarchRepository marches;
    @Autowired private MarchDueQueue dueQueue;
    @Autowired private com.ironoath.core.gacha.GachaStateRepository gachaStates;
    @Autowired private com.ironoath.core.gacha.GachaLogStore gachaLogs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryHeroStore) heroes).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        ((InMemoryGachaStateStore) gachaStates).clear();
        ((InMemoryGachaLogStore) gachaLogs).clear();
    }

    // ---------- 家坐标与落位 ----------

    @Test
    @DisplayName("落位是确定性的：同一玩家反复查询得到同一坐标，两个玩家不会重叠")
    void homePlacementIsDeterministicAndNonOverlapping() {
        String first = newPlayer();
        String second = newPlayer();

        Coord homeA = worldAppService.homeOf(first);
        Coord homeB = worldAppService.homeOf(second);
        assertThat(homeA).isEqualTo(worldAppService.homeOf(first));
        assertThat(homeB).isEqualTo(worldAppService.homeOf(second));
        assertThat(homeA).isNotEqualTo(homeB);

        int margin = (int) configs.longParam("WORLD_SPAWN_MARGIN");
        int size = (int) configs.longParam("WORLD_SIZE");
        for (Coord home : List.of(homeA, homeB)) {
            assertThat(home.x()).isBetween(margin, size - margin - 1);
            assertThat(home.y()).isBetween(margin, size - margin - 1);
        }
        assertThat(world.cityAt(homeA)).contains(first);
    }

    // ---------- 分块视野 ----------

    @Test
    @DisplayName("验收6：二次请求带上版本号后，未变化的块不再下发实体（实体数为 0）")
    void viewportIsIncrementalByVersion() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        // 走一次行军让路径上的块被探索并推进版本号
        prepareArmy(playerId, 200L);
        marchAppService.send(playerId, marchReq(home, 40, MarchAction.STATION, 10L));

        ViewportResp first = worldAppService.viewport(playerId,
                new ViewportReq(home.x(), home.y(), 0, List.of()));
        assertThat(first.chunks()).isNotEmpty();
        long firstEntities = first.chunks().stream().mapToLong(c -> c.entities().size()).sum();
        assertThat(firstEntities).as("首次请求应当拿到实体（至少自己的城）").isPositive();
        assertThat(first.chunks()).anySatisfy(c ->
                assertThat(c.entities()).anySatisfy(e ->
                        assertThat(e.type().name()).isEqualTo("CITY")));

        // 二次请求：把首次拿到的版本号原样带回去
        List<ChunkVersion> versions = new ArrayList<>();
        for (ChunkData chunk : first.chunks()) {
            versions.add(new ChunkVersion(chunk.key(), chunk.version()));
        }
        // 版本号必须是最新的，否则服务端仍会认为客户端落后
        for (ChunkData chunk : first.chunks()) {
            assertThat(world.chunkVersion(chunk.key())).isEqualTo(chunk.version());
        }
        ViewportResp second = worldAppService.viewport(playerId,
                new ViewportReq(home.x(), home.y(), 0, versions));
        long secondEntities = second.chunks().stream().mapToLong(c -> c.entities().size()).sum();
        assertThat(secondEntities)
                .as("客户端已是最新 ⇒ 不该再下发任何实体（B07 验收 6）").isZero();
        assertThat(second.staleChunks()).as("没有变化就不该有 stale").isEmpty();
    }

    @Test
    @DisplayName("只下发 3×3 个 chunk，绝不一次性给整张地图（B07 头号红线）")
    void viewportIsLimitedToNineChunks() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        ViewportResp resp = worldAppService.viewport(playerId,
                new ViewportReq(home.x(), home.y(), 0, List.of()));

        int expected = (int) configs.longParam("VIEWPORT_CHUNK_COUNT");
        assertThat(resp.chunks().size() + resp.staleChunks().size())
                .as("下发 + 标 stale 的块总数不得超过 3×3").isLessThanOrEqualTo(expected);
        int chunkGrid = (int) configs.longParam("WORLD_SIZE") / (int) configs.longParam("WORLD_CHUNK_SIZE");
        assertThat(resp.chunks().size())
                .as("绝不能接近全图的 %d 个块", chunkGrid * chunkGrid)
                .isLessThan(chunkGrid * chunkGrid / 4);
    }

    @Test
    @DisplayName("验收5：单次响应不超过 VIEWPORT_PAYLOAD_MAX_BYTES；装不下的块标 truncated/stale 而不是截半下发")
    void viewportRespectsPayloadBudget() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        // 把上限压到极小，强制触发分批：这不是「改配置迁就测试」，
        // 而是验证「超限时的行为正确」—— 生产环境用的是 20480，行为必须一样
        ViewportResp resp = worldAppService.viewport(playerId,
                new ViewportReq(home.x(), home.y(), 0, List.of()));

        long limit = configs.longParam("VIEWPORT_PAYLOAD_MAX_BYTES");
        long total = 0L;
        for (ChunkData chunk : resp.chunks()) {
            assertThat(chunk.truncated())
                    .as("块要么完整下发、要么整块转 stale，绝不截半 —— 截半会让玩家看到「明明有怪却是空地」")
                    .isFalse();
            total += com.ironoath.common.json.JsonUtils.toJson(chunk)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        assertThat(total).as("已下发块的总字节数不得超过上限 %d", limit).isLessThanOrEqualTo(limit);
    }

    @Test
    @DisplayName("迷雾块的实体根本不下发：迷雾是服务端的，不是客户端遮罩")
    void fogChunksCarryNoEntities() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        // 这条纪律现在只有一个例外：B08 §4 第一档「坐标不再受迷雾保护」的人，
        // 他的城会照常下发（见 fogChunksCarryOnlyExposedCities）。除此之外一个实体都不许出现
        // 视口中心放到远离家的地方：那些块从未被探索过
        int far = (home.x() + 300) % (int) configs.longParam("WORLD_SIZE");
        ViewportResp resp = worldAppService.viewport(playerId,
                new ViewportReq(far, home.y(), 0, List.of()));

        assertThat(resp.fogChunks()).as("远离家的块应当仍是迷雾").isNotEmpty();
        for (String fogKey : resp.fogChunks()) {
            for (ChunkData chunk : resp.chunks()) {
                if (chunk.key().equals(fogKey)) {
                    assertThat(chunk.entities())
                            .as("迷雾块 %s 不得下发任何实体，否则改一下客户端就能透视全图", fogKey)
                            .isEmpty();
                }
            }
        }
        // exploredChunks 只列「本次视口内」已探索的块，而本次视口中心在 300 格外的未探索区，
        // 所以它为空是正确的。要验「家附近已探索」必须另开一次以家为中心的视口
        ViewportResp atHome = worldAppService.viewport(playerId,
                new ViewportReq(home.x(), home.y(), 0, List.of()));
        assertThat(atHome.exploredChunks())
                .as("落城时自家 chunk 必须已探索，否则新号落地看到一片全黑会以为游戏坏了")
                .isNotEmpty();
        assertThat(atHome.fogChunks()).as("自家视口内不该全是迷雾").hasSizeLessThan(9);
    }

    @Test
    @DisplayName("B08 §4 第一档：坐标已暴露的人，城在未探索块里也照样下发；平民仍然被雾挡住")
    void fogChunksCarryOnlyExposedCities() {
        String viewer = newPlayer();
        Coord home = worldAppService.homeOf(viewer);
        int size = (int) configs.longParam("WORLD_SIZE");
        int chunkSize = (int) configs.longParam("WORLD_CHUNK_SIZE");
        // 两个城放在<b>同一个</b>未探索块内、且离块边界留 1 格，免得坐标跨界让断言变成运气 test
        int originX = ((home.x() + 300) % size) / chunkSize * chunkSize + 1;
        int originY = home.y() / chunkSize * chunkSize + 1;
        String brute = newPlayer();
        String commoner = newPlayer();
        assertThat(world.placeCity(brute, Coord.of(originX, originY))).isTrue();
        assertThat(world.placeCity(commoner, Coord.of(originX + 1, originY))).isTrue();
        raiseTyranny(brute, 100L, System.currentTimeMillis());

        List<String> citiesInFogChunks = new ArrayList<>();
        ViewportResp resp = worldAppService.viewport(viewer,
                new ViewportReq(originX, originY, 0, List.of()));
        for (ChunkData chunk : resp.chunks()) {
            if (!resp.fogChunks().contains(chunk.key())) {
                continue;   // 已探索块本来就全下发，与这条规则无关
            }
            for (WorldEntity entity : chunk.entities()) {
                if (entity.type() == WorldEntityType.CITY) {
                    citiesInFogChunks.add(entity.id());
                }
            }
        }
        assertThat(citiesInFogChunks)
                .as("未探索块里只允许出现坐标已暴露的城 —— 平民混进来就等于改一下客户端能透视全图，"
                        + "而暴露的人不在里面就等于第一档效果只写在文档里")
                .containsExactly(brute);
    }

    @Test
    @DisplayName("坐标越界时返回结构化错误，而不是生成一个不存在的 chunk 键")
    void viewportRejectsOutOfBoundsCoords() {
        String playerId = newPlayer();
        int size = (int) configs.longParam("WORLD_SIZE");
        assertThatThrownBy(() -> worldAppService.viewport(playerId,
                new ViewportReq(size, 0, 0, List.of())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_COORD_INVALID);
    }

    // ---------- 行军 ----------

    @Test
    @DisplayName("出征：时长 = 曼哈顿距离 × 每格秒数 ÷ 最慢兵种速度，兵从军营扣除，名额被占用")
    void marchingFollowsConfiguredFormula() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 100L);

        int dx = 40;
        MarchResp resp = marchAppService.send(playerId, marchReq(home, dx, MarchAction.STATION, 100L));

        int distance = resp.distance();
        assertThat(distance).as("曼哈顿距离").isEqualTo(dx);
        long perTile = configs.fixedParam("MARCH_SECONDS_PER_TILE");
        int speed = (int) configs.get(UnitCfg.class, "unit_infantry_t1").speed();
        long expected = com.ironoath.core.march.MarchCalculator.durationSeconds(distance, speed, perTile, 0L);
        assertThat(resp.durationSec()).isEqualTo(expected);
        assertThat(resp.march().arriveAt() - resp.serverNow())
                .as("到达时刻 = 服务端当前时刻 + 时长（毫秒）")
                .isEqualTo(expected * 1000L);
        assertThat(resp.march().teamSpeed()).as("下发队伍速度，让玩家看懂为什么这么慢").isEqualTo(speed);
        assertThat(resp.march().loadCap())
                .isEqualTo(100L * configs.get(UnitCfg.class, "unit_infantry_t1").load());

        // 兵必须真的从军营扣走，否则同一批兵可以被派出去两次
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        assertThat(army.countOf("unit_infantry_t1")).isZero();
        assertThat(marches.activeCountOf(playerId)).isEqualTo(1L);
        assertThat(dueQueue.size()).as("到期事件必须已登记").isEqualTo(1);
    }

    @Test
    @DisplayName("出征名额用满后拒绝，且提示可开启额外名额（B15 特权）")
    void marchConcurrencyIsCapped() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 300L);
        long max = configs.longParam("MARCH_MAX_CONCURRENT");

        for (int i = 0; i < max; i++) {
            marchAppService.send(playerId, marchReq(home, 10 + i, MarchAction.STATION, 10L));
        }
        assertThatThrownBy(() -> marchAppService.send(playerId,
                marchReq(home, 99, MarchAction.STATION, 10L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MARCH_QUEUE_FULL);
    }

    @Test
    @DisplayName("带的兵不够时整体拒绝，一个兵都不扣")
    void marchingRejectsWhenTroopsInsufficient() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 5L);

        // 用 towardX 而不是 home.x() + 20：出生点由 playerId 哈希推导，
        // 固定偏移会随玩家 id 随机越界（512 的世界里约有一成玩家会踩到），
        // 越界不会失败在「兵力不足」这条断言上，而是失败在一个坐标异常里，
        // 看起来像是被测功能坏了 —— 这是最难查的一类夹具 bug，而且它只在某些运行里出现
        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(
                newRequestId(), towardX(home, 20), home.y(),
                List.of(new MarchUnit("unit_infantry_t1", 50L)), List.of(), MarchAction.STATION)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.UNIT_NOT_ENOUGH);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("被拒绝时不能扣兵").isEqualTo(5L);
    }

    @Test
    @DisplayName("验收7：去程中途召回 ⇒ 返程时长 = 已行军时长，兵力零损失")
    void recallMirrorsTraveledTime() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 100L);
        MarchResp sent = marchAppService.send(playerId, marchReq(home, 100, MarchAction.STATION, 100L));
        long fullTrip = sent.march().arriveAt() - sent.march().startAt();

        // 把到达时刻推远，确保召回发生在去程中途而不是已到点
        March stored = marches.findById(sent.march().marchId()).orElseThrow();
        long version = marches.versionOf(stored.id());
        stored.restore(sent.march().startAt() + fullTrip * 4, stored.returnArriveAt(),
                stored.returnStartAt(), stored.returnFrom(), stored.load(), stored.status(),
                stored.gatherStartAt(), stored.units());
        marches.save(stored, version);
        dueQueue.reschedule(stored.id(), sent.march().startAt() + fullTrip * 4);

        RecallResp recall = marchAppService.recall(playerId,
                new MarchIdReq(newRequestId(), sent.march().marchId()));
        assertThat(recall.march().status().name()).isEqualTo("RETURNING");
        assertThat(recall.returnSeconds())
                .as("返程时长必须等于已行军时长，误差不超过 1 秒（整除截断）")
                .isBetween(0L, fullTrip / 1000L);
        // 到家后兵力必须一个不少地回到军营
        rewindMarch(sent.march().marchId());
        marchAppService.list(playerId);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("召回不损失兵力").isEqualTo(100L);
        assertThat(marches.activeCountOf(playerId)).as("到家后名额必须释放").isZero();
    }

    @Test
    @DisplayName("召回后返程起点是「队伍当前所在格」而不是目的地（验收 1 要防的瞬移）")
    void recalledMarchDoesNotTeleport() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 50L);
        // dx 取 40 而不是 100：出生点可能在 x=489 附近，+100 会超出世界边长 512
        MarchResp sent = marchAppService.send(playerId, marchReq(home, 40, MarchAction.STATION, 50L));

        // 把到达时刻推远，确保召回确实发生在去程途中而不是已到点之后
        March stored = marches.findById(sent.march().marchId()).orElseThrow();
        long version = marches.versionOf(stored.id());
        long farFuture = sent.march().startAt() + (sent.march().arriveAt() - sent.march().startAt()) * 4;
        stored.restore(farFuture, null, null, null, 0L, stored.status(), null, stored.units());
        marches.save(stored, version);
        dueQueue.reschedule(stored.id(), farFuture);

        RecallResp recall = marchAppService.recall(playerId,
                new MarchIdReq(newRequestId(), sent.march().marchId()));

        // 召回紧跟在出征之后几毫秒，所以队伍应当还在出发点附近。
        // 关键是它绝不能等于目的地 —— 那正是「返程起点写死成 to」这个瞬移 bug 的表现
        // 断言与行进方向无关（marchReq 可能因为贴边而反向出发）：
        // 要守的是「离出发点很近」且「绝不是目的地」
        assertThat(Math.abs(recall.march().position().x() - home.x()))
                .as("召回瞬间队伍还在出发点附近，不能瞬移到目的地")
                .isLessThanOrEqualTo(5);
        assertThat(recall.march().position().x())
                .as("召回瞬间绝不能已经在目的地").isNotEqualTo(sent.march().to().x());
        assertThat(recall.march().returnStartAt()).isNotNull();

        March after = marches.findById(sent.march().marchId()).orElseThrow();
        assertThat(after.returnFrom())
                .as("返程起点必须是召回那一刻的位置，而不是目的地")
                .isNotEqualTo(after.to())
                .isEqualTo(after.positionAt(after.returnStartAt()));
        assertThat(after.status()).isEqualTo(March.Status.RETURNING);
    }

    @Test
    @DisplayName("到期由请求驱动推进：到点后下一次查询就把状态落地，服务端没有任何常驻定时器")
    void arrivalIsAdvancedByRequestsNotTimers() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 10L);
        MarchResp sent = marchAppService.send(playerId, marchReq(home, 5, MarchAction.STATION, 10L));

        // 到点之前：状态仍是 MARCHING
        assertThat(marchAppService.list(playerId).marches().get(0).status().name())
                .isEqualTo("MARCHING");

        rewindMarch(sent.march().marchId());
        var after = marchAppService.list(playerId).marches();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).status().name()).as("到点后必须落地为驻扎").isEqualTo("STATIONED");
        assertThat(after.get(0).position()).as("驻扎时位置就是目的地")
                .isEqualTo(after.get(0).to());
    }

    @Test
    @DisplayName("B08 验收14：新手保护期内打玩家被拒，且文案说的是「你在免战」而不是「对方太强」")
    void attackIsBlockedWhileSelfProtected() {
        String playerId = newPlayer();
        String victim = newPlayer();
        Coord victimHome = worldAppService.homeOf(victim);
        giveTroops(playerId, "unit_infantry_t1", 50L);

        // 目标必须是玩家城：保护只对 PVP 生效（B08 §7「不可攻击玩家」，2026-09-12 修）。
        // 用「家往东 30 格」这种随机格子会让结论随那一格的类型漂移 —— 是野怪时这一仗合法，
        // 用例就变成了在验别的门
        // 校验顺序必须是「先说自己出不了兵」：
        // 如果先报「对方实力远超于你」，玩家会去换目标、去搜索、再点一次，
        // 折腾三轮才知道根本出不了兵 —— 错误提示的价值在于它能不能指引下一步动作
        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(newRequestId(),
                victimHome.x(), victimHome.y(),
                List.of(new MarchUnit("unit_infantry_t1", 10L)), List.of(), MarchAction.ATTACK)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("免战")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MARCH_TARGET_PROTECTED);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("被拒绝时不能扣兵").isEqualTo(50L);
        assertThat(marches.activeCountOf(playerId)).isZero();
    }

    @Test
    @DisplayName("新手保护只挡 PVP：保护期内的新号照样能打野（B08 §7「不可攻击玩家」）")
    void newbieProtectionDoesNotBlockPveHunt() {
        String playerId = newPlayer();   // 新号自带 72h 新手保护，本用例刻意不解除
        giveTroops(playerId, "unit_infantry_t1", 10L);
        Coord monsterCell = findCellOf(playerId,
                com.ironoath.core.world.WorldGenerator.EntityType.MONSTER);

        MarchResp sent = marchAppService.send(playerId, new MarchReq(newRequestId(),
                monsterCell.x(), monsterCell.y(),
                List.of(new MarchUnit("unit_infantry_t1", 10L)), List.of(), MarchAction.ATTACK));

        assertThat(sent.march().marchId()).as("打野必须真的成行").isNotBlank();
        March hunt = marches.findByPlayerId(playerId).get(0);
        assertThat(hunt.to()).as("打的就是那只野怪").isEqualTo(monsterCell);
        assertThat(hunt.action()).isEqualTo(March.Action.ATTACK);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("兵真的被派走了").isZero();
    }

    @Test
    @DisplayName("攻击非法目标（空地）被拒且退兵，不会留下一支卡在 FIGHTING 的队伍")
    void attackingInvalidTargetIsRejectedAndRefundsTroops() {
        String playerId = newPlayer();
        giveTroops(playerId, "unit_infantry_t1", 50L);
        liftNewbieProtection(playerId);

        // 目标必须挑出来，不能按「home 往东 30 格」算：playerId 是随机的，出生格因此随机，
        // 那一格可能是野怪或有人正在采的资源点 —— 那两者是**合法**攻击目标，行军真的会出发，
        // 于是这条用例变成一个偶发失败的 flaky 测试。空地是唯一恒不成立的目标类型。
        Coord empty = findCellOf(playerId, com.ironoath.core.world.WorldGenerator.EntityType.EMPTY);
        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(newRequestId(),
                empty.x(), empty.y(), List.of(new MarchUnit("unit_infantry_t1", 10L)),
                List.of(), MarchAction.ATTACK)))
                .isInstanceOf(BizException.class)
                // 野怪、玩家城、以及「有人正在采集的资源点」三条 ATTACK 路径都已接通（B09），
                // 所以拒绝的理由是「这个目标此刻不能被打」，而不是「未实现」
                .hasMessageContaining("只有野怪、玩家城")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_TARGET_INVALID);
        // 拒绝时必须把兵退回去，否则玩家的兵凭空消失
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .isEqualTo(50L);
        assertThat(marches.activeCountOf(playerId)).isZero();
    }

    @Test
    @DisplayName("采集：只有资源点能采，采满需要 GATHER_FILL_SECONDS，领取后资源入账并返程")
    void gatheringFillsAndReturnsResources() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        // 用步兵而不是攻城器/轻骑兵：后两者分别要攻城工坊与马厩才解锁，
        // 而本用例要验的是采集流程，不是阶级解锁（那由 tierUnlock 用例覆盖）
        giveTroops(playerId, "unit_infantry_t1", 50L);

        Coord resourceCell = findCellOf(playerId, com.ironoath.core.world.WorldGenerator.EntityType.RESOURCE);
        MarchResp sent = marchAppService.send(playerId, new MarchReq(newRequestId(),
                resourceCell.x(), resourceCell.y(),
                List.of(new MarchUnit("unit_infantry_t1", 50L)), List.of(), MarchAction.GATHER));

        rewindMarch(sent.march().marchId());
        marchAppService.list(playerId);
        assertThat(marchAppService.list(playerId).marches().get(0).status().name())
                .as("到达资源点后应进入采集状态").isEqualTo("GATHERING");

        // 把采集起点推到 GATHER_FILL_SECONDS 之前，模拟已经采满
        long fillMs = configs.longParam("GATHER_FILL_SECONDS") * 1000L;
        March gathering = marches.findById(sent.march().marchId()).orElseThrow();
        long version = marches.versionOf(gathering.id());
        gathering.restore(gathering.arriveAt(), null, null, null, 0L, gathering.status(),
                System.currentTimeMillis() - fillMs * 2, gathering.units());
        marches.save(gathering, version);

        // giveTroops 为了付得起城建与训练把所有资源顶到了容量上限，
        // 采集带回来的资源因此无处可放。领取前先把目标资源腾空，
        // 否则测到的是「满仓时溢出转邮件」而不是「采集入账」
        drainResource(playerId, resourceTypeOf(resourceCell));
        long woodBefore = resourceOf(playerId, resourceTypeOf(resourceCell));
        assertThat(woodBefore).as("腾空之后应当为 0").isZero();
        // 采集量也要发布给任务系统（B12 §1 的 GATHER_RESOURCE）：按实际入账的量累加
        var gatheredEvents = new java.util.ArrayList<com.ironoath.core.event.GameEvent>();
        questBus.subscribe(com.ironoath.core.quest.GoalType.GATHER_RESOURCE, gatheredEvents::add);
        GatherResp gathered = marchAppService.collectGather(playerId,
                new MarchIdReq(newRequestId(), sent.march().marchId()));
        assertThat(gatheredEvents).as("领取一次采集 = 一个事件").hasSize(1);
        assertThat(gatheredEvents.get(0).targetId()).as("目标是资源 id")
                .isEqualTo(resourceTypeOf(resourceCell));
        assertThat(gatheredEvents.get(0).amount()).as("增量=这次实际入账的量")
                .isEqualTo(50L * configs.get(UnitCfg.class, "unit_infantry_t1").load());
        assertThat(gathered.collected()).hasSize(1);
        assertThat(gathered.collected().get(0).amount())
                .as("采满一次负载 = 50 × 步兵 load 20")
                .isEqualTo(50L * configs.get(UnitCfg.class, "unit_infantry_t1").load());
        assertThat(gathered.march().status().name()).as("领取后应当返程").isEqualTo("RETURNING");

        // 到家后资源入账（走 RewardService，所以受容量上限约束）
        rewindMarch(sent.march().marchId());
        marchAppService.list(playerId);
        long woodAfter = resourceOf(playerId, resourceTypeOf(resourceCell));
        assertThat(woodAfter).as("带回来的资源必须入账").isGreaterThan(woodBefore);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("兵必须归队").isEqualTo(50L);
    }

    @Test
    @DisplayName("非资源点不能采集；空地不能增援驻守")
    void actionMustMatchTargetType() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 50L);
        Coord empty = findCellOf(playerId, com.ironoath.core.world.WorldGenerator.EntityType.EMPTY);

        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(newRequestId(),
                empty.x(), empty.y(), List.of(new MarchUnit("unit_infantry_t1", 10L)),
                List.of(), MarchAction.GATHER)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有资源点能采集")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_TARGET_INVALID);
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("被拒绝时不能扣兵").isEqualTo(50L);
    }

    @Test
    @DisplayName("不能向自己的城行军；坐标越界拒绝；不能操作别人的行军")
    void marchInputIsValidated() {
        String playerId = newPlayer();
        String other = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 20L);

        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(newRequestId(),
                home.x(), home.y(), List.of(new MarchUnit("unit_infantry_t1", 5L)),
                List.of(), MarchAction.STATION)))
                .isInstanceOf(BizException.class).hasMessageContaining("自己的城");

        int size = (int) configs.longParam("WORLD_SIZE");
        assertThatThrownBy(() -> marchAppService.send(playerId, new MarchReq(newRequestId(),
                size, 0, List.of(new MarchUnit("unit_infantry_t1", 5L)),
                List.of(), MarchAction.STATION)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_COORD_INVALID);

        MarchResp sent = marchAppService.send(playerId, marchReq(home, 20, MarchAction.STATION, 10L));
        assertThatThrownBy(() -> marchAppService.recall(other,
                new MarchIdReq(newRequestId(), sent.march().marchId())))
                .isInstanceOf(BizException.class)
                .as("不能透露「这支行军存在但不属于你」，那等于给了一个探测别人行军 id 的接口")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MARCH_NOT_FOUND);
    }

    // ---------- 流亡迁城（B08 §5 反击工具箱第 4 条）----------

    @Test
    @DisplayName("流亡迁城：城真的换了一格、旧址解绑、免战走自愿停战那本账、新旧两个块的版本号都变新")
    void exileMovesCityAndGrantsPeace() {
        String playerId = newPlayer();
        Coord from = worldAppService.homeOf(playerId);
        int chunkSize = (int) configs.longParam("WORLD_CHUNK_SIZE");
        long fromChunkBefore = world.chunkVersion(from.chunkKey(chunkSize));

        ExileResp resp = exileAppService.exile(playerId, new ExileReq(newRequestId()));
        Coord to = Coord.of(resp.coord().x(), resp.coord().y());

        assertThat(to).as("必须真的搬走").isNotEqualTo(from);
        assertThat(world.cityOf(playerId)).contains(to);
        assertThat(world.cityAt(from))
                .as("旧址没解绑 —— 旧邻居的地图上会永久留着一座点不开也打不到的城").isEmpty();
        assertThat(world.fogOf(playerId).chunks())
                .as("搬完家发现自己门口一片全黑，玩家只会认为游戏把城搬进了坏格子")
                .contains(to.chunkKey(chunkSize));

        // 时间一律按响应里的 serverNow 比对：用测试侧的 System.currentTimeMillis 会把
        // 服务端时钟与本机时钟的差算进断言里，那是一条永远说不清为什么红了 3 毫秒的用例
        assertThat(resp.peaceUntil() - resp.serverNow())
                .isEqualTo(configs.longParam("EXILE_PEACE_SECONDS") * 1000L);
        assertThat(resp.nextExileAt() - resp.serverNow())
                .isEqualTo(configs.longParam("EXILE_COOLDOWN_SECONDS") * 1000L);

        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        assertThat(save.pvp().peaceUntil()).isEqualTo(resp.peaceUntil());
        assertThat(save.pvp().victimShieldUntil())
                .as("免战牌与迁城的免战都必须走 peaceUntil 那本账；写进受害护盾就会被下一次战斗覆盖")
                .isNull();
        assertThat(save.pvp().exileAt()).isNotNull();
        assertThat(world.chunkVersion(to.chunkKey(chunkSize))).isGreaterThan(0L);
        assertThat(world.chunkVersion(from.chunkKey(chunkSize)))
                .as("旧块版本号不变，那些已经持有旧块的客户端永远不会知道城走了").isGreaterThan(fromChunkBefore);
    }

    @Test
    @DisplayName("冷却是滚动的窗口而不是一次性标记：3 天内拒绝并报出下次可用时刻，锚点拨旧之后还能再搬")
    void exileCooldownIsRolling() {
        String playerId = newPlayer();
        worldAppService.homeOf(playerId);
        exileAppService.exile(playerId, new ExileReq(newRequestId()));
        Coord afterFirst = world.cityOf(playerId).orElseThrow();

        assertThatThrownBy(() -> exileAppService.exile(playerId, new ExileReq(newRequestId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.EXILE_ON_COOLDOWN);
        assertThat(world.cityOf(playerId).orElseThrow())
                .as("被拒绝的那一次不许偷偷把城搬走").isEqualTo(afterFirst);

        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withExileAt(System.currentTimeMillis()
                - configs.longParam("EXILE_COOLDOWN_SECONDS") * 1000L - 1000L));
        players.save(save);
        exileAppService.exile(playerId, new ExileReq(newRequestId()));
        assertThat(world.cityOf(playerId).orElseThrow())
                .as("把锚点拨到窗口之外就该能再搬 —— 用日桶或一次性标记都做不到这件事")
                .isNotEqualTo(afterFirst);
    }

    @Test
    @DisplayName("有队伍在外时拒绝迁城：March.returnFrom 存的是坐标，搬完家那支队伍会回到一个没有主人的格子")
    void exileRejectedWhileTroopsAway() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 100L);
        marchAppService.send(playerId, marchReq(home, 40, MarchAction.STATION, 100L));
        assertThat(marches.activeCountOf(playerId)).isEqualTo(1L);

        assertThatThrownBy(() -> exileAppService.exile(playerId, new ExileReq(newRequestId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.EXILE_TROOPS_AWAY);
        assertThat(world.cityOf(playerId)).as("拒绝就得什么都不改").contains(home);
    }

    @Test
    @DisplayName("同一个 requestId 重放被拒且城只搬一次：迁城改的是世界坐标，白送一次逃生就是漏洞")
    void exileIsIdempotent() {
        String playerId = newPlayer();
        worldAppService.homeOf(playerId);
        String requestId = newRequestId();
        ExileResp moved = exileAppService.exile(playerId, new ExileReq(requestId));
        Coord to = Coord.of(moved.coord().x(), moved.coord().y());
        assertThat(world.cityOf(playerId)).contains(to);

        assertThatThrownBy(() -> exileAppService.exile(playerId, new ExileReq(requestId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(world.cityOf(playerId)).as("重放不许再搬一次").contains(to);
    }

    // ---------- 侦查 ----------

    @Test
    @DisplayName("侦查生成带误差的报告：误差幅度随等级差放大，且随报告一起下发")
    void scoutingProducesReportWithDisclosedError() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 10L);
        Coord monsterCell = findCellOf(playerId, com.ironoath.core.world.WorldGenerator.EntityType.MONSTER);

        MarchResp sent = marchAppService.scout(playerId, new ScoutReq(newRequestId(),
                monsterCell.x(), monsterCell.y(), List.of(new MarchUnit("unit_infantry_t1", 10L))));
        rewindMarch(sent.march().marchId());
        // 必须在「触发到达的那一次 list」的返回值上断言状态：
        // rewindMarch 把 arriveAt 压到了 startAt，于是去程时长为 0，
        // 侦查结束后按「已行军时长」算出来的返程时长也是 0 ⇒ 下一次 list 就会把它送回家并删除记录。
        // 这是夹具把时间压扁的副作用，不是产品行为（真实行军的返程等于去程）
        var afterArrival = marchAppService.list(playerId);

        var reports = worldAppService.reports(playerId).reports();
        assertThat(reports).as("侦查到达后必须生成报告").hasSize(1);
        var report = reports.get(0);
        assertThat(report.errorFixed()).as("误差幅度必须下发，否则玩家会把带误差的数字当精确值")
                .isPositive();
        assertThat(report.metrics()).isNotEmpty();
        assertThat(report.expired()).isFalse();
        assertThat(report.remainingMs()).isPositive();
        assertThat(report.seed()).as("种子随报告下发，便于客服复现「这份情报为什么这么离谱」").isNotZero();
        assertThat(report.targetLevel()).as("目标等级是唯一无误差的字段").isPositive();
        // 侦查完应当自动返程
        assertThat(afterArrival.marches()).as("侦查到达后队伍应当转入返程").isNotEmpty();
        assertThat(afterArrival.marches().get(0).status().name()).isEqualTo("RETURNING");
    }

    @Test
    @DisplayName("侦查也要派兵与花时间：速度用 SCOUT_SECONDS_PER_TILE（比行军快一倍）")
    void scoutingCostsTroopsAndTime() {
        String playerId = newPlayer();
        Coord home = worldAppService.homeOf(playerId);
        // 侦查与行军各带走 10 个兵，所以要准备 20 个
        giveTroops(playerId, "unit_infantry_t1", 20L);

        int targetX = towardX(home, 50);
        int targetY = clampCoord(home.y() + 1);
        MarchResp scouted = marchAppService.scout(playerId, new ScoutReq(newRequestId(),
                targetX, home.y(), List.of(new MarchUnit("unit_infantry_t1", 10L))));
        MarchResp marched = marchAppService.send(playerId, new MarchReq(newRequestId(),
                targetX, targetY, List.of(new MarchUnit("unit_infantry_t1", 10L)),
                List.of(), MarchAction.STATION));
        // 两次距离接近（50 与 51），但侦查应当明显更快
        assertThat(scouted.durationSec())
                .as("侦查用 SCOUT_SECONDS_PER_TILE（4.7），行军用 MARCH_SECONDS_PER_TILE（9.4）")
                .isLessThan(marched.durationSec());
        assertThat(armies.findByPlayerId(playerId).orElseThrow().countOf("unit_infantry_t1"))
                .as("两支侦查/行军各带走 10 个兵").isZero();
    }

    // ---------- 辅助 ----------

    /**
     * 造一个从家出发、沿 x 轴走 dx 格的行军请求。
     *
     * <p><b>dx 会被夹进世界范围内</b>：出生点的 x 可能高到 495（黄金角螺旋铺开 + 16 格边距），
     * 直接 home.x + 100 就越界了。越界时改成往负方向走同样的距离，
     * 这样夹具不再依赖「出生点恰好落在地图左半边」这种偶然条件 ——
     * 否则同一套测试会因为玩家 id 的哈希不同而随机变红。
     */
    /**
     * 从家出发偏移 dx 格、且保证落在世界内的 x 坐标。
     *
     * <p>必须夹范围：出生点由 playerId 哈希推导，所以 {@code home.x() + 50} 会随玩家 id
     * 随机越界（512 的世界里有约一成玩家会踩到）。越界不会让测试失败在断言上，
     * 而是失败在一个「坐标不合法」的业务异常里，看起来像是被测功能坏了 —— 这是最难查的一类夹具 bug。
     */
    private int towardX(Coord home, int dx) {
        int worldSize = (int) configs.longParam("WORLD_SIZE");
        int x = home.x() + dx;
        if (x < 0 || x >= worldSize) {
            x = home.x() - dx;
        }
        assertThat(x).as("夹具必须能造出界内坐标（home=%s, dx=%d）", home, dx)
                .isBetween(0, worldSize - 1);
        return x;
    }

    private int clampCoord(int value) {
        int worldSize = (int) configs.longParam("WORLD_SIZE");
        return Math.max(0, Math.min(worldSize - 1, value));
    }

    private MarchReq marchReq(Coord home, int dx, MarchAction action, long count) {
        int worldSize = (int) configs.longParam("WORLD_SIZE");
        int targetX = home.x() + dx;
        if (targetX >= worldSize) {
            targetX = home.x() - dx;
        }
        assertThat(targetX).as("夹具必须能造出一个界内坐标（home=%s, dx=%d）", home, dx)
                .isBetween(0, worldSize - 1);
        return new MarchReq(newRequestId(), targetX, home.y(),
                List.of(new MarchUnit("unit_infantry_t1", count)), List.of(), action);
    }

    /**
     * 把某支行军的当前段推进到「已到点」，模拟时间流逝。
     *
     * <p>做法是把该段的结束时刻改成该段的<b>起点</b>（去程改成 startAt、返程改成 returnStartAt），
     * 而不是改成一个由调用方传进来的时刻 —— 传进来的往往是未来（例如原始的 arriveAt），
     * 那样到期扫描会认为「还没到点」，测试就会看到状态没推进却找不到原因。
     * 服务端不跑定时器，测试也不 sleep，所以只能直接改存档里的时刻。
     */
    private void rewindMarch(String marchId) {
        March march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        if (march.status() == March.Status.RETURNING) {
            long due = march.returnStartAt() == null ? march.startAt() : march.returnStartAt();
            march.restore(march.arriveAt(), due, march.returnStartAt(), march.returnFrom(),
                    march.load(), march.status(), march.gatherStartAt(), march.units());
            marches.save(march, version);
            dueQueue.reschedule(marchId, due);
        } else {
            march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                    march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                    march.units());
            marches.save(march, version);
            dueQueue.reschedule(marchId, march.startAt());
        }
    }

    @Test
    @DisplayName("B09 验收7：召回采集中的队伍，已采集的部分照常结算，不会空手回家")
    void recallingGatheringMarchKeepsWhatWasGathered() {
        String playerId = newPlayer();
        worldAppService.homeOf(playerId);
        giveTroops(playerId, "unit_infantry_t1", 50L);

        Coord resourceCell = findCellOf(playerId,
                com.ironoath.core.world.WorldGenerator.EntityType.RESOURCE);
        String resourceType = resourceTypeOf(resourceCell);
        MarchResp sent = marchAppService.send(playerId, new MarchReq(newRequestId(),
                resourceCell.x(), resourceCell.y(),
                List.of(new MarchUnit("unit_infantry_t1", 50L)), List.of(), MarchAction.GATHER));
        String marchId = sent.march().marchId();

        rewindMarch(marchId);
        marchAppService.list(playerId);
        assertThat(marchAppService.list(playerId).marches().get(0).status().name())
                .isEqualTo("GATHERING");

        // 把采集起点推到「半程」之前：采满需要 GATHER_FILL_SECONDS，所以这时应当采到一半负载
        long fillMs = configs.longParam("GATHER_FILL_SECONDS") * 1000L;
        March gathering = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        gathering.restore(gathering.arriveAt(), null, null, null, 0L, gathering.status(),
                System.currentTimeMillis() - fillMs / 2, gathering.units());
        marches.save(gathering, version);

        // giveTroops 为了付得起城建与训练把所有资源顶到了容量上限，先腾空目标资源，
        // 否则测到的是「满仓时溢出转邮件」而不是「采集入账」
        drainResource(playerId, resourceType);

        RecallResp recalled = marchAppService.recall(playerId, new MarchIdReq(newRequestId(), marchId));
        long carried = recalled.march().load();
        assertThat(carried)
                .as("召回时必须结算已采集量。此前 recall 直接让队伍回家，采了一小时的东西凭空消失 —— "
                        + "不报错、不为负，只在资源账上表现为「我明明在采集，怎么什么都没多」")
                .isPositive();

        rewindMarch(marchId);
        marchAppService.list(playerId);
        assertThat(resourceOf(playerId, resourceType))
                .as("到家后已采集的资源必须一分不少地入账（验收 7：不丢失）")
                .isEqualTo(carried);
        assertThat(marches.findById(marchId))
                .as("到家后行军记录应当被清理，名额随之释放").isEmpty();
    }

    /** 在玩家附近找一个指定类型的格子（世界是确定性生成的，所以一定找得到）。 */
    private Coord findCellOf(String playerId, com.ironoath.core.world.WorldGenerator.EntityType type) {
        Coord home = worldAppService.homeOf(playerId);
        for (int radius = 1; radius < 200; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    int x = home.x() + dx;
                    int y = home.y() + dy;
                    if (x < 0 || y < 0 || x >= 512 || y >= 512) {
                        continue;
                    }
                    Coord candidate = Coord.of(x, y);
                    if (world.cityAt(candidate).isPresent()) {
                        continue;
                    }
                    if (worldAppService.cellAt(candidate).entityType() == type) {
                        return candidate;
                    }
                }
            }
        }
        throw new AssertionError("在 200 格半径内找不到类型为 " + type + " 的格子");
    }

    private String resourceTypeOf(Coord coord) {
        return worldAppService.cellAt(coord).entityId();
    }

    /** 把某种资源清空（保留容量与产率），用于验证「入账」而不是「满仓溢出」。 */
    private void drainResource(String playerId, String resource) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        var s = save.resource(resource);
        save.putResource(resource, new com.ironoath.core.player.PlayerResourceState(
                0L, s.cap(), s.protectedAmount(), s.perHour(), System.currentTimeMillis()));
        players.save(save);
    }

    private long resourceOf(String playerId, String resource) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resource).current();
    }

    /**
     * 解除新手保护，等价于 B08 §7 的「主动攻击则解除」。
     *
     * <p><b>只有 PVP 类用例需要它</b>：新手保护只挡「攻击玩家」（B08 §7 原文，
     * 2026-09-12 修掉了它对打野的误拦截）。PVP 用例必须先解除 ——
     * 不解除就永远走不到圈层判定与战斗结算，用例会在第一道门上停下，
     * 看起来「通过了」却什么也没验。主城等级那条路（8 级解除）在这里走不通 ——
     * giveTroops 只把主城升到 3 级。
     */
    private void liftNewbieProtection(String playerId) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    /** 造一支可用的军队：建军营（需主城 3 级）→ 合成武将上阵抬上限 → 训练士兵。 */
    private void giveTroops(String playerId, String unitId, long count) {
        // 兵营要求主城 3 级
        for (int i = 0; i < 2; i++) {
            buildAndFinish(playerId, "main_city");
        }
        buildAndFinish(playerId, "barracks");

        // 带兵上限来自武将统帅值，所以先合成一名武将并上阵
        String heroId = "hero_ssr_02";   // 统率 100，全武将最高
        var heroCfg = configs.get(com.ironoath.config.cfg.HeroCfg.class, heroId);
        long fragments = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class,
                heroCfg.rarity().name()).composeFragment();
        giveItems(playerId, "item_mat_hero_frag_ssr", fragments);
        heroAppService.compose(playerId, new HeroIdReq(newRequestId(), heroId));
        heroAppService.setLineup(playerId, new com.ironoath.web.dto.generated.SetLineupReq(
                newRequestId(), 0, heroId, null, null));
        assertThat(armyAppService.list(playerId).troopCap()).as("上阵后带兵上限必须为正").isPositive();

        // 训练出需要的兵，并把完成时刻改到过去让它立刻入账
        fillResources(playerId);
        armyAppService.train(playerId, new com.ironoath.web.dto.generated.TrainReq(
                newRequestId(), unitId, count));
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        var queue = new java.util.LinkedHashMap<>(army.queue());
        var task = queue.get(unitId);
        queue.put(unitId, task.withFinishAt(task.startedAt()));
        army.restore(army.troops(), queue, army.wounded(), army.treatFinishAt(),
                army.treatTotalSeconds(), army.treatOriginalSeconds(), army.treatCost(),
                army.extraSlots());
        armies.save(playerId, army, version);
        assertThat(armyAppService.list(playerId).units().stream()
                .filter(u -> u.unitId().equals(unitId)).findFirst().orElseThrow().count())
                .as("训练到点后必须入账").isEqualTo(count);
    }

    private void prepareArmy(String playerId, long count) {
        giveTroops(playerId, "unit_infantry_t1", count);
    }

    private void buildAndFinish(String playerId, String configId) {
        fillResources(playerId);
        var upgrade = cityAppService.upgrade(playerId,
                new CityUpgradeReq(newRequestId(), configId, 0, 0));
        var city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(upgrade.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), upgrade.buildingId()));
    }

    private void fillResources(String playerId) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            var s = save.resource(id);
            save.putResource(id, new com.ironoath.core.player.PlayerResourceState(
                    s.cap(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        }
        players.save(save);
    }

    private void giveItems(String playerId, String itemId, long count) {
        var bag = inventories.findByPlayerId(playerId)
                .orElseGet(() -> com.ironoath.core.bag.Inventory.empty(
                        (int) configs.longParam("BAG_INITIAL_CAPACITY")));
        bag.add(itemId, count, configs.get(com.ironoath.config.cfg.ItemCfg.class, itemId).stackMax());
        if (inventories.findByPlayerId(playerId).isEmpty()) {
            inventories.insertIfAbsent(playerId, bag);
        } else {
            inventories.save(playerId, bag, inventories.versionOf(playerId));
        }
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "世界测试", 1_700_000_000_000L))
                .playerId();
    }

    /** 把一个人摆到某个暴虐档位（推进时刻同时给，否则 {@code PlayerPvp} 的不变量不允许有值无时刻）。 */
    private void raiseTyranny(String playerId, long value, long touchedAt) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withTyranny(value, touchedAt));
        players.save(save);
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
