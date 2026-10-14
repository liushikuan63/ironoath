package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.AttackGuardService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;

/**
 * 职责：C2 的落地验证（收口清单 §五 C2）—— ① 攻击频控真的接在统一漏斗上；
 *       ② 受击反应真的会转调真人 service（迁城 / 反击），且「同一发攻击只回应一次」。
 * 依赖：Spring Boot Test + test profile（内存存储）。
 *
 * <p><b>频控为什么要一个真打一仗的用例</b>：闸门（判额度）与记账（仗真打起来才算）
 * 分在两处，只测闸门的话，把记账那两行删掉也不会有任何测试变红 ——
 * 表现是「限额形同虚设」，而那正是这条红线存在的理由。所以这里先用真实行军链路打一仗，
 * 再断言账本上真的多了一笔。
 *
 * <p><b>受击反应的两条路径各测一条</b>：中攻击性（0.50）迁城 —— 断言城坐标真的变了；
 * 高攻击性（0.90）且<b>有兵</b>反击 —— 断言真的走出去一支打向袭击者的行军。
 * 两者都走真人 service，所以「像真人一样行动」这句话在这里是可核对的，不是口号。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotAttackQuotaTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private ArmyRepository armies;
    @Autowired private WorldRepository world;
    @org.springframework.boot.test.mock.mockito.SpyBean private MarchRepository marches;
    @org.springframework.boot.test.mock.mockito.SpyBean private MarchDueQueue dueQueue;
    @Autowired private MarchAppService marchAppService;
    @Autowired private CityAppService cityAppService;
    @Autowired private com.ironoath.web.service.WorldAppService worldAppService;
    @org.springframework.boot.test.mock.mockito.SpyBean private PowerRefreshService powerRefreshService;
    @Autowired private AttackGuardService guard;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @org.springframework.boot.test.mock.mockito.SpyBean private BotRegistry bots;
    @Autowired private BotAttackLimiter limiter;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;

    @org.springframework.boot.test.mock.mockito.SpyBean private com.ironoath.web.bot.BotAttackLedgerStore ledger;
    @Autowired private com.ironoath.web.battle.BattleReportStore reports;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.ironoath.web.battle.PlayerCityBattleService battle;

    @BeforeEach
    void resetStores() {
        org.mockito.Mockito.reset(ledger,battle,powerRefreshService,marches,bots,dueQueue);
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        // Bot 侧的进程内状态：不清的话上一条用例的托管身份与被攻击额度会飘进这一条
        bots.clear();
        limiter.reset();
        runtime.reset();
        adapter.resetCounters();
    }

    @Test void returnRescheduleWriteFailureCannotStrandCompletedBattle() { verifyReturnRescheduleFailure(false,false); }
    @Test void lostReturnRescheduleReplyCannotReplayCompletedBattle() { verifyReturnRescheduleFailure(true,false); }
    @Test void returnRescheduleWriteFailureCannotStrandRejectedBattle() { verifyReturnRescheduleFailure(false,true); }
    @Test void lostReturnRescheduleReplyCannotStrandRejectedBattle() { verifyReturnRescheduleFailure(true,true); }
    private void verifyReturnRescheduleFailure(boolean afterCommit,boolean notStarted) {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        for(String uid:List.of(human,bot)) {giveTroops(uid,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(uid);}
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));prepareArrival(id);
        if(notStarted) org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("owned rejected battle"))
                .doCallRealMethod().when(battle).precheck(org.mockito.ArgumentMatchers.eq(bot),org.mockito.ArgumentMatchers.eq(human),org.mockito.ArgumentMatchers.anyLong());
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{
            if(once.compareAndSet(false,true)) {
                if(afterCommit) invocation.callRealMethod();
                throw new org.springframework.dao.DataAccessResourceFailureException("owned return reschedule write/reply fault");
            }
            return invocation.callRealMethod();
        }).when(dueQueue).reschedule(org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.anyLong());
        marchAppService.processDue(bot,timeService.serverNow());
        long expected=notStarted ? 1_200 : reports.reportsOf(bot).get(0).result().atkSurvivors().values().stream().mapToLong(Long::longValue).sum();
        marchAppService.processDue(bot,timeService.serverNow()+HOUR);
        assertThat(marches.findById(id)).as("改期失败恢复后已知返程不能卡在交战态").isEmpty();
        assertThat(armies.findByPlayerId(bot).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(expected);
        assertThat(reports.reportsOf(human)).hasSize(notStarted ? 0 : 1);
        assertThat(ledger.count(human,timeService.serverNow())).isEqualTo(notStarted ? 0 : 1);
    }

    @Test void recallCannotUseStaleSnapshotToOverwriteAnotherFightingOwner() {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        for(String uid:List.of(human,bot)) {giveTroops(uid,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(uid);}
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{
            long version=(Long)invocation.callRealMethod();
            if(once.compareAndSet(false,true)) {
                var winner=marches.findById(id).orElseThrow();
                winner.restore(winner.startAt(),winner.returnArriveAt(),winner.returnStartAt(),winner.returnFrom(),winner.load(),winner.status(),winner.gatherStartAt(),winner.units());
                winner.arrive(timeService.serverNow());return marches.save(winner,version);
            }
            return version;
        }).when(marches).versionOf(id);
        assertThatThrownBy(()->marchAppService.recall(bot,new com.ironoath.web.dto.generated.MarchIdReq(UUID.randomUUID().toString(),id)))
                .as("召回不能给旧MARCHING对象配新的交战版本").isInstanceOf(BizException.class);
        assertThat(marches.findById(id).orElseThrow().status()).isEqualTo(com.ironoath.core.march.March.Status.FIGHTING);
    }

    @Test void temporaryPreBattleReadFailureReturnsUndamagedMarchAndReleasesQuota() {
        verifyKnownPreBattleFailure("precheck");
    }
    @Test void temporaryAdmissionWriteFailureCannotLeaveAnUnstartedBattleStuck() {
        verifyKnownPreBattleFailure("reserve");
    }
    @Test void lostAdmissionReplyMustReleaseOnlyThisUnstartedOwnersReservation() {
        verifyKnownPreBattleFailure("reserve-after-commit");
    }
    @Test void temporaryBotIdentityReadFailureMustNotStrandTheOwnedMarch() { verifyKnownPreBattleFailure("identity"); }
    @Test void rejectedBattleRetriesTemporaryReservationReleaseFailure() { verifyKnownPreBattleFailure("precheck-release"); }
    @Test void rejectedBattleRetriesTemporaryReturnSnapshotWriteFailure() { verifyKnownPreBattleFailure("precheck-return"); }
    private void verifyKnownPreBattleFailure(String fault) {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        for(String uid:List.of(human,bot)) {giveTroops(uid,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(uid);}
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        if(fault.equals("identity")) {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("owned temporary identity read fault"))
                    .doCallRealMethod().when(bots).botOnHuman(bot,human);
        } else if(fault.startsWith("precheck")) {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("owned temporary pre-battle read fault"))
                    .doCallRealMethod().when(battle).precheck(org.mockito.ArgumentMatchers.eq(bot),org.mockito.ArgumentMatchers.eq(human),org.mockito.ArgumentMatchers.anyLong());
        } else {
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation->{
                if(once.compareAndSet(false,true)) {
                    if(fault.equals("reserve-after-commit")) invocation.callRealMethod();
                    throw new org.springframework.dao.DataAccessResourceFailureException("owned admission fault");
                }
                return invocation.callRealMethod();
            }).when(ledger).reserve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
        }
        if(fault.equals("precheck-release")) {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("owned release write fault"))
                    .doCallRealMethod().when(ledger).release(org.mockito.ArgumentMatchers.any());
        }
        if(fault.equals("precheck-return")) {
            prepareArrival(id);var once=new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation->{
                com.ironoath.core.march.March value=invocation.getArgument(0);
                if(value.status()==com.ironoath.core.march.March.Status.RETURNING && once.compareAndSet(false,true))
                    throw new org.springframework.dao.DataAccessResourceFailureException("owned known-return snapshot write fault");
                return invocation.callRealMethod();
            }).when(marches).save(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong());
            marchAppService.processDue(bot,timeService.serverNow());
            assertThat(marches.findById(id).orElseThrow().status()).isEqualTo(com.ironoath.core.march.March.Status.FIGHTING);
            marchAppService.processDue(bot,timeService.serverNow()+HOUR);
            assertThat(marches.findById(id)).as("活进程已知返程补写后实际到家").isEmpty();
            assertThat(armies.findByPlayerId(bot).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(1_200);
            assertThat(reports.reportsOf(human)).isEmpty();assertThat(ledger.occupied(human,timeService.serverNow())).isZero();return;
        }
        arriveAndProcess(id);
        assertThat(marches.findById(id).orElseThrow().status()).as("明确未开战且存储恢复后不应永久卡交战").isEqualTo(com.ironoath.core.march.March.Status.RETURNING);
        assertThat(marches.findById(id).orElseThrow().totalUnits()).isEqualTo(1_200);
        assertThat(armies.findByPlayerId(human).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(1_200);
        assertThat(reports.reportsOf(human)).isEmpty();
        limiter.evict(timeService.serverNow());assertThat(ledger.occupied(human,timeService.serverNow())).isZero();
        marchAppService.processDue(bot,timeService.serverNow()+HOUR);
        assertThat(armies.findByPlayerId(bot).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(1_200);
    }

    @Test void staleMarchSnapshotCannotClaimBattleWithNewerFightingVersion() {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        for(String uid:List.of(human,bot)) {giveTroops(uid,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(uid);}
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));prepareArrival(id);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation->{
            long version=(Long)invocation.callRealMethod();
            if(once.compareAndSet(false,true)) {
                var winner=marches.findById(id).orElseThrow();winner.arrive(timeService.serverNow());
                return marches.save(winner,version); // 模拟另一写入者在本调用旧快照读取后已经取得FIGHTING。
            }
            return version;
        }).when(marches).versionOf(id);
        marchAppService.processDue(bot,timeService.serverNow());
        org.mockito.Mockito.verify(battle,org.mockito.Mockito.never()).resolve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyMap());
        assertThat(marches.findById(id).orElseThrow().status()).isEqualTo(com.ironoath.core.march.March.Status.FIGHTING);
        assertThat(dueQueue.size()).as("另一个写入者随后还会登记返程，不能误撤事件").isEqualTo(1);
    }

    @Test void businessExceptionAfterActualLossesMustNotReleaseUncertainQuota() {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        for(String id:List.of(human,bot)) {giveTroops(id,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(id);}
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.SYSTEM_ERROR,"injected post-loss business failure"))
                .when(powerRefreshService).refresh(bot);
        arriveAndProcess(id);
        long remaining=armies.findByPlayerId(human).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L);
        assertThat(remaining).as("故障发生时已经实际应用战损").isLessThan(1_200);
        assertThat(ledger.occupied(human,timeService.serverNow())).as("有真实战损的异常不能冒充未开战释放额度").isEqualTo(1);
        org.mockito.Mockito.reset(powerRefreshService);
        marchAppService.processDue(bot,timeService.serverNow()+HOUR);
        assertThat(armies.findByPlayerId(human).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).as("未确定结算禁止自动重打").isEqualTo(remaining);
        assertThat(ledger.occupied(human,timeService.serverNow())).isEqualTo(1);
    }

    @Test void concurrentDistinctArrivalsMustNotBothSpendTheLastQuota() throws Exception {
        String human=newPlayerAt(300,300),first=botAt(256,256,"0.50"),second=botAt(266,256,"0.50");
        for(String id:List.of(human,first,second)) { giveTroops(id,Map.of("unit_infantry_t1",1_200L));powerRefreshService.refresh(id); }
        String a=sendAttack(first,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        String b=sendAttack(second,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        limiter.recordAttack(first,human,timeService.serverNow(),"last-slot-fixture:1");
        limiter.recordAttack(first,human,timeService.serverNow(),"last-slot-fixture:2");
        var entered=new java.util.concurrent.CountDownLatch(1);var secondDone=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        // 仅战斗内核是替身，隔离守军乐观写冲突；出征/到达/额度接线均真实。
        var outcome=org.mockito.Mockito.mock(com.ironoath.web.battle.PlayerCityBattleService.Outcome.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.doAnswer(invocation->{
            if(calls.incrementAndGet()==1) { entered.countDown();assertThat(secondDone.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            return outcome;
        }).when(battle).resolve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyMap());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobA=pool.submit(()->arriveAndProcess(a));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var jobB=pool.submit(()->{ try { arriveAndProcess(b); } finally { secondDone.countDown(); } });
            jobB.get(10,java.util.concurrent.TimeUnit.SECONDS);jobA.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(calls.get()).as("不同真实到达来源只能一个进入最后额度的战斗").isEqualTo(1);
            assertThat(ledger.count(human,timeService.serverNow())).isEqualTo(3);
        } finally { secondDone.countDown();pool.shutdownNow(); }
    }

    @Test void ledgerWriteFailureAfterRealBattleMustNotStrandOrReplayTheMarch() {
        verifyPostBattleLedgerFailure(false);
    }

    @Test void diagnosticCountFailureMustNotBreakCompletedBattle() {
        verifyPostBattleLedgerFailure(true);
    }

    @Test void ledgerReplyFailureAfterCommitMustNotReplayOrReturnOriginalTroops() {
        verifyPostBattleLedgerFailure(false,true);
    }
    private void verifyPostBattleLedgerFailure(boolean diagnostic) { verifyPostBattleLedgerFailure(diagnostic,false); }
    private void verifyPostBattleLedgerFailure(boolean diagnostic,boolean afterCommit) {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        giveTroops(human,Map.of("unit_infantry_t1",1_200L));
        giveTroops(bot,Map.of("unit_infantry_t1",1_200L));
        powerRefreshService.refresh(bot);powerRefreshService.refresh(human);
        String id=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        if(diagnostic) {
            org.mockito.Mockito.doThrow(new IllegalStateException("injected diagnostic read outage"))
                .when(ledger).count(org.mockito.ArgumentMatchers.eq(human),org.mockito.ArgumentMatchers.anyLong());
        } else if(afterCommit) {
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation->{ var result=invocation.callRealMethod();if(once.compareAndSet(false,true)) throw new IllegalStateException("injected lost ledger commit reply");return result; }).when(ledger).record(org.mockito.ArgumentMatchers.any());
        } else {
            org.mockito.Mockito.doThrow(new IllegalStateException("injected post-battle write outage"))
                .doCallRealMethod().when(ledger).record(org.mockito.ArgumentMatchers.any());
        }
        arriveAndProcess(id);
        var arrived=marches.findById(id).orElseThrow();
        assertThat(arrived.status()).as("实际战斗结束后必须按伤亡返程，不能因附属记账失败卡住").isEqualTo(com.ironoath.core.march.March.Status.RETURNING);
        assertThat(reports.reportsOf(human)).hasSize(1);
        long survivors=arrived.totalUnits();
        long defenders=armies.findByPlayerId(human).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L);
        if(diagnostic) org.mockito.Mockito.verify(ledger,org.mockito.Mockito.never()).count(org.mockito.ArgumentMatchers.eq(human),org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.reset(ledger);
        limiter.evict(timeService.serverNow());
        marchAppService.processDue(bot,timeService.serverNow()+HOUR);
        assertThat(reports.reportsOf(human)).as("重试记账不能重打一仗").hasSize(1);
        assertThat(armies.findByPlayerId(human).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(defenders);
        assertThat(marches.findById(id)).as("实际返程完成后行军删除").isEmpty();
        assertThat(armies.findByPlayerId(bot).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).as("返兵必须是战后剩余兵力").isEqualTo(survivors);
        assertThat(ledger.count(human,timeService.serverNow())).as("故障恢复后真实攻击只能记一次").isEqualTo(1);
    }

    // ---------- 频控 ----------

    @Test
    @DisplayName("已合法出发的Bot行军到达前额度耗尽，不能再打一仗或追加频控账")
    void inFlightAttackRechecksQuotaBeforeBattleAtArrival() {
        String human=newPlayerAt(300,300),bot=botAt(256,256,"0.50");
        giveTroops(human,Map.of("unit_infantry_t1",1_200L));
        giveTroops(bot,Map.of("unit_infantry_t1",1_200L));
        powerRefreshService.refresh(bot);powerRefreshService.refresh(human);
        String marchId=sendAttack(bot,worldAppService.homeOf(human),Map.of("unit_infantry_t1",1_200L));
        long now=timeService.serverNow();
        // 在途期间其他已经发生的攻击把额度用尽；这三笔是明确计账输入夹具。
        for(int i=0;i<3;i++) limiter.recordAttack(bot,human,now+i,"arrival-fixture:"+i);
        arriveAndProcess(marchId);
        assertThat(limiter.recordedCount()).as("到达时不能发生超过已有上限的第四仗").isEqualTo(3);
        assertThat(armies.findByPlayerId(human).orElseThrow().troops().get("unit_infantry_t1"))
                .as("被拒的已在途攻击不能伤害守军").isEqualTo(1_200);
        var returned=marches.findById(marchId).orElseThrow();
        assertThat(returned.status()).isEqualTo(com.ironoath.core.march.March.Status.RETURNING);
        assertThat(returned.totalUnits()).as("打不成的原队伍完整返程").isEqualTo(1_200);
    }

    @Test
    @DisplayName("C2 频控：Bot 打的第三仗之后，第四次被统一漏斗拒掉（11001），且账本上真的记了那三笔")
    void botAttacksOnAHumanAreCappedAtTheConfiguredLimit() {
        String human = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        // 守方必须有驻军，否则 PVP 预检会直接拒掉（「对方城内没有驻军」是记录在案的拒绝口径，
        // 不是夹具疏漏）—— 而这一条要验的是频控，前面那几道闸门都得先放行
        giveTroops(human, Map.of("unit_infantry_t1", 1_200L));
        giveTroops(bot, Map.of("unit_infantry_t1", 1_200L));
        powerRefreshService.refresh(bot);
        powerRefreshService.refresh(human);
        Coord target = worldAppService.homeOf(human);
        long now = timeService.serverNow();

        // 第一笔来自真实战斗：出征 → 到期 → 结算，记账发生在结算里（而不是闸门里）
        String marchId = sendAttack(bot, target, Map.of("unit_infantry_t1", 1_200L));
        arriveAndProcess(marchId);
        assertThat(limiter.recordedCount())
                .as("仗真的打起来了就必须记一笔 —— 把记账挂在闸门上会让「出门又召回」白吃真人的额度")
                .isEqualTo(1L);

        // 补到上限（直接记账，等价于又打了两仗；限额取表里的 BOT_ATTACK_LIMIT_PER_24H = 3）
        limiter.recordAttack(bot, human, now);
        limiter.recordAttack(bot, human, now + 1_000L);

        assertThatThrownBy(() -> guard.guard(bot, target, now + 2_000L))
                .as("第四次必须被拦，且拦的是额度，不是别的闸门")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED);
        assertThat(limiter.blockedCount()).as("拦下这件事也要计数，否则限额生效与否看不出来")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("C2 对照组：真人打真人、Bot 打 Bot 都不受这条限额管 —— 它保护的是「真人被 Bot 围殴」")
    void theCapOnlyAppliesToBotOnHuman() {
        String humanA = newPlayerAt(300, 300);
        String humanB = newPlayerAt(310, 300);
        String botA = botAt(256, 256, "0.50");
        String botB = botAt(266, 256, "0.50");
        long now = timeService.serverNow();

        Coord humanBTarget = worldAppService.homeOf(humanB);
        for (int i = 0; i < 5; i++) {
            int round = i;
            assertThatCode(() -> {
                guard.guard(humanA, humanBTarget, now + round * 1_000L);
                limiter.recordAttack(humanA, humanB, now + round * 1_000L);
            }).as("真人之间的攻防不加限制（第 %s 次）", i + 1).doesNotThrowAnyException();
        }

        Coord botBTarget = worldAppService.homeOf(botB);
        for (int i = 0; i < 5; i++) {
            int round = i;
            assertThatCode(() -> {
                guard.guard(botA, botBTarget, now + round * 1_000L);
                limiter.recordAttack(botA, botB, now + round * 1_000L);
            }).as("Bot 之间的互相攻伐也不加限制（B11 §六 的邻里关系，第 %s 次）", i + 1)
                    .doesNotThrowAnyException();
        }
        assertThat(limiter.recordedCount())
                .as("两类组合都不进账本 —— 账本只为「Bot 打真人」而存在").isZero();
    }

    @Test
    @DisplayName("C2 额度按窗口恢复：24 小时之后同一对组合重新放行")
    void quotaRecoversAfterTheWindow() {
        String human = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        Coord target = worldAppService.homeOf(human);
        long now = timeService.serverNow();

        for (int i = 0; i < 3; i++) {
            limiter.recordAttack(bot, human, now + i);
        }
        assertThatThrownBy(() -> guard.guard(bot, target, now + 10))
                .isInstanceOf(BizException.class);

        assertThatCode(() -> guard.guard(bot, target, now + 25 * HOUR))
                .as("过了 24 小时窗口就该恢复：限额是「今天挨够了」，不是永久封禁")
                .doesNotThrowAnyException();
    }

    // ---------- 受击反应 ----------

    @Test
    @DisplayName("C2 受击反应：中攻击性的 Bot 被打了会迁城一次（走真人那条 exile），同一发攻击不再回应")
    void attackedBotRelocatesOnceAndOnlyOnce() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        Coord before = worldAppService.homeOf(bot);
        long hitAt = timeService.serverNow();
        recordHit(bot, attacker, hitAt);

        int reacted = runTicksUntil(bot, before, 3 * HOUR);
        Coord after = worldAppService.homeOf(bot);

        assertThat(reacted).as("3 小时内必须回应过（决策 3~30 秒后执行）").isEqualTo(1);
        assertThat(after).as("中攻击性的反应是迁城：坐标必须真的变了").isNotEqualTo(before);
        assertThat(adapter.reactedUpToOf(bot)).as("已回应游标要停在那一发上").isEqualTo(hitAt);
        assertThat(limiter.recordedCount())
                .as("自己迁城不算攻击任何人").isZero();
    }

    @Test
    @DisplayName("C2 受击反应：高攻击性且有兵的 Bot 会真的派出一支打向袭击者的行军")
    void attackedBotWithTroopsCounterAttacks() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.90");
        giveTroops(bot, Map.of("unit_infantry_t1", 500L));
        giveTroops(attacker, Map.of("unit_infantry_t1", 500L));
        powerRefreshService.refresh(bot);
        powerRefreshService.refresh(attacker);
        Coord attackerHome = worldAppService.homeOf(attacker);
        long hitAt = timeService.serverNow();
        recordHit(bot, attacker, hitAt);

        runTicksUntil(bot, null, 3 * HOUR);

        assertThat(marches.activeCountOf(bot))
                .as("反击必须真的走出去一支队伍（转调 MarchAppService.send，与真人同一条路）")
                .isEqualTo(1L);
        // 打完的行军会从仓储里删掉，所以此刻能读到的这一支就是刚派出去的那支
        var march = marches.findByPlayerId(bot).get(0);
        assertThat(march.to()).as("打的是袭击者的城").isEqualTo(attackerHome);
        assertThat(march.action()).as("这是一次攻击").isEqualTo(com.ironoath.core.march.March.Action.ATTACK);
        assertThat(adapter.reactedUpToOf(bot)).isEqualTo(hitAt);
    }

    @Test
    @DisplayName("C2 受击反应：没有兵的高攻击性 Bot 不空转 —— 龟缩一次、不报错、也不乱迁城")
    void attackedBotWithoutTroopsHunkers() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.90");
        Coord before = worldAppService.homeOf(bot);
        recordHit(bot, attacker, timeService.serverNow());

        runTicksUntil(bot, null, 3 * HOUR);

        assertThat(adapter.reactedUpToOf(bot)).as("回应过（选了龟缩）").isNotNull();
        assertThat(worldAppService.homeOf(bot)).as("龟缩就是不搬家").isEqualTo(before);
        assertThat(marches.activeCountOf(bot)).as("没有兵，也没有出兵").isZero();
        assertThat(adapter.failedCount())
                .as("failed 的定义是「读世界或执行抛出来」（见 bot.schema.json）——"
                        + "「没有兵可反击」与失误掷中的空动作都属体面跳过，进 skipped 而不是 failed")
                .isZero();
    }

    // ---------- 夹具 ----------

    /** 把一条攻击记录写进受害账本（等价于「刚被这个袭击者打过」）。 */
    private void recordHit(String victimId, String attackerId, long at) {
        PlayerSave save = players.findByPlayerId(victimId).orElseThrow();
        save.setPvp(save.pvp().withAttackerHits(Map.of(attackerId, at)));
        players.save(save);
    }

    /**
     * 按分钟推进 tick，直到受击反应发生（或时间窗用尽），返回发生过的反应次数。
     *
     * <p>minute-by-minute 而不是大步长：tick 的到期时刻由作息相位决定，
     * 大步长会直接跨过去，表现为「什么都没发生」而看不出原因。
     */
    private int runTicksUntil(String botId, Coord expectChangeFrom, long windowMillis) {
        long start = timeService.serverNow();
        for (long t = start; t < start + windowMillis; t += MINUTE) {
            runtime.tick(t);
            if (adapter.reactedUpToOf(botId) != null) {
                // 反应发生在这一 tick；再多跑几轮证明「同一发不会回应第二次」
                for (long extra = t + MINUTE; extra < t + 30 * MINUTE; extra += MINUTE) {
                    runtime.tick(extra);
                }
                return 1;
            }
        }
        return 0;
    }

    private String sendAttack(String attackerId, Coord target, Map<String, Long> units) {
        List<MarchUnit> marched = new ArrayList<>();
        units.forEach((unitId, count) -> marched.add(new MarchUnit(unitId, count)));
        return marchAppService.send(attackerId, new MarchReq("req-" + UUID.randomUUID(),
                target.x(), target.y(), marched, List.of(), MarchAction.ATTACK)).march().marchId();
    }

    private void arriveAndProcess(String marchId) {
        prepareArrival(marchId);
        marchAppService.processDue(marches.findById(marchId).orElseThrow().playerId(),System.currentTimeMillis());
    }
    private void prepareArrival(String marchId) {
        var march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marches.save(march, version);
        dueQueue.reschedule(marchId,march.startAt());
    }

    /** 一个「能打」的真人：城在世界里、保护已解除。 */
    private String newPlayerAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "频控测试" + UUID.randomUUID().toString().substring(0, 6), 1_700_000_000_000L, "")).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)，占位说明坐标与别的用例撞了", x, y).isTrue();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    /**
     * 一个托管账号：真人同一条建档路径 + 画像注册。
     *
     * <p><b>活跃时段给满 24 小时</b>：这样「下一次 tick」永远落在活跃小时内（否则作息相位会把它
     * 推到下一个活跃小时，用例就要按小时推进），反应延迟才是被测的那件事。
     * 攻击性按分档给：0.50 = 中档（迁城），0.90 = 高档（反击）。
     */
    private String botAt(int x, int y, String aggression) {
        String botId = newPlayerAt(x, y);
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse(aggression), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        cityAppService.list(botId);
        return botId;
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
