package com.ironoath.web;

import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：通用奖励发放器的<b>端到端</b>集成测试 —— 验证它真的作用在玩家存档与背包上。
 * 依赖：Spring Boot Test，test profile（内存存储，不需要 MongoDB / Redis）。
 *
 * <p>与 {@code RewardGrantorTest}（game-core，用内存替身验证发放器自身逻辑）的分工：
 * 那边验证「发放器的语义对不对」，这边验证「发放器接到真实存档上之后还对不对」。
 * 后者是必需的 —— PlayerWallet / PlayerBag 这两个适配器有自己的惰性结算与上限逻辑，
 * 用替身测不到它们。
 */
@SpringBootTest
@ActiveProfiles("test")
class RewardGrantIntegrationTest {

    @Autowired
    private RewardService rewardService;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private PlayerRepository players;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private InventoryRepository inventories;

    @Autowired private com.ironoath.web.reward.PlayerWallet wallet;
    @Autowired private com.ironoath.core.reward.RewardPorts.Mailbox mailbox;
    @Autowired private com.ironoath.web.mail.MailStore mailStore;
    @Autowired private com.ironoath.web.mail.MailAppService mails;
    @Autowired private com.ironoath.common.time.TimeService time;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryInventoryStore) inventories).clear();
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "发奖测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static RewardContext ctx() {
        return RewardContext.toMail("activity", "activity_login_7d", "trace-reward-test");
    }

    private long woodOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("WOOD").current();
    }

    @Test
    @DisplayName("新免费金币按业务key永久幂等，重试和配置额度变化不再增发")
    void freeGoldUsesDurableBusinessKeyRatherThanRequestRetry() {
        String playerId = newPlayer();
        long before = players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current();
        RewardContext business = RewardContext.toMail("free_gold", "signin", "signin:20261013");
        rewardService.grant(playerId, List.of(new RewardItem(RewardType.RESOURCE, "GOLD", 20)), business);
        rewardService.grant(playerId, List.of(new RewardItem(RewardType.RESOURCE, "GOLD", 20)), business);
        rewardService.grant(playerId, List.of(new RewardItem(RewardType.RESOURCE, "GOLD", 30)), business);
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current())
                .as("换外层请求号/重放/配置更新都不能重发同一天的钱")
                .isEqualTo(before + 20);
    }

    @Test
    @DisplayName("溢出邮件创建后崩溃、已被领取后再恢复，不重置邮件或重复增发")
    void overflowRecoveryKeepsAlreadyRedeemedMailAndMoney() {
        String playerId = newPlayer();
        long now = time.serverNow();
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(999990, 1000000, 0, 0, now));
        players.save(save);
        RewardContext ctx = RewardContext.toMail("free_gold", "signin", "signin:20261013");
        wallet.grantGoldOnce(playerId, 20, ctx, now);
        String mailId = mailbox.sendOverflow(playerId,
                List.of(new RewardItem(RewardType.RESOURCE, "GOLD", 10)), ctx);
        assertThat(wallet.pendingGoldClaims(playerId)).hasSize(1);
        assertThat(mailStore.findById(playerId, mailId).orElseThrow().text())
                .contains("签到与成长奖励").doesNotContain("free_gold", "signin", "20261013");
        // 模拟已创建邮件、原收据尚未更新的崩溃窗口。正常花费后通过真实邮件服务领取。
        assertThat(wallet.deduct(playerId, "GOLD", 20, time.serverNow())).isEqualTo(20);
        var claimed = mails.claimAll(playerId,
                new com.ironoath.web.dto.generated.MailClaimAllReq("req-" + UUID.randomUUID()));
        assertThat(claimed.claimed()).isEqualTo(1);
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current()).isEqualTo(999990);
        assertThat(mailStore.findById(playerId, mailId).orElseThrow().claimedAt()).isNotNull();
        rewardService.recoverGoldClaims(playerId);
        rewardService.recoverGoldClaims(playerId);
        assertThat(wallet.pendingGoldClaims(playerId)).isEmpty();
        assertThat(mailStore.listOf(playerId, time.serverNow())).hasSize(1);
        assertThat(mailStore.findById(playerId, mailId).orElseThrow().claimedAt()).isNotNull();
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current()).isEqualTo(999990);
        assertThat(mails.claimAll(playerId,
                new com.ironoath.web.dto.generated.MailClaimAllReq("req-" + UUID.randomUUID())).claimed()).isZero();
    }

    @Test
    @DisplayName("新幂等金币通道拒绝混合道具，不能入账一半再进入普通补偿")
    void durableGoldRejectsMixedRewardsBeforeAnyCredit() {
        String playerId = newPlayer();
        long before = players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current();
        assertThatThrownBy(() -> rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "GOLD", 20),
                new RewardItem(RewardType.ITEM, "item_speedup_build_1h", 1)),
                RewardContext.toMail("free_gold", "signin", "signin:20261013")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current()).isEqualTo(before);
        assertThat(players.findByPlayerId(playerId).orElseThrow().goldClaims()).isEmpty();
    }

    @Test
    @DisplayName("发资源奖励真的写进了玩家存档，且数值精确")
    void resourceRewardLandsInPlayerSave() {
        String playerId = newPlayer();
        long before = woodOf(playerId);

        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 1234L)), ctx());

        assertThat(result.isClean()).isTrue();
        assertThat(result.granted()).containsExactly(new RewardItem(RewardType.RESOURCE, "WOOD", 1234L));
        assertThat(woodOf(playerId)).isEqualTo(before + 1234L);
    }

    @Test
    @DisplayName("发道具奖励真的进了背包，且受 item 表的堆叠上限约束")
    void itemRewardLandsInInventoryRespectingStackMax() {
        String playerId = newPlayer();

        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.ITEM, "item_speedup_build_1h", 5L)), ctx());

        assertThat(result.isClean()).isTrue();
        Inventory bag = inventories.findByPlayerId(playerId).orElseThrow();
        assertThat(bag.countOf("item_speedup_build_1h")).isEqualTo(5L);
        assertThat(bag.capacityMax()).as("新号背包容量来自 global.BAG_INITIAL_CAPACITY").isEqualTo(100);

        // item 表里 item_gold_1000 的 stackMax 是 999，发 1500 个应溢出 501
        GrantResult overflowed = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.ITEM, "item_gold_1000", 1500L)), ctx());
        assertThat(overflowed.granted()).containsExactly(
                new RewardItem(RewardType.ITEM, "item_gold_1000", 999L));
        assertThat(overflowed.overflow()).containsExactly(
                new RewardItem(RewardType.ITEM, "item_gold_1000", 501L));
        assertThat(overflowed.mailId()).as("溢出应转邮件").isNotNull();
    }

    @Test
    @DisplayName("资源超上限时溢出转邮件，存档里的量不超过容量")
    void resourceOverflowGoesToMail() {
        String playerId = newPlayer();
        long cap = players.findByPlayerId(playerId).orElseThrow().resource("WOOD").cap();
        long before = woodOf(playerId);

        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", cap * 2)), ctx());

        assertThat(woodOf(playerId)).as("不得超过容量上限").isEqualTo(cap);
        assertThat(result.granted()).containsExactly(
                new RewardItem(RewardType.RESOURCE, "WOOD", cap - before));
        assertThat(result.overflow()).containsExactly(
                new RewardItem(RewardType.RESOURCE, "WOOD", cap * 2 - (cap - before)));
        assertThat(result.mailId()).isNotNull();
    }

    @Test
    @DisplayName("未落地的奖励类型响亮失败并进补偿队列，而不是静默返回成功")
    void unsupportedRewardTypeFailsLoudlyIntoCompensation() {
        String playerId = newPlayer();
        long woodBefore = woodOf(playerId);

        // 刻意不放 STAMINA：它在 B09 已经落地成 resource 表的一行，走 Wallet 能正常发放，
        // 而新号体力是满的（发放会溢出转邮件）—— 放进这个用例只会引入与本用例无关的分支。
        // 体力发放由 StaminaEndpointTest 与 RewardGrantorTest 覆盖
        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 100L),
                new RewardItem(RewardType.HERO_FRAGMENT, "SSR", 5L),
                new RewardItem(RewardType.PRIVILEGE, "vip_exp", 1L)), ctx());

        // 已落地的类型照常发放（尽力发放语义）
        assertThat(result.granted()).containsExactly(new RewardItem(RewardType.RESOURCE, "WOOD", 100L));
        assertThat(woodOf(playerId)).isEqualTo(woodBefore + 100L);

        // 仍未落地的两类（武将碎片要等碎片系统、特权要等 B15）进补偿队列，留下可查凭证
        assertThat(result.hasCompensation()).isTrue();
        assertThat(result.overflow()).as("抛异常不等于溢出，不应转邮件").isEmpty();
    }

    @Test
    @DisplayName("发放顺序即入参顺序：客户端飘字按此排队播放，不可同时堆叠")
    void grantOrderIsPreserved() {
        String playerId = newPlayer();

        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "IRON", 10L),
                new RewardItem(RewardType.ITEM, "item_speedup_build_5m", 2L),
                new RewardItem(RewardType.RESOURCE, "WOOD", 20L)), ctx());

        assertThat(result.granted()).extracting(RewardItem::id)
                .containsExactly("IRON", "item_speedup_build_5m", "WOOD");
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_speedup_build_5m")).isEqualTo(2L);
    }

    @Test
    @DisplayName("混合批次里某条溢出不会影响其它条发放")
    void partialOverflowDoesNotBlockOtherRewards() {
        String playerId = newPlayer();
        long cap = players.findByPlayerId(playerId).orElseThrow().resource("WOOD").cap();
        // 先把木材填满
        rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", cap)), ctx());

        GrantResult result = rewardService.grant(playerId, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 500L),
                new RewardItem(RewardType.RESOURCE, "STONE", 500L),
                new RewardItem(RewardType.ITEM, "item_res_iron_5k", 1L)), ctx());

        // 木材溢出，但石料与道具照常入账
        assertThat(result.granted()).extracting(RewardItem::id).containsExactly("STONE", "item_res_iron_5k");
        assertThat(result.overflow()).extracting(RewardItem::id).containsExactly("WOOD");
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("STONE").current())
                .isGreaterThan(5000L);
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_res_iron_5k")).isEqualTo(1L);
    }

    private List<RewardItem> materialPair() {return List.of(new RewardItem(RewardType.ITEM,"item_speedup_build_1h",1),new RewardItem(RewardType.ITEM,"item_res_wood_10k",1));}
    @Test void paidMaterialPairIsOnceByBusinessKeyEvenAfterConsumption() {
        String uid=newPlayer();var ctx=RewardContext.toMail("paid_item","order1","monthly:daily:order1:1");
        rewardService.grant(uid,materialPair(),ctx);rewardService.grant(uid,materialPair(),ctx);
        var bag=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(bag.countOf("item_speedup_build_1h")).isEqualTo(1);assertThat(bag.countOf("item_res_wood_10k")).isEqualTo(1);
        bag.remove("item_speedup_build_1h",1);bag.remove("item_res_wood_10k",1);inventories.save(uid,bag,inventories.versionOf(uid));
        rewardService.grant(uid,materialPair(),ctx);assertThat(inventories.findByPlayerId(uid).orElseThrow().snapshot()).isEmpty();
    }
    @Test void acceptedInventorySaveThenLostAcknowledgementDoesNotRepeatEitherItem() {
        String uid=newPlayer();var ctx=RewardContext.toMail("paid_item","order1","monthly:daily:order1:1");
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();if(uid.equals(inv.getArgument(0))&&once.compareAndSet(false,true))throw new IllegalStateException("fixture: accepted inventory write then acknowledgement lost");return result;})
            .when(inventories).save(org.mockito.ArgumentMatchers.eq(uid),org.mockito.ArgumentMatchers.any(Inventory.class),org.mockito.ArgumentMatchers.anyLong());
        assertThatThrownBy(()->rewardService.grant(uid,materialPair(),ctx)).isInstanceOf(IllegalStateException.class);
        rewardService.grant(uid,materialPair(),ctx);var bag=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(bag.countOf("item_speedup_build_1h")).isEqualTo(1);assertThat(bag.countOf("item_res_wood_10k")).isEqualTo(1);
    }
    @Test void materialOverflowMailClaimLostAcknowledgementAndRecoveryKeepBothItemsOnce() {
        String uid=newPlayer();long now=time.serverNow();inventories.insertIfAbsent(uid,Inventory.empty(0));var bag=inventories.findByPlayerId(uid).orElseThrow();bag.setCapacityMax(0);inventories.save(uid,bag,inventories.versionOf(uid));
        var ctx=RewardContext.toMail("paid_item","order1","monthly:daily:order1:1");
        var granted=rewardService.grant(uid,materialPair(),ctx);assertThat(granted.overflow()).hasSize(2);String mailId=granted.mailId();
        assertThat(mailStore.findById(uid,mailId).orElseThrow().text()).doesNotContain("paid_item","order1","monthly:");
        bag=inventories.findByPlayerId(uid).orElseThrow();bag.setCapacityMax(2);inventories.save(uid,bag,inventories.versionOf(uid));
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();if(uid.equals(inv.getArgument(0))&&once.compareAndSet(false,true))throw new IllegalStateException("fixture: accepted mail attachment inventory write then acknowledgement lost");return result;})
            .when(inventories).save(org.mockito.ArgumentMatchers.eq(uid),org.mockito.ArgumentMatchers.any(Inventory.class),org.mockito.ArgumentMatchers.anyLong());
        var failed=mails.claimAll(uid,new com.ironoath.web.dto.generated.MailClaimAllReq("req-"+UUID.randomUUID()));assertThat(failed.failed()).hasSize(1);
        var retried=mails.claimAll(uid,new com.ironoath.web.dto.generated.MailClaimAllReq("req-"+UUID.randomUUID()));assertThat(retried.claimed()).isEqualTo(1);
        rewardService.grant(uid,materialPair(),ctx);bag=inventories.findByPlayerId(uid).orElseThrow();
        assertThat(bag.countOf("item_speedup_build_1h")).isEqualTo(1);assertThat(bag.countOf("item_res_wood_10k")).isEqualTo(1);
        assertThat(mailStore.findById(uid,mailId).orElseThrow().claimedAt()).isNotNull();assertThat(mailStore.listOf(uid,now)).hasSize(1);
    }
}
