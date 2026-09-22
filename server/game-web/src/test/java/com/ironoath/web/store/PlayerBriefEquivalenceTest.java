package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerBriefDocument;
import com.ironoath.web.store.mongo.PlayerDocumentMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：存档投影口 {@link PlayerRepository#findBriefs} 在内存版与 Mongo 版上必须是<b>同一件事</b>。
 * 依赖：本机 MongoDB（{@link TestMongo}）；连不上时相关用例报「跳过即未验证」而不是绿。
 *
 * <p><b>为什么单独钉这一族</b>：投影口是往 {@code PlayerSave} 的读路径上新增的第三条链路
 * （Mongo 侧那条字段投影列表是手写的）。它漏一列、或者把某一列接错来源，症状都不是报错，
 * 而是「内存版全绿、生产那一列恒为 0」——与荣耀 / 引导 / 付费 / 科技那几族漏 {@code $set} 完全同形。
 * 所以这里两侧跑同一组断言，并且把投影值与<b>同一次整档读</b>看到的值放在一起比：
 * 只有"两版互相等价"是不够的，两版一起读错也等价。
 *
 * <p><b>另一维是"只回被点名的人"</b>：批量口的返回值一旦带上没要的人，调用方的 map 查找不会报错，
 * 但 {@code findBriefs(all)} 那种写法就会把全服档案搬进一次列表响应。
 */
class PlayerBriefEquivalenceTest {

    private static final long NOW = 1_760_000_000_000L;
    private static final long LOGGED_IN_AT = NOW + 5_000L;
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

    /** 每人一项不同的值：任何两列接错来源都会当场红，用同一个默认值铺底的夹具抓不到。 */
    private static PlayerSave saveOf(String playerId, String nickName, int cityLevel, long displayPower) {
        Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
        resources.put("WOOD", new PlayerResourceState(5_000L, 10_000L, 0L, 100L, NOW));
        resources.put("GOLD", new PlayerResourceState(200L, 1_000_000L, 0L, 0L, NOW));
        // 三项刻意互不相等：战力三元组里 displayPower 与 matchPower 只差一个名字，
        // 夹具若给同值，"投影口接成了匹配战力"这条植入就是红的也测不出来
        return PlayerSave.createNew(playerId, "dev-" + playerId, nickName, 1, NOW, cityLevel,
                resources, new PlayerPower(displayPower, displayPower + 1L, displayPower + 1_000L), null);
    }

    private static List<PlayerRepository> bothStores() {
        List<PlayerRepository> stores = new ArrayList<>();
        stores.add(new InMemoryPlayerStore());
        if (db != null) {
            stores.add(new MongoPlayerStore(db.template()));
        }
        return stores;
    }

    /** 每个用例一套独立 id：同一个随机库里跑多个用例，撞了主键就是假绿。 */
    private static String id(String tag) {
        return "P-brief-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("两版投影同一结果，且每一项都与同一次整档读看到的一致（投影漏列 / 接错来源会当场红）")
    void bothStoresProjectTheSameColumnsAsTheFullSaveRead() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 两版等价这条今天没跑，跳过不算通过");
        String first = id("甲");
        String second = id("乙");
        Map<String, Map<String, PlayerBrief>> byStore = new LinkedHashMap<>();
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(saveOf(first, "投影甲", 7, 12_345L));
            store.insertIfAbsent(saveOf(second, "投影乙", 15, 999L));
            // 登录时刻走 touchLogin：它是那条不带乐观锁的定向更新，投影读的正是它写的同一列
            store.touchLogin(first, LOGGED_IN_AT);

            Map<String, PlayerBrief> briefs = store.findBriefs(List.of(first, second));
            assertThat(briefs).as("%s 两个人都该回来", label).hasSize(2);
            assertThat(briefs.get(first))
                    .as("%s 每一项都得对得上夹具（值各不相同，接错列会红）", label)
                    .isEqualTo(new PlayerBrief(first, "投影甲", 7, LOGGED_IN_AT, 12_345L));
            assertThat(briefs.get(second))
                    .as("%s 没被 touchLogin 的那个人仍是建号时刻", label)
                    .isEqualTo(new PlayerBrief(second, "投影乙", 15, NOW, 999L));

            // 投影值必须与整档读看到的四项一致：只比两版会漏掉"两版一起读错"
            PlayerSave full = store.findByPlayerId(first).orElseThrow();
            PlayerBrief brief = briefs.get(first);
            assertEquals(brief, new PlayerBrief(first, full.nickName(), full.cityLevel(),
                            full.lastLoginAt(), full.power().displayPower()),
                    label + " 的投影与整档读同源（少投影一列的症状是这里回 0）");
            byStore.put(label, store.findBriefs(List.of(first, second)));
        }
        assertThat(byStore.get("MongoPlayerStore"))
                .as("两版投影逐字段相同")
                .isEqualTo(byStore.get("InMemoryPlayerStore"));
    }

    @Test
    @DisplayName("只回被点名的人：库里别人一个都不许搭车，查不到的 id 不回填 null")
    void onlyTheNamedPlayersComeBackAndMissingIdsAreAbsent() {
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String asked = id("被点");
            String notAsked = id("旁观");
            store.insertIfAbsent(saveOf(asked, "被点名", 6, 500L));
            store.insertIfAbsent(saveOf(notAsked, "没被点", 6, 500L));

            Map<String, PlayerBrief> briefs =
                    store.findBriefs(List.of(asked, "P-不存在-" + label));

            assertThat(briefs.keySet())
                    .as("%s 结果集只含被点名且存在的人：多回来一个就是全服档案搭车", label)
                    .containsExactly(asked);
            assertThat(briefs.get(notAsked))
                    .as("%s 没被点的人不得出现在结果里（值也不许是 null 占位）", label)
                    .isNull();
            assertThat(briefs).as("%s 被点名但查不到的 id 直接不出现", label)
                    .doesNotContainKey("P-不存在-" + label);
            assertThat(briefs.values()).as("%s 值里不许有 null", label).doesNotContainNull();
        }
    }

    @Test
    @DisplayName("null、空集合、只装着 null 的集合：两版都回空 map，一次都不问存储")
    void emptyAndNullRequestsReadNothing() {
        for (PlayerRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.findBriefs(null)).as("%s null 入参", label).isEmpty();
            assertThat(store.findBriefs(List.of())).as("%s 空集合", label).isEmpty();
            assertThat(store.findBriefs(java.util.Arrays.asList(new String[] {null, null})))
                    .as("%s 只装着 null 的集合（列表页把缺失行映射成 id 时会出现）", label)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("老存档没有战力子文档：投影回 0 而不是抛 —— 列表行不该被一个缺失的派生列挡住")
    void aDocumentWithoutThePowerSubdocumentProjectsZero() {
        // B08 之前建的号没有 power 子文档；内存版构造不出这个状态（setPower 拒绝 null），
        // 所以这一维只在 Mongo 侧有，落在 mapper 上而不是 store 上
        assertThat(PlayerDocumentMapper.toBrief(new PlayerBriefDocument("P-legacy", "老号", 3, NOW, null)))
                .as("读成 0 战力，而不是让整页列表抛在这条档案上")
                .isEqualTo(new PlayerBrief("P-legacy", "老号", 3, NOW, 0L));
    }

    @Test
    @DisplayName("投影里没有整档：这个类型装不下资源表与 PVP 账本，多要一项就得回到端口上改")
    void theBriefCarriesNothingButListColumns() {
        assertThat(java.util.Arrays.stream(PlayerBrief.class.getRecordComponents())
                        .map(c -> c.getName()))
                .as("字段集合就是列表行点的那几项；加字段要连端口注释与两版实现一起改")
                .containsExactly("playerId", "nickName", "cityLevel", "lastLoginAt", "displayPower");
    }
}
