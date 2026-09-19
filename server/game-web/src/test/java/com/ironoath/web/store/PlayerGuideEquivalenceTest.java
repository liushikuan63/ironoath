package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerGuide;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;
import com.ironoath.web.store.mongo.PlayerDocumentMapper;

/**
 * 职责：引导进度作为<b>玩家存档上的一位</b>必须能落库、能读回，老存档读不炸（B18 §一.2「不是本地存储」）。
 * 依赖：本机 MongoDB（{@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>为什么单独测这一位</b>：{@code MongoPlayerStore.save} 的字段是一张手写的 {@code $set} 列表，
 * 新加字段最容易只写进领域模型与文档、忘了那一行 —— 症状不是报错，而是
 * 「内存 dev 一切正常、生产每次重启把玩家弹回第 1 步」，而那恰好是 B18 验收 2 的反面。
 * 荣耀那一档已经为此写过一条同族用例（{@link PlayerGloryEquivalenceTest}），这里照同一路子走。
 */
class PlayerGuideEquivalenceTest {

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

    private static PlayerSave saveWithGuide(String playerId, PlayerGuide guide) {
        Map<String, PlayerResourceState> resources = new java.util.LinkedHashMap<>();
        resources.put("GRAIN", new PlayerResourceState(5_000L, 10_000L, 0L, 100L, NOW));
        PlayerSave save = PlayerSave.createNew(playerId, "dev-" + playerId, "引导存档测试", 1,
                NOW, 1, resources, PlayerPower.zero(), null);
        save.setGuide(guide);
        return save;
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
    @DisplayName("推进之后的进度必须读得回来：两侧各插一遍、更新一遍，再读一次")
    void progressSurvivesTheRoundTripOnBothStores() {
        PlayerGuide advanced = new PlayerGuide(4, null);
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-guide-" + label;
            store.insertIfAbsent(saveWithGuide(playerId, new PlayerGuide(1, null)));
            // insert 写的是整份文档，压不到更新路径；漏字段恰恰发生在 Mongo 版那条手写 $set 列表上
            PlayerSave loaded = store.findByPlayerId(playerId).orElseThrow();
            loaded.setGuide(advanced);
            store.save(loaded);

            assertThat(store.findByPlayerId(playerId).orElseThrow().guide())
                    .as("%s 更新后的进度必须读得回来（少 set 一列的症状是每次重启回到第 1 步，而不是报错）", label)
                    .isEqualTo(advanced);
        }
    }

    @Test
    @DisplayName("结束时刻同样落库；换实例（= 重启一次）只有 Mongo 版还在")
    void restartKeepsProgressOnlyOnMongo() {
        PlayerGuide done = new PlayerGuide(7, NOW);
        String playerId = "P-guide-restart";
        new InMemoryPlayerStore().insertIfAbsent(saveWithGuide(playerId, done));
        assertThat(new InMemoryPlayerStore().findByPlayerId(playerId))
                .as("内存版换实例就没了：用它跑生产等于每次重启重弹引导")
                .isEmpty();

        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— Mongo 侧这条断言跳过即未验证");
        new MongoPlayerStore(db.template()).insertIfAbsent(saveWithGuide(playerId, done));
        PlayerGuide reread = new MongoPlayerStore(db.template())
                .findByPlayerId(playerId).orElseThrow().guide();
        assertThat(reread).as("换一个 Mongo 实例（等价于重启进程）之后进度还在").isEqualTo(done);
        assertThat(reread.finished()).as("结束时刻是显式的一位，不能靠序号推").isTrue();
    }

    @Test
    @DisplayName("老存档没有这一位，或子文档是脏的（负序号）：读成\"从未开始\"，绝不让它挡住登录")
    void missingOrDirtyProgressReadsAsEmpty() {
        PlayerDocument noGuide = new PlayerDocument("P-old", "dev-old", "老号", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(noGuide).guide())
                .as("B18 之前建的号压根没存过引导进度").isEqualTo(PlayerGuide.empty());

        PlayerDocument dirty = new PlayerDocument("P-dirty", "dev-dirty", "脏数据", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null,
                new PlayerDocument.GuideDoc(-3, null), null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(dirty).guide())
                .as("读不懂的代价是引导重弹一次，不是登录失败")
                .isEqualTo(PlayerGuide.empty());
    }

    @Test
    @DisplayName("值对象自己守住那两位：负序号与「0 当作结束时刻」都构造不出来")
    void progressValueObjectGuardsItself() {
        assertThat(PlayerGuide.empty().started()).isFalse();
        assertThat(PlayerGuide.empty().finished()).isFalse();
        assertThat(new PlayerGuide(1, null).started()).isTrue();
        assertThat(new PlayerGuide(3, NOW).finished()).isTrue();
        assertThatThrownBy(() -> new PlayerGuide(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerGuide(2, 0L))
                .as("0 会被读成\"1970 年走完过\"，那是不可信的读法")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
