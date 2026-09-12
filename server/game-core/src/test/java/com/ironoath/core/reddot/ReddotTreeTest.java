package com.ironoath.core.reddot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：红点树的单测 —— B12 §4、验收 1（父级链路正确聚合、无遗漏、无假红点）。
 * 依赖：JUnit 5 + AssertJ + game-core 的 reddot 包（纯 Java，零框架）。
 *
 * <p><b>本类盯的是三类错</b>，它们的共同点是「不报错，只让玩家困惑」：
 * <ol>
 *   <li><b>漏聚合</b>：叶子亮了父级没亮 ⇒ 玩家看不到入口，功能等于不存在</li>
 *   <li><b>假红点</b>：叶子没亮父级亮了 ⇒ 玩家点进去一片空白，报 bug 说「红点是假的」。
 *       验收 1 把「无假红点」与「无遗漏」并列写，因为后者更伤信任</li>
 *   <li><b>条件抛异常被静默吞掉</b> ⇒ 那个叶子永远不亮，而没人知道为什么。
 *       所以 errorCount 必须能被断言</li>
 * </ol>
 */
class ReddotTreeTest {

    private ReddotTree tree;
    /** 测试用的「世界状态」：条件函数读它，改它就等于模拟业务状态变化 */
    private Map<String, Boolean> world;

    @BeforeEach
    void setUp() {
        tree = new ReddotTree();
        world = new HashMap<>();
    }

    /** 注册一个读 world 的叶子。 */
    private void leaf(String key, String flag, String description) {
        tree.register(key, playerId -> world.getOrDefault(playerId + ":" + flag, false), description);
    }

    private void set(String playerId, String flag, boolean value) {
        world.put(playerId + ":" + flag, value);
    }

    // ---------- 聚合（验收 1） ----------

    @Test
    @DisplayName("验收1：叶子亮 ⇒ 它的每一级祖先都亮，且只亮那一条链路")
    void leafLightsUpItsWholeAncestorChain() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        leaf("city/building/farm", "farm", "农田可升级");
        leaf("army/train", "train", "训练队列空闲");

        set("p1", "wood", true);

        assertThat(tree.isLit("city/building/woodmill", "p1")).isTrue();
        assertThat(tree.isLit("city/building", "p1")).as("中间层必须聚合").isTrue();
        assertThat(tree.isLit("city", "p1")).as("根层必须聚合").isTrue();
        assertThat(tree.isLit("army", "p1")).as("兄弟链路不该被点亮（无假红点）").isFalse();
        assertThat(tree.isLit("army/train", "p1")).isFalse();
    }

    @Test
    @DisplayName("验收1：任一来源变化，父级立刻跟着变 —— 不缓存就不会有失效漏掉的问题")
    void aggregationReflectsChangesImmediately() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        assertThat(tree.isLit("city", "p1")).isFalse();

        set("p1", "wood", true);
        assertThat(tree.isLit("city", "p1")).as("状态一变，下一次查询就必须看到").isTrue();

        set("p1", "wood", false);
        assertThat(tree.isLit("city", "p1")).as("状态撤销后不该留假红点").isFalse();
    }

    @Test
    @DisplayName("验收1：红点是每个玩家各自的，一个玩家的状态不影响另一个")
    void reddotsArePerPlayer() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        set("p1", "wood", true);
        set("p2", "wood", false);

        assertThat(tree.isLit("city", "p1")).isTrue();
        assertThat(tree.isLit("city", "p2")).isFalse();
    }

    @Test
    @DisplayName("验收1：没有注册任何叶子的路径永远不亮（无假红点）")
    void unregisteredPathIsNeverLit() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        set("p1", "wood", true);

        assertThat(tree.isLit("shop", "p1")).as("整棵未注册的子树").isFalse();
        assertThat(tree.isLit("city/shop", "p1")).as("已注册根下的未注册分支").isFalse();
        assertThat(tree.isLit("city/buildingx", "p1"))
                .as("前缀相似但不是祖先的路径不该被误判").isFalse();
    }

    @Test
    @DisplayName("路径前缀匹配必须按段而不是按字符串：city/buildingx 不是 city/building 的子节点")
    void prefixMatchingIsSegmentAware() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        leaf("city/buildingx/other", "other", "另一个分支");
        set("p1", "other", true);
        set("p1", "wood", false);

        assertThat(tree.isLit("city/building", "p1"))
                .as("buildingx 里的叶子不该点亮 building").isFalse();
        assertThat(tree.isLit("city/buildingx", "p1")).isTrue();
        assertThat(tree.isLit("city", "p1")).isTrue();
    }

    // ---------- 子树下发 ----------

    @Test
    @DisplayName("subtree 给出完整树结构，父节点的 lit 等于后代叶子的或")
    void subtreeAggregatesBottomUp() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        leaf("city/building/farm", "farm", "农田可升级");
        leaf("city/army/train", "train", "训练队列空闲");
        set("p1", "farm", true);

        ReddotTree.NodeState root = tree.subtree("city", "p1");
        assertThat(root.key()).isEqualTo("city");
        assertThat(root.lit()).isTrue();
        assertThat(root.children()).extracting(ReddotTree.NodeState::key)
                .containsExactly("city/army", "city/building");

        ReddotTree.NodeState army = root.children().get(0);
        assertThat(army.lit()).as("训练叶子没亮").isFalse();
        ReddotTree.NodeState building = root.children().get(1);
        assertThat(building.lit()).isTrue();
        assertThat(building.children()).extracting(ReddotTree.NodeState::key)
                .containsExactly("city/building/farm", "city/building/woodmill");
        assertThat(building.children().get(0).lit()).as("农田可升级").isTrue();
        assertThat(building.children().get(1).lit()).as("伐木场不可升级").isFalse();
    }

    @Test
    @DisplayName("subtree 传空串给出整棵树；传一个没有任何叶子的子树给出空树而不是 null")
    void subtreeHandlesRootAndEmptyCases() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        ReddotTree.NodeState whole = tree.subtree("", "p1");
        assertThat(whole.key()).isEmpty();
        assertThat(whole.children()).extracting(ReddotTree.NodeState::key).containsExactly("city");

        ReddotTree.NodeState empty = tree.subtree("shop", "p1");
        assertThat(empty.key()).isEqualTo("shop");
        assertThat(empty.lit()).isFalse();
        assertThat(empty.children()).isEmpty();
    }

    @Test
    @DisplayName("子树内的节点顺序稳定：客户端要对这棵树做 diff，顺序抖动会让每帧都以为变了")
    void subtreeOrderIsStable() {
        leaf("b/leaf", "b", "b");
        leaf("a/leaf", "a", "a");
        leaf("c/leaf", "c", "c");
        ReddotTree.NodeState first = tree.subtree("", "p1");
        ReddotTree.NodeState second = tree.subtree("", "p1");
        assertThat(first.children()).extracting(ReddotTree.NodeState::key)
                .containsExactly("a", "b", "c");
        assertThat(second.children()).extracting(ReddotTree.NodeState::key)
                .containsExactly("a", "b", "c");
    }

    // ---------- 注册约束 ----------

    @Test
    @DisplayName("同 key 重复注册直接抛错：两个条件挂在一个叶子上意味着其中一个是死代码")
    void duplicateRegistrationIsRejected() {
        leaf("city/building", "wood", "伐木场可升级");
        assertThatThrownBy(() -> leaf("city/building", "farm", "农田可升级"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复注册");
    }

    @Test
    @DisplayName("非法路径在注册期就被拒绝：带首尾分隔符会多出一个空段，聚合时它会被当成真实节点")
    void invalidPathsAreRejected() {
        assertThatThrownBy(() -> leaf("/city/building", "x", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("开头或结尾");
        assertThatThrownBy(() -> leaf("city/building/", "x", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("开头或结尾");
        assertThatThrownBy(() -> leaf("city//building", "x", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("连续分隔符");
        assertThatThrownBy(() -> tree.register("", playerId -> true, "空 key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为空");
        assertThatThrownBy(() -> tree.register("city", null, "无条件"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("条件函数不得为 null");
    }

    @Test
    @DisplayName("查询时 playerId 不得为空：红点是每个玩家各自的，没有主体就无从判断")
    void queryRequiresPlayerId() {
        leaf("city/building", "wood", "伐木场可升级");
        assertThatThrownBy(() -> tree.isLit("city", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("playerId");
        assertThatThrownBy(() -> tree.isLit("city", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("playerId");
    }

    @Test
    @DisplayName("注销后该叶子不再参与聚合：功能下线了红点还亮着，是最难排查的一类残留")
    void unregisterRemovesLeafFromAggregation() {
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        set("p1", "wood", true);
        assertThat(tree.isLit("city", "p1")).isTrue();

        assertThat(tree.unregister("city/building/woodmill")).isTrue();
        assertThat(tree.unregister("city/building/woodmill")).as("重复注销返回 false").isFalse();
        assertThat(tree.isLit("city", "p1")).isFalse();
        assertThat(tree.leafCount()).isZero();
    }

    // ---------- 异常隔离 ----------

    @Test
    @DisplayName("条件抛异常时按不亮处理，但 errorCount 记下来 —— 静默吞掉会让那个叶子永远不亮而没人知道")
    void failingConditionIsIsolatedAndCounted() {
        tree.register("city/broken", playerId -> {
            throw new IllegalStateException("存档读不到");
        }, "会抛异常的红点");
        leaf("city/building/woodmill", "wood", "伐木场可升级");
        set("p1", "wood", true);

        assertThat(tree.isLit("city", "p1")).as("一个叶子坏了不该让整棵树查询失败").isTrue();
        assertThat(tree.errorCount()).isEqualTo(1);

        // 再查一次，计数继续累加：它是「这个红点一直算不出来」的信号，不是「出过一次错」
        tree.isLit("city", "p1");
        assertThat(tree.errorCount()).isEqualTo(2);

        tree.clear();
        assertThat(tree.errorCount()).isZero();
    }

    @Test
    @DisplayName("条件返回 null 按不亮处理（Boolean 拆箱不能炸）")
    void nullConditionResultMeansNotLit() {
        tree.register("city/nullish", playerId -> null, "返回 null 的红点");
        assertThat(tree.isLit("city", "p1")).isFalse();
        assertThat(tree.errorCount()).as("返回 null 不是异常，不该计入 errorCount").isZero();
    }

    @Test
    @DisplayName("即使已经找到亮的叶子也要把剩余条件求值完，否则 errorCount 会随查询路径变化")
    void allConditionsAreEvaluatedForErrorAccounting() {
        tree.register("city/a", playerId -> true, "恒亮");
        tree.register("city/b", playerId -> {
            throw new IllegalStateException("坏掉的叶子");
        }, "会抛异常");
        tree.isLit("city", "p1");
        assertThat(tree.errorCount()).as("city/a 先命中不该让 city/b 逃过求值").isEqualTo(1);
    }

    // ---------- 规模 ----------

    @Test
    @DisplayName("几十个叶子的一次全树查询是廉价的：不缓存换来了「永远不会聚合错」")
    void fullTreeQueryIsCheap() {
        for (int i = 0; i < 40; i++) {
            leaf("city/building/b" + i, "flag" + i, "建筑 " + i);
        }
        set("p1", "flag37", true);
        long start = System.nanoTime();
        for (int round = 0; round < 1000; round++) {
            assertThat(tree.isLit("city", "p1")).isTrue();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMillis)
                .as("1000 次全树查询应当在 1 秒内完成（40 个叶子 × 1000 次 = 4 万次条件调用）")
                .isLessThan(1000L);
        assertThat(tree.isLit("city/building/b37", "p1")).isTrue();
        assertThat(tree.isLit("city/building/b36", "p1")).isFalse();
        assertThat(tree.leafKeys()).hasSize(40);
    }

    @Test
    @DisplayName("一个节点既是聚合者又是条件源时，两种来源取或 —— 但设计上应当避免（见类注释）")
    void nodeCanBeBothLeafAndParent() {
        leaf("city", "cityFlag", "主城本身有事可做");
        leaf("city/building/woodmill", "wood", "伐木场可升级");

        set("p1", "cityFlag", true);
        assertThat(tree.isLit("city", "p1")).isTrue();
        set("p1", "cityFlag", false);
        assertThat(tree.isLit("city", "p1")).isFalse();
        set("p1", "wood", true);
        assertThat(tree.isLit("city", "p1")).as("后代亮了父级也亮").isTrue();

        ReddotTree.NodeState root = tree.subtree("city", "p1");
        assertThat(root.lit()).isTrue();
        assertThat(root.children()).extracting(ReddotTree.NodeState::key)
                .containsExactly("city/building");
    }

    @Test
    @DisplayName("叶子注册顺序不影响聚合结果（LinkedHashMap 只保证遍历稳定，不保证语义顺序）")
    void registrationOrderDoesNotAffectResult() {
        ReddotTree other = new ReddotTree();
        Map<String, Boolean> otherWorld = new HashMap<>();
        otherWorld.put("p1:z", true);
        List<String> keys = List.of("root/a", "root/m", "root/z");
        for (String key : keys) {
            String flag = key.substring(key.length() - 1);
            other.register(key, playerId -> otherWorld.getOrDefault(playerId + ":" + flag, false), key);
        }
        assertThat(other.isLit("root", "p1")).isTrue();
        assertThat(other.subtree("root", "p1").children())
                .extracting(ReddotTree.NodeState::key)
                .containsExactly("root/a", "root/m", "root/z");
        assertThat(other.subtree("root", "p1").children().get(2).lit()).isTrue();
    }
}
