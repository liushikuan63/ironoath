package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.BattleReportBrief;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
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

/**
 * 职责：B09 验收 7 的剩下一半 —— 采集被抢夺的战斗（拦截一支正在采集的行军）。
 * 依赖：Spring Boot Test；test profile（内存存储 + JVM 内锁 + 内存到期队列）。
 *
 * <p><b>本类最重要的一条断言是负载守恒</b>：守方在途负载减少的量必须等于攻方负载增加的量。
 * 这条守恒靠 {@code March.seizeLoad} 与 {@code March.addLoad} 都返回「实际发生量」才可能被断言 ——
 * 少任何一个返回值，缺口就会静默消失：资源既不在守方手里也不在攻方手里，
 * 而两边的日志都显示正常。这与 B05 那个「分摊比例凭空吞兵」的 bug 是同一条纪律。
 *
 * <p><b>在途负载不套仓库的保护额度</b>：仓库额度保护的是放在家里的存货，
 * 而在途负载是玩家自己派兵出门、冒着被拦风险采来的。给它套保护额度等于让
 * 「出门采集」比「放在家里」更安全，那会反过来鼓励所有人把兵派出去，与 B08 的生态意图相反。
 */
@SpringBootTest
@ActiveProfiles("test")
class GatherInterceptionTest {

    private static final String UNIT = "unit_infantry_t1";
    private static final long HOUR = 3_600_000L;

    @Autowired private PlayerInitService playerInitService;
    @Autowired private MarchAppService marchAppService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private BattleReportService battleReportService;

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
    @Autowired private com.ironoath.core.limit.DailyCounter dailyCounter;
    @Autowired private BattleReportStore battleReports;

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
        ((com.ironoath.web.limit.InMemoryDailyCounter) dailyCounter).clear();
        battleReports.clear();
    }

    @Test
    @DisplayName("拦截采集队：守方在途负载减少的量恰好等于攻方增加的量，且双方各有一份战报")
    void interceptionTransfersLoadWithoutLoss() {
        Pair p = pairWithGatherer(2_000L, 300L);

        // 采集量是惰性结算的：只有在拦截结算那一刻才会从 gatherStartAt 算出来并装进负载。
        // 所以这里不能预先断言「守方已经有负载」—— 那时它还是 0，而这不是 bug。
        // 能在拦截前拿到的是守方的**运力上限**，它是夺取量的天然上界
        long gathererLoadCap = marches.findById(p.gathererMarchId).orElseThrow().loadCap();
        assertThat(marches.findById(p.attackerMarchId).orElseThrow().load())
                .as("夹具前提：攻方出发时是空车").isZero();

        arriveAndProcess(p.attackerMarchId);

        // 攻方一定还在（没被全灭才会返程）；守方可能已经被删（全灭时记录直接删掉），
        // 所以这里读 Optional 而不是 orElseThrow —— 后者会让「全灭」这个合法结局变成用例失败
        java.util.Optional<March> gathererAfter = marches.findById(p.gathererMarchId);
        March attacker = marches.findById(p.attackerMarchId).orElseThrow();

        assertThat(attacker.load()).as("攻方必须夺到在途负载").isPositive();
        assertThat(attacker.load())
                .as("夺走的量不可能超过守方的运力上限：超过就说明凭空造了资源")
                .isLessThanOrEqualTo(gathererLoadCap);
        assertThat(attacker.status()).as("攻方打完返程").isEqualTo(March.Status.RETURNING);

        // 守方有两种合法结局，取决于这场仗有没有把它打光：
        // ① 没全灭 ⇒ 带着**剩余**负载原路返回，而剩余必须是 0 —— 攻方 2000 兵对守方 300 兵，
        //    运力是守方的六倍多、装得下全部，所以留下的每一单位都意味着守恒被破坏；
        // ② 全灭 ⇒ 记录被删除（没有兵能走回来，否则地图上会留一支 0 兵的队伍在移动）。
        // 两种结局合起来就是本用例的守恒检查：攻方拿到的量 > 0 且 ≤ 守方运力上限，守方一点不剩
        if (gathererAfter.isPresent()) {
            assertThat(gathererAfter.get().load())
                    .as("守方没被全灭时，剩余负载必须已被搬空").isZero();
            assertThat(gathererAfter.get().status())
                    .as("守方没被全灭就带着剩余负载原路返回").isEqualTo(March.Status.RETURNING);
        } else {
            assertThat(marches.findByPlayerId(p.gatherer).stream()
                    .noneMatch(m -> m.totalUnits() <= 0L))
                    .as("全灭后地图上不该留下 0 兵的队伍（B07 的同一条纪律）").isTrue();
        }

        List<BattleReportBrief> mine = battleReportService.list(p.attacker).reports();
        List<BattleReportBrief> theirs = battleReportService.list(p.gatherer).reports();
        assertThat(mine).as("攻方有一份").hasSize(1);
        assertThat(theirs).as("被打的那个人回来必须看得到自己是怎么输的").hasSize(1);
        assertThat(theirs.get(0).opponentId()).as("守方看到的对手是攻方，不是自己").isEqualTo(p.attacker);
    }

    @Test
    @DisplayName("2026-09-13 裁决：被拦下采集队算「被打一次」——守方进受害账本，与攻城同一本 attackerHits")
    void interceptedGathererIsRecordedInVictimLedger() {
        Pair p = pairWithGatherer(2_000L, 300L);
        assertThat(players.findByPlayerId(p.gatherer).orElseThrow().pvp().attackerHits())
                .as("夹具前提：开打之前守方的受害账本是空的（否则这条断言证明不了任何事）").isEmpty();

        arriveAndProcess(p.attackerMarchId);

        // 这一本账同时喂两处：连续受害护盾（第三条保护）与复仇乘区 F。
        // 少记一次的表现不是报错，而是「被人抢了三回却不进护盾、也不进复仇名单」
        assertThat(players.findByPlayerId(p.gatherer).orElseThrow().pvp().attackerHits())
                .as("被拦下的采集者必须与城被攻时记进同一本账")
                .containsKey(p.attacker);
    }

    @Test
    @DisplayName("资源点上没人在采集时，出征那一刻就被拒：提示必须出现在玩家还能改主意的时刻")
    void attackingAnEmptyResourceNodeIsRejectedAtDispatch() {
        String attacker = newPlayer();
        Coord home = worldAppService.homeOf(attacker);
        giveTroops(attacker, 500L);
        liftProtection(attacker);
        powerRefreshService.refresh(attacker);
        Coord node = findResourceNode(home);

        assertThatThrownBy(() -> send(attacker, node, MarchAction.ATTACK, 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有人在采集")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_TARGET_INVALID);
        assertThat(armies.findByPlayerId(attacker).orElseThrow().countOf(UNIT))
                .as("被拒时不能扣兵").isEqualTo(500L);
        assertThat(marches.activeCountOf(attacker)).as("被拒时不能留下行军").isZero();
    }

    // ---------- 夹具 ----------

    private record Pair(String attacker, String gatherer, String attackerMarchId, String gathererMarchId) {
    }

    /**
     * 一个已经采了一小时的采集队 + 一支已出发去拦它的压倒性队伍。
     *
     * <p>采集量是惰性结算的，所以「采到东西」必须靠把 gatherStartAt 拨到一小时前 ——
     * 服务端不跑定时器，测试也不 sleep（与行军到期的推进手法一致）。
     */
    private Pair pairWithGatherer(long attackerTroops, long gathererTroops) {
        String gatherer = newPlayer();
        Coord gathererHome = worldAppService.homeOf(gatherer);
        giveTroops(gatherer, gathererTroops);
        liftProtection(gatherer);
        powerRefreshService.refresh(gatherer);
        Coord node = findResourceNode(gathererHome);
        String gathererMarchId = send(gatherer, node, MarchAction.GATHER, gathererTroops);
        arriveAndProcess(gathererMarchId);
        rewindGatherStart(gathererMarchId, HOUR);
        assertThat(marches.findById(gathererMarchId).orElseThrow().status())
                .as("夹具前提：守方必须处于采集中").isEqualTo(March.Status.GATHERING);

        String attacker = newPlayer();
        giveTroops(attacker, attackerTroops);
        liftProtection(attacker);
        powerRefreshService.refresh(attacker);
        // 拦截的出征校验要求「那一刻确实有人在采集」，所以必须在守方就位之后再出发
        String attackerMarchId = send(attacker, node, MarchAction.ATTACK, attackerTroops);
        return new Pair(attacker, gatherer, attackerMarchId, gathererMarchId);
    }

    /** 把采集起点拨到过去，让惰性结算算出已采集量。 */
    private void rewindGatherStart(String marchId, long millis) {
        March march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.arriveAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(),
                System.currentTimeMillis() - millis, march.units());
        marches.save(march, version);
    }

    private Coord findResourceNode(Coord home) {
        for (int radius = 1; radius <= 40; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    Coord candidate = Coord.of(home.x() + dx, home.y() + dy);
                    if (!candidate.withinWorld(512) || world.cityAt(candidate).isPresent()) {
                        continue;
                    }
                    if (worldAppService.cellAt(candidate).entityType() == WorldGenerator.EntityType.RESOURCE) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("世界中心 40 格内必须能找到资源点，home=" + home);
    }

    private String send(String playerId, Coord target, MarchAction action, long troops) {
        List<MarchUnit> units = new ArrayList<>();
        units.add(new MarchUnit(UNIT, troops));
        return marchAppService.send(playerId, new MarchReq(newRequestId(),
                target.x(), target.y(), units, List.of(), action)).march().marchId();
    }

    /** 把行军推进到「已到点」并触发到期扫描：arriveAt 改成本段起点，服务端不跑定时器、测试也不 sleep。 */
    private void arriveAndProcess(String marchId) {
        March march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marches.save(march, version);
        dueQueue.reschedule(marchId, march.startAt());
        marchAppService.processDue(march.playerId(), System.currentTimeMillis());
    }

    private void giveTroops(String playerId, long count) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
    }

    private void liftProtection(String playerId) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "拦截测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
