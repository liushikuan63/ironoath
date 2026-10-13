package com.ironoath.web.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.*;
import com.ironoath.web.dto.generated.*;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.mail.MailStore.*;

/** Real grant ports with injected process/acknowledgement boundaries, also inherited by Mongo tests. */
@SpringBootTest @ActiveProfiles("test")
class MailGrantRecoveryTest {
    static final String ITEM="item_res_iron_5k";
    @Autowired MailAppService service;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired InventoryRepository inventories;
    @SpyBean MailStore store;
    @SpyBean RewardService rewards;
    @SpyBean TimeService time;
    @SpyBean com.ironoath.config.ConfigRegistry configs;
    String uid,mailId; long now,beforeGold,beforeItems;
    @BeforeEach void setup(){
        now=System.currentTimeMillis(); doReturn(now).when(time).serverNow();
        uid=init.init(new PlayerInitReq(id(),"mail-recovery-"+id(),"邮件恢复验收",now,"")).playerId();
        mailId=id(); beforeGold=gold();beforeItems=items();
    }
    static String id(){return UUID.randomUUID().toString();}
    long gold(){return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();}
    long items(){return inventories.findByPlayerId(uid).map(b->b.countOf(ITEM)).orElse(0L);}
    void seed(List<RewardLine> lines){seed(lines,"SYSTEM");}
    void seed(List<RewardLine> lines,String kind){store.save(new MailRecord(mailId,uid,kind,"验收附件","断点测试",lines,"ops:recovery-fixture",now,now+86400000L,null,null));}
    List<RewardLine> mixed(){return List.of(new RewardLine("RESOURCE","GOLD",7,"金币"),new RewardLine("ITEM",ITEM,2,"铁矿包"));}
    MailClaimAllResp claim(){return service.claimAll(uid,new MailClaimAllReq(id()));}
    void assertOnce(){
        assertThat(claim().failed()).isEmpty();
        assertThat(gold()).isEqualTo(beforeGold+7);assertThat(items()).isEqualTo(beforeItems+2);
        assertThat(store.findById(uid,mailId).orElseThrow().claimedAt()).isEqualTo(now);
        assertThat(claim().claimed()).isZero();
        assertThat(gold()).isEqualTo(beforeGold+7);assertThat(items()).isEqualTo(beforeItems+2);
    }
    @Test void processStopsBeforeAnyRewardMustLeaveAttachmentClaimable(){
        seed(mixed());doThrow(new AssertionError("fixture: process stopped before reward")).when(rewards).grant(anyString(),any(),any());
        assertThatThrownBy(this::claim).isInstanceOf(AssertionError.class);
        assertThat(store.findById(uid,mailId).orElseThrow().claimedAt()).isNull();
        reset(rewards);assertOnce();
    }
    @ParameterizedTest @ValueSource(strings={"free_gold","free_item"})
    void acceptedComponentWithLostReplyDoesNotRepeatAfterRetry(String source){
        seed(mixed());var once=new AtomicBoolean(true);
        doAnswer(call->{var result=call.callRealMethod();RewardContext ctx=call.getArgument(2);
            if(ctx.source().equals(source)&&once.getAndSet(false))throw new IllegalStateException("fixture: reward committed reply lost");return result;
        }).when(rewards).grant(anyString(),any(),any());
        assertThat(claim().failed()).hasSize(1);assertOnce();
    }
    @Test void processStopsBetweenGoldAndItemsDoesNotDuplicateGold(){
        seed(mixed());var once=new AtomicBoolean(true);
        doAnswer(call->{RewardContext ctx=call.getArgument(2);if(ctx.durableItems()&&once.getAndSet(false))throw new AssertionError("fixture: process stopped between components");return call.callRealMethod();
        }).when(rewards).grant(anyString(),any(),any());
        assertThatThrownBy(this::claim).isInstanceOf(AssertionError.class);
        assertThat(gold()).isEqualTo(beforeGold+7);assertThat(items()).isEqualTo(beforeItems);
        assertOnce();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void mailClaimWriteFailureAndLostReplyPreserveAcceptedAssets(boolean after){
        seed(mixed());var once=new AtomicBoolean(true);
        doAnswer(call->{if(once.getAndSet(false)){if(after)call.callRealMethod();throw new IllegalStateException("fixture: mail mark boundary");}return call.callRealMethod();
        }).when(store).claim(eq(uid),eq(mailId),anyLong());
        assertThat(claim().failed()).hasSize(1);assertOnce();
    }
    @Test void duplicateLinesAreAcceptedAsOneCanonicalPack(){
        seed(List.of(new RewardLine("ITEM",ITEM,1,"铁矿包"),new RewardLine("RESOURCE","GOLD",3,"金币"),new RewardLine("ITEM",ITEM,1,"铁矿包"),new RewardLine("RESOURCE","GOLD",4,"金币")));
        assertOnce();
    }
    @Test void invalidItemInMixedMailMustNotCreditGoldOrMarkClaimed(){
        seed(List.of(mixed().get(0),new RewardLine("ITEM","item_missing",2,"错误附件")));
        assertThat(claim().failed()).hasSize(1);assertThat(claim().failed()).hasSize(1);
        assertThat(gold()).isEqualTo(beforeGold);assertThat(store.findById(uid,mailId).orElseThrow().claimedAt()).isNull();
    }
    @Test void expiredMailAndHistoricalClaimedMailAreNeverGuessedOrExtended(){
        seed(mixed());store.claim(uid,mailId,now);
        assertThat(claim().claimed()).isZero();assertThat(gold()).isEqualTo(beforeGold);
        mailId=id();seed(mixed());doReturn(now+86400001L).when(time).serverNow();
        assertThat(claim().claimed()).isZero();assertThat(gold()).isEqualTo(beforeGold);
        assertThat(store.findById(uid,mailId).orElseThrow().expireAt()).isEqualTo(now+86400000L);
    }
    @ParameterizedTest @ValueSource(strings={"SYSTEM","BATTLE_PASS","OVERFLOW"})
    void fullBagKeepsOneOverflowMailAcrossLostReplyAndThenActuallyClaimsIt(String kind){
        inventories.insertIfAbsent(uid,com.ironoath.core.bag.Inventory.empty(0));
        var bag=inventories.findByPlayerId(uid).orElseThrow();
        bag.setCapacityMax(bag.capacityUsed());inventories.save(uid,bag,inventories.versionOf(uid));
        seed(mixed(),kind);
        assertThat(store.findById(uid,mailId).orElseThrow().kind()).isEqualTo(kind);
        var once=new AtomicBoolean(true);
        doAnswer(call->{var result=call.callRealMethod();RewardContext ctx=call.getArgument(2);
            if(ctx.durableItems()&&once.getAndSet(false))throw new IllegalStateException("fixture: overflow delivery reply lost");return result;
        }).when(rewards).grant(anyString(),any(),any());
        assertThat(claim().failed()).hasSize(1);assertThat(claim().claimed()).isGreaterThanOrEqualTo(1);
        assertThat(gold()).isEqualTo(beforeGold+7);assertThat(items()).isEqualTo(beforeItems);
        var pending=store.listOf(uid,now).stream().filter(m->m.claimable(now)).toList();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).text()).contains("邮件附件奖励").doesNotContain("free_item","ops:",mailId);
        var reopened=inventories.findByPlayerId(uid).orElseThrow();reopened.setCapacityMax(60);
        inventories.save(uid,reopened,inventories.versionOf(uid));
        assertThat(claim().failed()).isEmpty();assertThat(items()).isEqualTo(beforeItems+2);
        assertThat(claim().claimed()).isZero();assertThat(gold()).isEqualTo(beforeGold+7);
    }
    @Test void concurrentDifferentRequestsCannotDuplicateAnAttachment() throws Exception {
        seed(mixed());var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(this::claim);var second=executor.submit(this::claim);
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).claimed()+second.get(10,java.util.concurrent.TimeUnit.SECONDS).claimed()).isEqualTo(1);
            assertThat(gold()).isEqualTo(beforeGold+7);assertThat(items()).isEqualTo(beforeItems+2);
        } finally {executor.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void acceptedReceiptsMustNotDowngradeOrStallWhenItemConfigChanges(boolean equipment){
        seed(mixed());var once=new AtomicBoolean(true);
        doAnswer(call->{if(once.getAndSet(false))throw new IllegalStateException("fixture: final mail mark not saved");return call.callRealMethod();
        }).when(store).claim(eq(uid),eq(mailId),anyLong());
        assertThat(claim().failed()).hasSize(1);
        var original=configs.get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        if(equipment) {
            var changed=new com.ironoath.config.cfg.ItemCfg(original.id(),original.name(),com.ironoath.config.cfg.ItemCfg.Type.EQUIP,
                    original.rarity(),original.effectKind(),original.effectValue(),original.effectTarget(),original.stackMax(),original.obtainFrom());
            doReturn(changed).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        } else doThrow(new com.ironoath.config.ConfigException("fixture: item removed by config reload")).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        assertOnce();
    }
    @Test void partiallyAcceptedGoldMustNeverDowngradeWhenUnacceptedItemBecomesEquipment(){
        seed(mixed());var once=new AtomicBoolean(true);
        doAnswer(call->{RewardContext ctx=call.getArgument(2);if(ctx.durableItems()&&once.getAndSet(false))throw new IllegalStateException("fixture: item not yet accepted");return call.callRealMethod();
        }).when(rewards).grant(anyString(),any(),any());
        assertThat(claim().failed()).hasSize(1);
        var original=configs.get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        var changed=new com.ironoath.config.cfg.ItemCfg(original.id(),original.name(),com.ironoath.config.cfg.ItemCfg.Type.EQUIP,
                original.rarity(),original.effectKind(),original.effectValue(),original.effectTarget(),original.stackMax(),original.obtainFrom());
        doReturn(changed).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        assertThat(claim().failed()).hasSize(1);assertThat(gold()).isEqualTo(beforeGold+7);
        assertThat(items()).isEqualTo(beforeItems);assertThat(store.findById(uid,mailId).orElseThrow().claimedAt()).isNull();
        doReturn(original).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);assertOnce();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void pendingOverflowWithRemovedItemConfigMustCompleteFirstDeliveryOrReuseItsStoredMail(boolean after){
        inventories.insertIfAbsent(uid,com.ironoath.core.bag.Inventory.empty(0));
        seed(List.of(mixed().get(1)));var once=new AtomicBoolean(true);
        doAnswer(call->{MailRecord mail=call.getArgument(0);if("OVERFLOW".equals(mail.kind())&&once.getAndSet(false)){
            if(after)call.callRealMethod();throw new IllegalStateException("fixture: overflow mail save boundary");}return call.callRealMethod();
        }).when(store).save(any(MailRecord.class));
        assertThat(claim().failed()).hasSize(1);
        var original=configs.get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        doThrow(new com.ironoath.config.ConfigException("fixture: removed item config")).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        var recovered=claim();
        assertThat(recovered.failed()).noneMatch(f->f.mailId().equals(mailId));
        // The pre-existing overflow is also in this request's snapshot; its new bag receipt cannot
        // be accepted without a valid item row. Keep that separate mail pending, never erase it.
        assertThat(recovered.failed()).hasSize(after?1:0);
        assertThat(store.findById(uid,mailId).orElseThrow().claimedAt()).isEqualTo(now);
        var overflow=store.listOf(uid,now).stream().filter(m->m.claimable(now)).toList();
        assertThat(overflow).hasSize(1);
        assertThat(overflow.get(0).text()).doesNotContain(ITEM,"free_item","ops:");
        doReturn(original).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,ITEM);
        var bag=inventories.findByPlayerId(uid).orElseThrow();bag.setCapacityMax(60);inventories.save(uid,bag,inventories.versionOf(uid));
        assertThat(claim().failed()).isEmpty();assertThat(items()).isEqualTo(beforeItems+2);
        assertThat(claim().claimed()).isZero();
    }
}
