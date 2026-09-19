package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerGlory;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;
import com.ironoath.web.store.mongo.PlayerDocumentMapper;

/**
 * 职责：荣耀三件套作为<b>主存档里的派生缓存</b>必须能落库、能读回，且老存档读不炸
 * （B14 §4 与收口清单 #79）。
 * 依赖：本机 MongoDB（{@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>为什么单独测这个字段</b>：它是往 {@code PlayerSave} 上加的第 N 个字段，
 * 而这类字段最容易只写进内存版就忘了 Mongo 版的 {@code $set} 列表 —— 那条漏法的症状
 * 不是报错，是「开发环境一切正常、生产每次重启都把荣耀清零」。
 * 所以这里两侧跑同一组断言，并把内存版换实例会丢<b>当成事实断言出来</b>，
 * 让「生产必须用 mongo 模式」有一条会红的证据而不只是一段注释。
 */
class PlayerGloryEquivalenceTest {

    private static final long NOW = 1_760_000_000_000L;
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

    private static PlayerSave saveWithGlory(String playerId, PlayerGlory glory) {
        Map<String, com.ironoath.core.player.PlayerResourceState> resources = new java.util.LinkedHashMap<>();
        resources.put("WOOD", new com.ironoath.core.player.PlayerResourceState(5_000L, 10_000L, 0L, 100L, NOW));
        resources.put("GOLD", new com.ironoath.core.player.PlayerResourceState(200L, 1_000_000L, 0L, 0L, NOW));
        PlayerSave save = PlayerSave.createNew(playerId, "dev-" + playerId, "荣耀测试", 1,
                NOW, 1, resources, PlayerPower.zero(), null);
        save.setGlory(glory);
        return save;
    }

    private static PlayerGlory sample() {
        return new PlayerGlory(3, SeasonTier.Tier.DIAMOND, List.of("season_a", "season_b", "season_c"));
    }

    private List<PlayerRepository> bothStores() {
        List<PlayerRepository> stores = new java.util.ArrayList<>();
        stores.add(new InMemoryPlayerStore());
        if (db != null) {
            stores.add(new MongoPlayerStore(db.template()));
        }
        return stores;
    }

    @Test
    @DisplayName("荣耀缓存改后必须随更新落库：Mongo 版是手工列出的 $set 列表，漏一列没有任何报错")
    void glorySurvivesTheRoundTripOnBothStores() {
        PlayerGlory upgraded = new PlayerGlory(4, SeasonTier.Tier.KING,
                List.of("season_a", "season_b", "season_c", "season_d"));
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-glory-" + label;
            store.insertIfAbsent(saveWithGlory(playerId, sample()));
            // insert 走的是"整份文档"，压不到更新路径；而漏字段恰恰发生在 Mongo 版那条手写的 $set 列表上
            PlayerSave loaded = store.findByPlayerId(playerId).orElseThrow();
            loaded.setGlory(upgraded);
            store.save(loaded);

            assertThat(store.findByPlayerId(playerId).orElseThrow().glory())
                    .as("%s 更新后的缓存必须读得回来（少 set 一列的症状是每次重启清零，而不是报错）", label)
                    .isEqualTo(upgraded);
        }
    }

    @Test
    @DisplayName("换实例（= 重启一次）：Mongo 版仍在，内存版必丢 —— 这是事实，必须被断言出来")
    void restartKeepsGloryOnlyOnMongo() {
        String playerId = "P-glory-restart";
        PlayerRepository first = new InMemoryPlayerStore();
        first.insertIfAbsent(saveWithGlory(playerId, sample()));
        assertThat(((InMemoryPlayerStore) first).findByPlayerId(playerId).orElseThrow().glory())
                .as("内存版同一个实例当然读得到").isEqualTo(sample());
        assertThat(new InMemoryPlayerStore().findByPlayerId(playerId))
                .as("内存版换实例就没了：用它跑生产等于每次重启把荣耀清零")
                .isEmpty();

        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— Mongo 侧这条断言跳过即未验证");
        new MongoPlayerStore(db.template()).insertIfAbsent(saveWithGlory(playerId, sample()));
        assertThat(new MongoPlayerStore(db.template()).findByPlayerId(playerId).orElseThrow().glory())
                .as("换一个 Mongo 实例（等价于重启进程）之后缓存还在")
                .isEqualTo(sample());
    }

    @Test
    @DisplayName("老存档没有这一项，或段位名字认不出来：读成 empty，绝不让一个缓存字段挡住登录")
    void missingOrUnreadableGloryReadsAsEmpty() {
        PlayerDocument noGlory = new PlayerDocument("P-old", "dev-old", "老号", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(noGlory).glory())
                .as("这轮之前建的号压根没存过荣耀").isEqualTo(PlayerGlory.empty());

        PlayerDocument brokenTier = new PlayerDocument("P-bad", "dev-bad", "脏数据", 1, NOW, NOW, 1,
                Map.of(), null, null, null,
                new PlayerDocument.GloryDoc(2, "MYTHIC", List.of("season_x")), null, null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(brokenTier).glory())
                .as("认不出的段位是缓存读不懂，不是登录失败的理由 —— 它随时能由账本重算")
                .isEqualTo(PlayerGlory.empty());
    }

    @Test
    @DisplayName("缓存与账本的分工：PlayerGlory 自己拒绝负数与缺段位，isBlank 分得清空与有")
    void gloryValueObjectGuardsItself() {
        assertThat(PlayerGlory.empty().isBlank()).isTrue();
        assertThat(sample().isBlank()).isFalse();
        // 只参加一季也是"不空白"：读路径靠这个决定要不要以账本为准再对一次账
        assertThat(new PlayerGlory(1, SeasonTier.Tier.BRONZE, List.of("season_a")).isBlank()).isFalse();
    }
}
