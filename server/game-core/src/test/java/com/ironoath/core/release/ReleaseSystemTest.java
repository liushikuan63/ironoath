package com.ironoath.core.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.track.TrackBatcher;

/**
 * 职责：B16 埋点批量与发布闸门的单测 —— 验收 3（埋点批量）、7（热更生效）、8（强制更新）。
 * 依赖：JUnit 5 + AssertJ + game-core 的 track / release 包（纯 Java，零框架）。
 *
 * <p><b>本类盯的三条都是「功能正常但线上会出事」的问题</b>：
 * <ol>
 *   <li>埋点逐条上报在功能测试里完全正常，只在弱网下把客户端拖垮 ——
 *       而弱网恰恰是最需要埋点数据的场景</li>
 *   <li>版本号按字符串比较时 "1.10.0" &lt; "1.9.0"，于是 1.9 之后所有版本都被判为更旧，
 *       全服被要求强制更新 —— 那是一个会直接停服的 bug，而它在 1.9 之前永远测不出来</li>
 *   <li>配置热更若靠版本号判断，「改了内容忘了升版本」就会让客户端认为自己是最新的 ——
 *       那是配置表最常见的事故，而症状是「我明明改了表却看不到效果」</li>
 * </ol>
 */
class ReleaseSystemTest {

    private static final long SECOND = 1000L;

    // ---------- 埋点批量（验收 3、禁止项） ----------

    /** 被攒批的条目。核心层不再规定事件形状，所以测试自带一个最小条目类型。 */
    private record Item(String name) {
    }

    private static TrackBatcher<Item> batcher() {
        return new TrackBatcher<>(new TrackBatcher.Rules(10, 10 * SECOND));
    }

    private static Item item(String name) {
        return new Item(name);
    }

    @Test
    @DisplayName("禁止项：攒满 10 条才发一批，前 9 条一个请求都不发")
    void batchingWaitsUntilFull() {
        TrackBatcher<Item> batcher = batcher();
        for (int i = 0; i < 9; i++) {
            assertThat(batcher.track(item("evt_" + i), 1000L + i)).as("第 %d 条不该触发发送", i + 1).isNull();
        }
        assertThat(batcher.pendingCount()).isEqualTo(9);
        assertThat(batcher.batchCount()).isZero();

        TrackBatcher.Batch<Item> batch = batcher.track(item("evt_9"), 1009L);
        assertThat(batch).as("第 10 条触发发送").isNotNull();
        assertThat(batch.size()).isEqualTo(10);
        assertThat(batch.reason()).contains("攒满");
        assertThat(batcher.pendingCount()).isZero();
        assertThat(batcher.batchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("攒不满 10 条时，超过 10 秒也要发（否则低频玩家的事件永远发不出去）")
    void batchingFlushesOnTimeout() {
        TrackBatcher<Item> batcher = batcher();
        batcher.track(item("startup"), 1000L);
        batcher.track(item("login"), 2000L);

        assertThat(batcher.tick(5000L)).as("才过 4 秒").isNull();
        assertThat(batcher.tick(10_999L)).as("距首条事件 9.999 秒").isNull();

        TrackBatcher.Batch<Item> batch = batcher.tick(11_000L);
        assertThat(batch).as("距首条事件 10 秒 ⇒ 触发").isNotNull();
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.reason()).contains("秒");
        assertThat(batcher.pendingCount()).isZero();
    }

    @Test
    @DisplayName("时间窗从队首事件算起，而不是从上一次发送后算起")
    void flushWindowAnchorsOnOldestEvent() {
        TrackBatcher<Item> batcher = batcher();
        batcher.track(item("a"), 0L);
        batcher.track(item("b"), 9 * SECOND);
        // 距队首已过 10 秒 ⇒ 该发，即使第二条才进来 1 秒
        assertThat(batcher.tick(10 * SECOND)).isNotNull();
    }

    @Test
    @DisplayName("切后台/崩溃前可强制冲刷：最后几个事件往往正是解释崩溃原因的那几个")
    void flushNowEmptiesQueue() {
        TrackBatcher<Item> batcher = batcher();
        batcher.track(item("a"), 1000L);
        assertThat(batcher.flushNow("切后台").size()).isEqualTo(1);
        assertThat(batcher.flushNow("再切一次")).as("空队列不产生空批次").isNull();
        assertThat(batcher.pendingCount()).isZero();
    }

    @Test
    @DisplayName("内存不变量：无论灌多少条，待发队列恒小于 maxBatchSize（断网也撑不爆内存）")
    void queueIsBoundedByConstruction() {
        TrackBatcher<Item> small = new TrackBatcher<>(new TrackBatcher.Rules(10, 10 * SECOND));
        for (int i = 0; i < 20; i++) {
            small.track(item("evt_" + i), 1000L + i);
            assertThat(small.pendingCount()).as("灌到第 %d 条时", i + 1).isLessThan(10);
        }
        assertThat(small.pendingCount()).as("20 条正好两批，全部交出").isZero();
        assertThat(small.batchCount()).isEqualTo(2);

        // 单批上限为 1 时也不该丢：每条自成一批，队列立刻清空
        TrackBatcher<Item> oneByOne = new TrackBatcher<>(new TrackBatcher.Rules(1, 10 * SECOND));
        for (int i = 0; i < 5; i++) {
            assertThat(oneByOne.track(item("evt_" + i), i).size()).isEqualTo(1);
        }
        assertThat(oneByOne.pendingCount()).isZero();
        assertThat(oneByOne.batchCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("null 条目被拒；条目内容本身不校验（字典是运营的资产，校验属于边界职责）")
    void nullItemIsRejectedButContentIsNot() {
        assertThatThrownBy(() -> batcher().track(null, 1000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为 null");
        // 核心层不认识事件名字典：任何非 null 条目都能被攒批，
        // 「事件名不得为空」这条校验在服务端读 HTTP 请求体时做，在客户端读 UI 输入时做
        assertThat(batcher().track(item(" "), 1000L)).isNull();
    }

    @Test
    @DisplayName("批量规则构造期校验：flushInterval 为 0 等于逐条上报，maxBatchSize 为 0 等于永远不发")
    void batcherRulesAreValidated() {
        assertThatThrownBy(() -> new TrackBatcher.Rules(0, SECOND))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBatchSize");
        assertThatThrownBy(() -> new TrackBatcher.Rules(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("那就是禁止项说的逐条上报");
    }

    // ---------- 版本检查与强制更新（验收 8） ----------

    private static ReleaseGate gate(String latest, String minSupported, String gray) {
        return new ReleaseGate(new ReleaseGate.Rules(latest, minSupported,
                com.ironoath.common.num.FixedPoint.parse(gray),
                "当前版本过低，请更新到 " + latest + " 后再进入游戏"));
    }

    @Test
    @DisplayName("验收8：低于最低可玩版本的客户端收到强制更新，且提示文案说明为什么")
    void outdatedClientIsForcedToUpdate() {
        ReleaseGate gate = gate("1.4.0", "1.2.0", "1.0");
        ReleaseGate.Verdict old = gate.check("1.1.9", "p1");
        assertThat(old.forceUpdate()).isTrue();
        assertThat(old.notice()).contains("版本过低").contains("1.4.0");
        assertThat(old.latestVersion()).isEqualTo("1.4.0");

        assertThat(gate.check("1.2.0", "p1").forceUpdate()).as("等于最低版本 ⇒ 可玩").isFalse();
        assertThat(gate.check("1.4.0", "p1").forceUpdate()).isFalse();
        assertThat(gate.check("1.2.0", "p1").notice()).as("不强制更新时不该打扰玩家").isNull();
    }

    @Test
    @DisplayName("版本号按段比较：1.10.0 高于 1.9.0（字符串比较会让全服被强制更新）")
    void versionComparisonIsSegmentWise() {
        assertThat(ReleaseGate.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(ReleaseGate.compareVersions("1.9.0", "1.10.0")).isNegative();
        assertThat(ReleaseGate.compareVersions("1.2.3", "1.2.3")).isZero();
        assertThat(ReleaseGate.compareVersions("1.2", "1.2.0")).as("缺段按 0 补齐").isZero();
        assertThat(ReleaseGate.compareVersions("2.0.0", "1.99.99")).isPositive();
        assertThat(ReleaseGate.compareVersions("1.4.0-beta", "1.4.0")).as("带后缀只取数字前缀").isZero();
        assertThat(ReleaseGate.compareVersions("1.02", "1.2")).as("前导零不改变段值").isZero();

        // 段长超过 long 时不能塌回 0：那会让「号特别长的那个」判成最旧，
        // 而最低版本判定一旦反向，被强制更新的恰好是最新的客户端。
        assertThat(ReleaseGate.compareVersions("1.99999999999999999999.0", "1.2.0"))
                .as("溢出段必须钳在上限，比较结果不许反向").isPositive();
        assertThat(ReleaseGate.compareVersions("1.2.0", "1.99999999999999999999.0")).isNegative();

        // 这个 bug 的实际后果：minSupported=1.9.0 时，1.10.0 会被判为更旧
        ReleaseGate gate = gate("1.11.0", "1.9.0", "1.0");
        assertThat(gate.check("1.10.0", "p1").forceUpdate())
                .as("1.10.0 高于最低版本 1.9.0，不该被强制更新").isFalse();
    }

    @Test
    @DisplayName("minSupported 高于 latest 时在构造期就炸：那会让全服被要求更新到一个不存在的版本")
    void rulesRejectImpossibleVersionWindow() {
        assertThatThrownBy(() -> gate("1.2.0", "1.5.0", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全服进不去");
        assertThatThrownBy(() -> new ReleaseGate.Rules("1.0.0", "1.0.0", 5000L, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forceUpdateNotice");
    }

    // ---------- 灰度 ----------

    @Test
    @DisplayName("灰度按 playerId 稳定哈希：同一个玩家每次判定结果一致（随机会让他在灰度内外横跳）")
    void grayAssignmentIsStablePerPlayer() {
        ReleaseGate gate = gate("1.4.0", "1.0.0", "0.20");
        boolean first = gate.inGray("player-abc");
        for (int i = 0; i < 50; i++) {
            assertThat(gate.inGray("player-abc")).isEqualTo(first);
        }
        assertThat(gate.check("1.0.0", "player-abc").grayEnabled()).isEqualTo(first);
    }

    @Test
    @DisplayName("灰度比例大致生效，且 0% / 100% 是两个硬边界")
    void grayPercentIsRespected() {
        ReleaseGate none = gate("1.4.0", "1.0.0", "0.0");
        ReleaseGate all = gate("1.4.0", "1.0.0", "1.0");
        for (int i = 0; i < 200; i++) {
            assertThat(none.inGray("p" + i)).isFalse();
            assertThat(all.inGray("p" + i)).isTrue();
        }

        ReleaseGate twenty = gate("1.4.0", "1.0.0", "0.20");
        int in = 0;
        for (int i = 0; i < 5000; i++) {
            if (twenty.inGray("player-" + i)) {
                in++;
            }
        }
        assertThat(in).as("5000 个玩家里 20% 灰度，允许抽样波动").isBetween(700, 1300);
    }

    @Test
    @DisplayName("未登录（无 playerId）不进灰度：灰度批次里的崩溃要能归因到具体玩家")
    void anonymousPlayersAreNotInGray() {
        ReleaseGate gate = gate("1.4.0", "1.0.0", "1.0");
        assertThat(gate.inGray(null)).isFalse();
        assertThat(gate.inGray("")).isFalse();
    }

    @Test
    @DisplayName("强制更新与灰度是正交的：灰度 5% 不等于另外 95% 的人不能玩")
    void forceUpdateAndGrayAreIndependent() {
        ReleaseGate gate = gate("1.4.0", "1.2.0", "0.05");
        int forced = 0;
        int gray = 0;
        for (int i = 0; i < 1000; i++) {
            ReleaseGate.Verdict verdict = gate.check("1.3.0", "player-" + i);
            if (verdict.forceUpdate()) {
                forced++;
            }
            if (verdict.grayEnabled()) {
                gray++;
            }
        }
        assertThat(forced).as("1.3.0 高于最低版本 1.2.0，没有人被强制更新").isZero();
        assertThat(gray).as("只有约 5% 进灰度").isLessThan(120);
        assertThat(gray).isPositive();
    }

    // ---------- 配置热更（验收 7） ----------

    private static ReleaseGate.TableMeta table(String name, String version, String hash) {
        return new ReleaseGate.TableMeta(name, version, hash);
    }

    @Test
    @DisplayName("验收7：内容变了 hash 就变，客户端据此拉新表 —— 不改包生效")
    void outdatedTablesAreDetectedByHash() {
        ReleaseGate.Manifest server = new ReleaseGate.Manifest("m5", List.of(
                table("unit", "2", "hash-a"),
                table("global", "29", "hash-b"),
                table("shop", "1", "hash-c")));

        Map<String, String> client = new HashMap<>();
        client.put("unit", "hash-a");
        client.put("global", "hash-b");
        client.put("shop", "hash-c");
        assertThat(ReleaseGate.outdatedTables(server, client)).as("全都一致 ⇒ 无需更新").isEmpty();

        // 服务端改了 global 的内容（hash 变），但**忘了升版本号**
        ReleaseGate.Manifest changed = new ReleaseGate.Manifest("m6", List.of(
                table("unit", "2", "hash-a"),
                table("global", "29", "hash-B-NEW"),
                table("shop", "1", "hash-c")));
        assertThat(ReleaseGate.outdatedTables(changed, client))
                .as("版本号没变也必须被发现：hash 由内容算出，改一个字符它就变")
                .containsExactly("global");

        // 客户端缺一张表（新装或首次登录）
        client.remove("shop");
        assertThat(ReleaseGate.outdatedTables(changed, client)).containsExactly("global", "shop");
        // 客户端一张表都没有
        assertThat(ReleaseGate.outdatedTables(changed, Map.of()))
                .containsExactly("unit", "global", "shop");
        assertThat(ReleaseGate.outdatedTables(changed, null))
                .as("null 当作空表处理").containsExactly("unit", "global", "shop");
    }

    @Test
    @DisplayName("清单构造期校验：hash 缺失、表重复、清单为空都要炸")
    void manifestIsStrictlyValidated() {
        assertThatThrownBy(() -> table("unit", "2", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("改了内容忘了升版本");
        assertThatThrownBy(() -> table("unit", " ", "h"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("版本不得为空");
        assertThatThrownBy(() -> new ReleaseGate.Manifest("m1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空清单会让客户端以为所有表都不需要更新");
        assertThatThrownBy(() -> new ReleaseGate.Manifest("m1",
                List.of(table("unit", "1", "a"), table("unit", "2", "b"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("出现了两次");

        ReleaseGate.Manifest manifest = new ReleaseGate.Manifest("m1",
                List.of(table("unit", "1", "a"), table("global", "2", "b")));
        assertThat(manifest.table("unit").hash()).isEqualTo("a");
        assertThat(manifest.table("nope")).isNull();
    }

    @Test
    @DisplayName("灰度比例越界在构造期就拒绝（超过 100% 没有意义，负数更没有）")
    void grayPercentIsRangeChecked() {
        assertThatThrownBy(() -> gate("1.4.0", "1.0.0", "1.5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grayPercent");
        assertThatThrownBy(() -> gate("1.4.0", "1.0.0", "-0.1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("grayPercent");
    }

    @Test
    @DisplayName("一批埋点从入队到发出的完整链路：10 条内不发、超时发、强制发，批数可观测")
    void endToEndBatching() {
        TrackBatcher<Item> batcher = batcher();
        List<TrackBatcher.Batch<Item>> sent = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            TrackBatcher.Batch<Item> batch = batcher.track(item("evt_" + i), i * SECOND);
            if (batch != null) {
                sent.add(batch);
            }
            TrackBatcher.Batch<Item> timed = batcher.tick(i * SECOND);
            if (timed != null) {
                sent.add(timed);
            }
        }
        int total = 0;
        for (TrackBatcher.Batch<Item> batch : sent) {
            total += batch.size();
        }
        assertThat(batcher.pendingCount() + total)
                .as("35 条事件必须全部有下落：已发 + 在队，一条都不能凭空消失").isEqualTo(35);
        assertThat(batcher.batchCount()).as("批量生效 ⇒ 批数远小于事件数").isLessThan(10);
    }
}
