package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;
import com.ironoath.web.store.mongo.PlayerDocumentMapper;

/**
 * 职责：科技这一位必须真的能落库、能读回，老存档读不炸（B20 验收 9「存储双实现等价」）。
 * 依赖：本机 MongoDB（{@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>为什么单独测这一位</b>：{@code MongoPlayerStore.save} 的字段是一张手写的 {@code $set} 列表，
 * 新加字段最容易只写进领域模型与文档、忘了那一行 —— 症状不是报错，而是
 * 「内存 dev 一切正常、生产每次重启把研究出来的等级清零」，而且只在生产出现。
 * 荣耀（{@link PlayerGloryEquivalenceTest}）、引导（{@link PlayerGuideEquivalenceTest}）、
 * 付费（{@link PlayerPaidEquivalenceTest}）都为同一族缺陷各写过一条，这里照同一条路子走。
 *
 * <p><b>研究槽单独测一次</b>：账本（levels）漏写只是掉等级，队列（researchingId/finishAt）漏写
 * 会让玩家每次重启都发现「刚才那 20 天研究没了」，而资源已经扣掉了 —— 那是付过代价的丢失。
 */
class PlayerTechEquivalenceTest {

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

    private static PlayerSave saveWithTech(String playerId, PlayerTech tech) {
        Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
        resources.put("GRAIN", new PlayerResourceState(5_000L, 10_000L, 0L, 100L, NOW));
        PlayerSave save = PlayerSave.createNew(playerId, "dev-" + playerId, "科技存档测试", 1,
                NOW, 1, resources, PlayerPower.zero(), null);
        save.setTech(tech);
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
    @DisplayName("账本 + 研究槽两位都要过一轮写读：两侧各插一遍、更新一遍，再读一次")
    void levelsAndQueueBothSurviveTheRoundTrip() {
        PlayerTech advanced = new PlayerTech(Map.of("tech_agri_wood", 3, "tech_mil_atk", 1),
                "tech_fort_build", NOW + 13_000L, NOW, 13L);
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-tech-" + label;
            store.insertIfAbsent(saveWithTech(playerId, PlayerTech.empty()));
            // insert 写的是整份文档，压不到更新路径；漏字段恰恰发生在 Mongo 版那条手写 $set 列表上
            PlayerSave loaded = store.findByPlayerId(playerId).orElseThrow();
            loaded.setTech(advanced);
            store.save(loaded);

            PlayerTech back = store.findByPlayerId(playerId).orElseThrow().tech();
            assertThat(back.levels())
                    .as("%s 更新后的账本必须原样读得回来（漏 set 一列的症状是重启清零，而不是报错）", label)
                    .isEqualTo(advanced.levels());
            assertThat(back)
                    .as("%s 研究槽四位也要整体回来：漏一位等于把玩家正在等的研究丢掉", label)
                    .isEqualTo(advanced);
        }
    }

    @Test
    @DisplayName("换实例（= 重启一次）只有 Mongo 版还在；内存版换实例就没了")
    void restartKeepsTechOnlyOnMongo() {
        PlayerTech researched = new PlayerTech(Map.of("tech_com_march", 2), null, null, 0L, 0L);
        String playerId = "P-tech-restart";
        new InMemoryPlayerStore().insertIfAbsent(saveWithTech(playerId, researched));
        assertThat(new InMemoryPlayerStore().findByPlayerId(playerId))
                .as("内存版换实例就没了：用它跑生产等于每次重启把科技清零")
                .isEmpty();

        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— Mongo 侧这条断言跳过即未验证");
        new MongoPlayerStore(db.template()).insertIfAbsent(saveWithTech(playerId, researched));
        PlayerTech reread = new MongoPlayerStore(db.template())
                .findByPlayerId(playerId).orElseThrow().tech();
        assertThat(reread).as("换一个 Mongo 实例（等价于重启进程）之后这一位还在").isEqualTo(researched);
        assertThat(reread.levelOf("tech_com_march")).isEqualTo(2);
        assertThat(reread.isResearching()).isFalse();
    }

    @Test
    @DisplayName("老存档没有这一位：读成「一行都没研究」，绝不挡住登录")
    void missingTechReadsAsEmpty() {
        PlayerDocument noTech = new PlayerDocument("P-old", "dev-old", "老号", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null, null, 0L);
        assertThat(PlayerDocumentMapper.toDomain(noTech).tech())
                .as("B20 之前建的号压根没存过科技").isEqualTo(PlayerTech.empty());
    }

    @Test
    @DisplayName("账本里的 0 占位读回来时丢掉（与「没研究过」同义），但半截的研究槽要抛而不是读成空闲")
    void zeroPlaceholdersAreDroppedWhileATornQueueThrows() {
        Map<String, Integer> dirty = new LinkedHashMap<>();
        dirty.put("tech_agri_wood", 0);
        dirty.put("tech_mil_atk", 2);
        PlayerDocument withZero = new PlayerDocument("P-zero", "dev-zero", "脏账本", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null,
                new PlayerDocument.TechDoc(dirty, null, null, 0L, 0L), 0L);
        PlayerTech read = PlayerDocumentMapper.toDomain(withZero).tech();
        assertThat(read.levels())
                .as("0 与「这一行没研究过」是同一件事，读回来不该占一位")
                .containsOnlyKeys("tech_mil_atk");
        assertThat(read.levelOf("tech_agri_wood")).isZero();

        PlayerDocument torn = new PlayerDocument("P-torn", "dev-torn", "脏队列", 1, NOW, NOW, 1,
                Map.of(), null, null, null, null, null, null,
                new PlayerDocument.TechDoc(Map.of(), "tech_agri_wood", null, NOW, 13L), 0L);
        assertThatThrownBy(() -> PlayerDocumentMapper.toDomain(torn))
                .as("有 researchingId 却没完成时刻：静默读成空闲等于把玩家已经等掉的时间扔掉，宁可炸出来")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
