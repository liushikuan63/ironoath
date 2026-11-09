package com.ironoath.web.battlepass;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.*;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.reward.*;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Existing FREE-track recovery requirement; no real payment or player storage. */
@SpringBootTest @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BattlePassLegacyRecoveryTest {
    static class Interrupted extends Error { Interrupted(){super("synthetic cut after committed tier mark");} }
    @SpyBean BattlePassStore progress;
    @Autowired BattlePassService service;
    @SpyBean BattlePassRules rules;
    @SpyBean RewardService rewards;
    @Autowired RewardNames names;
    @Autowired PlayerLock locks;
    @SpyBean TimeService time;
    @SpyBean ConfigRegistry configs;
    @Autowired StoreMailbox mailbox;
    @SpyBean MailStore mails;
    @Autowired PlayerInitService init;
    @SpyBean InventoryRepository inventories;
    @Autowired com.ironoath.core.player.PlayerRepository players;
    static final long T=1_700_000_000_000L;
    @BeforeEach void resetFaults(){reset(progress,rules,rewards,time,configs,mails,inventories);doReturn(T).when(time).serverNow();}
    private String id(){return UUID.randomUUID().toString();}
    private String player(){return init.init(new PlayerInitReq(id(),"bp-recovery-"+id(),"战令恢复合成夹具",time.serverNow(),"")).playerId();}
    private BattlePassService rebuilt(){return new BattlePassService(progress,rules,rewards,names,locks,time,configs,mailbox);}
    private void cutAfterMark(String season,String uid,int tier){
        var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();var after=(BattlePassStore.Progress)result;
            if(after.claimed(tier,BattlePassTrack.FREE)&&armed.compareAndSet(true,false))throw new Interrupted();return result;
        }).when(progress).update(eq(season),eq(uid),any());
    }
    @Test void directFreeTierMarkCutDoesNotLoseRewardAfterServiceRebuild(){
        String uid=player();var row=rules.tiers().get(0);String season=rules.seasonId();
        assertThat(row.freeRewardType().name()).isEqualTo("ITEM");
        long before=inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L);
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()));cutAfterMark(season,uid,(int)row.tier());
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(Interrupted.class);
        assertThat(progress.load(season,uid).claimed((int)row.tier(),BattlePassTrack.FREE)).isTrue();
        rebuilt().status(uid);
        assertThat(inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L)).isEqualTo(before+row.freeRewardCount());
    }
    @Test void seasonFreeTierMarkCutStillDeliversMailWhenSweepRetried(){
        String uid=player(),season="bp-recovery-season-"+id();var row=rules.tiers().get(0);
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()));cutAfterMark(season,uid,(int)row.tier());
        assertThatThrownBy(()->service.sweepSeasonToMail(season)).isInstanceOf(Interrupted.class);
        assertThat(progress.load(season,uid).claimed((int)row.tier(),BattlePassTrack.FREE)).isTrue();
        rebuilt().sweepSeasonToMail(season);
        var delivered=mails.listOf(uid,time.serverNow()).stream().filter(m->m.kind().equals("BATTLE_PASS")).toList();
        assertThat(delivered).hasSize(1);assertThat(delivered.get(0).rewards()).anySatisfy(line->{assertThat(line.id()).isEqualTo(row.freeRewardId());assertThat(line.count()).isEqualTo(row.freeRewardCount());});
        rebuilt().sweepSeasonToMail(season);assertThat(mails.listOf(uid,time.serverNow()).stream().filter(m->m.kind().equals("BATTLE_PASS")).count()).isEqualTo(1);
    }

    private com.ironoath.config.cfg.BattlePassCfg rowFor(String kind){
        var base=rules.tiers().get(0);boolean resource=kind.equals("gold")||kind.equals("resource");
        String item=kind.equals("gold")?"GOLD":kind.equals("resource")?"WOOD":kind.equals("equipment")?"eq_iron_sword":"item_res_wood_10k";
        return new com.ironoath.config.cfg.BattlePassCfg(base.id(),base.tier(),base.requiredPoints(),resource?com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.RESOURCE:com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.ITEM,
                item,resource?17:1,base.paidRewardType(),base.paidRewardId(),base.paidRewardCount());
    }
    private void useRow(com.ironoath.config.cfg.BattlePassCfg row){doReturn(row).when(rules).requireTier(row.tier());doReturn(java.util.List.of(row)).when(rules).tiers();}
    private long asset(String uid,com.ironoath.config.cfg.BattlePassCfg row){
        return row.freeRewardType()==com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.RESOURCE?players.findByPlayerId(uid).orElseThrow().resource(row.freeRewardId()).current()
                :inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L);
    }
    @ParameterizedTest @ValueSource(strings={"item","gold","resource","equipment"})
    void actualAssetSaveThenLostResponseCannotDuplicateOnRebuild(String kind){
        var row=rowFor(kind);useRow(row);String uid=player(),season=rules.seasonId();
        if(row.freeRewardType()==com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.RESOURCE){var save=players.findByPlayerId(uid).orElseThrow();var state=save.resource(row.freeRewardId());
            save.putResource(row.freeRewardId(),new com.ironoath.core.player.PlayerResourceState(0,state.cap(),state.protectedAmount(),0,T));players.save(save);}
        long before=asset(uid,row);progress.update(season,uid,p->p.withPoints(row.requiredPoints()));var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();if(armed.compareAndSet(true,false))throw new Interrupted();return result;})
                .when(rewards).grantBattlePass(eq(uid),anyList(),any(com.ironoath.core.reward.RewardContext.class));
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(Interrupted.class);
        assertThat(asset(uid,row)).isEqualTo(before+row.freeRewardCount());assertThat(progress.load(season,uid).pendingPlans()).hasSize(1);
        var original=inventories.findByPlayerId(uid).map(com.ironoath.core.bag.Inventory::equipSnapshot).orElse(java.util.List.of());
        rebuilt().status(uid);rebuilt().status(uid);assertThat(asset(uid,row)).isEqualTo(before+row.freeRewardCount());
        assertThat(inventories.findByPlayerId(uid).map(com.ironoath.core.bag.Inventory::equipSnapshot).orElse(java.util.List.of())).containsExactlyElementsOf(original);
        assertThat(progress.load(season,uid).pendingPlans()).isEmpty();
    }
    @Test void sweepLostMailReplyKeepsFirstFrozenWindowAndAttachmentPack(){
        String uid=player(),season="sweep-ack-"+id();var row=rules.tiers().get(0);progress.update(season,uid,p->p.withPoints(row.requiredPoints()));var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();MailStore.MailRecord written=call.getArgument(0);
            if(written.playerId().equals(uid)&&written.kind().equals("BATTLE_PASS")&&armed.compareAndSet(true,false))throw new Interrupted();return result;})
                .when(mails).save(any(MailStore.MailRecord.class));
        assertThatThrownBy(()->service.sweepSeasonToMail(season)).isInstanceOf(Interrupted.class);
        var initial=mails.listOf(uid,T);assertThat(initial).hasSize(1);var first=initial.get(0);
        doReturn(1L).when(configs).longParam("MAIL_RETENTION_DAYS");doReturn(T+1000).when(time).serverNow();
        rebuilt().sweepSeasonToMail(season);rebuilt().sweepSeasonToMail(season);
        assertThat(mails.listOf(uid,T)).containsExactly(first);assertThat(progress.load(season,uid).pendingPlans()).isEmpty();
    }
    @Test void originalSeasonPendingSurvivesSeasonAndRewardChange(){
        String uid=player();var row=rules.tiers().get(0);String old=rules.seasonId();progress.update(old,uid,p->p.withPoints(row.requiredPoints()));cutAfterMark(old,uid,(int)row.tier());
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(Interrupted.class);
        var changed=new com.ironoath.config.cfg.BattlePassCfg(row.id(),row.tier(),row.requiredPoints(),row.freeRewardType(),row.freeRewardId(),777,row.paidRewardType(),row.paidRewardId(),row.paidRewardCount());
        doReturn("new-"+old).when(rules).seasonId();useRow(changed);
        var status=rebuilt().status(uid);rebuilt().status(uid);
        assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(row.freeRewardId())).isEqualTo(row.freeRewardCount());
        assertThat(progress.load(old,uid).claimed((int)row.tier(),BattlePassTrack.FREE)).isTrue();assertThat(progress.load(old,uid).pendingPlans()).isEmpty();
        assertThat(status.points()).isZero();assertThat(status.tiers().get(0).freeClaimed()).isFalse();
    }
    @Test void laterPointsProduceAnotherSweepBatchWithoutReusingOrRepeatingFirst(){
        String uid=player(),season="sweep-batches-"+id();var first=rules.tiers().get(0);var second=rules.tiers().get(1);
        progress.update(season,uid,p->p.withPoints(first.requiredPoints()));assertThat(service.sweepSeasonToMail(season)).isEqualTo(1);
        var old=mails.listOf(uid,T).get(0);progress.update(season,uid,p->p.withPoints(second.requiredPoints()-first.requiredPoints()));
        assertThat(service.sweepSeasonToMail(season)).isEqualTo(1);assertThat(service.sweepSeasonToMail(season)).isZero();
        assertThat(mails.listOf(uid,T)).hasSize(2).contains(old);assertThat(mails.listOf(uid,T)).allSatisfy(m->assertThat(m.rewards()).hasSize(1));
    }
    @ParameterizedTest @ValueSource(strings={"direct","sweep","late"})
    void oldStableMailProofWinsBeforeAnyNewQualificationEvenAfterExpiry(String route){
        String uid=player(),season="legacy-proof-"+id();var row=rules.tiers().get(0);
        // Old late delivery only existed after points were committed; enumeration uses that original book.
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()));
        String key="activity-battle-pass:"+season+":"+row.tier()+":FREE";
        var proof=mailbox.sendGrantOnce(uid,"BATTLE_PASS","旧稳定邮件",java.util.List.of(new com.ironoath.core.reward.RewardItem(com.ironoath.core.reward.RewardType.ITEM,row.freeRewardId(),row.freeRewardCount())),"免费旧证据",
                new com.ironoath.core.reward.RewardContext("battlePassRecovery",season+":FREE",key,false));
        mails.markRead(uid,proof.mailId(),T);doReturn(proof.expireAt()+1).when(time).serverNow();mails.purgeExpired(time.serverNow());
        clearInvocations(mails);
        var altered=new com.ironoath.config.cfg.BattlePassCfg(row.id(),row.tier(),999999,row.freeRewardType(),row.freeRewardId(),777,row.paidRewardType(),row.paidRewardId(),row.paidRewardCount());useRow(altered);
        doReturn(route.equals("late")?"new-season":season).when(rules).seasonId();
        if(route.equals("direct"))assertThatThrownBy(()->rebuilt().claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(com.ironoath.common.BizException.class);
        else if(route.equals("sweep"))rebuilt().sweepSeasonToMail(season);
        else rebuilt().recoverActivityTierRewards(uid,season,java.util.List.of(altered));
        var book=progress.load(season,uid);assertThat(book.claimed((int)row.tier(),BattlePassTrack.FREE)).isTrue();assertThat(book.pendingPlans()).isEmpty();
        assertThat(book.tierMailWindows().get(key).createdAt()).isEqualTo(proof.createdAt());assertThat(book.tierMailWindows().get(key).expireAt()).isEqualTo(proof.expireAt());
        assertThat(inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L)).isZero();
        verify(mails,never()).save(any(MailStore.MailRecord.class));
        assertThat(mails.findById(uid,proof.mailId()).orElseThrow().rewards()).isEqualTo(proof.rewards());
    }
    @Test void historicalClaimedWithoutPlanIsNeverGuessed(){
        String uid=player(),season=rules.seasonId();var row=rules.tiers().get(0);progress.update(season,uid,p->p.withPoints(row.requiredPoints()).withClaimed((int)row.tier(),BattlePassTrack.FREE));
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(com.ironoath.common.BizException.class);
        rebuilt().status(uid);rebuilt().sweepSeasonToMail(season);assertThat(mails.listOf(uid,T)).isEmpty();assertThat(inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"item","gold","resource","equipment"})
    void overflowSaveReplyLostRetainsOneOriginalPackAndGenericRecoveryCannotDuplicate(String kind){
        var row=rowFor(kind);useRow(row);String uid=player(),season=rules.seasonId();
        inventories.insertIfAbsent(uid,com.ironoath.core.bag.Inventory.empty(1));
        var bag=inventories.findByPlayerId(uid).orElseThrow();bag.add("item_mat_hero_frag_n",1,99);bag.setCapacityMax(1);
        inventories.save(uid,bag,inventories.versionOf(uid));
        if(row.freeRewardType()==com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.RESOURCE){
            var save=players.findByPlayerId(uid).orElseThrow();var state=save.resource(row.freeRewardId());
            save.putResource(row.freeRewardId(),new com.ironoath.core.player.PlayerResourceState(state.cap(),state.cap(),state.protectedAmount(),0,T));players.save(save);
        }
        long before=asset(uid,row);progress.update(season,uid,p->p.withPoints(row.requiredPoints()));var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();MailStore.MailRecord written=call.getArgument(0);
            if(written.playerId().equals(uid)&&written.kind().equals("OVERFLOW")&&armed.compareAndSet(true,false))throw new Interrupted();return result;
        }).when(mails).save(any(MailStore.MailRecord.class));
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(Interrupted.class);
        var first=mails.listOf(uid,T);assertThat(first).hasSize(1);assertThat(first.get(0).rewards()).singleElement().satisfies(line->{
            assertThat(line.id()).isEqualTo(row.freeRewardId());assertThat(line.count()).isEqualTo(row.freeRewardCount());
        });
        assertThat(progress.load(season,uid).pendingPlans()).hasSize(1);
        rewards.recoverGoldClaims(uid);rewards.recoverItemClaims(uid);
        doReturn(1L).when(configs).longParam("MAIL_RETENTION_DAYS");doReturn(T+1000).when(time).serverNow();
        rebuilt().status(uid);rebuilt().status(uid);
        assertThat(mails.listOf(uid,T)).containsExactlyElementsOf(first);assertThat(asset(uid,row)).isEqualTo(before);
        assertThat(progress.load(season,uid).pendingPlans()).isEmpty();
    }
    @Test void pendingAssetsDoNotConsultCurrentDeletedRewardRows(){
        String uid=player(),season=rules.seasonId();var row=rules.tiers().get(0);progress.update(season,uid,p->p.withPoints(row.requiredPoints()));cutAfterMark(season,uid,(int)row.tier());
        assertThatThrownBy(()->service.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE))).isInstanceOf(Interrupted.class);
        doThrow(new com.ironoath.config.ConfigException("synthetic removed reward row")).when(configs).get(eq(com.ironoath.config.cfg.ItemCfg.class),eq(row.freeRewardId()));
        // Recovery itself must complete before status rendering is allowed to fail on its current display table.
        try{rebuilt().status(uid);}catch(com.ironoath.config.ConfigException displayOnly){}
        assertThat(progress.load(season,uid).pendingPlans()).isEmpty();
        assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(row.freeRewardId())).isEqualTo(row.freeRewardCount());
    }
    @Test void firstLateMailWriteOnlyAfterLongOutageStartsItsFrozenRetentionAtActualDelivery(){
        String uid=player(),season="late-first-write-"+id();var row=rules.tiers().get(0);long duration=configs.longParam("MAIL_RETENTION_DAYS")*86400000L;
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()));doReturn("next-"+season).when(rules).seasonId();var armed=new AtomicBoolean(true);
        doAnswer(call->{MailStore.MailRecord written=call.getArgument(0);
            if(written.playerId().equals(uid)&&written.kind().equals("BATTLE_PASS")&&armed.compareAndSet(true,false))throw new Interrupted();return call.callRealMethod();
        }).when(mails).save(any(MailStore.MailRecord.class));
        assertThatThrownBy(()->service.recoverActivityTierRewards(uid,season,java.util.List.of(row))).isInstanceOf(Interrupted.class);
        assertThat(progress.load(season,uid).pendingPlans()).hasSize(1);assertThat(mails.listOf(uid,T)).isEmpty();
        long later=T+31L*86400000L;doReturn(later).when(time).serverNow();doReturn(1L).when(configs).longParam("MAIL_RETENTION_DAYS");
        rebuilt().status(uid);rebuilt().status(uid);var delivered=mails.listOf(uid,later);assertThat(delivered).hasSize(1);
        var first=delivered.get(0);assertThat(first.createdAt()).isEqualTo(later);assertThat(first.expireAt()).isEqualTo(later+duration);
        var book=progress.load(season,uid);assertThat(book.pendingPlans()).isEmpty();
        assertThat(book.tierMailWindows().get("activity-battle-pass:"+season+":"+row.tier()+":FREE").createdAt()).isEqualTo(later);
    }
    @Test void concurrentFirstMailWritersWithDifferentClocksConfirmTheStoredWinner() throws Exception {
        String uid=player(),season="clock-race-"+id();var row=rules.tiers().get(0);long duration=configs.longParam("MAIL_RETENTION_DAYS")*86400000L;
        var plan=BattlePassClaimRecovery.plan(season,BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.LATE_MAIL,
                java.util.List.of(new BattlePassClaimRecovery(progress,configs,rewards,names,mailbox).freeze(row,BattlePassTrack.FREE,false)),T,T+duration,"活动");
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()).withDeliveryPlan(plan));
        var barrier=new CyclicBarrier(2);
        // Rendezvous after each absent-mail read. Never wait inside a synchronized MailStore spy answer.
        java.util.function.LongSupplier early=()->{awaitFirstMail(barrier);return T+100;};
        java.util.function.LongSupplier late=()->{awaitFirstMail(barrier);return T+900;};
        var first=new BattlePassClaimRecovery(progress,configs,rewards,names,new StoreMailbox(mails,names,new TimeService(early),configs));
        var second=new BattlePassClaimRecovery(progress,configs,rewards,names,new StoreMailbox(mails,names,new TimeService(late),configs));
        var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->first.recoverSeason(uid,season));var b=pool.submit(()->second.recoverSeason(uid,season));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
            var stored=mails.listOf(uid,T);assertThat(stored).hasSize(1);var mail=stored.get(0);
            assertThat(mail.createdAt()).isIn(T+100,T+900);assertThat(mail.expireAt()-mail.createdAt()).isEqualTo(duration);
            var book=progress.load(season,uid);assertThat(book.pendingPlans()).isEmpty();assertThat(book.tierMailWindows().get(plan.key()).createdAt()).isEqualTo(mail.createdAt());
            assertThat(book.tierMailWindows().get(plan.key()).expireAt()).isEqualTo(mail.expireAt());
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    private static void awaitFirstMail(CyclicBarrier barrier){
        try{barrier.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        catch(BrokenBarrierException|TimeoutException e){throw new IllegalStateException(e);}
    }
    @Test void mailSuccessThenProgressConfirmationFailureCannotRenewClaimedExpiredMail(){
        String uid=player(),season="confirm-cut-"+id();var row=rules.tiers().get(0);long duration=configs.longParam("MAIL_RETENTION_DAYS")*86400000L;
        var plan=BattlePassClaimRecovery.plan(season,BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.LATE_MAIL,
                java.util.List.of(new BattlePassClaimRecovery(progress,configs,rewards,names,mailbox).freeze(row,BattlePassTrack.FREE,false)),T,T+duration,"活动");
        progress.update(season,uid,p->p.withPoints(row.requiredPoints()).withDeliveryPlan(plan));var armed=new AtomicBoolean(true);
        doAnswer(call->{java.util.function.UnaryOperator<BattlePassStore.Progress> change=call.getArgument(2);var current=progress.load(season,uid);var next=change.apply(current);
            if(!current.pendingPlans().isEmpty()&&next.pendingPlans().isEmpty()&&armed.compareAndSet(true,false))throw new Interrupted();return call.callRealMethod();
        }).when(progress).update(eq(season),eq(uid),any());
        assertThatThrownBy(()->new BattlePassClaimRecovery(progress,configs,rewards,names,mailbox).recoverSeason(uid,season)).isInstanceOf(Interrupted.class);
        var first=mails.listOf(uid,T).get(0);assertThat(progress.load(season,uid).pendingPlans()).hasSize(1);
        assertThat(mails.claim(uid,first.mailId(),T)).isTrue();doReturn(first.expireAt()+1).when(time).serverNow();mails.purgeExpired(time.serverNow());
        rebuilt().status(uid);rebuilt().status(uid);var stored=mails.findById(uid,first.mailId()).orElseThrow();
        assertThat(stored.createdAt()).isEqualTo(first.createdAt());assertThat(stored.expireAt()).isEqualTo(first.expireAt());assertThat(stored.claimedAt()).isEqualTo(T);assertThat(stored.claimable(time.serverNow())).isFalse();
        var book=progress.load(season,uid);assertThat(book.pendingPlans()).isEmpty();assertThat(book.tierMailWindows().get(plan.key()).createdAt()).isEqualTo(first.createdAt());
    }
    private BattlePassService independentNode(String season,com.ironoath.config.cfg.BattlePassCfg row){
        var other=mock(BattlePassRules.class);when(other.seasonId()).thenReturn(season);when(other.requireTier(row.tier())).thenReturn(row);when(other.tiers()).thenReturn(java.util.List.of(row));when(other.seasonTotalDays()).thenReturn(45L);
        return new BattlePassService(progress,other,rewards,names,new com.ironoath.web.lock.InJvmPlayerLock(),time,configs,mailbox);
    }
    @Test void directSweepAndLateWithIndependentOwnerLocksAcceptOneQualification() throws Exception {
        String uid=player(),season="race-"+id();var row=rules.tiers().get(0);progress.update(season,uid,p->p.withPoints(row.requiredPoints()));
        var direct=independentNode(season,row);var sweep=independentNode(season,row);var late=independentNode("next-season",row);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
        try {
            var tasks=java.util.List.<Callable<Void>>of(()->{start.await();try{direct.claim(uid,new BattlePassClaimReq(id(),row.tier(),BattlePassTrack.FREE));}catch(RuntimeException retryable){}return null;},
                    ()->{start.await();try{sweep.sweepSeasonToMail(season);}catch(RuntimeException retryable){}return null;},
                    ()->{start.await();try{late.recoverActivityTierRewards(uid,season,java.util.List.of(row));}catch(RuntimeException retryable){}return null;});
            var futures=new java.util.ArrayList<Future<Void>>();for(var task:tasks)futures.add(pool.submit(task));start.countDown();for(var future:futures)future.get(20,TimeUnit.SECONDS);
            direct.status(uid);long received=inventories.findByPlayerId(uid).map(b->b.countOf(row.freeRewardId())).orElse(0L);
            long attached=mails.listOf(uid,T).stream().filter(m->m.kind().equals("BATTLE_PASS")).flatMap(m->m.rewards().stream()).filter(line->line.id().equals(row.freeRewardId())).mapToLong(MailStore.RewardLine::count).sum();
            assertThat(received+attached).isEqualTo(row.freeRewardCount());assertThat(progress.load(season,uid).claimed((int)row.tier(),BattlePassTrack.FREE)).isTrue();assertThat(progress.load(season,uid).pendingPlans()).isEmpty();
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
