package com.ironoath.core.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：社交聚合的<b>内容版本语义</b> —— 每个真正改变状态的方法 +1，非法/空操作不推进。
 * 依赖：无（纯领域，零框架）。
 *
 * <p><b>为什么单开一条测试，而不是在等价测试里顺带断言</b>：版本号是乐观锁（CAS）的期望值来源，
 * 它的正确性有两半 —— 存储层"版本对不上就拒绝"（由 {@code SocialStoreEquivalenceTest} 钉住），
 * 以及领域层"每次变更都恰好 +1"（本类钉住）。漏 bump 的症状是<b>静默</b>的：
 * 两次不同的改动带同一个版本，第二次会被 CAS 当成重放放行，后写覆盖先写；
 * 多 bump 的症状则是所有人都拿不到正确版本，每次保存都冲突。两半都必须在，
 * 所以两半各有一个家，而不是只测存储那一半。
 */
class SocialVersionTest {

    private static final long NOW = 1_800_000_000_000L;

    // ---------- 夹具（与 SocialSystemTest 同源的最小规则） ----------

    private static Squad.Rules squadRules() {
        return new Squad.Rules(List.of(
                new Squad.LevelRule(1, 5, 5, 5, FixedPoint.of(1) / 100, false),
                new Squad.LevelRule(2, 8, 8, 5, FixedPoint.of(1) / 100, true),
                new Squad.LevelRule(3, 10, 8, 5, FixedPoint.of(1) / 100, true)),
                5, 1, 500, FixedPoint.parse("1.6"));
    }

    private static Alliance.LevelRule tier(long level, long cap, long territory, String techBonus) {
        return new Alliance.LevelRule(level, cap, 10, 3, territory, 20,
                FixedPoint.parse(techBonus), 3);
    }

    private static Alliance.Rules allianceRules() {
        return new Alliance.Rules(
                List.of(tier(1, 30, 1, "0.0"), tier(3, 50, 2, "0.25"), tier(5, 80, 3, "0.50"),
                        tier(7, 120, 4, "0.75"), tier(9, 150, 5, "1.00")),
                10, 3,
                500L,
                86400 * 1000L,
                20000L,
                FixedPoint.parse("1.8"),
                List.of(
                        new Alliance.DonateTier(0, null, 0, 0, 100, 10, 200),
                        new Alliance.DonateTier(1, "GRAIN", 5000, 0, 600, 60, 1200),
                        new Alliance.DonateTier(2, null, 0, 100, 2500, 250, 5000)),
                FixedPoint.parse("1.15"));
    }

    private static Rally.Rules rallyRules() {
        return new Rally.Rules(2, 60_000L, 600_000L);
    }

    // ---------- 小队 ----------

    @Test
    @DisplayName("小队：每个真正改变状态的方法恰好把版本推进 1")
    void squadEveryMutationAdvancesVersionExactlyOnce() {
        Squad squad = Squad.create("s1", "铁血", "leader", squadRules());
        assertThat(squad.version()).as("创建即为 1：0 留给「我认为它不存在」").isEqualTo(1L);

        long v = squad.version();
        squad.join("m1", 5);
        assertThat(squad.version()).as("join").isEqualTo(++v);
        squad.join("m2", 5);
        assertThat(squad.version()).as("join 第二人").isEqualTo(++v);
        squad.completeDailyQuest(10L, 1L);
        assertThat(squad.version()).as("每日任务发币").isEqualTo(++v);
        squad.spendSquadCoin("m1", 3L);
        assertThat(squad.version()).as("花小队币").isEqualTo(++v);
        squad.addExp(100L);
        assertThat(squad.version()).as("加活跃度").isEqualTo(++v);
        squad.transferLeadership("leader", "m1");
        assertThat(squad.version()).as("转让队长").isEqualTo(++v);
        squad.join("m3", 5);
        assertThat(squad.version()).as("第三人加入").isEqualTo(++v);
        squad.kick("m1", "m2");
        assertThat(squad.version()).as("踢人").isEqualTo(++v);
        squad.join("m2", 5);
        assertThat(squad.version()).as("被踢的人可以重新加入").isEqualTo(++v);
        squad.leave("m3");
        assertThat(squad.version()).as("退队").isEqualTo(++v);
        squad.memberLeavesAlliance("m2");
        assertThat(squad.version()).as("队员退盟（同时离开分队）").isEqualTo(++v);
        squad.attachToAlliance("a1");
        assertThat(squad.version()).as("转分队").isEqualTo(++v);
        squad.detachFromAlliance();
        assertThat(squad.version()).as("全员退盟").isEqualTo(++v);
        squad.disband(NOW);
        assertThat(squad.version()).as("解散").isEqualTo(++v);
    }

    @Test
    @DisplayName("小队：非法调用与空操作不推进版本（拒绝的请求不许占用一个版本号）")
    void squadInvalidCallsDoNotAdvanceVersion() {
        Squad squad = Squad.create("s1", "铁血", "leader", squadRules());
        squad.join("m1", 5);
        squad.join("m2", 5);
        squad.join("m3", 5);
        squad.join("m4", 5);
        long full = squad.version();

        assertThatThrownBy(() -> squad.join("m5", 5)).isInstanceOf(IllegalStateException.class);
        assertThat(squad.version()).as("满员被拒").isEqualTo(full);
        assertThatThrownBy(() -> squad.join("m1", 5)).isInstanceOf(IllegalStateException.class);
        assertThat(squad.version()).as("重复加入被拒").isEqualTo(full);
        assertThatThrownBy(() -> squad.spendSquadCoin("m1", 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(squad.version()).as("余额不足被拒").isEqualTo(full);
        assertThatThrownBy(() -> squad.addExp(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(squad.version()).as("非正活跃度被拒").isEqualTo(full);
        assertThatThrownBy(() -> squad.leave("leader")).isInstanceOf(IllegalStateException.class);
        assertThat(squad.version()).as("队长直接退队被拒").isEqualTo(full);

        squad.disband(NOW);
        long disbanded = squad.version();
        assertThatThrownBy(() -> squad.addExp(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(squad.version()).as("已解散后再变更被拒").isEqualTo(disbanded);
    }

    // ---------- 集结 ----------

    @Test
    @DisplayName("集结：加入/退出/取消/出发/到达各自恰好推进 1；发起人退出等于取消也推进")
    void rallyEveryMutationAdvancesVersionExactlyOnce() {
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), List.of(), 5,
                120_000L, NOW, rallyRules(), 10L, 20L, "MONSTER");
        assertThat(rally.version()).isEqualTo(1L);

        long v = rally.version();
        rally.join("m2", Map.of("unit_infantry_t1", 50L), List.of());
        assertThat(rally.version()).as("加入").isEqualTo(++v);
        rally.quit("m2");
        assertThat(rally.version()).as("退出").isEqualTo(++v);
        rally.join("m2", Map.of("unit_infantry_t1", 50L), List.of());
        assertThat(rally.version()).as("再次加入").isEqualTo(++v);
        rally.depart(NOW + 60_000L);
        assertThat(rally.version()).as("出发").isEqualTo(++v);
        rally.arrive();
        assertThat(rally.version()).as("到达").isEqualTo(++v);

        Rally cancelled = Rally.initiate("r2", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), List.of(), 5,
                120_000L, NOW, rallyRules(), 10L, 20L, "MONSTER");
        long c = cancelled.version();
        cancelled.cancel("leader");
        assertThat(cancelled.version()).as("发起人取消").isEqualTo(++c);

        Rally quitByInitiator = Rally.initiate("r3", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), List.of(), 5,
                120_000L, NOW, rallyRules(), 10L, 20L, "MONSTER");
        long q = quitByInitiator.version();
        quitByInitiator.quit("leader");
        assertThat(quitByInitiator.status()).isEqualTo(Rally.Status.CANCELLED);
        assertThat(quitByInitiator.version()).as("发起人退出等于取消").isEqualTo(++q);

        Rally aborted = Rally.initiate("r4", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), List.of(), 5,
                120_000L, NOW, rallyRules(), 10L, 20L, "MONSTER");
        aborted.join("m2", Map.of("unit_infantry_t1", 50L), List.of());
        aborted.depart(NOW + 60_000L);
        long a = aborted.version();
        aborted.abortDeparted();
        assertThat(aborted.version()).as("撤销出发").isEqualTo(++a);
    }

    @Test
    @DisplayName("集结：非法迁移不推进版本")
    void rallyInvalidCallsDoNotAdvanceVersion() {
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), List.of(), 5,
                120_000L, NOW, rallyRules(), 10L, 20L, "MONSTER");
        long v = rally.version();

        assertThatThrownBy(() -> rally.arrive()).isInstanceOf(IllegalStateException.class);
        assertThat(rally.version()).as("未出发不能到达").isEqualTo(v);
        assertThatThrownBy(() -> rally.cancel("other")).isInstanceOf(IllegalStateException.class);
        assertThat(rally.version()).as("非发起人不能取消").isEqualTo(v);
        assertThatThrownBy(() -> rally.depart(NOW + 60_000L)).isInstanceOf(IllegalStateException.class);
        assertThat(rally.version()).as("人数不足不能出发").isEqualTo(v);
        assertThatThrownBy(() -> rally.join("leader", Map.of("unit_infantry_t1", 1L), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rally.version()).as("重复加入被拒").isEqualTo(v);
    }

    // ---------- 联盟 ----------

    @Test
    @DisplayName("联盟：日切只在真的清掉了当日捐献记录时推进版本")
    void allianceRollDayAdvancesOnlyWhenThereIsSomethingToClear() {
        Alliance alliance = Alliance.create("a1", "铁誓同盟", "IRON", "leader", 5000L, allianceRules());
        assertThat(alliance.version()).isEqualTo(1L);

        long empty = alliance.version();
        alliance.rollDay();
        assertThat(alliance.version()).as("没有记录时清空是空操作").isEqualTo(empty);

        alliance.donate("leader", 0, "2026-09-12");
        assertThat(alliance.version()).as("捐献推进").isEqualTo(empty + 1);
        long donated = alliance.version();
        alliance.rollDay();
        assertThat(alliance.version()).as("清掉记录必须推进").isEqualTo(donated + 1);
        assertThat(alliance.donatedToday("leader", "2026-09-12")).isZero();
    }

    @Test
    @DisplayName("联盟：非法调用不推进版本")
    void allianceInvalidCallsDoNotAdvanceVersion() {
        Alliance alliance = Alliance.create("a1", "铁誓同盟", "IRON", "leader", 5000L, allianceRules());
        long v = alliance.version();

        assertThatThrownBy(() -> alliance.join("leader")).isInstanceOf(IllegalStateException.class);
        assertThat(alliance.version()).as("重复入盟被拒").isEqualTo(v);
        assertThatThrownBy(() -> alliance.donate("outsider", 0, "2026-09-12"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(alliance.version()).as("非成员捐献被拒").isEqualTo(v);
        assertThatThrownBy(() -> alliance.donate("leader", 0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(alliance.version()).as("空 dayKey 被拒").isEqualTo(v);
    }
}