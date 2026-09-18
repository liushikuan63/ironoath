package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerPaid;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;
import com.ironoath.web.store.mongo.PlayerDocumentMapper;

/**
 * 职责：付费权益作为<b>玩家存档上的一位</b>必须能落库、能读回，老存档读不炸（B19 §一.1）。
 * 依赖：本机 MongoDB（{@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>为什么必须单独测这一位</b>：{@code MongoPlayerStore.save} 的字段是一张手写的 {@code $set} 列表，
 * 新加字段最容易只写进领域模型与文档、忘了那一行 —— 而 {@code insertIfAbsent} 写的是整份文档，
 * 所以<b>新号看起来完全正常，只有更新路径在丢</b>。这一位丢的东西是钱：
 * 症状是「买了月卡的号每次重启都变回没买过」，没有任何一处会报错。
 * 荣耀（{@link PlayerGloryEquivalenceTest}）与引导（{@link PlayerGuideEquivalenceTest}）各写过一条同族用例。
 */
class PlayerPaidEquivalenceTest {

    private static final long NOW = 1_760_000_000_000L;
    private static final long DAY = 86_400_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    /** 六位全非默认，这样"漏了哪一位"在读回比对里当场就能看出来。 */
    private static PlayerPaid fullPaid() {
        return new PlayerPaid(NOW + 30 * DAY, NOW + DAY,
                NOW - 5 * DAY, Set.of("pr_fund_t1", "pr_fund_t2"), NOW - 7 * DAY,
                Set.of("order_a", "order_b"));
    }

    private static PlayerSave saveWithPaid(String playerId, PlayerPaid paid) {
        Map<String, PlayerResourceState> resources = new java.util.LinkedHashMap<>();
        resources.put("GOLD", new PlayerResourceState(200L, 1_000_000L, 0L, 0L, NOW));
        PlayerSave save = PlayerSave.createNew(playerId, "dev-" + playerId, "付费存档测试", 1,
                NOW, 1, resources, PlayerPower.zero(), null);
        save.setPaid(paid);
        return save;
    }

    private List<PlayerRepository> bothStores() {
        List<PlayerRepository> stores = new ArrayList<>();
        stores.add(new InMemoryPlayerStore());
        if (db != null) {
            stores.add(new MongoPlayerStore(db.template()));
        }
        return stores;
    }

    @Test
    @DisplayName("六位权益都要过一遍更新路径：两侧各插一遍、改一遍、再读一次")
    void paidStateSurvivesTheRoundTripOnBothStores() {
        PlayerPaid updated = fullPaid();
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-paid-" + label;
            store.insertIfAbsent(saveWithPaid(playerId, PlayerPaid.empty()));
            // 先 insert 空态再 save：insert 走整份文档，压不到那条手写的 $set 列表
            PlayerSave loaded = store.findByPlayerId(playerId).orElseThrow();
            loaded.setPaid(updated);
            store.save(loaded);

            PlayerPaid reread = store.findByPlayerId(playerId).orElseThrow().paid();
            assertThat(reread)
                    .as("%s 更新之后的权益必须整位读得回来（漏 set 一列的症状是重启后清零，而不是报错）", label)
                    .isEqualTo(updated);
            assertThat(reread.cardExpireAt()).as("%s 月卡到期时刻", label).isEqualTo(updated.cardExpireAt());
            assertThat(reread.fundClaimedTiers()).as("%s 已领档位", label)
                    .containsExactlyInAnyOrderElementsOf(updated.fundClaimedTiers());
        }
    }

    @Test
    @DisplayName("发货台账的插入顺序也要保住：淘汰规则是「丢最早记进去的」，顺序错了就是丢随机几笔")
    void fulfilmentLedgerKeepsItsOrderAcrossTheRoundTrip() {
        PlayerPaid ordered = new PlayerPaid(NOW + DAY, NOW, null, Set.of(), null,
                new java.util.LinkedHashSet<>(List.of("order_first", "order_second", "order_third")));
        String playerId = "P-paid-order";
        PlayerSave seeded = saveWithPaid(playerId, ordered);

        if (db != null) {
            MongoPlayerStore store = new MongoPlayerStore(db.template());
            store.insertIfAbsent(seeded);
            assertThat(store.findByPlayerId(playerId).orElseThrow().paid().fulfilledOrderIds())
                    .as("Mongo 回读后台账仍是插入序（存的是有序 List，不无序 Set）")
                    .containsExactly("order_first", "order_second", "order_third");
        } else {
            // 内存侧同样要走一遍，否则"没有 Mongo"就成了不测顺序的借口
            InMemoryPlayerStore store = new InMemoryPlayerStore();
            store.insertIfAbsent(seeded);
            assertThat(store.findByPlayerId(playerId).orElseThrow().paid().fulfilledOrderIds())
                    .containsExactly("order_first", "order_second", "order_third");
        }
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 有序性这条只算了内存侧，跳过即未验证");
    }

    @Test
    @DisplayName("换实例（= 重启一次）只有 Mongo 版还在：内存版当生产等于每次重启把付费权益清零")
    void restartKeepsPaidOnlyOnMongo() {
        PlayerPaid paid = fullPaid();
        String playerId = "P-paid-restart";
        new InMemoryPlayerStore().insertIfAbsent(saveWithPaid(playerId, paid));
        assertThat(new InMemoryPlayerStore().findByPlayerId(playerId))
                .as("内存版换实例就没了").isEmpty();

        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— Mongo 侧这条跳过即未验证");
        new MongoPlayerStore(db.template()).insertIfAbsent(saveWithPaid(playerId, paid));
        PlayerPaid reread = new MongoPlayerStore(db.template())
                .findByPlayerId(playerId).orElseThrow().paid();
        assertThat(reread).as("换一个 Mongo 实例（等价于重启进程）之后权益还在").isEqualTo(paid);
        assertThat(reread.cardActive(reread.cardExpireAt() - 1)).as("到期判定读的是回读出来的那一位").isTrue();
    }

    @Test
    @DisplayName("老存档没有这一位：读成「什么都没买过」，不挡登录")
    void missingPaidReadsAsEmpty() {
        PlayerDocument noPaid = new PlayerDocument("P-old", "dev-old", "老号", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(noPaid).paid())
                .as("B19 之前建的号压根没存过付费权益").isEqualTo(PlayerPaid.empty());
    }

    @Test
    @DisplayName("子文档存在但内容是脏的（到期时刻为 0）：抛，而不是悄悄读成没买过")
    void dirtyPaidThrowsInsteadOfVanishingThePurchase() {
        PlayerDocument dirty = new PlayerDocument("P-bad", "dev-bad", "脏数据", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null,
                new PlayerDocument.PaidDoc(0L, null, null, List.of(), null, List.of()), null, null, 0L);

        assertThatThrownBy(() -> PlayerDocumentMapper.toDomain(dirty))
                .as("这一位与荣耀/引导不同：那两位是派生缓存，读不懂能重算；"
                        + "这一位是付过钱的账，静默读成 empty 的症状是玩家的月卡凭空消失且不留下任何痕迹")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("值对象自己守住每一位：非正时刻、空集合项、台账封顶都构造不出坏值")
    void paidValueObjectGuardsItself() {
        assertThat(PlayerPaid.empty().cardActive(NOW)).isFalse();
        assertThat(PlayerPaid.empty().fundPurchased()).isFalse();
        assertThat(PlayerPaid.empty().firstCharged()).isFalse();
        assertThat(PlayerPaid.empty().fulfilled("order_x")).isFalse();

        assertThatThrownBy(() -> new PlayerPaid(0L, null, null, Set.of(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerPaid(NOW, null, -1L, Set.of(), null, Set.of()))
                .as("负时刻会被读成 1969 年买过").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerPaid(NOW, null, null, Set.of(" "), null, Set.of()))
                .as("空白档位 id 会让「这一档领过没有」永远判不出来")
                .isInstanceOf(IllegalArgumentException.class);

        PlayerPaid grown = PlayerPaid.empty();
        for (int i = 0; i < PlayerPaid.FULFILL_LEDGER_MAX + 20; i++) {
            grown = grown.withFulfilledOrder("order_" + i);
        }
        assertThat(grown.fulfilledOrderIds()).as("台账必须封顶，否则它随付费次数无限长大")
                .hasSize(PlayerPaid.FULFILL_LEDGER_MAX);
        assertThat(grown.fulfilled("order_" + (PlayerPaid.FULFILL_LEDGER_MAX + 19)))
                .as("留下的是最近的几笔").isTrue();
        assertThat(grown.fulfilled("order_0")).as("最早的那几笔被丢掉").isFalse();
    }

    @Test
    @DisplayName("一次性登记的三个 with 都不重复登记：首充与基金只记第一次的时刻")
    void oneShotTransitionsDoNotOverwriteTheFirstMoment() {
        PlayerPaid base = PlayerPaid.empty().withFirstCharged(NOW).withFundPurchased(NOW);
        assertThat(base.withFirstCharged(NOW + DAY)).as("首充时刻不被第二笔改写")
                .isEqualTo(base);
        assertThat(base.withFundPurchased(NOW + DAY)).as("基金购买时刻不被第二笔改写")
                .isEqualTo(base);
        assertThat(base.withCardExtended(NOW, DAY).cardExpireAt())
                .as("月卡是唯一可以叠加的那一位").isEqualTo(NOW + DAY);
    }
}
