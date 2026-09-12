package com.ironoath.core.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：B10 小队与联盟核心规则的单测 —— 覆盖验收 2/3/4/8/9/11 这六条可自动判定的项，
 * 以及禁止项「不要让小队互助与联盟帮助简单叠加」。
 * 依赖：JUnit 5 + AssertJ + game-core 的 social 包（纯 Java，零框架）。
 *
 * <p>夹具里的数值直接抄 contract/config 的 squad_config / alliance_config / global：
 * 5→8→10、30→50→80→120→150、扩容 base 20000 增长 1.8、帮助每次 1% 上限 50%、
 * 聊天 3 次 / 10 秒。手抄配置值到测试夹具有漂移风险（与 BattleSimulatorTest 同一类问题，
 * 已记入待办清单），但这些数字同时是 B10 文档明写的验收标准，
 * 所以「表值 == 文档值」这一层由 SquadAllianceConfigTest 在 game-config 侧断言。
 */
class SocialSystemTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;

    // ---------- 夹具 ----------

    /** squad_config 的三行：5（创建）→ 8（队长主城 8 级）→ 10（小队等级 3）。 */
    private static Squad.Rules squadRules() {
        return new Squad.Rules(List.of(
                new Squad.LevelRule(1, 5, 5, 5, FixedPoint.of(1) / 100, false),
                new Squad.LevelRule(2, 8, 8, 5, FixedPoint.of(1) / 100, true),
                new Squad.LevelRule(3, 10, 8, 5, FixedPoint.of(1) / 100, true)),
                5, 1, 500, FixedPoint.parse("1.6"));
    }

    /** alliance_config 的五行：30(Lv1) → 50(Lv3) → 80(Lv5) → 120(Lv7) → 150(Lv9)。 */
    private static Alliance.LevelRule tier(long level, long cap, long territory, String techBonus) {
        return new Alliance.LevelRule(level, cap, 10, 3, territory, 20,
                FixedPoint.parse(techBonus), 3);
    }

    private static Alliance.Rules allianceRules() {
        return new Alliance.Rules(
                List.of(tier(1, 30, 1, "0.0"), tier(3, 50, 2, "0.25"), tier(5, 80, 3, "0.50"),
                        tier(7, 120, 4, "0.75"), tier(9, 150, 5, "1.00")),
                10, 3,
                500L,                       // ALLIANCE_CREATE_COST_GOLD
                86400 * SECOND,             // ALLIANCE_DISBAND_PROTECT_SECONDS
                20000L,                     // ALLIANCE_EXPAND_COST_BASE
                FixedPoint.parse("1.8"),    // ALLIANCE_EXPAND_COST_GROWTH
                List.of(
                        new Alliance.DonateTier(0, null, 0, 0, 100, 10, 200),
                        new Alliance.DonateTier(1, "GRAIN", 5000, 0, 600, 60, 1200),
                        new Alliance.DonateTier(2, null, 0, 100, 2500, 250, 5000)),
                FixedPoint.parse("1.15"));
    }

    private static Alliance newAlliance(String leader) {
        return Alliance.create("a1", "铁誓同盟", "IRON", leader, 5000L, allianceRules());
    }

    /**
     * 公账里有 {@code fund} 的联盟。
     *
     * <p>{@code Alliance.create} 的第 5 个参数是<b>创建者的金币余额</b>（用来验创建费），
     * 不是联盟资金 —— 公账从 0 起，靠捐献或活动收入累积。科技用例要先有钱。
     */
    private static Alliance allianceWithFund(long fund) {
        Alliance alliance = newAlliance("leader");
        if (fund > 0L) {
            alliance.addFund(fund);
        }
        return alliance;
    }

    // ---------- 验收 3：人数上限 ----------

    @Test
    @DisplayName("验收3：小队人数上限 5→8→10，第二档的门槛是队长主城 8 级而不是小队等级")
    void squadMemberCapFollowsLevelAndLeaderCityLevel() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());

        // Lv1：无论队长主城多高都只有 5 人
        assertThat(squad.memberCap(5)).isEqualTo(5);
        assertThat(squad.memberCap(20)).isEqualTo(5);

        // 升到 Lv2 但队长主城还停在 7 级 ⇒ 仍然是 5 人
        grantExpToLevel(squad, 2);
        assertThat(squad.memberCap(7)).as("队长主城未到 8 级，第二档不生效").isEqualTo(5);

        // 队长主城到 8 级 ⇒ 放开到 8 人
        assertThat(squad.memberCap(8)).isEqualTo(8);

        grantExpToLevel(squad, 3);
        assertThat(squad.memberCap(8)).isEqualTo(10);
        assertThat(squad.level()).isEqualTo(3);
    }

    @Test
    @DisplayName("人数上限递减的配置要在构造期就炸：递减意味着升级会把成员挤出去")
    void squadRulesRejectNonMonotonicCap() {
        assertThatThrownBy(() -> new Squad.Rules(List.of(
                new Squad.LevelRule(1, 10, 5, 5, 100, false),
                new Squad.LevelRule(2, 5, 5, 5, 100, true)), 5, 1, 500, FixedPoint.SCALE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单调不减");
    }

    @Test
    @DisplayName("验收3：联盟人数上限 30→50→80→120→150，且必须付费扩容才生效")
    void allianceMemberCapRequiresPaidExpansion() {
        Alliance alliance = newAlliance("leader");
        assertThat(alliance.effectiveMemberCap()).as("Lv1 的 30 人是创建时自带的，不用付费").isEqualTo(30);

        // 等级够了但没付费扩容 ⇒ 上限仍然是 30
        alliance.addExp(alliance.expPerLevel() * 10);
        assertThat(alliance.level()).isEqualTo(9);
        assertThat(alliance.effectiveMemberCap()).as("不付费就不放开：否则扩容这个资金消耗点会退化成数字变化")
                .isEqualTo(30);

        alliance.addFund(1_000_000L);
        assertThat(alliance.expand().memberCap()).isEqualTo(50);
        assertThat(alliance.expand().memberCap()).isEqualTo(80);
        assertThat(alliance.expand().memberCap()).isEqualTo(120);
        assertThat(alliance.expand().memberCap()).isEqualTo(150);
        assertThatThrownBy(alliance::expand)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("最高档位");
    }

    @Test
    @DisplayName("验收3：扩容消耗按 1.8 几何增长，四次合计约 23.7 万联盟资金")
    void allianceExpandCostGrowsGeometrically() {
        Alliance.Rules rules = allianceRules();
        assertThat(rules.expandCost(0)).isEqualTo(20000L);
        assertThat(rules.expandCost(1)).isEqualTo(36000L);
        assertThat(rules.expandCost(2)).isEqualTo(64800L);
        assertThat(rules.expandCost(3)).isEqualTo(116640L);
        assertThat(rules.expandCost(0) + rules.expandCost(1) + rules.expandCost(2) + rules.expandCost(3))
                .as("中后期最重要的资金消耗点之一：总额必须真的吃紧")
                .isEqualTo(237440L);
        assertThatThrownBy(() -> rules.expandCost(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("偶数等级沿用低一档的上限：配置表只登记 1/3/5/7/9，不编造四个文档里没有的数字")
    void allianceEvenLevelReusesLowerTier() {
        Alliance alliance = newAlliance("leader");
        alliance.addFund(1_000_000L);
        // 推到 Lv4（偶数级）：查表规则是「取 allianceLevel <= 当前等级 的最高一行」，所以命中 Lv3 那行
        alliance.addExp(alliance.expPerLevel() * 3);
        assertThat(alliance.level()).isEqualTo(4);
        alliance.expand();   // 解锁 Lv3 的 50 人档

        assertThat(alliance.effectiveMemberCap()).isEqualTo(50);
        assertThat(alliance.territoryCap()).isEqualTo(2);
        assertThat(alliance.rallyCapacity()).isEqualTo(20);
        assertThat(alliance.techLevelCap(40)).as("Lv3 的 techCapBonus=0.25 ⇒ 40 × 1.25 = 50").isEqualTo(50);
    }

    // ---------- 验收 2：小队解散边界 ----------

    @Test
    @DisplayName("验收2：队长退盟但队员未退，小队自动解散且队员（不含队长）收到通知")
    void squadDisbandsWhenLeaderLeavesAlliance() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());
        squad.join("a", 5);
        squad.join("b", 5);
        squad.attachToAlliance("a1");
        assertThat(squad.isSubSquad()).isTrue();

        List<String> notified = squad.leaderLeavesAlliance(1000L);

        assertThat(squad.isDisbanded()).as("验收2 明写：小队自动解散").isTrue();
        assertThat(squad.memberCount()).isZero();
        assertThat(notified).as("队员必须收到通知，且不含自己做了决定的队长")
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("普通队员退盟只解除他自己的关系，小队完整保留（验收1：小队不被稀释）")
    void squadSurvivesWhenOrdinaryMemberLeavesAlliance() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());
        squad.join("a", 5);
        squad.join("b", 5);
        squad.attachToAlliance("a1");

        squad.memberLeavesAlliance("a");

        assertThat(squad.isDisbanded()).isFalse();
        assertThat(squad.isSubSquad()).as("队长还在盟里，小队仍然是分队").isTrue();
        assertThat(squad.memberIds()).containsExactly("leader", "b");
        assertThat(squad.level()).isEqualTo(1);
    }

    @Test
    @DisplayName("验收1：加入联盟不清空任何小队状态 —— 等级、活跃度、小队币一律保留")
    void joiningAllianceKeepsEverySquadState() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());
        squad.join("a", 5);
        grantExpToLevel(squad, 2);
        squad.completeDailyQuest(150, 20);
        long expBefore = squad.exp();
        long coinBefore = squad.squadCoinPool();

        squad.attachToAlliance("a1");

        assertThat(squad.level()).isEqualTo(2);
        assertThat(squad.exp()).isEqualTo(expBefore);
        assertThat(squad.squadCoinPool()).isEqualTo(coinBefore);
        assertThat(squad.memberIds()).hasSize(2);
        assertThat(squad.isSubSquad()).isTrue();
        // 分队状态下互助、集结、商店全部照常
        assertThat(squad.rallyCapacity()).isEqualTo(5);
        assertThat(squad.shopUnlocked()).isTrue();
    }

    @Test
    @DisplayName("独立小队的队长「退盟」是个不存在的操作，必须报错而不是静默解散")
    void independentSquadLeaderCannotLeaveAlliance() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());
        assertThatThrownBy(() -> squad.leaderLeavesAlliance(1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("独立状态");
    }

    @Test
    @DisplayName("队长不能直接退队：先转让或解散，否则小队会剩下没有责任人的成员")
    void squadLeaderCannotSimplyLeave() {
        Squad squad = Squad.create("s1", "五个人", "leader", squadRules());
        squad.join("a", 5);
        assertThatThrownBy(() -> squad.leave("leader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("转让");

        squad.transferLeadership("leader", "a");
        assertThat(squad.leaderId()).isEqualTo("a");
        assertThat(squad.roleOf("leader")).isEqualTo(SquadRole.MEMBER);
        squad.leave("leader");
        assertThat(squad.memberIds()).containsExactly("a");
    }

    // ---------- 验收 4：权限矩阵配置化 ----------

    @Test
    @DisplayName("验收4：权限完全由配置行决定 —— 新增一个权限位不改任何代码，行为随之变化")
    void permissionMatrixIsConfigDriven() {
        PermissionMatrix before = PermissionMatrix.of(List.of(
                grant(PermissionMatrix.Scope.ALLIANCE, "KICK_MEMBER", true, true, false),
                grant(PermissionMatrix.Scope.SQUAD, "KICK_MEMBER", true, false, false)));

        assertThat(before.allows(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.LEADER, "KICK_MEMBER")).isTrue();
        assertThat(before.allows(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.MEMBER, "KICK_MEMBER")).isFalse();
        assertThat(before.allows(PermissionMatrix.Scope.SQUAD, PermissionMatrix.Tier.OFFICER, "KICK_MEMBER"))
                .as("小队没有干部档，表里 allowOfficer=false").isFalse();

        // 只改配置：给成员档放开踢人，并新增一个此前不存在的权限位
        PermissionMatrix after = PermissionMatrix.of(List.of(
                grant(PermissionMatrix.Scope.ALLIANCE, "KICK_MEMBER", true, true, true),
                grant(PermissionMatrix.Scope.SQUAD, "KICK_MEMBER", true, false, false),
                grant(PermissionMatrix.Scope.ALLIANCE, "BUILD_FORTRESS", true, true, false)));

        assertThat(after.allows(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.MEMBER, "KICK_MEMBER"))
                .as("改配置就生效，代码零改动").isTrue();
        assertThat(after.allows(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.OFFICER, "BUILD_FORTRESS"))
                .as("新增的权限位不需要在代码里登记").isTrue();
        assertThat(after.permissionCount(PermissionMatrix.Scope.ALLIANCE)).isEqualTo(2);
    }

    @Test
    @DisplayName("未登记的权限位一律拒绝：默认放行意味着任何成员都能执行一个没人审过的操作")
    void unknownPermissionIsDenied() {
        PermissionMatrix matrix = PermissionMatrix.of(List.of(
                grant(PermissionMatrix.Scope.SQUAD, "KICK_MEMBER", true, false, false)));
        assertThat(matrix.allows(PermissionMatrix.Scope.SQUAD, PermissionMatrix.Tier.LEADER, "SOMETHING_NEW")).isFalse();
        assertThat(matrix.allows(PermissionMatrix.Scope.NATION, PermissionMatrix.Tier.LEADER, "KICK_MEMBER")).isFalse();
        assertThat(matrix.allows(null, PermissionMatrix.Tier.LEADER, "KICK_MEMBER")).isFalse();
    }

    @Test
    @DisplayName("permissionsOf 给出「我能做什么」的结论列表（下发给客户端），而不是整张矩阵")
    void permissionsOfReturnsConclusions() {
        PermissionMatrix matrix = PermissionMatrix.of(List.of(
                grant(PermissionMatrix.Scope.ALLIANCE, "REVIEW_APPLY", true, true, false),
                grant(PermissionMatrix.Scope.ALLIANCE, "INITIATE_RALLY", true, true, false),
                grant(PermissionMatrix.Scope.ALLIANCE, "KICK_MEMBER", true, false, false),
                grant(PermissionMatrix.Scope.ALLIANCE, "TRANSFER", true, false, false)));

        assertThat(matrix.permissionsOf(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.LEADER))
                .containsExactlyInAnyOrder("REVIEW_APPLY", "INITIATE_RALLY", "KICK_MEMBER", "TRANSFER");
        assertThat(matrix.permissionsOf(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.OFFICER))
                .containsExactlyInAnyOrder("REVIEW_APPLY", "INITIATE_RALLY");
        assertThat(matrix.permissionsOf(PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Tier.MEMBER)).isEmpty();
    }

    @Test
    @DisplayName("同一权限位登记两次要在构造期炸：两条规则意味着判定结果取决于遍历顺序")
    void duplicatePermissionIsRejected() {
        assertThatThrownBy(() -> PermissionMatrix.of(List.of(
                grant(PermissionMatrix.Scope.SQUAD, "KICK_MEMBER", true, false, false),
                grant(PermissionMatrix.Scope.SQUAD, "KICK_MEMBER", false, false, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复定义");
    }

    @Test
    @DisplayName("空矩阵要在构造期炸：所有判定都返回 false 会表现为「盟主什么都做不了」")
    void emptyMatrixIsRejected() {
        assertThatThrownBy(() -> PermissionMatrix.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("职位到档位的映射：长老与副盟主同档（role_permission 表只有三个 allow* 列）")
    void roleTierMapping() {
        assertThat(AllianceRole.LEADER.tier()).isEqualTo(PermissionMatrix.Tier.LEADER);
        assertThat(AllianceRole.OFFICER.tier()).isEqualTo(PermissionMatrix.Tier.OFFICER);
        assertThat(AllianceRole.ELDER.tier()).isEqualTo(PermissionMatrix.Tier.OFFICER);
        assertThat(AllianceRole.MEMBER.tier()).isEqualTo(PermissionMatrix.Tier.MEMBER);
        assertThat(SquadRole.LEADER.tier()).isEqualTo(PermissionMatrix.Tier.LEADER);
        assertThat(SquadRole.MEMBER.tier()).isEqualTo(PermissionMatrix.Tier.MEMBER);
        // 任命校验依赖 rank 的严格顺序
        assertThat(AllianceRole.LEADER.rank()).isGreaterThan(AllianceRole.OFFICER.rank());
        assertThat(AllianceRole.OFFICER.rank()).isGreaterThan(AllianceRole.ELDER.rank());
        assertThat(AllianceRole.ELDER.rank()).isGreaterThan(AllianceRole.MEMBER.rank());
    }

    @Test
    @DisplayName("副盟主不能任命不低于自己的职位，否则他能造出一个新盟主")
    void officerCannotAppointPeerOrHigher() {
        Alliance alliance = newAlliance("leader");
        alliance.join("officer");
        alliance.setRole("leader", "officer", AllianceRole.OFFICER);
        alliance.join("member");

        assertThatThrownBy(() -> alliance.setRole("officer", "member", AllianceRole.OFFICER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不低于自己");
        assertThatThrownBy(() -> alliance.setRole("officer", "member", AllianceRole.LEADER))
                .isInstanceOf(IllegalStateException.class);
        alliance.setRole("officer", "member", AllianceRole.ELDER);
        assertThat(alliance.roleOf("member")).isEqualTo(AllianceRole.ELDER);
    }

    // ---------- 验收 8：捐献 ----------

    @Test
    @DisplayName("验收8：捐献后联盟资金与个人贡献值同步增加，两者比例来自配置")
    void donateIncreasesFundAndContributionTogether() {
        Alliance alliance = newAlliance("leader");
        alliance.join("member");

        Alliance.Donation free = alliance.donate("member", 0, "20260908");
        assertThat(free.fundGained()).isEqualTo(100L);
        assertThat(free.contributionGained()).isEqualTo(10L);
        assertThat(free.fund()).isEqualTo(100L);
        assertThat(free.contribution()).isEqualTo(10L);

        Alliance.Donation gold = alliance.donate("member", 2, "20260908");
        assertThat(gold.fundGained()).isEqualTo(2500L);
        assertThat(gold.contributionGained()).isEqualTo(250L);
        assertThat(gold.fund()).as("资金累加").isEqualTo(2600L);
        assertThat(gold.contribution()).as("贡献值累加").isEqualTo(260L);
        assertThat(alliance.contributionOf("member")).isEqualTo(260L);
        assertThat(alliance.donatedToday("member", "20260908")).isEqualTo(2);
    }

    /**
     * 验收 8 的另一半。原文是「捐献后两者同步增加，<b>商店兑换正确扣减</b>」——
     * 只有加法的话这半句永远不可能被满足，而 B10 §表格明写「贡献值：可兑换联盟商店道具」。
     */
    @Test
    @DisplayName("贡献值可以花掉：兑换只动本人的贡献值，联盟公共资金一分不动")
    void contributionIsSpendable() {
        Alliance alliance = newAlliance("leader");
        alliance.join("member");
        alliance.donate("member", 2, "20260908");   // +2500 资金、+250 贡献
        long fundBefore = alliance.fund();

        assertThat(alliance.spendContribution("member", 30L)).isEqualTo(220L);
        assertThat(alliance.contributionOf("member")).isEqualTo(220L);
        assertThat(alliance.fund()).as("花的是成员自己的贡献值，不是公账").isEqualTo(fundBefore);

        alliance.join("other");
        alliance.donate("other", 0, "20260908");
        assertThat(alliance.contributionOf("other")).as("扣减只作用于本人，别的成员不受影响").isEqualTo(10L);
    }

    @Test
    @DisplayName("贡献值不足 / 非成员 / 额度非正都当场拒绝，且不能扣成负数")
    void spendContributionIsGuarded() {
        Alliance alliance = newAlliance("leader");
        alliance.join("member");
        alliance.donate("member", 0, "20260908");   // 10 点

        assertThatThrownBy(() -> alliance.spendContribution("member", 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("贡献值不足")
                .hasMessageContaining("需要 11").hasMessageContaining("当前 10");
        assertThat(alliance.contributionOf("member")).as("失败不能留下半个扣减").isEqualTo(10L);
        assertThatThrownBy(() -> alliance.spendContribution("stranger", 1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不是本联盟成员");
        assertThatThrownBy(() -> alliance.spendContribution("member", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正数");
        assertThat(alliance.spendContribution("member", 10L)).as("正好扣光是允许的").isZero();
    }

    // ---------- 联盟科技（B10 §2） ----------

    @Test
    @DisplayName("联盟科技：一次点几级就付那几级的价（按 growth 逐级累加），扣的是公账，效果按级线性放大")
    void allianceTechCostsCompoundAndChargeFund() {
        Alliance.Rules rules = allianceRules();
        Alliance alliance = allianceWithFund(5000L);
        assertThat(alliance.techLevel("atech_atk")).as("没研究过就是 0，账本里不存 0 占位").isZero();

        Alliance.Research first = alliance.researchTech("atech_atk", 1000L, 40, 150L, 1);
        assertThat(first.fundCost()).as("第 1 级就是基础价，不乘 growth").isEqualTo(1000L);
        assertThat(first.level()).isEqualTo(1);
        assertThat(first.fundAfter()).isEqualTo(4000L);
        assertThat(first.effectFixed()).as("单级 1.5%（定点 150）× 1 级").isEqualTo(150L);

        Alliance.Research next = alliance.researchTech("atech_atk", 1000L, 40, 150L, 2);
        assertThat(next.fundCost())
                .as("第 2、3 级要按各自递增后的价付，不是拿 1 级的价乘 2")
                .isEqualTo(rules.techCost(1000L, 2) + rules.techCost(1000L, 3));
        assertThat(next.level()).isEqualTo(3);
        assertThat(next.effectFixed()).as("效果按级线性累加").isEqualTo(450L);
        assertThat(alliance.fund()).isEqualTo(5000L - 1000L - next.fundCost());
        assertThat(alliance.techLevels()).containsOnlyKeys("atech_atk");
    }

    @Test
    @DisplayName("到顶 / 超点 / 钱不够 / 等级数非法，全部当场拒绝且不记账不扣钱")
    void allianceTechRejectsWithoutSideEffects() {
        Alliance alliance = allianceWithFund(5000L);

        assertThatThrownBy(() -> alliance.researchTech("atech_atk", 1000L, 40, 150L, 41))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("最多只能研究 40 级");
        assertThat(alliance.techLevel("atech_atk")).as("被拒的研究不得记账").isZero();
        assertThat(alliance.fund()).as("被拒的研究不得扣钱").isEqualTo(5000L);

        assertThatThrownBy(() -> alliance.researchTech("atech_atk", 6000L, 40, 150L, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("联盟资金不足");
        alliance.spendFund(4900L, "测试：把公账几乎花光");
        assertThatThrownBy(() -> alliance.researchTech("atech_atk", 1000L, 40, 150L, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("联盟资金不足").hasMessageContaining("需要 1000");
        assertThat(alliance.techLevel("atech_atk")).isZero();

        assertThatThrownBy(() -> alliance.researchTech("atech_atk", 1000L, 40, 150L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> alliance.researchTech(" ", 1000L, 40, 150L, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("techId");
    }

    @Test
    @DisplayName("科技上限随联盟等级放大（Lv1 用表里的 maxLevel，Lv3 的 techCapBonus=0.25 放大到 50）")
    void techCapGrowsWithAllianceLevel() {
        Alliance alliance = newAlliance("leader");
        assertThat(alliance.techLevelCap(40)).as("Lv1 的 bonus=0 ⇒ 上限就是表里的 maxLevel").isEqualTo(40);

        for (int i = 0; i < 400 && alliance.level() < 3; i++) {
            alliance.addExp(100L);
        }
        assertThat(alliance.level()).isEqualTo(3);
        assertThat(alliance.techLevelCap(40)).as("40 × (1 + 0.25)").isEqualTo(50);
    }

    /**
     * 联盟资金是<b>联盟级</b>共享资产，而应用层拿的是<b>玩家级</b>锁 —— 两个官员同时点研究时
     * 彼此不互斥。没有 {@code Alliance.researchTech} 上的监视器，「检查余额 → 扣款 → 抬等级」
     * 就会被穿过：公账扣成负数，或者两笔按同一等级计价（少收递增后的差价）。
     *
     * <p>5000 资金、1 级 1000 且逐级 ×1.15 ⇒ 恰好付得起 4 级（1000+1150+1323+1522=4995）。
     * 所以「成功次数、最终等级、余额」三个数在正确实现下都是确定的，不是"看运气"。
     */
    @Test
    @DisplayName("八个并发研究同一项科技：公账不会变负，等级与成功次数一致")
    void concurrentResearchCannotOverdrawFund() throws Exception {
        Alliance alliance = allianceWithFund(5000L);
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        alliance.researchTech("atech_atk", 1000L, 40, 150L, 1);
                        succeeded.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        // 钱不够被拒是正确行为
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("八个线程都就位后再放行").isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("5000 资金只够 4 级（价格逐级递增）").isEqualTo(4);
        assertThat(alliance.techLevel("atech_atk")).as("每笔成功都必须留下一格等级，不能丢更新")
                .isEqualTo(4);
        assertThat(alliance.fund()).as("公账不能被扣成负数").isGreaterThanOrEqualTo(0L);
        assertThat(alliance.fund())
                .as("停在「刚好付不起下一级」的位置：说明扣款与记账一一对应，没有漏扣也没有多扣")
                .isLessThan(alliance.researchCost(1000L, "atech_atk", 1));
    }

    @Test
    @DisplayName("每日捐献档数受 alliance_config.donationDailyCap 约束，跨天重置")
    void donateDailyCapResetsNextDay() {
        Alliance alliance = newAlliance("leader");
        alliance.donate("leader", 0, "20260908");
        alliance.donate("leader", 1, "20260908");
        alliance.donate("leader", 2, "20260908");
        assertThatThrownBy(() -> alliance.donate("leader", 0, "20260908"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("档位已用完");

        // 次日重新计数（dayKey 不同）
        Alliance.Donation next = alliance.donate("leader", 0, "20260909");
        assertThat(next.donateToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("捐献档位必须从 0 起连续编号，且付费档不能没有消耗、免费档不能有消耗")
    void donateTierShapeIsChecked() {
        assertThatThrownBy(() -> new Alliance.Rules(
                List.of(tier(1, 30, 1, "0.0")), 10, 3, 500, 1000, 100, FixedPoint.SCALE,
                // 形状合法但编号从 1 起：缺了 tier=0 那一档
                List.of(new Alliance.DonateTier(1, "GRAIN", 5000, 0, 100, 10, 10)), FixedPoint.SCALE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("从 0 起连续编号");

        assertThatThrownBy(() -> new Alliance.DonateTier(0, "GRAIN", 5000, 0, 100, 10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("免费档");

        assertThatThrownBy(() -> new Alliance.DonateTier(1, "GRAIN", 5000, 100, 100, 10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能消耗一种");
    }

    @Test
    @DisplayName("资金不足时扩容/研究失败且一分钱都不扣（不能先改状态再报错）")
    void spendFundIsAtomic() {
        Alliance alliance = newAlliance("leader");
        alliance.addFund(1000L);
        assertThatThrownBy(() -> alliance.spendFund(2000L, "研究联盟科技"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("资金不足");
        assertThat(alliance.fund()).isEqualTo(1000L);
        assertThat(alliance.spendFund(400L, "研究联盟科技")).isEqualTo(600L);
    }

    @Test
    @DisplayName("联盟科技上限随联盟等级放大：Lv1 取表值，Lv9 是表值的两倍")
    void techLevelCapScalesWithAllianceLevel() {
        Alliance alliance = newAlliance("leader");
        assertThat(alliance.techLevelCap(40)).as("Lv1 的 techCapBonus=0，取表值原样").isEqualTo(40);

        alliance.addFund(1_000_000L);
        alliance.addExp(alliance.expPerLevel() * 10);
        for (int i = 0; i < 4; i++) {
            alliance.expand();
        }
        assertThat(alliance.level()).isEqualTo(9);
        assertThat(alliance.paidCapTier()).isEqualTo(4);
        assertThat(alliance.techLevelCap(40)).as("Lv9 的 techCapBonus=1.0 ⇒ 两倍").isEqualTo(80);
    }

    // ---------- 验收 9：聊天限流 ----------

    @Test
    @DisplayName("验收9：同内容 10 秒内发 4 次，第 4 次被拦截；不同内容不受影响")
    void chatRateLimitBlocksFourthIdenticalMessage() {
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimiter.Rules(10 * SECOND, 3, 64));
        long now = 100_000L;

        assertThat(limiter.check("p1", "招人了", now).allowed()).isTrue();
        assertThat(limiter.check("p1", "招人了", now + SECOND).allowed()).isTrue();
        assertThat(limiter.check("p1", "招人了", now + 2 * SECOND).allowed()).isTrue();

        ChatRateLimiter.Verdict blocked = limiter.check("p1", "招人了", now + 3 * SECOND);
        assertThat(blocked.allowed()).as("验收9：第 4 次必须被拦截").isFalse();
        assertThat(blocked.hitsInWindow()).isEqualTo(3);
        assertThat(blocked.retryAfterMillis()).as("给 0 会让客户端立刻重发并再次被拦").isPositive();

        // 不同内容不受影响：小队集结时刷屏报坐标是正常行为
        assertThat(limiter.check("p1", "坐标 (120, 88)", now + 3 * SECOND).allowed()).isTrue();
        // 别人也不受影响
        assertThat(limiter.check("p2", "招人了", now + 3 * SECOND).allowed()).isTrue();
    }

    @Test
    @DisplayName("滑动窗口而不是固定窗口：窗口交界处连发 6 条同样会被拦")
    void chatRateLimitUsesSlidingWindow() {
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimiter.Rules(10 * SECOND, 3, 64));
        // 上个窗口末尾发 3 条
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check("p1", "广告", 9_000L + i).allowed()).isTrue();
        }
        // 固定窗口会在 10_000 重置计数并放行下面 3 条；滑动窗口只放行到第一条滑出为止
        assertThat(limiter.check("p1", "广告", 10_000L).allowed()).isFalse();
        // 等最早那条滑出窗口（9000 + 10000 = 19000 之后）
        assertThat(limiter.check("p1", "广告", 19_001L).allowed()).isTrue();
    }

    @Test
    @DisplayName("限流表的内存有上界：一个玩家发一万条不同内容也不会让跟踪键超过上限")
    void chatRateLimitBoundsMemory() {
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimiter.Rules(10 * SECOND, 3, 8));
        for (int i = 0; i < 10_000; i++) {
            limiter.check("p1", "内容 " + i, 100_000L + i);
        }
        assertThat(limiter.trackedKeys("p1")).isLessThanOrEqualTo(8);
        assertThat(limiter.trackedPlayers()).isEqualTo(1);

        limiter.forget("p1");
        assertThat(limiter.trackedKeys("p1")).isZero();
        assertThat(limiter.trackedPlayers()).isZero();
    }

    @Test
    @DisplayName("集结武将位按加入顺序抢、跨成员去重、总量受上限约束：落选者要有自己的原因")
    void rallyHeroSlotsFollowJoinOrderCapAndDedup() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), java.util.List.of("h_a", "h_b"),
                5, 0L, 1_000L, rules, 10L, 20L, "MONSTER");
        rally.join("a", Map.of("unit_infantry_t1", 50L), java.util.List.of("h_b", "h_c"));
        rally.join("b", Map.of("unit_infantry_t1", 50L), java.util.List.of("h_b"));

        // cap=3：leader 先占 h_a、h_b；a 的 h_b 与前者是同一个武将（DUPLICATE，不占位），
        // a 的 h_c 占第三个；b 只报了那个重复武将 ⇒ 它既不是「位满了」也不是「能上场」
        assertThat(rally.heroSlots(3)).containsExactly(
                new Rally.HeroSlot("leader", "h_a", Rally.HeroState.SELECTED),
                new Rally.HeroSlot("leader", "h_b", Rally.HeroState.SELECTED),
                new Rally.HeroSlot("a", "h_b", Rally.HeroState.DUPLICATE),
                new Rally.HeroSlot("a", "h_c", Rally.HeroState.SELECTED),
                new Rally.HeroSlot("b", "h_b", Rally.HeroState.DUPLICATE));
        assertThat(rally.selectedHeroes(3)).containsExactly("h_a", "h_b", "h_c");
        assertThat(rally.selectedHeroes(2))
                .as("上限收紧时是后面的整体落选，而不是每人保留一个：位是整支集结共用的")
                .containsExactly("h_a", "h_b");
    }

    // ---------- 验收 11：集结统一出发 ----------

    @Test
    @DisplayName("验收11：倒计时结束时统一出发，兵力按 unitId 合并且 Σ 守恒")
    void rallyDepartsTogetherWithConservedTroops() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t3", 500L, "unit_cavalry_t2", 200L), java.util.List.of(),
                20, 15 * MINUTE, 1_000L, rules, 100L, 200L, "PLAYER_CITY");

        rally.join("a", Map.of("unit_infantry_t3", 300L, "unit_archer_t1", 100L), java.util.List.of());
        rally.join("b", Map.of("unit_cavalry_t2", 50L), java.util.List.of());
        assertThat(rally.joinedCount()).isEqualTo(3);
        assertThat(rally.totalTroops()).as("准备期间就要能看到已凑了多少兵").isEqualTo(1150L);

        Rally.Departure departure = rally.depart(rally.prepareUntil());
        assertThat(departure.memberCount()).isEqualTo(3);
        assertThat(departure.mergedTroops()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "unit_archer_t1", 100L,
                "unit_cavalry_t2", 250L,
                "unit_infantry_t3", 800L));
        assertThat(departure.totalTroops()).as("Σ 守恒：合并后必须精确等于各人承诺之和")
                .isEqualTo(500 + 200 + 300 + 100 + 50);
        assertThat(rally.status()).isEqualTo(Rally.Status.DEPARTED);
    }

    @Test
    @DisplayName("出发后不可再加入或退出：否则会出现「已经打完的那一波里凭空多出一个人」")
    void rallyIsImmutableAfterDeparture() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), java.util.List.of(), 5, 10 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        rally.join("a", Map.of("unit_infantry_t1", 100L), java.util.List.of());
        rally.depart(rally.prepareUntil());

        assertThatThrownBy(() -> rally.join("b", Map.of("unit_infantry_t1", 10L), java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEPARTED");
        assertThatThrownBy(() -> rally.quit("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rally.depart(rally.prepareUntil())).isInstanceOf(IllegalStateException.class);
        rally.arrive();
        assertThat(rally.status()).isEqualTo(Rally.Status.ARRIVED);
    }

    @Test
    @DisplayName("人数不足下限时拒绝出发：一个人出发不叫集结，那只是普通出征")
    void rallyRequiresMinimumMembers() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                Map.of("unit_infantry_t1", 100L), java.util.List.of(), 5, 10 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        assertThatThrownBy(() -> rally.depart(rally.prepareUntil()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("人数不足");
        assertThat(rally.status()).as("出发失败必须留在准备中，不能把状态改坏").isEqualTo(Rally.Status.PREPARING);
    }

    @Test
    @DisplayName("准备时长被夹到配置区间而不是拒绝：滑块越界时拒绝会让人以为集结坏了")
    void rallyPrepareDurationIsClamped() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally tooShort = Rally.initiate("r1", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t1", 10L), java.util.List.of(), 20, MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        assertThat(tooShort.prepareMillis()).isEqualTo(10 * MINUTE);

        Rally tooLong = Rally.initiate("r2", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t1", 10L), java.util.List.of(), 20, 5 * 60 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        assertThat(tooLong.prepareMillis()).isEqualTo(30 * MINUTE);

        assertThatThrownBy(() -> Rally.initiate("r3", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t1", 10L), java.util.List.of(), 1, 10 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("永远无法出发");
    }

    @Test
    @DisplayName("重复加入与承诺零兵力都要拒绝：前者会让同一个人的兵被算两遍")
    void rallyRejectsDoubleJoinAndEmptyTroops() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t1", 100L), java.util.List.of(), 5, 10 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        rally.join("a", Map.of("unit_infantry_t1", 50L), java.util.List.of());

        assertThatThrownBy(() -> rally.join("a", Map.of("unit_infantry_t1", 50L), java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复加入");
        assertThatThrownBy(() -> rally.join("b", Map.of(), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须承诺兵力");
        assertThatThrownBy(() -> rally.join("c", Map.of("unit_infantry_t1", 0L), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为正");
    }

    @Test
    @DisplayName("发起人退出等于取消：没有人能替他指出兵")
    void initiatorQuitCancelsRally() {
        Rally.Rules rules = new Rally.Rules(2, 10 * MINUTE, 30 * MINUTE);
        Rally rally = Rally.initiate("r1", Rally.Scope.ALLIANCE, "a1", "leader",
                Map.of("unit_infantry_t1", 100L), java.util.List.of(), 5, 10 * MINUTE, 0L, rules, 100L, 200L, "PLAYER_CITY");
        rally.join("a", Map.of("unit_infantry_t1", 50L), java.util.List.of());
        rally.quit("leader");
        assertThat(rally.status()).isEqualTo(Rally.Status.CANCELLED);
    }

    // ---------- 禁止项：互助不得简单叠加 ----------

    @Test
    @DisplayName("禁止项：小队互助与联盟帮助共用每日额度，且单个目标有总加速上限")
    void helpDoesNotSimplyStack() {
        HelpLedger ledger = new HelpLedger(new HelpLedger.Rules(
                20, FixedPoint.parse("0.50"), FixedPoint.parse("0.01")));

        // 同一个帮助方帮同一个人的不同事项：共用一个每日额度
        for (int i = 0; i < 20; i++) {
            HelpLedger.Outcome outcome = ledger.help("helper", "target:req" + i, 1_000L);
            assertThat(outcome).as("第 %d 次应当成功", i + 1).isNotNull();
            assertThat(outcome.grantedFixed()).isEqualTo(FixedPoint.parse("0.01"));
        }
        assertThat(ledger.remainingToday("helper", 1_000L)).isZero();
        assertThat(ledger.help("helper", "target:req20", 1_000L))
                .as("额度用完后返回 null，由调用方报 SOCIAL_HELP_DAILY_LIMIT").isNull();

        // 总上限：150 人的联盟每人帮一次也到不了 150%
        HelpLedger.Outcome capped = null;
        for (int i = 0; i < 60; i++) {
            capped = ledger.help("helper" + i, "target:same", 1_000L);
        }
        assertThat(capped).isNotNull();
        assertThat(ledger.speedupOf("target:same")).as("封顶在 50%").isEqualTo(FixedPoint.parse("0.50"));
        assertThat(capped.capped()).isTrue();
        assertThat(capped.grantedFixed()).as("触顶后只给剩余额度，照实返回而不是拒绝").isZero();
    }

    @Test
    @DisplayName("已帮过的目标要能被识别：「一键帮助全部」据此跳过，否则会在同一个人身上重复消耗额度")
    void helpTracksAlreadyHelpedTargets() {
        HelpLedger ledger = new HelpLedger(new HelpLedger.Rules(
                20, FixedPoint.parse("0.50"), FixedPoint.parse("0.01")));
        assertThat(ledger.alreadyHelped("h", "t:1", 1_000L)).isFalse();
        ledger.help("h", "t:1", 1_000L);
        assertThat(ledger.alreadyHelped("h", "t:1", 1_000L)).isTrue();
        assertThat(ledger.alreadyHelped("h", "t:2", 1_000L)).as("同一个人的另一件事不受影响").isFalse();
        // 跨天后重新计数
        assertThat(ledger.alreadyHelped("h", "t:1", 1_000L + 86400 * SECOND)).isFalse();
        assertThat(ledger.remainingToday("h", 1_000L + 86400 * SECOND)).isEqualTo(20);
    }

    @Test
    @DisplayName("帮助规则的构造期校验：单次比例超过总上限会让「一键帮助」只生效一次")
    void helpRulesAreValidated() {
        assertThatThrownBy(() -> new HelpLedger.Rules(0, 5000, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dailyLimit");
        assertThatThrownBy(() -> new HelpLedger.Rules(20, FixedPoint.parse("1.5"), 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1.0");
        assertThatThrownBy(() -> new HelpLedger.Rules(20, FixedPoint.parse("0.10"), FixedPoint.parse("0.20")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("speedupCap");
    }

    // ---------- 验收 10：版本号 diff ----------

    @Test
    @DisplayName("验收10：每次状态变更都推进版本号，客户端据此只拉 diff 而不是全量")
    void allianceVersionAdvancesOnEveryChange() {
        Alliance alliance = newAlliance("leader");
        long v0 = alliance.version();
        alliance.join("a");
        assertThat(alliance.version()).isGreaterThan(v0);
        long v1 = alliance.version();
        alliance.donate("a", 0, "20260908");
        assertThat(alliance.version()).isGreaterThan(v1);
        long v2 = alliance.version();
        alliance.setRole("leader", "a", AllianceRole.ELDER);
        assertThat(alliance.version()).isGreaterThan(v2);
        long v3 = alliance.version();
        alliance.kick("leader", "a");
        assertThat(alliance.version()).isGreaterThan(v3);
    }

    @Test
    @DisplayName("解散保护期（验收7）：由解散时刻加配置的保护时长得出，UI 据此画倒计时")
    void disbandProtectionWindow() {
        Alliance.Rules rules = allianceRules();
        Alliance alliance = newAlliance("leader");
        alliance.disband("leader", 5_000L);
        assertThat(alliance.isDisbanded()).isTrue();
        assertThat(rules.disbandProtectUntil(alliance.disbandedAt()))
                .isEqualTo(5_000L + 86400 * SECOND);
        assertThatThrownBy(() -> alliance.join("a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已解散");
    }

    @Test
    @DisplayName("盟主不能直接退盟；转让后原盟主降为副盟主")
    void leaderCannotLeaveWithoutTransfer() {
        Alliance alliance = newAlliance("leader");
        alliance.join("a");
        assertThatThrownBy(() -> alliance.leave("leader"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("转让");
        assertThatThrownBy(() -> alliance.transferLeadership("leader", "leader"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自己");
        assertThatThrownBy(() -> alliance.kick("leader", "leader"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("踢盟主");

        alliance.transferLeadership("leader", "a");
        assertThat(alliance.leaderId()).isEqualTo("a");
        assertThat(alliance.roleOf("leader")).isEqualTo(AllianceRole.OFFICER);
        alliance.leave("leader");
        assertThat(alliance.memberIds()).containsExactly("a");
    }

    @Test
    @DisplayName("人数已满时拒绝加入，并且上限用的是「已付费扩容」后的值")
    void allianceJoinRespectsPaidCap() {
        Alliance.Rules small = new Alliance.Rules(
                List.of(tier(1, 2, 1, "0.0"), tier(3, 3, 2, "0.25")),
                10, 3, 0, 1000, 100, FixedPoint.SCALE,
                List.of(new Alliance.DonateTier(0, null, 0, 0, 100, 10, 200)),
                FixedPoint.SCALE);
        Alliance alliance = Alliance.create("a1", "小队联盟", "TAG", "leader", 0L, small);
        alliance.join("a");
        assertThatThrownBy(() -> alliance.join("b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("人数已满");

        alliance.addFund(1000L);
        alliance.addExp(alliance.expPerLevel() * 4);
        alliance.expand();
        alliance.join("b");
        assertThat(alliance.memberCount()).isEqualTo(3);
    }

    // ---------- 辅助 ----------

    private static PermissionMatrix.Grant grant(PermissionMatrix.Scope scope, String permission,
                                                boolean leader, boolean officer, boolean member) {
        return new PermissionMatrix.Grant(scope, permission, leader, officer, member);
    }

    /** 把小队推到指定等级（活跃度按几何增长，直接喂足量最省事）。 */
    private static void grantExpToLevel(Squad squad, int targetLevel) {
        int guard = 0;
        while (squad.level() < targetLevel && guard++ < 20) {
            squad.addExp(squad.expToNext());
        }
        assertThat(squad.level()).as("推不到目标等级说明活跃度曲线有问题").isEqualTo(targetLevel);
    }
}
