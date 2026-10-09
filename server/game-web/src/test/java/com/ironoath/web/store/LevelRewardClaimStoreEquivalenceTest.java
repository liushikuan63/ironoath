package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.web.levelreward.LevelRewardClaimStore;
import com.ironoath.web.levelreward.LevelRewardClaimStore.State;
import com.ironoath.web.store.memory.InMemoryLevelRewardClaimStore;
import com.ironoath.web.store.mongo.LevelRewardClaimDocument;
import com.ironoath.web.store.mongo.MongoLevelRewardClaimStore;

/**
 * 职责：等级奖励领取账本在内存与 MongoDB 两端遵守同一存储契约（收口清单 #835 的存储验证缺口）。
 * 依赖：两种生产存储实现与 {@link TestMongo} 的隔离随机库。
 *
 * <p>已领等级是历史事实，漏写或读丢会让玩家换幂等键再领一份，因此同时检查覆盖更新、
 * 玩家隔离与实例重建。Mongo 不可达时明确失败，不能把只测到内存的一轮报成两端等价。
 */
class LevelRewardClaimStoreEquivalenceTest {

    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db)
                .as("等级奖励账本两端等价验证需要真实 MongoDB（%s）；未接通是环境阻塞，不能以跳过代替验证",
                        TestMongo.uri())
                .isNotNull();
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    private static List<LevelRewardClaimStore> bothStores() {
        return List.of(new InMemoryLevelRewardClaimStore(), new MongoLevelRewardClaimStore(db.template()));
    }

    @Test
    @DisplayName("缺档与已保存的空账本有区别，两端都不凭空补出领取记录")
    void missingAndPersistedEmptyStatesRemainDistinctOnBothStores() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-lr-empty-" + label;
            assertThat(store.load(playerId)).as("%s 新号没有领取账本", label).isEmpty();
            assertThat(store.load(null)).as("%s 空身份查询不应抛异常或串到别人", label).isEmpty();
            assertThat(store.load(" ")).as("%s 空白身份不能读到记录", label).isEmpty();

            store.save(playerId, State.empty(playerId));
            assertThat(store.load(playerId)).as("%s 保存过的空账本仍是一份存在的记录", label)
                    .contains(State.empty(playerId));
            assertThat(store.load(playerId).orElseThrow().claimed(1L))
                    .as("%s 空账本不把任何一级读成已领", label).isFalse();
        }
    }

    @Test
    @DisplayName("save 是整份覆盖，新增与删除都必须读回，不能只测首笔插入")
    void updatesReplaceTheWholeLedgerOnBothStores() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-lr-replace-" + label;
            store.save(playerId, new State(playerId, List.of(1L, 8L)));
            assertThat(store.load(playerId)).as("%s 初次写入", label)
                    .contains(new State(playerId, List.of(1L, 8L)));

            State replaced = new State(playerId, List.of(31L, 40L));
            store.save(playerId, replaced);
            assertThat(store.load(playerId)).as("%s 更新后必须完整替换，不能保留旧级别或丢掉新级别", label)
                    .contains(replaced);

            store.save(playerId, State.empty(playerId));
            assertThat(store.load(playerId)).as("%s 清空载荷也必须覆盖，不能只追加已领级别", label)
                    .contains(State.empty(playerId));
        }
    }

    @Test
    @DisplayName("更新甲玩家的领取账本，乙玩家的级别与身份都不变")
    void playersCannotOverwriteEachOthersClaims() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String firstId = "P-lr-first-" + label;
            String secondId = "P-lr-second-" + label;
            State second = new State(secondId, List.of(2L, 9L, 32L));
            store.save(firstId, new State(firstId, List.of(1L)));
            store.save(secondId, second);

            State first = new State(firstId, List.of(1L, 31L, 40L));
            store.save(firstId, first);
            assertThat(store.load(firstId)).as("%s 甲的更新按自己的键落盘", label).contains(first);
            assertThat(store.load(secondId)).as("%s 甲的更新不能覆盖乙的领取事实", label).contains(second);
        }
    }

    @Test
    @DisplayName("clear 删除两名玩家的账本，之后仍能重新保存领取记录")
    void clearRemovesAllLedgersAndLeavesTheStoreWritable() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String firstId = "P-lr-clear-first-" + label;
            String secondId = "P-lr-clear-second-" + label;
            State first = new State(firstId, List.of(1L, 31L));
            State second = new State(secondId, List.of(2L, 40L));
            store.save(firstId, first);
            store.save(secondId, second);
            assertThat(store.load(firstId)).as("%s clear 前甲有账", label).contains(first);
            assertThat(store.load(secondId)).as("%s clear 前乙有账", label).contains(second);

            store.clear();
            assertThat(store.load(firstId)).as("%s clear 后甲必须缺档，而非只清空列表", label).isEmpty();
            assertThat(store.load(secondId)).as("%s clear 后乙也必须缺档", label).isEmpty();
            store.save(firstId, first);
            assertThat(store.load(firstId)).as("%s clear 后存储仍可重新写入", label).contains(first);
            assertThat(store.load(secondId)).as("%s 重新写甲不能复活乙的旧账", label).isEmpty();
        }
    }

    @Test
    @DisplayName("重复且乱序的级别写回后升序去重，再记同一级不会重复")
    void claimedLevelsKeepTheirCanonicalOrderOnBothStores() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-lr-order-" + label;
            State state = new State(playerId, List.of(40L, 1L, 31L, 1L, 8L, 40L));
            store.save(playerId, state);
            State loaded = store.load(playerId).orElseThrow();
            assertThat(loaded.claimedLevels()).as("%s 读回的顺序决定面板行序，不能重复或跳动", label)
                    .containsExactly(1L, 8L, 31L, 40L);

            store.save(playerId, loaded.withClaimed(31L));
            assertThat(store.load(playerId).orElseThrow().claimedLevels())
                    .as("%s 重新登记已领等级不增加第二份记录", label)
                    .containsExactly(1L, 8L, 31L, 40L);
            assertThat(loaded.claimed(31L)).isTrue();
            assertThat(loaded.claimed(30L)).isFalse();
        }
    }

    @Test
    @DisplayName("输入列表与读出状态不能偷偷改库存，withClaimed 必须显式保存才生效")
    void callerOwnedListsAndReadValuesCannotMutateStoredClaims() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String playerId = "P-lr-copy-" + label;
            List<Long> input = new ArrayList<>(List.of(1L, 31L));
            store.save(playerId, new State(playerId, input));
            input.clear();
            input.add(40L);
            State loaded = store.load(playerId).orElseThrow();
            assertThat(loaded.claimedLevels()).as("%s 原始输入列表被修改不应修改已保存账本", label)
                    .containsExactly(1L, 31L);
            assertThatThrownBy(() -> loaded.claimedLevels().add(40L))
                    .as("%s 读出列表必须不可变，不能绕过 save", label)
                    .isInstanceOf(UnsupportedOperationException.class);

            State advanced = loaded.withClaimed(40L);
            assertThat(loaded.claimedLevels()).as("%s withClaimed 不修改旧快照", label)
                    .containsExactly(1L, 31L);
            assertThat(store.load(playerId).orElseThrow().claimedLevels())
                    .as("%s 新快照没有保存之前不能改库存", label).containsExactly(1L, 31L);
            store.save(playerId, advanced);
            assertThat(store.load(playerId).orElseThrow().claimedLevels())
                    .as("%s 显式保存后才记上新一级", label).containsExactly(1L, 31L, 40L);
        }
    }

    @Test
    @DisplayName("空键、空状态与身份错配都拒写，拒绝后原有两份账本原样保留")
    void invalidWritesAreRejectedWithoutChangingEitherPlayersLedger() {
        for (LevelRewardClaimStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            String firstId = "P-lr-key-first-" + label;
            String secondId = "P-lr-key-second-" + label;
            State first = new State(firstId, List.of(1L, 31L));
            State second = new State(secondId, List.of(2L, 40L));
            store.save(firstId, first);
            store.save(secondId, second);

            assertThatThrownBy(() -> store.save(null, first))
                    .as("%s null 键必须拒绝", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.save(" ", first))
                    .as("%s 空白键必须拒绝", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.save(firstId, null))
                    .as("%s null 状态必须拒绝", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.save(firstId, second))
                    .as("%s key 与 state.playerId 不一致不能把乙的领取事实写到甲名下", label)
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(store.load(firstId)).as("%s 被拒的写不能破坏甲的旧账", label).contains(first);
            assertThat(store.load(secondId)).as("%s 被拒的写不能破坏乙的旧账", label).contains(second);
        }
    }

    @Test
    @DisplayName("换实例后内存账本为空，Mongo 仍能读到更新后的每一级")
    void aFreshMongoInstanceRetainsTheUpdatedClaims() {
        String playerId = "P-lr-restart";
        State first = new State(playerId, List.of(1L));
        State updated = first.withClaimed(31L).withClaimed(40L);
        LevelRewardClaimStore memory = new InMemoryLevelRewardClaimStore();
        memory.save(playerId, first);
        memory.save(playerId, updated);
        assertThat(memory.load(playerId)).as("内存旧实例确实记过更新后的账本").contains(updated);
        assertThat(new InMemoryLevelRewardClaimStore().load(playerId))
                .as("内存换实例即丢领取事实，这就是生产必须 Mongo 的原因").isEmpty();

        LevelRewardClaimStore mongo = new MongoLevelRewardClaimStore(db.template());
        mongo.save(playerId, first);
        mongo.save(playerId, updated);
        assertThat(new MongoLevelRewardClaimStore(db.template()).load(playerId))
                .as("Mongo 新实例不能凭进程缓存保住领取事实，必须从库里读到完整更新")
                .contains(updated);
    }

    @Test
    @DisplayName("Mongo 旧文档缺少级别列表或存 null 时读为空账本，后续领取可正常覆盖")
    void legacyMongoDocumentsWithMissingLevelsReadAsEmptyAndCanBeUpdated() {
        LevelRewardClaimStore store = new MongoLevelRewardClaimStore(db.template());
        for (boolean explicitNull : List.of(false, true)) {
            String playerId = explicitNull ? "P-lr-old-null" : "P-lr-old-missing";
            Document old = new Document("_id", playerId);
            if (explicitNull) {
                old.append("claimedLevels", null);
            }
            db.template().getCollection(LevelRewardClaimDocument.COLLECTION).insertOne(old);
            assertThat(store.load(playerId))
                    .as("旧文档 claimedLevels %s 必须保留身份并补为空列表", explicitNull ? "为 null" : "缺失")
                    .contains(State.empty(playerId));

            State advanced = store.load(playerId).orElseThrow().withClaimed(31L);
            store.save(playerId, advanced);
            assertThat(new MongoLevelRewardClaimStore(db.template()).load(playerId))
                    .as("旧文档首次领取之后也必须跨实例读得回来").contains(advanced);
        }
    }
}
