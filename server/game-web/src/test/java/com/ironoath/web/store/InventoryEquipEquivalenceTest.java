package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.mongo.MongoInventoryStore;

/**
 * 职责：装备实例账本在**两套存储**里落得回去、读得出来（B20 §五⑤ 代价清单的第④条）。
 * 依赖：内存实现 + 本机 MongoDB（{@link TestMongo}）。
 *
 * <p><b>为什么这一族字段值得单独钉</b>：{@code VersionedStoreContractTest} 那四条通用契约
 * 只看"版本推进 / 读返回副本 / 并发插入一个赢家"，<b>不看载荷里有没有少抄一个字段</b> ——
 * 少抄 {@code equips} 的表现不是报错，而是"玩家重启之后装备全变成了 +0"，
 * 而强化等级是花铁买来的。所以这里逐字段比对，而不是只断言"读得回来"。
 *
 * <p><b>两套实现必须同时被同一条断言覆盖</b>：内存侧存的是活对象（改了就改了），
 * Mongo 侧存的是文档（只有 {@code .set} 里列出的字段会写回去）。
 * 这类不对称在本仓库已经咬过两次（{@code NationStoreEquivalenceTest} 的 {@code holderAlliance}、
 * 以及 #164 里"失败的一笔也占掉本周额度"），每次都是<b>只有一套实现会错</b>的那种形状 ——
 * 单测只跑内存侧就永远绿。
 */
class InventoryEquipEquivalenceTest {

    private static final String SWORD = "eq_iron_sword";
    private static final String BLADE = "eq_pojun_blade";
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 迁移判据：真表由 {@code MongoInventoryStore} 自己读，这里只需要与它同形状的谓词。 */
    private static final Predicate<String> IS_EQUIP = id -> id.startsWith("eq_");

    private static TestMongo db;

    @BeforeAll
    static void open() {
        db = TestMongo.tryOpen();
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    /** 两套实现。没有 Mongo 时只剩一套 —— 那条 Mongo 专有的用例会用假定的方式说明，不静默通过。 */
    private List<InventoryRepository> bothStores() {
        List<InventoryRepository> stores = new ArrayList<>();
        stores.add(new InMemoryInventoryStore());
        if (db != null) {
            stores.add(new MongoInventoryStore(db.template(),
                    com.ironoath.config.ConfigRegistry.loadFromDirectory(configDir())));
        }
        return stores;
    }

    private static java.nio.file.Path configDir() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null) {
            java.nio.file.Path candidate = dir.resolve("contract/config");
            if (java.nio.file.Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }

    private static String describe(Inventory bag) {
        StringBuilder text = new StringBuilder();
        text.append("nextUid=").append(bag.nextEquipUid());
        text.append("|counts=").append(new java.util.TreeMap<>(bag.snapshot()));
        for (Inventory.EquipInstance instance : bag.equipInstances()) {
            text.append('|').append(instance.uid()).append('=')
                    .append(instance.equipId()).append(',')
                    .append(instance.forgeLevel()).append(',')
                    .append(instance.worn() ? "穿着" : "在包里");
        }
        return text.toString();
    }

    /**
     * 摆一份"有装备、有等级、有穿着状态"的背包进库。
     *
     * <p><b>先插空包、再把完整状态只经 {@code save} 落库</b>：如果直接用带装备的包走
     * {@code insertIfAbsent}，那么文档里的 {@code equips} 是插入那条路写的，
     * {@code save} 少写一个字段照样读得回来 —— 而"save 漏字段"正是本类要抓的那条缺陷
     * （{@code MongoInventoryStore} 的 update 只写列出来的字段，这不是错觉，是它的语义）。
     */
    private String seeded(InventoryRepository store, String playerId) {
        assertThat(store.insertIfAbsent(playerId, Inventory.empty(20)))
                .as("夹具必须能建包").isTrue();
        Inventory bag = store.findByPlayerId(playerId).orElseThrow();
        bag.restore(Map.of("item_res_wood_10k", 3L), List.of(
                        new Inventory.EquipInstance("e1", SWORD, 0, false),
                        new Inventory.EquipInstance("e2", BLADE, 7, true),
                        new Inventory.EquipInstance("e3", SWORD, 4, false)),
                20, 4, IS_EQUIP);
        store.save(playerId, bag, store.versionOf(playerId));
        return playerId;
    }

    @Test
    @DisplayName("整件账本逐字段落得回去：uid、哪一行、强化到几、穿着没有、铸造序号")
    void everyInstanceFieldSurvivesTheRoundTrip() {
        List<String> described = new ArrayList<>();
        for (InventoryRepository store : bothStores()) {
            String playerId = seeded(store, "P-equip-" + SEQ.incrementAndGet());
            described.add(describe(store.findByPlayerId(playerId).orElseThrow()));
        }
        assertThat(new java.util.LinkedHashSet<>(described))
                .as("两套实现读回来的必须逐字段相同（少抄一个 equips 字段就红在这里）")
                .hasSize(1);
        assertThat(described.get(0))
                .as("原件与落库读回来的那份也必须一字不差")
                .isEqualTo("nextUid=4|counts={item_res_wood_10k=3}"
                        + "|e1=eq_iron_sword,0,在包里"
                        + "|e2=eq_pojun_blade,7,穿着"
                        + "|e3=eq_iron_sword,4,在包里");
    }

    @Test
    @DisplayName("铸造序号不回拨：重启之后再发一件不会盖掉已有实例")
    void theMintingSequenceDoesNotGoBackwards() {
        for (InventoryRepository store : bothStores()) {
            String playerId = seeded(store, "P-equip-seq-" + SEQ.incrementAndGet());
            Inventory again = store.findByPlayerId(playerId).orElseThrow();
            String fresh = again.mintEquips(SWORD, 1).get(0);
            store.save(playerId, again, store.versionOf(playerId));

            Inventory reloaded = store.findByPlayerId(playerId).orElseThrow();
            assertThat(fresh).as("新铸的号不能与已有的 e1..e3 撞").doesNotStartWith("e1");
            assertThat(reloaded.equipInstances(SWORD)).as("三件里的一件都不能被覆盖掉")
                    .hasSize(3)
                    .extracting(Inventory.EquipInstance::uid).doesNotHaveDuplicates();
            assertThat(reloaded.equipInstance("e2").forgeLevel())
                    .as("那件 +7 的破军刃必须还是 +7 —— 它是玩家花铁买来的").isEqualTo(7);
        }
    }

    @Test
    @DisplayName("老文档（没有 equips 字段、counts 里躺着 eq_xxx: 2）读出来是两件，不是零件")
    void aLegacyDocumentReadsAsTwoInstancesNotAsNothing() {
        Inventory migrated = Inventory.empty(20);
        // 这就是 §五⑤ 明令不许读成"没穿装备"的那个形状：数量条目 + 没有实例字段
        migrated.restore(Map.of(SWORD, 2L), null, 20, 1, IS_EQUIP);

        assertThat(migrated.countOf(SWORD)).isEqualTo(2L);
        assertThat(migrated.equipInstances(SWORD)).extracting(Inventory.EquipInstance::uid)
                .containsExactly("u:eq_iron_sword:1", "u:eq_iron_sword:2");

        if (db == null) {
            // 只有内存实现时这一条不构成"两套实现都覆盖了"的证据 —— 说破它，别让绿色冒充覆盖
            throw new AssertionError("本机 MongoDB 没接通：老文档迁移只在内存侧验过，"
                    + "Mongo 侧（真正会有老文档的那一侧）本轮未验。起一个 27017 或按 TestMongo 的说明连库再跑");
        }
        InventoryRepository store = new MongoInventoryStore(db.template(),
                com.ironoath.config.ConfigRegistry.loadFromDirectory(configDir()));
        String playerId = "P-equip-legacy-" + SEQ.incrementAndGet();
        Inventory legacyShape = Inventory.empty(20);
        // 先用"什么都不算实例"的谓词写一份旧形状的档（等价于升级之前落库的文档：只有 counts）
        legacyShape.restore(Map.of(SWORD, 2L), List.of(), 20, 1, id -> false);
        store.insertIfAbsent(playerId, legacyShape);
        assertThat(store.findByPlayerId(playerId).orElseThrow().countOf(SWORD))
                .as("读回来必须还是两件（store 自己带真表判据，不靠调用方提醒）")
                .isEqualTo(2L);
        assertThatThrownBy(() -> store.save(playerId, Inventory.empty(20), 99L))
                .as("顺带确认这份档确实落了库：过期版本必须被拒").isInstanceOf(IllegalStateException.class);
    }
}
