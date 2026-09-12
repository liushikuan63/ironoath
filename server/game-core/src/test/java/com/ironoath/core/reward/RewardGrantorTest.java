package com.ironoath.core.reward;

import com.ironoath.core.chest.ChestOpener;
import com.ironoath.core.reward.InMemoryRewardPorts.InMemoryBag;
import com.ironoath.core.reward.InMemoryRewardPorts.InMemoryCompensation;
import com.ironoath.core.reward.InMemoryRewardPorts.InMemoryExtras;
import com.ironoath.core.reward.InMemoryRewardPorts.InMemoryMailbox;
import com.ironoath.core.reward.InMemoryRewardPorts.InMemoryWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：通用奖励发放器与开箱器单测 —— 覆盖 B04 验收 4、6、7、10 与尽力发放语义。
 * 依赖：JUnit 5 + AssertJ、{@link InMemoryRewardPorts}。<b>无 Spring、无数据库</b>，
 *       这本身就是「RewardService 不得直接操作数据库」这条禁止项的可执行证明。
 */
class RewardGrantorTest {

    private static final String PLAYER = "P_TEST";
    private static final long NOW = 1_788_000_000_000L;

    private InMemoryWallet wallet;
    private InMemoryBag bag;
    private InMemoryMailbox mailbox;
    private InMemoryCompensation compensation;
    private InMemoryExtras extras;
    private RewardGrantor grantor;

    @BeforeEach
    void setUp() {
        wallet = new InMemoryWallet();
        bag = new InMemoryBag();
        mailbox = new InMemoryMailbox();
        compensation = new InMemoryCompensation();
        extras = new InMemoryExtras();
        // 时间源注入而不是让发放器读系统时钟：game-core 禁止 System.currentTimeMillis()
        grantor = new RewardGrantor(wallet, bag, mailbox, compensation, extras, () -> NOW);
    }

    private static RewardContext ctx() {
        return RewardContext.toMail("quest", "quest_main_09", "trace-test");
    }

    // ---------- 发放主路径 ----------

    @Test
    @DisplayName("全部发放成功：无溢出、无补偿、无邮件")
    void grantSucceedsCleanly() {
        wallet.seed(PLAYER, "WOOD", 1000L, 20000L, 0L);
        bag.seedStackMax("item_speedup_build_1h", 999L);

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 500L),
                new RewardItem(RewardType.ITEM, "item_speedup_build_1h", 3L)), ctx());

        assertThat(result.isClean()).isTrue();
        assertThat(result.granted()).hasSize(2);
        assertThat(wallet.available(PLAYER, "WOOD", NOW)).isEqualTo(1500L);
        assertThat(bag.countOf(PLAYER, "item_speedup_build_1h")).isEqualTo(3L);
        assertThat(mailbox.sent()).isEmpty();
        assertThat(compensation.entries()).isEmpty();
    }

    @Test
    @DisplayName("验收2：资源超上限时溢出部分转邮件，邮件正文携带溢出明细与来源")
    void overflowGoesToMailWithDetails() {
        wallet.seed(PLAYER, "WOOD", 19_800L, 20_000L, 0L);

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 1000L)), ctx());

        assertThat(wallet.available(PLAYER, "WOOD", NOW)).as("不得超过容量上限").isEqualTo(20_000L);
        assertThat(result.granted()).containsExactly(new RewardItem(RewardType.RESOURCE, "WOOD", 200L));
        assertThat(result.overflow()).containsExactly(new RewardItem(RewardType.RESOURCE, "WOOD", 800L));
        assertThat(result.hasOverflow()).isTrue();

        InMemoryMailbox.OverflowMail mail = mailbox.last();
        assertThat(mail).isNotNull();
        assertThat(mail.mailId()).isEqualTo(result.mailId());
        assertThat(mail.overflow()).containsExactly(new RewardItem(RewardType.RESOURCE, "WOOD", 800L));
        assertThat(mail.source()).as("邮件必须能追溯到来源系统").isEqualTo("quest");
        assertThat(mail.sourceRef()).isEqualTo("quest_main_09");
    }

    @Test
    @DisplayName("背包堆叠上限导致的溢出同样转邮件")
    void stackLimitOverflowGoesToMail() {
        bag.seedStackMax("item_res_wood_10k", 5L);
        bag.seedItem(PLAYER, "item_res_wood_10k", 4L);

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.ITEM, "item_res_wood_10k", 10L)), ctx());

        assertThat(bag.countOf(PLAYER, "item_res_wood_10k")).as("不得超过堆叠上限 5").isEqualTo(5L);
        assertThat(result.granted()).containsExactly(new RewardItem(RewardType.ITEM, "item_res_wood_10k", 1L));
        assertThat(result.overflow()).containsExactly(new RewardItem(RewardType.ITEM, "item_res_wood_10k", 9L));
        assertThat(result.mailId()).isNotNull();
    }

    @Test
    @DisplayName("ctx 禁止转邮件时，溢出被丢弃且不发邮件")
    void overflowDiscardedWhenContextForbidsMail() {
        wallet.seed(PLAYER, "WOOD", 20_000L, 20_000L, 0L);

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 500L)),
                RewardContext.discardOverflow("ranking", "season_01", "trace-test"));

        assertThat(result.overflow()).hasSize(1);
        assertThat(result.mailId()).as("禁止转邮件时不应产生邮件").isNull();
        assertThat(mailbox.sent()).isEmpty();
    }

    @Test
    @DisplayName("验收7：某条奖励抛异常时不静默 —— 其余照常发放，失败项进补偿队列并返回凭证")
    void failureIsRecordedNotSwallowed() {
        wallet.seed(PLAYER, "WOOD", 0L, 20_000L, 0L);
        // 体力在 B09 落成了 resource 表的一行，发放走 Wallet 而不是 Extras ——
        // Wallet 的实现已经保证「绝不加到超过上限」，那正是 B09 §5 的溢出不超上限
        wallet.seed(PLAYER, "STAMINA", 0L, 100L, 0L);
        extras.reject(RewardType.HERO_FRAGMENT, "SSR");

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.RESOURCE, "WOOD", 300L),
                new RewardItem(RewardType.HERO_FRAGMENT, "SSR", 5L),
                new RewardItem(RewardType.STAMINA, "STAMINA", 20L)), ctx());

        // 失败项前后的奖励都必须发放成功：尽力发放，不是全有或全无
        assertThat(result.granted()).extracting(RewardItem::id).containsExactly("WOOD", "STAMINA");
        assertThat(wallet.available(PLAYER, "WOOD", NOW)).isEqualTo(300L);
        assertThat(wallet.available(PLAYER, "STAMINA", NOW)).isEqualTo(20L);

        assertThat(result.hasCompensation()).isTrue();
        InMemoryCompensation.Entry entry = compensation.entries().get(0);
        assertThat(entry.compensationId()).isEqualTo(result.compensationId());
        assertThat(entry.failed()).containsExactly(new RewardItem(RewardType.HERO_FRAGMENT, "SSR", 5L));
        assertThat(entry.source()).isEqualTo("quest");
        assertThat(entry.traceId()).isEqualTo("trace-test");
        assertThat(entry.cause()).contains("模拟发放失败");
    }

    @Test
    @DisplayName("发放顺序即入参顺序：客户端飘字按此顺序排队播放，不可同时堆叠")
    void grantedOrderMatchesRequestOrder() {
        wallet.seed(PLAYER, "WOOD", 0L, 20_000L, 0L);
        wallet.seed(PLAYER, "STONE", 0L, 20_000L, 0L);
        wallet.seed(PLAYER, "IRON", 0L, 10_000L, 0L);

        GrantResult result = grantor.grant(PLAYER, List.of(
                new RewardItem(RewardType.RESOURCE, "IRON", 10L),
                new RewardItem(RewardType.RESOURCE, "WOOD", 20L),
                new RewardItem(RewardType.RESOURCE, "STONE", 30L)), ctx());

        assertThat(result.granted()).extracting(RewardItem::id)
                .containsExactly("IRON", "WOOD", "STONE");
    }

    @Test
    @DisplayName("入参校验：空 playerId、null ctx、非正数量都被拒绝")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> grantor.grant(null, List.of(), ctx()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grantor.grant(PLAYER, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法归因");
        assertThatThrownBy(() -> new RewardGrantor(wallet, bag, mailbox, compensation, extras, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不读系统时钟");
        assertThatThrownBy(() -> new RewardItem(RewardType.RESOURCE, "WOOD", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RewardItem(RewardType.RESOURCE, "WOOD", -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为正");
        assertThatThrownBy(() -> new RewardContext("", "ref", "trace", true))
                .isInstanceOf(IllegalArgumentException.class);
        // 空奖励列表是合法的 no-op，不该抛异常
        assertThat(grantor.grant(PLAYER, List.of(), ctx()).isClean()).isTrue();
    }

    // ---------- 验收6：保护量 ----------

    @Test
    @DisplayName("验收6：保护量内的资源不可被掠夺，掠夺只吃掉非保护部分")
    void protectedAmountSurvivesPlunder() {
        // 容量 20000、持有 15000、保护量 6000 ⇒ 可掠夺 9000
        wallet.seed(PLAYER, "WOOD", 15_000L, 20_000L, 6_000L);

        long available = wallet.available(PLAYER, "WOOD", NOW);
        long protectedAmount = wallet.protectedAmount(PLAYER, "WOOD", NOW);
        long plunderable = Math.max(0L, available - protectedAmount);
        assertThat(plunderable).isEqualTo(9_000L);

        // 掠夺方负载只有 5000 ⇒ 实际掠走 min(可掠夺量, 负载) = 5000（B00 掠夺公式）
        long lootCapacity = 5_000L;
        long looted = Math.min(plunderable, lootCapacity);
        assertThat(wallet.deduct(PLAYER, "WOOD", looted, NOW)).isEqualTo(looted);
        assertThat(wallet.available(PLAYER, "WOOD", NOW)).isEqualTo(10_000L);
        assertThat(wallet.available(PLAYER, "WOOD", NOW))
                .as("剩余量不得低于保护量").isGreaterThanOrEqualTo(protectedAmount);

        // 试图掠夺超过可掠夺量：只能拿到剩下的非保护部分，保护量始终保留
        long rest = wallet.deduct(PLAYER, "WOOD", 99_999L, NOW);
        assertThat(rest).as("持有 10000 不足 99999，扣减必须原子失败").isZero();
        assertThat(wallet.available(PLAYER, "WOOD", NOW)).isEqualTo(10_000L);
    }

    // ---------- 验收10：道具数量校验 ----------

    @Test
    @DisplayName("验收10：道具 count 超过持有量时拒绝，不扣成负数")
    void itemRemovalRejectsInsufficientCount() {
        bag.seedStackMax("item_speedup_build_1h", 999L);
        bag.seedItem(PLAYER, "item_speedup_build_1h", 3L);

        assertThat(bag.remove(PLAYER, "item_speedup_build_1h", 5L))
                .as("持有 3 个却要用 5 个，必须整体拒绝").isZero();
        assertThat(bag.countOf(PLAYER, "item_speedup_build_1h"))
                .as("拒绝后持有量不得变化，更不得为负").isEqualTo(3L);

        assertThat(bag.remove(PLAYER, "item_speedup_build_1h", 3L)).isEqualTo(3L);
        assertThat(bag.countOf(PLAYER, "item_speedup_build_1h")).isZero();
        assertThat(bag.remove(PLAYER, "item_speedup_build_1h", 1L)).isZero();
        assertThat(bag.countOf(PLAYER, "item_speedup_build_1h")).isZero();
    }

    @Test
    @DisplayName("扣减必须原子：资源不足时完全不扣，不会出现扣一半")
    void deductionIsAtomic() {
        wallet.seed(PLAYER, "IRON", 100L, 10_000L, 0L);
        assertThat(wallet.deduct(PLAYER, "IRON", 250L, NOW)).isZero();
        assertThat(wallet.available(PLAYER, "IRON", NOW)).isEqualTo(100L);
        assertThat(wallet.deduct(PLAYER, "IRON", 100L, NOW)).isEqualTo(100L);
        assertThat(wallet.available(PLAYER, "IRON", NOW)).isZero();
    }

    // ---------- 验收4：开箱可复现 ----------

    private static ChestOpener.Table chestTable() {
        return new ChestOpener.Table("item_chest_resource", List.of(
                new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 40L, 1000L, false),
                new ChestOpener.Drop("STONE", RewardType.RESOURCE, 30L, 1000L, false),
                new ChestOpener.Drop("IRON", RewardType.RESOURCE, 20L, 500L, false),
                new ChestOpener.Drop("item_chest_hero", RewardType.ITEM, 9L, 1L, true),
                new ChestOpener.Drop("SSR", RewardType.HERO_FRAGMENT, 1L, 1L, true)),
                10L);
    }

    @Test
    @DisplayName("验收4：同 seed 开箱两次，结果逐条完全一致")
    void chestOpeningIsReproducible() {
        ChestOpener.Table table = chestTable();
        List<RewardItem> first = ChestOpener.openBatch(table, 20260906L, 100);
        List<RewardItem> second = ChestOpener.openBatch(table, 20260906L, 100);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEmpty();
    }

    @Test
    @DisplayName("不同 seed 产生不同结果；开箱数量不同结果也不同")
    void differentSeedsDiverge() {
        ChestOpener.Table table = chestTable();
        assertThat(ChestOpener.openBatch(table, 1L, 50))
                .isNotEqualTo(ChestOpener.openBatch(table, 2L, 50));
        assertThat(ChestOpener.openBatch(table, 1L, 50))
                .isNotEqualTo(ChestOpener.openBatch(table, 1L, 51));
    }

    @Test
    @DisplayName("验收3：批量开 100 个返回的是按掉落表顺序聚合的清单，而不是 100 条流水")
    void batchOpeningAggregatesResults() {
        ChestOpener.Table table = chestTable();
        List<RewardItem> result = ChestOpener.openBatch(table, 777L, 100);

        assertThat(result.size()).as("聚合后条目数应远小于开箱数").isLessThanOrEqualTo(table.drops().size());
        // 顺序必须与掉落表声明顺序一致（客户端按稀有度展示需要稳定顺序）
        List<String> tableOrder = table.drops().stream().map(ChestOpener.Drop::rewardId).toList();
        List<String> resultOrder = result.stream().map(RewardItem::id).toList();
        assertThat(tableOrder).containsSubsequence(resultOrder);

        long totalCount = result.stream().mapToLong(RewardItem::count).sum();
        assertThat(totalCount).as("100 次开箱每次至少掉 1 份").isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("保底生效：连续 10 次不出稀有，第 10 次必出稀有")
    void pityGuaranteesRareDrop() {
        // 构造一个稀有概率极低的表：不启用保底时 10 连几乎不可能出稀有
        ChestOpener.Table table = new ChestOpener.Table("pity_chest", List.of(
                new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 10_000L, 100L, false),
                new ChestOpener.Drop("SSR", RewardType.HERO_FRAGMENT, 1L, 1L, true)),
                10L);

        boolean sawRareByTen = false;
        long sinceLastRare = 0L;
        for (int i = 0; i < 10; i++) {
            ChestOpener.Drop hit = ChestOpener.drawOnce(table, 4242L, i, sinceLastRare);
            sinceLastRare = hit.rare() ? 0L : sinceLastRare + 1;
            if (i == 9) {
                sawRareByTen = hit.rare();
            }
        }
        assertThat(sawRareByTen).as("第 10 次必须触发保底出稀有").isTrue();
    }

    @Test
    @DisplayName("掉落表校验：空表、非正权重、配了保底却没有稀有项都被拒绝")
    void chestTableValidation() {
        assertThatThrownBy(() -> new ChestOpener.Table("c", List.of(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 0L, 1L, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 1L, 0L, false))
                .isInstanceOf(IllegalArgumentException.class);
        // 配了保底却没有稀有项：构造期就失败，不等到玩家开箱时才发现
        assertThatThrownBy(() -> new ChestOpener.Table("c",
                List.of(new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 1L, 1L, false)), 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何 rare 项");
        // 不启用保底（阈值 0）时没有稀有项是合法的
        assertThat(new ChestOpener.Table("c",
                List.of(new ChestOpener.Drop("WOOD", RewardType.RESOURCE, 1L, 1L, false)), 0L)
                .pityThreshold()).isZero();
        assertThatThrownBy(() -> ChestOpener.openBatch(chestTable(), 1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("掉落分布在 20000 次抽取下接近权重比例（随机无偏）")
    void dropDistributionMatchesWeights() {
        ChestOpener.Table table = new ChestOpener.Table("dist_chest", List.of(
                new ChestOpener.Drop("A", RewardType.RESOURCE, 50L, 1L, false),
                new ChestOpener.Drop("B", RewardType.RESOURCE, 30L, 1L, false),
                new ChestOpener.Drop("C", RewardType.RESOURCE, 20L, 1L, false)),
                0L);   // 关闭保底，否则保底会扭曲分布

        int total = 20000;
        long a = 0;
        long b = 0;
        long c = 0;
        for (int i = 0; i < total; i++) {
            String hit = ChestOpener.drawOnce(table, 99L, i, 0L).rewardId();
            switch (hit) {
                case "A" -> a++;
                case "B" -> b++;
                default -> c++;
            }
        }
        assertThat((double) a / total).isBetween(0.47d, 0.53d);
        assertThat((double) b / total).isBetween(0.27d, 0.33d);
        assertThat((double) c / total).isBetween(0.17d, 0.23d);
    }
}
