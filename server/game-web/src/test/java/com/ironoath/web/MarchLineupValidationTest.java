package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
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

/**
 * 职责：随军武将编队在<b>出征那一刻</b>的校验（B06 的编队上限延伸到行军 + B07「提示要出现在
 * 玩家还能改主意的时刻」）。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>这条校验原本不存在，而两种后果都不报错</b>：
 * <ol>
 *   <li>不属于自己的 heroId 会一路活到战斗结算，{@code HeroRoster.hero} 在那里抛
 *       {@code IllegalStateException} —— 那是到期扫描的调用栈，于是这支行军被反复重试，
 *       玩家的兵永远卡在目标面前，而日志里一切正常；</li>
 *   <li>武将数量没有上限，而每个武将提供一整套攻防加成 —— 客户端想带几个带几个，
 *       {@code LINEUP_HERO_COUNT} 形同虚设。</li>
 * </ol>
 *
 * <p>用例都走 {@code STATION}（驻扎）：它不限制目标类型、也不会真打一场，
 * 这样断言里失败或成功的唯一原因就只剩编队校验本身。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarchLineupValidationTest {

    private static final String UNIT = "unit_infantry_t1";
    private static final String HERO = "hero_ssr_01";

    @Autowired private PlayerInitService playerInitService;
    @Autowired private MarchAppService marchAppService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private ConfigRegistry configs;

    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private com.ironoath.core.bag.InventoryRepository inventories;
    @Autowired private ArmyRepository armies;
    @Autowired private HeroRepository heroes;
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
    @DisplayName("带着并不拥有的武将出征：出征时就拒，一个兵都不扣、一行军都不留")
    void unownedHeroIsRejectedAtDispatch() {
        String playerId = player(300L);
        long troopsBefore = troopsOf(playerId);

        assertThatThrownBy(() -> marchAppService.send(playerId, req(playerId, List.of(HERO))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未拥有武将")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(troopsOf(playerId)).as("校验发生在扣兵之前").isEqualTo(troopsBefore);
        assertThat(marches.activeCountOf(playerId)).as("被拒时不该留下行军").isZero();
    }

    @Test
    @DisplayName("编队上限与去重都由配置说话：上限来自 LINEUP_HERO_COUNT 而不是代码里的数字")
    void lineupSizeAndDedupComeFromConfig() {
        String playerId = player(3_000L);
        int max = (int) configs.longParam("LINEUP_HERO_COUNT");
        List<String> owned = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            owned.add(HERO + "_" + i);
            grantHero(playerId, HERO + "_" + i);
        }

        // 先验边界本身：带满必须放行。否则下面「不得超过」那条断言可能只是把边界写错成偏小
        assertThat(marchAppService.send(playerId, req(playerId, owned)).march().heroes())
                .as("带满 %d 个武将应当合法", max).containsExactlyElementsOf(owned);

        assertThatThrownBy(() -> marchAppService.send(playerId,
                req(playerId, List.of(owned.get(0), owned.get(0)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同一武将不能占两个随军位");

        List<String> overSize = new ArrayList<>(owned);
        overSize.add(HERO + "_extra");
        assertThatThrownBy(() -> marchAppService.send(playerId, req(playerId, overSize)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("随军武将不得超过 " + max);
    }

    @Test
    @DisplayName("带自己拥有的武将正常放行，且名单如实落到行军上")
    void ownedHeroReachesTheMarch() {
        String playerId = player(300L);
        grantHero(playerId, HERO);

        String marchId = marchAppService.send(playerId, req(playerId, List.of(HERO)))
                .march().marchId();

        assertThat(marches.findById(marchId).orElseThrow().heroes())
                .as("行军存档必须带着这份名单，否则到点战斗时等于没带武将").containsExactly(HERO);
    }

    @Test
    @DisplayName("一个武将都不带是合法出征（B06 的编队允许空位）")
    void emptyLineupIsLegal() {
        String playerId = player(300L);

        marchAppService.send(playerId, req(playerId, List.of()));

        assertThat(marches.activeCountOf(playerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("闭城死守期间不可出兵采集；同一时刻侦查与驻扎照常放行（只挡字面上那一条）")
    void closedCityBansGatherOnly() {
        String playerId = player(300L);
        closeCity(playerId, 3_600_000L);

        assertThatThrownBy(() -> marchAppService.send(playerId, req(playerId, List.of(), MarchAction.GATHER)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("闭城死守期间不可出兵采集")
                .hasMessageContaining("分钟")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MARCH_STATE_INVALID);

        // 判别性：闭城不该顺手把侦查也禁掉。若哪天有人扩大限制范围，这条就会红
        marchAppService.send(playerId, req(playerId, List.of(), MarchAction.SCOUT));
        assertThat(marches.activeCountOf(playerId))
                .as("闭城只禁「出兵采集」这一条，侦查照做").isEqualTo(1L);
    }

    /**
     * 免战牌只挡「被打」，不挡自己出兵。
     *
     * <p><b>目标必须现找一格真资源点</b>：以前这条断的是「home 东三格不是资源点，
     * 所以被挡下的原因只能是别的」，而那一格是什么完全由世界生成决定 ——
     * 按资源点密度，它会偶尔真的是个资源点，于是采集成功、测试以
     * 「Expecting code to raise a throwable」失败。一个随机器生成翻脸的断言不是断言。
     * 改成铺满前置条件（真资源点 + 兵够 + 只多一张牌）后必须放行，
     * 唯一的变量就真的只剩那张牌。
     */
    @Test
    @DisplayName("免战牌不附带出兵限制：带着牌照样能出发采集（24h 的牌不能比 8h 的更亏）")
    void peaceCardDoesNotBanGathering() {
        String playerId = player(300L);
        Coord resource = cellOfType(playerId, com.ironoath.core.world.WorldGenerator.EntityType.RESOURCE);
        peace(playerId, 3_600_000L);

        marchAppService.send(playerId, req(playerId, List.of(), MarchAction.GATHER, resource));

        assertThat(marches.activeCountOf(playerId))
                .as("目标是真的资源点、兵也够，此时唯一能挡住出兵的就是那张免战牌").isEqualTo(1L);
    }

    // ---------- 夹具 ----------

    /** 直接铺闭城状态：本类测的是禁令本身，道具侧的接线由 BagEndpointTest 覆盖。 */
    private void closeCity(String playerId, long millis) {
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withClosedUntil(System.currentTimeMillis() + millis));
        players.save(save);
    }

    private void peace(String playerId, long millis) {
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withPeaceUntil(System.currentTimeMillis() + millis));
        players.save(save);
    }

    private String player(long troops) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "编队测试", 1_700_000_000_000L, ""))
                .playerId();
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, troops);
        armies.save(playerId, army, version);
        worldAppService.homeOf(playerId);
        return playerId;
    }

    /** 直接给玩家一张武将表（绕过抽卡：本类测的是归属校验，不是获得路径）。 */
    private void grantHero(String playerId, String heroId) {
        if (heroes.findByPlayerId(playerId).isEmpty()) {
            HeroRoster fresh = new HeroRoster();
            fresh.obtain(heroId);
            heroes.insertIfAbsent(playerId, fresh);
            return;
        }
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseThrow();
        long version = heroes.versionOf(playerId);
        roster.obtain(heroId);
        heroes.save(playerId, roster, version);
    }

    private MarchReq req(String playerId, List<String> heroIds) {
        return req(playerId, heroIds, MarchAction.STATION);
    }

    private MarchReq req(String playerId, List<String> heroIds, MarchAction action) {
        return req(playerId, heroIds, action, Coord.of(worldAppService.homeOf(playerId).x() + 3,
                worldAppService.homeOf(playerId).y()));
    }

    private MarchReq req(String playerId, List<String> heroIds, MarchAction action, Coord target) {
        return new MarchReq("req-" + UUID.randomUUID(), target.x(), target.y(),
                List.of(new MarchUnit(UNIT, 100L)), heroIds, action);
    }

    /**
     * 从出生点向外逐圈找一格指定类型的地。
     *
     * <p>世界是程序生成的，「home 东三格是什么」不是断言可以依赖的前提（它会让测试偶尔翻脸）。
     * 边长取 {@code rules().worldSize()} 而不是写死 512：那是表里的值，改它不该让测试找不到地。
     */
    private Coord cellOfType(String playerId, com.ironoath.core.world.WorldGenerator.EntityType type) {
        Coord home = worldAppService.homeOf(playerId);
        int size = worldAppService.rules().worldSize();
        for (int radius = 1; radius < size; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    int x = home.x() + dx;
                    int y = home.y() + dy;
                    if (x < 0 || y < 0 || !Coord.of(x, y).withinWorld(size)) {
                        continue;
                    }
                    if (worldAppService.cellAt(Coord.of(x, y)).entityType() == type) {
                        return Coord.of(x, y);
                    }
                }
            }
        }
        throw new AssertionError("整张地图里找不到类型为 " + type + " 的格子，"
                + "这条测试的前置条件已经不成立了（世界生成规则改过，断言要跟着改）");
    }

    private long troopsOf(String playerId) {
        return armies.findByPlayerId(playerId).map(ArmyState::totalTroops).orElse(0L);
    }
}
