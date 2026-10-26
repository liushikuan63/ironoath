package com.ironoath.web.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.*;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.*;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.*;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Real equipment persistence cuts; never uses player databases. */
@SpringBootTest @ActiveProfiles("test") @Import(MailEquipmentRecoveryTest.Isolation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailEquipmentRecoveryTest {
    @TestConfiguration static class Isolation {
        @Bean AtomicLong equipmentRecoveryClock() { return new AtomicLong(1_700_000_000_000L); }
        @Bean @Primary TimeService equipmentTime(AtomicLong clock) { return new TimeService(clock::get); }
    }
    static final class Interrupted extends Error { Interrupted() { super("synthetic cut after committed state"); } }
    static final String EQUIP = "eq_iron_sword", ITEM = "item_res_iron_5k";
    @SpyBean MailStore store;
    @SpyBean InventoryRepository inventories;
    @Autowired PlayerRepository players;
    @Autowired PlayerInitService init;
    @Autowired TimeService time;
    @SpyBean ConfigRegistry configs;
    @SpyBean RewardService rewards;
    @Autowired IdempotencyStore idempotency;
    @Autowired PlayerLock locks;
    @Autowired RewardNames names;
    @Autowired MailAppService service;
    @Autowired AtomicLong equipmentRecoveryClock;
    @BeforeEach void resetFaults() { reset(store, inventories, rewards, configs); equipmentRecoveryClock.set(1_700_000_000_000L); }
    private String id() { return UUID.randomUUID().toString(); }
    private MailAppService rebuilt() { return new MailAppService(store, time, configs, rewards, idempotency, locks, names); }
    private String player() {
        String uid = init.init(new PlayerInitReq(id(), "equip-recovery-" + id(), "装备邮件合成夹具", time.serverNow(), "")).playerId();
        inventories.insertIfAbsent(uid, Inventory.empty(100));
        var bag = inventories.findByPlayerId(uid).orElseThrow(); assertThat(bag.equipSnapshot()).isEmpty();
        bag.setCapacityMax(100); inventories.save(uid, bag, inventories.versionOf(uid));
        var save = players.findByPlayerId(uid).orElseThrow(); var state = save.resource("GRAIN");
        save.putResource("GRAIN", new PlayerResourceState(0, state.cap(), state.protectedAmount(), 0, time.serverNow())); players.save(save);
        return uid;
    }
    private String mail(String uid, List<MailStore.RewardLine> lines) {
        String id = "mail_equip_" + id(); long now = time.serverNow();
        store.save(new MailStore.MailRecord(id, uid, "SYSTEM", "合成装备附件", "中断恢复夹具", lines,
                "fixture:ordinary-equipment", now, now + 86_400_000L, null, null)); return id;
    }
    private List<Inventory.EquipInstance> equips(String uid) { return inventories.findByPlayerId(uid).orElseThrow().equipSnapshot(); }
    @Test void afterClaimMarkerCutStillDeliversEquipmentWhenServiceRebuilt() {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("ITEM", EQUIP, 1, "铁剑")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> { Object result = call.callRealMethod();
            if (Boolean.TRUE.equals(result) && armed.compareAndSet(true, false)) throw new Interrupted(); return result;
        }).when(store).acceptClaim(eq(uid), eq(mailId), anyLong(), any(MailClaimPlan.class));
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        assertThat(store.findById(uid, mailId).orElseThrow().claimedAt()).isNotNull();
        assertThat(equips(uid)).isEmpty();
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(equips(uid)).singleElement().extracting(Inventory.EquipInstance::equipId).isEqualTo(EQUIP);
    }
    @Test void cutAfterActualEquipmentSaveRestoresMixedRemainderWithoutRemintingOriginalUid() {
        String uid = player(); mail(uid, List.of(new MailStore.RewardLine("ITEM", EQUIP, 1, "铁剑"),
                new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮草"), new MailStore.RewardLine("ITEM", ITEM, 2, "铁矿包")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> { Object result = call.callRealMethod(); Inventory saved = call.getArgument(1);
            if (saved.equipSnapshot().size() == 1 && armed.compareAndSet(true, false)) throw new Interrupted(); return result;
        }).when(inventories).save(eq(uid), any(Inventory.class), anyLong());
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var actual = equips(uid); assertThat(actual).hasSize(1); int next = inventories.findByPlayerId(uid).orElseThrow().nextEquipUid();
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(equips(uid)).containsExactlyElementsOf(actual);
        assertThat(inventories.findByPlayerId(uid).orElseThrow().nextEquipUid()).isEqualTo(next);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);
        assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(ITEM)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void interleavedAttachmentsKeepOriginalCapacityAllocation(boolean equipmentFirst) {
        String uid=player();var bag=inventories.findByPlayerId(uid).orElseThrow();assertThat(bag.capacityUsed()).isZero();
        bag.setCapacityMax(2);inventories.save(uid,bag,inventories.versionOf(uid));
        var item=new MailStore.RewardLine("ITEM",ITEM,1,"铁矿包");var equip=new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑");
        var third=new MailStore.RewardLine("ITEM",equipmentFirst?"eq_leather_armor":"item_mat_hero_frag_n",1,"合成附件");
        String mailId=mail(uid,equipmentFirst?List.of(equip,item,third):List.of(item,equip,third));
        assertThat(service.claimAll(uid,new MailClaimAllReq(id())).failed()).isEmpty();
        assertThat(equips(uid)).singleElement().extracting(Inventory.EquipInstance::equipId).isEqualTo(EQUIP);
        assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(ITEM)).isEqualTo(1);
        var overflow=store.listOf(uid,time.serverNow()).stream().filter(m->!m.mailId().equals(mailId)).toList();
        assertThat(overflow).hasSize(1);assertThat(overflow.get(0).rewards()).singleElement().satisfies(line->{
            assertThat(line.id()).isEqualTo(third.id());assertThat(line.count()).isEqualTo(1);
        });
    }
    @ParameterizedTest @ValueSource(strings={"forged","worn","consumed"})
    void replayNeverRemintsOrResetsLaterEquipmentState(String mutation) {
        String uid=player();mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑"),new MailStore.RewardLine("RESOURCE","GRAIN",8000,"粮草")));
        var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();Inventory saved=call.getArgument(1);
            if(saved.equipSnapshot().size()==1&&armed.compareAndSet(true,false))throw new Interrupted();return result;
        }).when(inventories).save(eq(uid),any(Inventory.class),anyLong());
        assertThatThrownBy(()->service.claimAll(uid,new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var bag=inventories.findByPlayerId(uid).orElseThrow();String original=bag.equipSnapshot().get(0).uid();int next=bag.nextEquipUid();
        switch(mutation){case "forged"->bag.forge(original,10);case "worn"->bag.markWorn(original,true);case "consumed"->bag.removeEquips(EQUIP,1);default->throw new AssertionError();}
        inventories.save(uid,bag,inventories.versionOf(uid));var expected=bag.equipSnapshot();
        rebuilt().list(uid);rebuilt().list(uid);
        var after=inventories.findByPlayerId(uid).orElseThrow();assertThat(after.equipSnapshot()).containsExactlyElementsOf(expected);assertThat(after.nextEquipUid()).isEqualTo(next);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);
    }
    @Test void fullMixedOverflowIsOneStablePackAndGenericRecoveryCannotSplitIt() {
        String uid=player();var bag=inventories.findByPlayerId(uid).orElseThrow();bag.add("item_mat_hero_frag_n",1,99);bag.setCapacityMax(1);inventories.save(uid,bag,inventories.versionOf(uid));
        var save=players.findByPlayerId(uid).orElseThrow();
        for(String resource:List.of("GRAIN","GOLD")){var state=save.resource(resource);save.putResource(resource,new PlayerResourceState(state.cap(),state.cap(),state.protectedAmount(),0,time.serverNow()));}
        players.save(save);
        String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑"),new MailStore.RewardLine("RESOURCE","GRAIN",8000,"粮草"),
                new MailStore.RewardLine("RESOURCE","GOLD",7,"金币"),new MailStore.RewardLine("ITEM",ITEM,2,"铁矿包")));
        var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();MailStore.MailRecord written=call.getArgument(0);
            if(written.sourceRef().equals("mail:"+mailId)&&armed.compareAndSet(true,false))throw new Interrupted();return result;
        }).when(store).save(any(MailStore.MailRecord.class));
        assertThatThrownBy(()->service.claimAll(uid,new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var overflow=store.listOf(uid,time.serverNow()).stream().filter(m->!m.mailId().equals(mailId)).toList();assertThat(overflow).hasSize(1);
        assertThat(overflow.get(0).rewards()).hasSize(4);
        rewards.recoverGoldClaims(uid);rewards.recoverItemClaims(uid);
        equipmentRecoveryClock.addAndGet(1000);rebuilt().list(uid);rebuilt().list(uid);
        assertThat(store.listOf(uid,time.serverNow()).stream().filter(m->!m.mailId().equals(mailId)).toList()).containsExactlyElementsOf(overflow);
        assertThat(equips(uid)).isEmpty();assertThat(store.findById(uid,mailId).orElseThrow().acceptedPlan()).isNull();
        bag=inventories.findByPlayerId(uid).orElseThrow();bag.setCapacityMax(10);inventories.save(uid,bag,inventories.versionOf(uid));
        save=players.findByPlayerId(uid).orElseThrow();for(String resource:List.of("GRAIN","GOLD")){var state=save.resource(resource);save.putResource(resource,new PlayerResourceState(0,state.cap(),state.protectedAmount(),0,time.serverNow()));}players.save(save);
        rebuilt().claimAll(uid,new MailClaimAllReq(id()));rebuilt().claimAll(uid,new MailClaimAllReq(id()));
        assertThat(equips(uid)).hasSize(1);assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(ITEM)).isEqualTo(2);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GOLD").current()).isEqualTo(7);
    }
    @Test void invalidLaterAttachmentRejectsEntirePlanBeforeMinting() {
        String uid=player();String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑"),new MailStore.RewardLine("ITEM","missing_equip_fixture",1,"错误附件")));
        assertThat(service.claimAll(uid,new MailClaimAllReq(id())).failed()).hasSize(1);assertThat(equips(uid)).isEmpty();
        var original=store.findById(uid,mailId).orElseThrow();assertThat(original.acceptedPlan()).isNull();assertThat(original.claimedAt()).isNull();
    }

    @Test void concurrentInventoryVersionReadCannotOverwriteLaterForgeState() {
        String uid=player();var original=inventories.findByPlayerId(uid).orElseThrow();String first=original.mintEquips(EQUIP,1).get(0);
        inventories.save(uid,original,inventories.versionOf(uid));mail(uid,List.of(new MailStore.RewardLine("ITEM","eq_leather_armor",1,"皮甲")));
        var armed=new AtomicBoolean(true);
        doAnswer(call->{long version=(long)call.callRealMethod();
            if(armed.compareAndSet(true,false)) {
                var concurrent=inventories.findByPlayerId(uid).orElseThrow();concurrent.forge(first,10);
                inventories.save(uid,concurrent,version);
                return call.callRealMethod();
            }
            return version;
        }).when(inventories).versionOf(uid);
        assertThat(service.claimAll(uid,new MailClaimAllReq(id())).failed()).isEmpty();
        var after=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(after.equipInstance(first).forgeLevel()).as("读取库存后再取新版本不能覆盖两者之间已保存的强化").isEqualTo(1);
        assertThat(after.equipSnapshot()).hasSize(2);
    }

    @Test void sameRequestRecoversBeforeDuplicateGuardAndDoesNotRemint() {
        String uid=player();String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑")));
        var request=new MailClaimAllReq(id());var armed=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();if(Boolean.TRUE.equals(result)&&armed.compareAndSet(true,false))throw new Interrupted();return result;
        }).when(store).acceptClaim(eq(uid),eq(mailId),anyLong(),any(MailClaimPlan.class));
        assertThatThrownBy(()->service.claimAll(uid,request)).isInstanceOf(Interrupted.class);
        assertThatThrownBy(()->rebuilt().claimAll(uid,request)).isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(equips(uid)).hasSize(1);var original=equips(uid);rebuilt().list(uid);assertThat(equips(uid)).containsExactlyElementsOf(original);
    }
    @Test void acceptedExpiredMixedPlanSurvivesConfigRemovalAndPurge() {
        String uid=player();String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑"),
                new MailStore.RewardLine("ITEM",ITEM,2,"铁矿包"),new MailStore.RewardLine("RESOURCE","GRAIN",8000,"粮草")));
        var original=store.findById(uid,mailId).orElseThrow();var plan=new MailResourceRecovery(store,configs,rewards).freeze(original,time.serverNow());
        assertThat(store.acceptClaim(uid,mailId,time.serverNow(),plan)).isTrue();
        doThrow(new com.ironoath.config.ConfigException("synthetic removed equipment row")).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,EQUIP);
        doThrow(new com.ironoath.config.ConfigException("synthetic removed ordinary item row")).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        doReturn(1L).when(configs).longParam("MAIL_RETENTION_DAYS");
        equipmentRecoveryClock.set(original.expireAt());store.purgeExpired(time.serverNow());
        assertThat(store.pendingOf(uid)).hasSize(1);rebuilt().list(uid);rebuilt().list(uid);
        assertThat(equips(uid)).hasSize(1);assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(ITEM)).isEqualTo(2);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);assertThat(store.pendingOf(uid)).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void completionFailureOrAcknowledgementLossNeverMintsTwice(boolean after) {
        String uid=player();String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑"),new MailStore.RewardLine("ITEM",ITEM,2,"铁矿包")));
        var armed=new AtomicBoolean(true);
        doAnswer(call->{if(armed.compareAndSet(true,false)){if(after)call.callRealMethod();throw new Interrupted();}return call.callRealMethod();
        }).when(store).completeAccepted(eq(uid),eq(mailId),eq(MailClaimPlan.keyOf(mailId)));
        assertThatThrownBy(()->service.claimAll(uid,new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var original=equips(uid);assertThat(original).hasSize(1);rebuilt().list(uid);rebuilt().list(uid);
        assertThat(equips(uid)).containsExactlyElementsOf(original);assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf(ITEM)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(longs={0,3})
    void acceptedOrdinaryItemClassificationMustNotTurnIntoEquipmentOnMongoRead(long existing) {
        String uid=player();var before=inventories.findByPlayerId(uid).orElseThrow();if(existing>0)before.add(ITEM,existing,999);
        before.setCapacityMax(1);inventories.save(uid,before,inventories.versionOf(uid));
        String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",ITEM,2,"铁矿包"),new MailStore.RewardLine("RESOURCE","GRAIN",8000,"粮草")));
        var source=store.findById(uid,mailId).orElseThrow();var plan=new MailResourceRecovery(store,configs,rewards).freeze(source,time.serverNow());
        assertThat(store.acceptClaim(uid,mailId,time.serverNow(),plan)).isTrue();
        var original=configs.get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        var changed=new com.ironoath.config.cfg.ItemCfg(original.id(),original.name(),com.ironoath.config.cfg.ItemCfg.Type.EQUIP,
                original.rarity(),original.effectKind(),original.effectValue(),original.effectTarget(),original.stackMax(),original.obtainFrom());
        doReturn(changed).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        rebuilt().list(uid);
        var firstRead=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(firstRead.snapshot()).as("已冻结普通道具仍保留原堆叠").containsEntry(ITEM,existing+2);
        assertThat(firstRead.equipSnapshot()).as("不能按后来配置铸造legacy装备").isEmpty();
        assertThat(firstRead.capacityUsed()).isEqualTo(1);
        rebuilt().list(uid);var after=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(after.snapshot()).containsEntry(ITEM,existing+2);assertThat(after.equipSnapshot()).isEmpty();assertThat(after.capacityUsed()).isEqualTo(1);
    }

    @Test void acceptedOrdinaryPlanDoesNotHideItsRewardBehindEquipmentCreatedInTheMeantime() {
        String uid=player();String mailId=mail(uid,List.of(new MailStore.RewardLine("ITEM",ITEM,2,"铁矿包"),new MailStore.RewardLine("RESOURCE","GRAIN",8000,"粮草")));
        var source=store.findById(uid,mailId).orElseThrow();var plan=new MailResourceRecovery(store,configs,rewards).freeze(source,time.serverNow());
        assertThat(store.acceptClaim(uid,mailId,time.serverNow(),plan)).isTrue();
        var original=configs.get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        doReturn(new com.ironoath.config.cfg.ItemCfg(original.id(),original.name(),com.ironoath.config.cfg.ItemCfg.Type.EQUIP,
                original.rarity(),original.effectKind(),original.effectValue(),original.effectTarget(),original.stackMax(),original.obtainFrom()))
                .when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        assertThat(new com.ironoath.web.reward.PlayerBag(inventories,configs).add(uid,ITEM,1)).isEqualTo(1);
        var intervening=equips(uid);assertThat(intervening).hasSize(1);
        assertThatThrownBy(()->rebuilt().list(uid)).isInstanceOf(IllegalStateException.class);
        assertThat(equips(uid)).containsExactlyElementsOf(intervening);assertThat(inventories.findByPlayerId(uid).orElseThrow().snapshot()).doesNotContainKey(ITEM);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isZero();
        assertThat(store.pendingOf(uid)).hasSize(1);
    }
}
