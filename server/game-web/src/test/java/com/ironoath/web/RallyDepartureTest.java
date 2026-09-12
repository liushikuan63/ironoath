package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.ScoutReq;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadRallyReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;

/**
 * 职责：B10 验收 11 的完整链路 —— 倒计时结束统一出发（兵力合并）+ 返程按承诺比例分回各人。
 * 依赖：Spring Boot Test；test profile（内存存储 + JVM 内锁 + 内存到期队列）。
 *
 * <p><b>本类盯的是「兵有去有回」而不是「集结能出发」</b>。出发只是把已经锁定的兵力搬到一支
 * 合并行军里，任何一步漏掉都会立刻报错；返程分兵才是那条会静默出错的路 ——
 * 合并行军的主人是发起人，只要 {@code rallyId} 在中途丢一次，到家时全部幸存兵力就记到他名下，
 * 其他成员的兵凭空变成他的。既不报错也不为负，只有玩家会发现「我出了 200 兵，回来一个没有」。
 * 所以每条分兵用例都同时断言各人增量<b>和</b>总量守恒。
 *
 * <p><b>为什么用最大余数而不是「四舍五入」</b>：Σ分回 必须恰好等于 Σ幸存。
 * 逐个四舍五入会出现 33.3+33.3+33.3=99.9 那种一分不差的零头，而零头既不算阵亡也不算归队 ——
 * 与 B05 那个「某排为空时它那一份损失直接消失」的吞兵 bug 是同一个家族。
 *
 * <p><b>推进时间的手法与 {@code GatherInterceptionTest} 一致</b>：服务端不跑定时器，
 * 行军与集结都由请求驱动的到期扫描推进，所以测试把 {@code now} 当成参数传进去，
 * 而不是 sleep 十分钟。
 */
@SpringBootTest
@ActiveProfiles("test")
class RallyDepartureTest {

    @Autowired private com.ironoath.core.event.GameEventBus questBus;


    private static final String T1 = "unit_infantry_t1";
    private static final String T2 = "unit_infantry_t2";
    /** 武将表里真实存在的 id：面板要按 id 查名字，所以不能用编造的 id。 */
    private static final String HERO_1 = "hero_ssr_01";
    private static final String HERO_2 = "hero_ssr_02";

    @Autowired private PlayerInitService playerInitService;
    @Autowired private SocialAppService socials;
    @Autowired private MarchAppService marchAppService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private com.ironoath.web.battle.BattleReportService battleReportService;

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
    @Autowired private SocialStore socialStore;

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
        socialStore.clear();
    }

    // ---------- 出发 ----------

    @Test
    @DisplayName("到点且人数够：统一出发成一支归属发起人的合并行军，兵力按 unitId 合并且不再扣第二次兵")
    void dueRallyDepartsAsOneMergedMarch() {
        Squad squad = squadOf(player(1_000L, 0L), player(800L, 0L));
        String rallyId = initiate(squad, Map.of(T1, 300L));
        join(squad.mates().get(0), rallyId, Map.of(T1, 200L));
        long leaderAtDepart = countOf(squad.leader(), T1);
        long mateAtDepart = countOf(squad.mates().get(0), T1);

        depart(rallyId);

        March merged = onlyMarch(squad.leader());
        assertThat(merged.isRallyMarch()).as("合并行军必须知道自己属于哪次集结，否则返程无法分兵").isTrue();
        assertThat(merged.rallyId()).isEqualTo(rallyId);
        assertThat(merged.playerId()).as("统一出发只有一支行军，主人是发起人").isEqualTo(squad.leader());
        assertThat(merged.units()).as("300 + 200 合并成一支，不是一人一支行军")
                .containsExactly(entry(T1, 500L));
        assertThat(merged.action()).isEqualTo(March.Action.ATTACK);
        assertThat(merged.targetType()).isEqualTo(March.TargetType.PLAYER_CITY);
        assertThat(marches.findByPlayerId(squad.mates().get(0)))
                .as("成员不再各自出行：兵力已经在合并行军里").isEmpty();

        assertThat(countOf(squad.leader(), T1)).as("出发不得把已承诺锁定的兵再扣一次").isEqualTo(leaderAtDepart);
        assertThat(countOf(squad.mates().get(0), T1)).isEqualTo(mateAtDepart);
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().status()).isEqualTo(Rally.Status.DEPARTED);
        assertThat(marchAppService.list(squad.leader()).marches().get(0).rallyId())
                .as("行军视图必须带 rallyId：成员的主人是发起人，他只能靠这个字段认出「我的兵在那支队伍里」")
                .isEqualTo(rallyId);

        marchAppService.processDue(squad.leader(), merged.arriveAt() + 1);
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().status())
                .as("一到目标就标记到达：全灭时行军记录当场被删，之后没有第二次机会标")
                .isEqualTo(Rally.Status.ARRIVED);
    }

    @Test
    @DisplayName("出发之后再加入：说的是「已经出发」，不是「准备时长不合法」，而且一根兵都不锁")
    void joiningAfterDepartureGetsItsOwnCode() {
        Squad squad = squadOf(player(1_000L, 0L), player(800L, 0L), player(700L, 0L));
        String rallyId = initiate(squad, Map.of(T1, 300L));
        join(squad.mates().get(0), rallyId, Map.of(T1, 200L));
        depart(rallyId);
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().status())
                .as("前置：这次集结必须真的已经出发").isEqualTo(Rally.Status.DEPARTED);

        // 判别性：改之前这条落到 errorOfJoinFailure 的兜底 RALLY_PREPARE_INVALID，
        // 而那条的文案是「准备时长不在允许区间内」—— 玩家会去调准备时长，
        // 而真问题是队伍已经飞出去了。专用码 10054 声明了却从没被用过。
        String late = squad.mates().get(1);
        assertThatThrownBy(() -> socials.rallyJoin(late,
                new RallyJoinReq(newRequestId(), rallyId, troops(Map.of(T1, 200L)), java.util.List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许加入")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RALLY_ALREADY_DEPARTED);
        assertThat(countOf(late, T1))
                .as("被拒时不许留下已锁定承诺的兵").isEqualTo(700L);
    }

    @Test
    @DisplayName("合并行军的时长、速度与运力和同样兵力的个人出征完全一致")
    void mergedMarchTravelsExactlyLikeAPersonalOne() {
        Squad squad = squadOf(player(1_000L, 0L), player(800L, 0L));
        String rallyId = initiate(squad, Map.of(T1, 300L));
        join(squad.mates().get(0), rallyId, Map.of(T1, 200L));
        depart(rallyId);
        March merged = onlyMarch(squad.leader());

        // 同一批兵、同一段路：个人侦查行军就是参照物。集结若自己另算一套速度或时长，
        // 差值不会让任何测试变红，只会被玩家当成「集结跑得快，用集结抢怪」的漏洞
        Coord target = worldAppService.homeOf(squad.victim());
        marchAppService.scout(squad.leader(), new ScoutReq(newRequestId(),
                target.x(), target.y(), List.of(new MarchUnit(T1, 500L))));
        March personal = marches.findByPlayerId(squad.leader()).stream()
                .filter(m -> !m.id().equals(merged.id()))
                .findFirst().orElseThrow();

        assertThat(merged.arriveAt() - merged.startAt())
                .as("行军时长：同一份 MarchCalculator 实现，不该有第二种算法")
                .isEqualTo(personal.arriveAt() - personal.startAt());
        assertThat(merged.teamSpeed()).as("队伍速度取最慢兵种，两边同一个实现").isEqualTo(personal.teamSpeed());
        assertThat(merged.loadCap()).as("运力 Σ数量×单位负载，两边同一个实现").isEqualTo(personal.loadCap());
    }

    @Test
    @DisplayName("到点时人数不足下限：原路退兵并取消，而不是带着一个人的兵出发")
    void insufficientMembersRefundInsteadOfDeparting() {
        Squad squad = squadOf(player(1_000L, 0L), player(800L, 0L));
        String rallyId = initiate(squad, Map.of(T1, 300L));
        long before = countOf(squad.leader(), T1);

        assertThat(depart(rallyId)).as("只有 1 人（下限 2），本次没有出发").isZero();

        assertThat(marches.findByPlayerId(squad.leader())).isEmpty();
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().status()).isEqualTo(Rally.Status.CANCELLED);
        assertThat(countOf(squad.leader(), T1)).as("凑不够人就必须把兵还回去").isEqualTo(before + 300L);
    }

    @Test
    @DisplayName("集结不能打参与者自己的城：到点撤销出发并退兵，而不是让掠夺左口袋换右口袋")
    void rallyCannotTargetAParticipantsOwnCity() {
        String leader = player(1_000L, 0L);
        String mate = player(800L, 0L);
        Squad squad = squadOf(leader, mate);
        // 目标刻意选成员自己的城：圈层校验拦不住它（两人战力比在区间内），
        // 必须由出发阶段的结构校验来拦
        String rallyId = initiateAt(squad, mate, Map.of(T1, 600L));
        join(mate, rallyId, Map.of(T1, 400L));
        long leaderAtDepart = countOf(leader, T1);
        long mateAtDepart = countOf(mate, T1);

        assertThat(depart(rallyId)).as("人数够了，但目标不成立 ⇒ 本次没有出发").isZero();

        assertThat(marches.findByPlayerId(leader)).as("不该留下一支无处可打的合并行军").isEmpty();
        assertThat(countOf(leader, T1)).as("发起人的兵退回").isEqualTo(leaderAtDepart + 600L);
        assertThat(countOf(mate, T1)).as("成员的兵也退回，撤销出发不是没收").isEqualTo(mateAtDepart + 400L);
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().status())
                .as("终态必须是取消，而不是停在 DEPARTED 让兵永久锁着")
                .isEqualTo(Rally.Status.CANCELLED);
    }

    @Test
    @DisplayName("成员报名的武将随合并行军一起出发，面板能看到每个人的武将位状态")
    void rallyHeroesMarchWithTheMergedColumn() {
        String leader = player(1_000L, 0L);
        String mate = player(800L, 0L);
        grantHero(leader, HERO_1);
        grantHero(mate, HERO_2);
        Squad squad = squadOf(leader, mate);
        String rallyId = socials.squadRally(leader, new SquadRallyReq(newRequestId(),
                coordOf(squad.victim()), SocialTargetType.PLAYER_CITY,
                troops(Map.of(T1, 300L)), List.of(HERO_1))).rally().rallyId();
        socials.rallyJoin(mate, new RallyJoinReq(newRequestId(), rallyId,
                troops(Map.of(T1, 200L)), List.of(HERO_2)));

        depart(rallyId);

        assertThat(onlyMarch(leader).heroes())
                .as("合并行军带的是两人报名的武将，按加入顺序")
                .containsExactly(HERO_1, HERO_2);

        var slots = socials.rallyView(mate, rallyId).rally().heroSlots();
        assertThat(slots).as("两位成员各自的武将位都要下发").hasSize(2);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.heroName()).as("武将名由服务端下发，客户端不得自己拼").isNotBlank();
            assertThat(slot.selected()).as("武将位没满，两人都该上场").isTrue();
        });
        assertThat(slots).extracting(slot -> slot.playerId())
                .containsExactlyInAnyOrder(leader, mate);
    }

    // ---------- 收益分摊（掠夺与掉落） ----------

    @Test
    @DisplayName("集结抢到的资源按承诺兵力分给成员：兵分回来了，抢到的也得跟着回来")
    void rallySpoilsAreSplitByCommitment() {
        String leader = player(1_000L, 0L);
        String mate = player(800L, 0L);
        Squad squad = squadOf(leader, mate);
        String victim = squad.victim();
        String rallyId = initiate(squad, Map.of(T1, 600L));
        join(mate, rallyId, Map.of(T1, 400L));
        depart(rallyId);

        long leaderBefore = resourceTotal(leader);
        long mateBefore = resourceTotal(mate);
        long victimBefore = resourceTotal(victim);

        March merged = onlyMarch(leader);
        marchAppService.processDue(leader, merged.arriveAt() + 1);

        long leaderGain = resourceTotal(leader) - leaderBefore;
        long mateGain = resourceTotal(mate) - mateBefore;
        assertThat(leaderGain + mateGain)
                .as("Σ各受益人增量不得超过守方被搬走的量（守方同时还在产，所以不断言严格相等）")
                .isLessThanOrEqualTo(victimBefore - resourceTotal(victim) + 10L);
        assertThat(mateGain)
                .as("成员出了 400/1000 的兵，却一分抢不到 ⇒ 集结就是替发起人打工").isPositive();
        assertThat(leaderGain)
                .as("发起人承诺 600/1000，拿大头")
                .isGreaterThan(mateGain);

        // 战报类型也要是集结：这是玩家唯一能回看「这一仗是谁打的、按什么口径算」的地方。
        // 只修喂内核那一处而漏掉战报，客户端的「集结」文案分支就永远走不到 ——
        // 看起来像客户端多写了一行，实际是服务端标错了
        assertThat(battleReportService.list(victim).reports())
                .as("守方看到的必须是「集结来打我」，而不是「一个人来打我」")
                .isNotEmpty()
                .allSatisfy(brief -> assertThat(brief.battleType())
                        .isEqualTo(com.ironoath.web.dto.generated.BattleType.PVP_RALLY));
        assertThat(battleReportService.list(leader).reports())
                .as("攻方自己的战报同一次战斗，类型不能不一致")
                .isNotEmpty()
                .allSatisfy(brief -> assertThat(brief.battleType())
                        .isEqualTo(com.ironoath.web.dto.generated.BattleType.PVP_RALLY));
    }

    @Test
    @DisplayName("对照：普通行军的掠夺仍全额归行军主人")
    void soloMarchKeepsAllSpoils() {
        String raider = player(1_000L, 0L);
        String victim = player(600L, 0L);
        Coord target = worldAppService.homeOf(victim);
        String marchId = marchAppService.send(raider, new MarchReq(newRequestId(),
                target.x(), target.y(), List.of(new MarchUnit(T1, 1_000L)), List.of(),
                MarchAction.ATTACK)).march().marchId();
        March march = marches.findById(marchId).orElseThrow();
        long before = resourceTotal(raider);

        marchAppService.processDue(raider, march.arriveAt() + 1);

        assertThat(resourceTotal(raider) - before)
                .as("一个人打的仗，全额归他：拆分不能改变单人场景")
                .isPositive();
    }

    // ---------- 返程分兵 ----------

    @Test
    @DisplayName("幸存兵力按承诺比例分回各人：300/200 出 500 兵、回 100 兵 ⇒ 各回 60 与 40")
    void survivorsSplitBackByCommitmentRatio() {
        String leader = player(1_000L, 0L);
        String mate = player(800L, 0L);
        Squad squad = squadOf(leader, mate);
        String rallyId = initiate(squad, Map.of(T1, 300L));
        join(mate, rallyId, Map.of(T1, 200L));
        depart(rallyId);
        long leaderBase = countOf(leader, T1);
        long mateBase = countOf(mate, T1);
        March merged = onlyMarch(leader);
        String marchId = merged.id();

        bringHome(merged, Map.of(T1, 100L));

        assertThat(countOf(leader, T1) - leaderBase).as("承诺 300/500 ⇒ 拿回 100 里的 60%").isEqualTo(60L);
        assertThat(countOf(mate, T1) - mateBase).isEqualTo(40L);
        assertThat(countOf(leader, T1) + countOf(mate, T1))
                .as("守恒：Σ分回 == Σ幸存（战斗损失另计，不会凭空多也不会凭空少）")
                .isEqualTo(leaderBase + mateBase + 100L);
        assertThat(marches.findById(marchId)).as("到家后行军记录清理").isEmpty();
    }

    @Test
    @DisplayName("按 unitId 逐个分回：只出 T2 的人不会拿回一堆 T1，兵种不在结算里被偷换")
    void eachMemberGetsBackTheUnitTheyCommitted() {
        String leader = player(1_000L, 0L);
        String mate = player(0L, 800L);
        Squad squad = squadOf(leader, mate);
        String rallyId = initiate(squad, Map.of(T1, 300L));
        join(mate, rallyId, Map.of(T2, 200L));
        depart(rallyId);
        long leaderT1 = countOf(leader, T1);
        long leaderT2 = countOf(leader, T2);
        long mateT1 = countOf(mate, T1);
        long mateT2 = countOf(mate, T2);

        bringHome(onlyMarch(leader), Map.of(T1, 150L, T2, 100L));

        assertThat(countOf(leader, T1)).as("T1 只有发起人出过 ⇒ 全部 T1 幸存都归他").isEqualTo(leaderT1 + 150L);
        assertThat(countOf(leader, T2)).as("没出过的兵种不能凭空分给他").isEqualTo(leaderT2);
        assertThat(countOf(mate, T2)).as("T2 只有成员出过 ⇒ 全部 T2 幸存都归他").isEqualTo(mateT2 + 100L);
        assertThat(countOf(mate, T1)).as("反向也一样").isEqualTo(mateT1);
    }

    @Test
    @DisplayName("除不尽的余数按 playerId 字典序小者优先：同一场结算在两次运行里分毫不差")
    void remainderIsAssignedDeterministically() {
        String first = player(500L, 0L);
        String second = player(500L, 0L);
        String third = player(500L, 0L);
        Squad squad = squadOf(first, second, third);
        String rallyId = initiate(squad, Map.of(T1, 100L));
        join(second, rallyId, Map.of(T1, 100L));
        join(third, rallyId, Map.of(T1, 100L));
        depart(rallyId);
        Map<String, Long> bases = new LinkedHashMap<>();
        for (String member : List.of(first, second, third)) {
            bases.put(member, countOf(member, T1));
        }

        bringHome(onlyMarch(first), Map.of(T1, 100L));

        // 100 兵对三个各出 100 的人：整数部分是 33/33/33，余下的 1 必须有个确定的归属，
        // 否则同一份存档重放两次会分出不同的结果，战报就不可复现（铁律 4）
        List<String> byId = new ArrayList<>(bases.keySet());
        byId.sort(String::compareTo);
        assertThat(countOf(byId.get(0), T1) - bases.get(byId.get(0))).as("余数给字典序最小的人").isEqualTo(34L);
        assertThat(countOf(byId.get(1), T1) - bases.get(byId.get(1))).isEqualTo(33L);
        assertThat(countOf(byId.get(2), T1) - bases.get(byId.get(2))).isEqualTo(33L);
        long total = 0L;
        for (String member : byId) {
            total += countOf(member, T1) - bases.get(member);
        }
        assertThat(total).as("最大余数法的下限：Σ分回 == Σ幸存，一个零头都不许掉").isEqualTo(100L);
    }

    // ---------- 夹具 ----------

    @Test
    @DisplayName("加入集结发布 JOIN_RALLY（B12 §1：记在加入者头上，一次 +1；发起不算）")
    void rallyJoinPublishesRallyEvent() {
        String leader = player(1_000L, 0L);
        String mate = player(800L, 0L);
        Squad squad = squadOf(leader, mate);
        String rallyId = initiate(squad, Map.of(T1, 300L));
        var captured = new java.util.ArrayList<com.ironoath.core.event.GameEvent>();
        questBus.subscribe(com.ironoath.core.quest.GoalType.JOIN_RALLY, captured::add);

        socials.rallyJoin(mate, new RallyJoinReq(newRequestId(), rallyId,
                troops(Map.of(T1, 200L)), List.of()));

        assertThat(captured).as("加入一次 = 一个事件（发起那一步不在订阅之后，所以这里只有一次）")
                .hasSize(1);
        assertThat(captured.get(0).playerId()).as("进度记在加入者头上，不是发起人")
                .isEqualTo(mate);
        assertThat(captured.get(0).amount()).isEqualTo(1L);
    }

    private record Squad(String squadId, String leader, List<String> mates, String victim) {
    }

    /**
     * 一个 n 人小队，外加一名<b>第三方</b>被攻击者。
     *
     * <p>目标刻意不是「某个成员的城」：出发校验会拒掉「集结打参与者自己的城」
     * （掠夺变成左口袋换右口袋，还要给发起人记暴虐值），拿成员当目标会让整条出发链路
     * 被撤销 —— 那是校验正确，不是夹具该绕过去的东西。
     * 被攻击者带 600 兵是为了让战力比落在圈层区间内，否则发起阶段就被拒。
     */
    private Squad squadOf(String leader, String... mates) {
        socials.squadCreate(leader, new SquadCreateReq(newRequestId(), "出发小队"));
        String squadId = socialStore.squadOf(leader).orElseThrow().id();
        for (String mate : mates) {
            socials.squadJoin(mate, new SquadIdReq(newRequestId(), squadId));
        }
        return new Squad(squadId, leader, List.of(mates), player(600L, 0L));
    }

    private String initiate(Squad squad, Map<String, Long> troops) {
        return initiateAt(squad, squad.victim(), troops);
    }

    /** 以 {@code targetPlayer} 的城为目标发起集结。 */
    private String initiateAt(Squad squad, String targetPlayer, Map<String, Long> troops) {
        Coord target = worldAppService.homeOf(targetPlayer);
        return socials.squadRally(squad.leader(), new SquadRallyReq(newRequestId(),
                new SocialCoord(target.x(), target.y()), SocialTargetType.PLAYER_CITY,
                troops(troops), java.util.List.of())).rally().rallyId();
    }

    /** 全部资源的当前存量合计。用于「谁抢到了东西」这种只看总量的断言，不逐资源展开。 */
    private long resourceTotal(String playerId) {
        long total = 0L;
        for (var state : players.findByPlayerId(playerId).orElseThrow().resources().values()) {
            total += state.current();
        }
        return total;
    }

    private void join(String playerId, String rallyId, Map<String, Long> troops) {
        socials.rallyJoin(playerId, new RallyJoinReq(newRequestId(), rallyId, troops(troops), java.util.List.of()));
    }

    /** 把时刻推到准备窗口结束并跑一次出发扫描。返回本次实际出发的集结数。 */
    private int depart(String rallyId) {
        long dueAt = socialStore.rallyOf(rallyId).orElseThrow().prepareUntil() + 1;
        return marchAppService.departDueRallies(dueAt);
    }

    /**
     * 让这支合并行军带着给定的幸存兵力到家。
     *
     * <p>不真打一场再把结果读回来，是因为那样用例断的就不是分兵而是战斗内核：
     * 分兵要的是「给定幸存数 ⇒ 各人拿回多少」，所以直接把幸存数写进行军再跑到期扫描
     * （手法与 {@code GatherInterceptionTest} 拨采集起点同源）。
     */
    private void bringHome(March march, Map<String, Long> survivors) {
        long version = marches.versionOf(march.id());
        long past = System.currentTimeMillis() - 1_000L;
        march.restore(march.startAt(), past, past - 60_000L, march.to(),
                0L, March.Status.RETURNING, null, survivors);
        marches.save(march, version);
        dueQueue.reschedule(march.id(), past);
        marchAppService.processDue(march.playerId(), System.currentTimeMillis());
    }

    private March onlyMarch(String playerId) {
        List<March> list = marches.findByPlayerId(playerId);
        assertThat(list).as("该玩家应只有一支在途行军").hasSize(1);
        return list.get(0);
    }

    private static List<RallyTroop> troops(Map<String, Long> byUnitId) {
        List<RallyTroop> out = new ArrayList<>();
        byUnitId.forEach((unitId, count) -> out.add(new RallyTroop(unitId, count)));
        return out;
    }

    /** 某玩家主城坐标，协议形状。 */
    private SocialCoord coordOf(String playerId) {
        Coord home = worldAppService.homeOf(playerId);
        return new SocialCoord(home.x(), home.y());
    }

    /** 直接给玩家一张武将表（绕过抽卡：这里要验的是承诺与名单，不是获得路径）。 */
    private void grantHero(String playerId, String heroId) {
        if (heroes.findByPlayerId(playerId).isEmpty()) {
            com.ironoath.core.hero.HeroRoster fresh = new com.ironoath.core.hero.HeroRoster();
            fresh.obtain(heroId);
            heroes.insertIfAbsent(playerId, fresh);
            return;
        }
        com.ironoath.core.hero.HeroRoster roster = heroes.findByPlayerId(playerId).orElseThrow();
        long version = heroes.versionOf(playerId);
        roster.obtain(heroId);
        heroes.save(playerId, roster, version);
    }

    private long countOf(String playerId, String unitId) {
        return armies.findByPlayerId(playerId).map(army -> army.countOf(unitId)).orElse(0L);
    }

    /** 一个有兵、解除新手保护、战力已刷新的玩家（圈层校验读的是存档里的战力）。 */
    private String player(long t1, long t2) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "集结出发测试", 1_700_000_000_000L))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(10);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        save.setProtectUntil(null);
        players.save(save);
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        // 只在为正时加：ArmyState.add 拒绝 0（「增加数量必须为正」），而夹具里 0 是常态
        // —— 例如「只出 T1」的那名成员，他的 T2 就是 0
        if (t1 > 0L) {
            army.add(T1, t1);
        }
        if (t2 > 0L) {
            army.add(T2, t2);
        }
        armies.save(playerId, army, version);
        worldAppService.homeOf(playerId);
        powerRefreshService.refresh(playerId);
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
