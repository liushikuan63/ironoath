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

import com.ironoath.battle.BattleModifier;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.BattleReportBrief;
import com.ironoath.web.dto.generated.BattleType;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
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
 * 职责：玩家城 PVP 的端到端验证 —— 双方战损、掠夺、暴虐值、受害护盾、双方各一份战报。
 * 依赖：Spring Boot Test；test profile（内存存储 + JVM 内锁 + 内存到期队列）。
 *
 * <p><b>与 {@code MonsterHuntEndpointTest} 同样必须走完整行军链路</b>（出征 → 到期扫描 → 战斗 → 返程），
 * 而不是直接调 PlayerCityBattleService：链上有三处容易断的接缝（到期扫描的异常处理、
 * 行军状态机推进、战斗打不成时队伍怎么办），单独测结算会把它们全部跳过。
 *
 * <p><b>PVP 特有的、PVE 里根本不存在的两件事，本类专门盯着</b>：
 * <ol>
 *   <li><b>守方不是这次请求的发起人</b>。他的兵被扣了、资源被搬了、护盾被推了、战力变了，
 *       而 HTTP 边界的战力重算拦截器只覆盖请求发起人 —— 所以守方的战力必须被显式重算。
 *       漏掉的表现是「我被人打了，战力没变」，不会让任何测试变红</li>
 *   <li><b>战报要两份</b>。只记攻方的话，被打的那个人下线回来完全不知道自己是怎么输的，
 *       而那是他最想知道的时刻</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayerCityAttackEndpointTest {

    @Autowired private PlayerInitService playerInitService;
    @Autowired private MarchAppService marchAppService;
    @Autowired private com.ironoath.web.battle.PlayerCityBattleService playerCityBattleService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private BattleReportService battleReportService;
    @Autowired private com.ironoath.web.service.WorldAppService worldAppService;
    @Autowired private com.ironoath.config.ConfigRegistry configs;

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

    // ---------- 完整结算 ----------

    @Test
    @DisplayName("压倒性进攻：守方掉兵掉资源、攻方拿到掠夺、攻方带着残兵返程")
    void overwhelmingAttackSettlesBothSides() {
        Fixture f = readyPair(1_800L, 1_000L);
        long defenderTroopsBefore = totalTroops(f.defender);
        // 用金币断言掠夺：ResourceProtection 对货币类的保护额度恒为 0，所以金币一定可掠。
        // 木石粮有保护额度，填到容量一半时可能整份落在保护额度里 —— 那时「掠夺量为 0」
        // 既可能是口径错了也可能只是保护生效了，断言就失去了判别力
        long defenderGoldBefore = resourceOf(f.defender, "GOLD");
        long attackerGoldBefore = resourceOf(f.attacker, "GOLD");
        long defenderStaminaBefore = resourceOf(f.defender, "STAMINA");

        String marchId = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(marchId);

        assertThat(totalTroops(f.defender)).as("守方必须有战损").isLessThan(defenderTroopsBefore);
        assertThat(resourceOf(f.defender, "GOLD")).as("守方金币被掠走").isLessThan(defenderGoldBefore);
        assertThat(resourceOf(f.attacker, "GOLD")).as("攻方金币增加").isGreaterThan(attackerGoldBefore);
        assertThat(resourceOf(f.defender, "STAMINA"))
                .as("体力住在 resource 表里但不是仓库存货：掠走它等于替对方决定接下来几天能做什么。"
                        + "用「不少于开战前」而不是「等于」，因为体力会随时间惰性恢复")
                .isGreaterThanOrEqualTo(defenderStaminaBefore);

        March march = marches.findById(marchId).orElseThrow();
        assertThat(march.status()).as("打完就返程，占领属 B13").isEqualTo(March.Status.RETURNING);
        assertThat(march.totalUnits()).as("攻方也必须有战损，且不是全灭").isPositive();
        assertThat(totalTroops(f.attacker)).as("攻方在外的兵还没归队，所以城内兵力此时为 0")
                .isZero();
    }

    @Test
    @DisplayName("PVP 战报双方各一份：内容同一场，ownerId 不同 —— 被打的人回来必须看得到自己是怎么输的")
    void bothSidesGetTheirOwnReport() {
        Fixture f = readyPair(1_800L, 1_000L);
        String marchId = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(marchId);

        List<BattleReportBrief> attackerReports = battleReportService.list(f.attacker).reports();
        List<BattleReportBrief> defenderReports = battleReportService.list(f.defender).reports();
        assertThat(attackerReports).as("攻方有一份").hasSize(1);
        assertThat(defenderReports).as("守方也有一份").hasSize(1);

        BattleReportBrief mine = attackerReports.get(0);
        BattleReportBrief theirs = defenderReports.get(0);
        assertThat(mine.battleType()).isEqualTo(BattleType.PVP_SOLO);
        assertThat(theirs.battleType()).isEqualTo(BattleType.PVP_SOLO);
        assertThat(mine.opponentId()).as("攻方看到的对手是守方").isEqualTo(f.defender);
        assertThat(theirs.opponentId()).as("守方看到的对手是攻方").isEqualTo(f.attacker);
        assertThat(mine.won()).isTrue();
        assertThat(theirs.won()).as("同一场战斗，守方的视角必须是输").isFalse();
        assertThat(mine.totalRounds()).isEqualTo(theirs.totalRounds());
        assertThat(mine.attackerLoss()).isEqualTo(theirs.attackerLoss());
        assertThat(mine.defenderLoss()).isEqualTo(theirs.defenderLoss());
        assertThat(mine.reportId()).as("两份是两个 id，不是一条被两个人共享").isNotEqualTo(theirs.reportId());
    }

    // ---------- 暴虐值 ----------

    @Test
    @DisplayName("区间内的正常对抗不记暴虐值：R 未过阈值时一分不加（B08 头号禁止项是不得限制强者打弱者）")
    void evenFightDoesNotAccumulateTyranny() {
        Fixture f = readyPair(1_000L, 1_000L);
        String marchId = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_000L));
        arriveAndProcess(marchId);

        assertThat(tyrannyOf(f.attacker))
                .as("势均力敌的一场仗不该把人推上公敌档").isZero();
    }

    @Test
    @DisplayName("碾压才记暴虐值：R 高于阈值时累积为正 —— 而 R 又必须落在圈层内，否则出征就被拦下")
    void crushingAttackAccumulatesTyranny() {
        // 1.8 倍是一个刻意选出来的窄区间：高于暴虐阈值 1.5 才会累积，
        // 又必须低于 B08 圈层的 2.0 上限才出得了征 —— 用更大的兵力比（例如 40 倍）
        // 会在出征那一刻就被 AttackGuardService 拦下，暴虐值这条路径根本走不到，
        // 于是「碾压要留痕迹」这条规则永远测不出来
        Fixture f = readyPair(1_800L, 1_000L);
        String marchId = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(marchId);

        assertThat(tyrannyOf(f.attacker)).as("碾压必须留下痕迹").isPositive();
    }

    // ---------- 乘区 F：反击加成必须真的进到结算里（C01 §4 / B08 验收 9）----------

    @Test
    @DisplayName("复仇 +15% 不再恒为 0：被 A 打过的 B 反打 A 时，喂进内核的攻方乘区 F 带上 BONUS_REVENGE")
    void revengeBonusReachesTheKernel() {
        Fixture f = readyPair(2_000L, 2_000L);
        // 第一仗刻意走完整链路：目的是让「谁打过我」这本账由真实结算写下，而不是测试自己伪造一本
        arriveAndProcess(sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 500L)));
        assertThat(attackerHitsOf(f.defender)).as("被打的人的受害账本必须记下打他的人")
                .containsKey(f.attacker);

        BattleModifier bonus = settleOneMarch(f.defender, f.attackerCoord, 500L);
        assertThat(bonus.revenge())
                .as("反打仇人必须拿到 global.BONUS_REVENGE —— 恒为 0 正是这次要消灭的那个 bug")
                .isEqualTo(configs.fixedParam("BONUS_REVENGE"));
    }

    @Test
    @DisplayName("判别性对照：没挨过打的人反打也没有复仇、目标不是公敌也没有围剿")
    void noHistoryMeansNoCounterplayBonus() {
        Fixture f = readyPair(2_000L, 2_000L);
        BattleModifier bonus = settleOneMarch(f.attacker, f.defenderCoord, 500L);
        assertThat(bonus.revenge()).as("没人打过他，复仇就该是 0").isZero();
        assertThat(bonus.crusade()).as("目标暴虐值为 0，围剿就该是 0").isZero();
    }

    @Test
    @DisplayName("围剿 +15% 接上了调用点：打暴虐档以上的人，攻方吃到 BONUS_SIEGE_PUBLIC_ENEMY")
    void crusadeBonusAppliesAgainstBrute() {
        Fixture f = readyPair(2_000L, 2_000L);
        raiseTyranny(f.defender, 350L, System.currentTimeMillis());
        assertThat(settleOneMarch(f.attacker, f.defenderCoord, 500L).crusade())
                .as("C01 §3 的「全服围剿令」不能只活在 Tyranny 的判定函数里")
                .isEqualTo(configs.fixedParam("BONUS_SIEGE_PUBLIC_ENEMY"));
    }

    @Test
    @DisplayName("围剿按衰减后的暴虐值定档：十二个日切前的 350 点早该掉出围剿名单")
    void crusadeFollowsDecayedTyrannyNotStoredValue() {
        Fixture f = readyPair(2_000L, 2_000L);
        raiseTyranny(f.defender, 350L, System.currentTimeMillis() - 12L * 24 * 3_600_000L);
        assertThat(settleOneMarch(f.attacker, f.defenderCoord, 500L).crusade())
                .as("直接拿存档裸值定档会把三个月前的旧账当成现状 —— 那正好取消化了「靶子是打出来的」")
                .isZero();
    }

    @Test
    @DisplayName("跨过坐标暴露线那一刻，所在块的版本号必须变新（否则持有该块的客户端永远不会重新拉）")
    void crossingExposureThresholdInvalidatesOwnChunk() {
        Fixture f = readyPair(1_800L, 1_000L);
        // 90 分 + 一次碾压必须跨过 100（强横档 = 坐标不再受迷雾保护）。刻意不从 0 开始：
        // 从 0 累积到跨过 100 需要两个不同目标，那会把「防刷」那条规则也牵进这条断言
        raiseTyranny(f.attacker, 90L, System.currentTimeMillis());
        String homeChunk = f.attackerCoord.chunkKey(worldAppService.rules().chunkSize());
        long versionBefore = world.chunkVersion(homeChunk);

        arriveAndProcess(sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L)));

        assertThat(tyrannyOf(f.attacker)).as("夹具必须真的跨过 100，否则版本号那条断言什么也没验")
                .isGreaterThanOrEqualTo(100L);
        assertThat(world.chunkVersion(homeChunk))
                .as("档位跨过暴露线却不让块变新，「坐标可见」就只写在协议里：客户端手里那块是"
                        + "「已知版本、无实体」，版本号不动它就不会再来要")
                .isGreaterThan(versionBefore);
    }

    @Test
    @DisplayName("防刷：同一对玩家第二次互攻不再累积，单日对同一目标也只记一次")
    void tyrannyIsNotFarmable() {
        Fixture f = readyPair(1_800L, 1_000L);
        String first = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(first);
        long afterFirst = tyrannyOf(f.attacker);
        assertThat(afterFirst).isPositive();

        // 把兵补回去再打一次：这一对已经互相攻击过，防刷判定应当把它拦下。
        // **补兵之后必须重算守方战力**：圈层校验读的是守方存档里的匹配战力，
        // 而 giveTroops 只改了军队没刷新存档，存档里还是上一仗打完后的峰值地板（64000），
        // 落在攻方可攻击区间（72000 起）之下会被正当拒绝。
        // 这不是缺陷，是夹具漏了一步 —— 真实玩家补兵后打开面板就会触发重算
        // 守方要补得比攻方多：第二次出征能不能成，卡的不是战斗而是 B08 圈层 ——
        // 攻方的兵一部分还在上一仗的返程路上、一部分是新补的，重算后的战力比第一次更高，
        // 而守方只剩残兵，比值会冲破 2.0 的上限被正当拒绝（实测 2.35）。
        // 补到两边大致持平即可：这两条用例断言的是「暴虐值不再累积」与「护盾不被单人推起」，
        // 都只要求第二仗**打得起来**，不要求攻方赢
        giveTroops(f.attacker, Map.of("unit_infantry_t1", 1_800L));
        giveTroops(f.defender, Map.of("unit_infantry_t1", 3_000L));
        powerRefreshService.refresh(f.defender);
        powerRefreshService.refresh(f.attacker);
        String second = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(second);

        assertThat(tyrannyOf(f.attacker))
                .as("同一对玩家反复互攻不能把暴虐值刷上去，否则「两个小号轮流让大号打」就是完整套利链")
                .isEqualTo(afterFirst);
    }

    // ---------- 受害护盾 ----------

    @Test
    @DisplayName("受害护盾按不同攻击者计数：同一个人连打两次不触发，否则大佬能把目标永久锁进护盾")
    void victimShieldCountsDistinctAttackers() {
        Fixture f = readyPair(1_800L, 1_000L);
        String first = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(first);
        assertThat(players.findByPlayerId(f.defender).orElseThrow().pvp().attackerHits())
                .as("第一次攻击必须被记账").containsKey(f.attacker);

        // 守方要补得比攻方多：第二次出征能不能成，卡的不是战斗而是 B08 圈层 ——
        // 攻方的兵一部分还在上一仗的返程路上、一部分是新补的，重算后的战力比第一次更高，
        // 而守方只剩残兵，比值会冲破 2.0 的上限被正当拒绝（实测 2.35）。
        // 补到两边大致持平即可：这两条用例断言的是「暴虐值不再累积」与「护盾不被单人推起」，
        // 都只要求第二仗**打得起来**，不要求攻方赢
        giveTroops(f.attacker, Map.of("unit_infantry_t1", 1_800L));
        giveTroops(f.defender, Map.of("unit_infantry_t1", 3_000L));
        // 补兵之后必须重算双方战力，否则存档里还是上一仗后的旧值，
        // 圈层校验读的就是这个值（与防刷用例同一条理由）
        powerRefreshService.refresh(f.defender);
        powerRefreshService.refresh(f.attacker);
        String second = sendAttack(f.attacker, f.defenderCoord, Map.of("unit_infantry_t1", 1_800L));
        arriveAndProcess(second);

        long now = System.currentTimeMillis();
        assertThat(players.findByPlayerId(f.defender).orElseThrow().pvp().shieldActive(now))
                .as("同一个攻击者连打两次不算「被多人围殴」，不该触发护盾").isFalse();
    }

    // ---------- 拒绝路径 ----------

    @Test
    @DisplayName("不能攻击自己的城：那会让自己既当攻方又当守方，掠夺等于把资源从左口袋搬到右口袋")
    void cannotAttackOwnCity() {
        Fixture f = readyPair(1_000L, 1_000L);
        Coord own = ownCityOf(f.attacker);
        assertThatThrownBy(() -> sendAttack(f.attacker, own, Map.of("unit_infantry_t1", 100L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_TARGET_INVALID);
    }

    // ---------- 夹具 ----------

    private record Fixture(String attacker, String defender, Coord attackerCoord, Coord defenderCoord) {
    }

    /**
     * 一对相邻的玩家：攻方在城市中心，守方在旁边 3 格。
     *
     * @param attackerTroops 攻方兵力
     * @param defenderTroops 守方兵力
     */
    private Fixture readyPair(long attackerTroops, long defenderTroops) {
        String attacker = newPlayerAt(256, 256);
        Coord attackerCoord = ownCityOf(attacker);
        Coord defenderCoord = Coord.of(attackerCoord.x() + 3, attackerCoord.y() + 1);
        String defender = newPlayerAt(defenderCoord.x(), defenderCoord.y());
        liftProtection(attacker);
        liftProtection(defender);

        giveTroops(attacker, Map.of("unit_infantry_t1", attackerTroops));
        giveTroops(defender, Map.of("unit_infantry_t1", defenderTroops));
        // 守方仓库：每种资源填到它自己 1 级容量的一半。**不能写死一个绝对值** ——
        // 各资源的容量不同，写死 20000 会在容量只有 10000 的资源上把存档写成非法状态
        // （current > cap），而 ResourceSettlement.settle 遇到这种状态会抛异常，
        // 炸点却在几十个调用之外的战力重算里，看起来完全不像夹具的问题。
        // 保护额度按容量比例自动算出，所以「可掠夺量 = 当前量 - 保护额度」这条口径会被真的走到
        for (String resourceType : List.of("WOOD", "STONE", "IRON", "GRAIN", "GOLD")) {
            setResource(defender, resourceType, configs.getResource(resourceType).initCap() / 2L);
        }

        // 两边都重算成真实战力后落库：出征时的圈层校验读的是存档值，
        // 而给兵之前存档里的 matchPower 是旧的。
        // **不伪造守方战力** —— PlayerPower 有「峰值不得低于当前匹配战力」的不变量，
        // 伪造高 matchPower 就必须同时伪造高 peakPower，而 B08 的峰值记忆让
        // matchPower = max(当前值, 峰值衰减后的地板)，于是结算时重算出来的守方战力
        // 仍然是攻方那个量级，战力比恒等于 1、暴虐值永远不累积 ——
        // 看起来像「暴虐值坏了」，实际是夹具把峰值也伪造了。
        // 兵力比由用例自己给：1.8 倍落在圈层的 [0.5, 2.0] 内所以能出发，
        // 又高于暴虐阈值 1.5 所以会累积
        powerRefreshService.refresh(attacker);
        powerRefreshService.refresh(defender);
        return new Fixture(attacker, defender, attackerCoord, defenderCoord);
    }

    private String sendAttack(String attackerId, Coord target, Map<String, Long> units) {
        List<MarchUnit> marched = new ArrayList<>();
        units.forEach((unitId, count) -> marched.add(new MarchUnit(unitId, count)));
        return marchAppService.send(attackerId, new MarchReq(newRequestId(),
                target.x(), target.y(), marched, List.of(), MarchAction.ATTACK)).march().marchId();
    }

    /**
     * 把行军推进到「已到点」并触发到期扫描。服务端不跑定时器，测试也不 sleep。
     *
     * <p>arriveAt 改成本段的<b>起点</b>而不是某个传进来的时刻：传进来的往往是未来，
     * 那样到期扫描会认为「还没到点」，测试就会看到状态没推进却找不到原因。
     */
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

    private Coord ownCityOf(String playerId) {
        return worldAppService.homeOf(playerId);
    }

    private long totalTroops(String playerId) {
        return armies.findByPlayerId(playerId).map(ArmyState::totalTroops).orElse(0L);
    }

    private long tyrannyOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().pvp().tyranny();
    }

    /**
     * 发一支队伍到点，然后<b>直接调结算边界</b>打一仗，返回它实际喂进内核的攻方乘区 F。
     *
     * <p>本类的其它用例都刻意走完整行军链路（出征 → 到期扫描 → 战斗 → 返程），因为链上有几处容易断的接缝。
     * 这一处是有意例外：要断言的恰恰是「结算自己带出来的那个加成」，而加成没有协议字段、
     * 也不在战报视图里 —— 走完整链路的话它会被 {@code MarchAppService} 丢掉，测试就只能去猜。
     * 所以让 {@code resolve} 把它用的那个值原样返回，装配与喂入是同一个局部变量，无从漂移。
     */
    private BattleModifier settleOneMarch(String attackerId, Coord target, long units) {
        String marchId = sendAttack(attackerId, target, Map.of("unit_infantry_t1", units));
        March march = marches.findById(marchId).orElseThrow();
        return playerCityBattleService.resolve(march, System.currentTimeMillis(),
                Map.of(attackerId, 1L)).attackerModifier();
    }

    /** 受害账本：谁在窗口内打过这个人。复仇判定读的就是它。 */
    private Map<String, Long> attackerHitsOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().pvp().attackerHits();
    }

    /** 直接写暴虐值与它的推进时刻（{@code PlayerPvp} 要求两者同时给，见其构造器校验）。 */
    private void raiseTyranny(String playerId, long value, long touchedAt) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withTyranny(value, touchedAt));
        players.save(save);
    }

    private long resourceOf(String playerId, String resourceType) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resourceType).current();
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

    private void setResource(String playerId, String resourceType, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState state = save.resource(resourceType);
        save.putResource(resourceType, new PlayerResourceState(
                amount, Math.max(state.cap(), amount), state.protectedAmount(),
                state.perHour(), state.lastSettle()));
        players.save(save);
    }

    private void liftProtection(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    private String newPlayerAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "PVP 测试", 1_700_000_000_000L, ""))
                .playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("验收8 之二：出征战败真的会记 BATTLE_LOST，而赢的一方不记（平局也不算）")
    void losingAnAttackMarksBattleLost() {
        // 必须落在同一个战力圈层内（10 打 5000 会被"无法发起进攻"挡在门外），
        // 所以「输」用同圈层内的少打多来造：1300 vs 1800（上界是攻方战力的 4 倍，1800 守军贴着线内）
        Fixture pair = readyPair(1_300L, 1_800L);

        String marchId = sendAttack(pair.attacker(), pair.defenderCoord(),
                Map.of("unit_infantry_t1", 1_300L));
        arriveAndProcess(marchId);

        assertThat(players.findByPlayerId(pair.attacker()).orElseThrow().giftPopup().triggeredAtOf("BATTLE_LOST"))
                .as("输的一方要记下时刻").isPositive();
        assertThat(players.findByPlayerId(pair.defender()).orElseThrow().giftPopup().triggeredAtOf("BATTLE_LOST"))
                .as("赢的一方不该被记成战败").isZero();
    }
}
