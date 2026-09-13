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

    @Autowired
    private InventoryRepository inventories;

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
}
