package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.AllianceRole;
import com.ironoath.core.social.ChatRateLimiter;
import com.ironoath.core.social.HelpLedger;
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Squad;
import com.ironoath.core.social.SquadRole;
import com.ironoath.web.social.SocialRulesAssembler;

/**
 * 职责：SocialRulesAssembler 的单测 —— 钉住「配置表 → game-core 规则对象」这条链上的单位换算。
 * 依赖：JUnit 5 + AssertJ；直接读仓库里的真实表，不启动容器。
 *
 * <p><b>本类存在的唯一理由是单位换算</b>。装配层是全项目最容易出静默错误的地方，
 * 因为三种量纲在同一段代码里混着出现：
 * <ol>
 *   <li>DECIMAL 列已由 FixedPointDeserializer 转成定点 long（0.01 → 100），<b>不能再转一次</b>。
 *       多转一次不报错，只会让「每次帮助减 1%」变成「减 100%」</li>
 *   <li>LONG_POS 列是普通整数计数，不参与定点运算</li>
 *   <li>global 的 {@code *_SECONDS} 是秒，而 core 的规则对象要毫秒。
 *       漏乘 1000 的表现是「解散保护期只有 86 秒」—— 验收 7 会过（保护期确实存在）
 *       而线上完全失效，是最难发现的一类 bug</li>
 * </ol>
 * BattleParamsResolver 的类注释里记着同一类事故（attack/defense 漏放大 10000 倍，
 * 战报数字小了四个数量级而与策划对表时完全对不上）。这里把每一条都写成断言。
 */
class SocialRulesAssemblerTest {

    private static SocialRulesAssembler assembler;
    private static ConfigRegistry configs;

    @BeforeAll
    static void loadRealConfig() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        assembler = new SocialRulesAssembler(configs);
    }

    // ---------- 小队 ----------

    @Test
    @DisplayName("小队三档人数上限 5→8→10，第二档的门槛是队长主城 8 级")
    void squadLevels() {
        Squad.Rules rules = assembler.squadRules();
        assertThat(rules.levels()).hasSize(3);
        assertThat(rules.levels()).extracting(Squad.LevelRule::memberCap).containsExactly(5L, 8L, 10L);
        assertThat(rules.levels()).extracting(Squad.LevelRule::squadLevel).containsExactly(1L, 2L, 3L);
        assertThat(rules.levels().get(1).unlockMainLevel()).as("第二档门槛在队长主城 8 级").isEqualTo(8L);
        assertThat(rules.unlockMainLevel()).as("B10 §1：主城 5 级解锁").isEqualTo(5L);
        assertThat(rules.unlockDayOffset()).as("开服 D1 ⇒ 0-based 偏移 0").isZero();
        assertThat(rules.maxLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("helpSpeedBonus 是 DECIMAL 列，装配时不得再放大一次（多转一次就是「帮助一次减 100%」）")
    void squadHelpBonusIsAlreadyFixedPoint() {
        Squad.Rules rules = assembler.squadRules();
        for (Squad.LevelRule level : rules.levels()) {
            assertThat(level.helpSpeedBonusFixed())
                    .as("定点 0.01 = 100。若装配层又调了一次 FixedPoint.of 就会变成 1000000")
                    .isEqualTo(100L);
        }
        assertThat(FixedPoint.parse("0.01")).isEqualTo(100L);
    }

    @Test
    @DisplayName("装配出的 Squad.Rules 喂进领域对象后，人数上限确实是 5→8→10（装配与内核口径一致）")
    void assembledSquadRulesDriveDomainCorrectly() {
        Squad squad = Squad.create("s1", "五个人", "leader", assembler.squadRules());
        assertThat(squad.memberCap(5)).isEqualTo(5);

        squad.addExp(squad.expToNext());
        assertThat(squad.level()).isEqualTo(2);
        assertThat(squad.memberCap(7)).as("队长主城未到 8 级").isEqualTo(5);
        assertThat(squad.memberCap(8)).isEqualTo(8);

        squad.addExp(squad.expToNext());
        assertThat(squad.level()).isEqualTo(3);
        assertThat(squad.memberCap(8)).isEqualTo(10);
        assertThat(squad.rallyCapacity()).as("等于 global.RALLY_MAX_SIZE_SQUAD").isEqualTo(5);
        assertThat(squad.shopUnlocked()).isTrue();
    }

    @Test
    @DisplayName("小队升级活跃度：base 500、增长 1.6（定点 16000）")
    void squadExpCurve() {
        Squad.Rules rules = assembler.squadRules();
        assertThat(rules.expBase()).isEqualTo(500L);
        assertThat(rules.expGrowthFixed()).isEqualTo(16000L);
        assertThat(assembler.squadQuest()).isEqualTo(new SocialRulesAssembler.SquadQuest(20L, 150L));
    }

    // ---------- 联盟 ----------

    @Test
    @DisplayName("联盟五档人数上限 30→50→80→120→150，只登记奇数级")
    void allianceLevels() {
        Alliance.Rules rules = assembler.allianceRules();
        assertThat(rules.levels()).hasSize(5);
        assertThat(rules.levels()).extracting(Alliance.LevelRule::allianceLevel)
                .containsExactly(1L, 3L, 5L, 7L, 9L);
        assertThat(rules.levels()).extracting(Alliance.LevelRule::memberCap)
                .containsExactly(30L, 50L, 80L, 120L, 150L);
        assertThat(rules.levels()).extracting(Alliance.LevelRule::territoryCap)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(rules.unlockMainLevel()).as("B10 §2：主城 10 级解锁").isEqualTo(10L);
        assertThat(rules.unlockDayOffset()).as("开服 D3 ⇒ 0-based 偏移 2").isEqualTo(2L);
        assertThat(rules.maxLevel()).isEqualTo(9);
        assertThat(rules.capTierCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("所有 *_SECONDS 参数都换算成了毫秒：漏乘 1000 会让解散保护期从 1 天变成 86 秒")
    void secondParametersAreConvertedToMillis() {
        Alliance.Rules rules = assembler.allianceRules();
        assertThat(configs.longParam("ALLIANCE_DISBAND_PROTECT_SECONDS")).isEqualTo(86400L);
        assertThat(rules.disbandProtectMillis())
                .as("秒 → 毫秒").isEqualTo(86_400_000L);
        assertThat(rules.disbandProtectUntil(0L)).isEqualTo(86_400_000L);

        ChatRateLimiter.Rules chat = assembler.chatRules();
        assertThat(configs.longParam("CHAT_RATE_LIMIT_WINDOW_SECONDS")).isEqualTo(10L);
        assertThat(chat.windowMillis()).as("验收 9 的 10 秒窗口").isEqualTo(10_000L);

        Rally.Rules rally = assembler.allianceRallyRules();
        assertThat(configs.longParam("RALLY_PREPARE_MIN_SECONDS")).isEqualTo(600L);
        assertThat(rally.prepareMinMillis()).isEqualTo(600_000L);
        assertThat(rally.prepareMaxMillis()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("扩容消耗：base 20000、增长定点 1.8，四次合计 237440")
    void allianceExpandCost() {
        Alliance.Rules rules = assembler.allianceRules();
        assertThat(rules.createCostGold()).isEqualTo(500L);
        assertThat(rules.expandCostBase()).isEqualTo(20000L);
        assertThat(rules.expandCostGrowthFixed()).isEqualTo(18000L);
        assertThat(rules.expandCost(0)).isEqualTo(20000L);
        assertThat(rules.expandCost(1)).isEqualTo(36000L);
        assertThat(rules.expandCost(2)).isEqualTo(64800L);
        assertThat(rules.expandCost(3)).isEqualTo(116640L);
        assertThat(rules.expandCost(0) + rules.expandCost(1) + rules.expandCost(2) + rules.expandCost(3))
                .isEqualTo(237440L);
        assertThat(rules.techCostGrowthFixed()).isEqualTo(11500L);
        // 科技第 1 级取表值原样，第 2 级 ×1.15
        assertThat(rules.techCost(2000L, 1)).isEqualTo(2000L);
        assertThat(rules.techCost(2000L, 2)).isEqualTo(2300L);
    }

    @Test
    @DisplayName("捐献三档：免费 / 粮食 / 金币，资金与贡献值比例一致（1:6:25）")
    void allianceDonateTiers() {
        Alliance.Rules rules = assembler.allianceRules();
        assertThat(rules.donateTiers()).hasSize(3);

        Alliance.DonateTier free = rules.donateTiers().get(0);
        assertThat(free.tier()).isZero();
        assertThat(free.costResourceType()).as("免费档不消耗任何东西").isNull();
        assertThat(free.costGold()).isZero();
        assertThat(free.fundGained()).isEqualTo(100L);
        assertThat(free.contributionGained()).isEqualTo(10L);

        Alliance.DonateTier resource = rules.donateTiers().get(1);
        assertThat(resource.costResourceType())
                .as("资源种类来自 global.DONATE_TIER_RESOURCE_TYPE，不是写死在 Java 里")
                .isEqualTo(configs.stringParam("DONATE_TIER_RESOURCE_TYPE"))
                .isEqualTo("GRAIN");
        assertThat(resource.costResourceAmount()).isEqualTo(5000L);
        assertThat(resource.costGold()).isZero();
        assertThat(resource.fundGained()).isEqualTo(600L);
        assertThat(resource.contributionGained()).isEqualTo(60L);

        Alliance.DonateTier gold = rules.donateTiers().get(2);
        assertThat(gold.costResourceType()).isNull();
        assertThat(gold.costGold()).isEqualTo(100L);
        assertThat(gold.fundGained()).isEqualTo(2500L);
        assertThat(gold.contributionGained()).isEqualTo(250L);

        // 三档的资金/贡献值比例必须一致：只涨一条会让玩家在「为盟里做」和「为自己攒」之间感到被设计
        assertThat(free.fundGained() / free.contributionGained())
                .isEqualTo(resource.fundGained() / resource.contributionGained())
                .isEqualTo(gold.fundGained() / gold.contributionGained())
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("装配出的 Alliance.Rules 喂进领域对象后，捐献让资金与贡献值同步增长（验收 8）")
    void assembledAllianceRulesDriveDomainCorrectly() {
        Alliance alliance = Alliance.create("a1", "铁誓同盟", "IRON", "leader",
                5000L, assembler.allianceRules());
        assertThat(alliance.effectiveMemberCap()).isEqualTo(30);

        alliance.join("member");
        Alliance.Donation donation = alliance.donate("member", 2, "20260908");
        assertThat(donation.fundGained()).isEqualTo(2500L);
        assertThat(donation.contributionGained()).isEqualTo(250L);
        assertThat(donation.fund()).isEqualTo(2500L);
        assertThat(alliance.contributionOf("member")).isEqualTo(250L);
        assertThat(donation.dailyCap()).as("alliance_config.donationDailyCap").isEqualTo(3);

        alliance.addFund(1_000_000L);
        alliance.addExp(alliance.expPerLevel() * 10);
        assertThat(alliance.level()).isEqualTo(9);
        assertThat(alliance.expand().memberCap()).isEqualTo(50);
        assertThat(alliance.techLevelCap(40)).isEqualTo(80);
    }

    // ---------- 权限矩阵 ----------

    @Test
    @DisplayName("验收4：权限矩阵完全从 role_permission 表装配，装配后新增权限位无需改代码")
    void permissionsAreAssembledFromTable() {
        PermissionMatrix matrix = assembler.permissions();

        // B10 §4 表格点名的关键权限
        assertThat(matrix.allows(PermissionMatrix.Scope.SQUAD, SquadRole.LEADER.tier(), "START_RALLY")).isTrue();
        assertThat(matrix.allows(PermissionMatrix.Scope.SQUAD, SquadRole.MEMBER.tier(), "START_RALLY"))
                .as("队员不能发起集结").isFalse();
        assertThat(matrix.allows(PermissionMatrix.Scope.SQUAD, SquadRole.MEMBER.tier(), "CALL_FOR_HELP"))
                .as("求助是全员开放的：不能求助的小队提供不了庇护").isTrue();

        assertThat(matrix.allows(PermissionMatrix.Scope.ALLIANCE, AllianceRole.LEADER.tier(), "TRANSFER_LEADER"))
                .isTrue();
        assertThat(matrix.allows(PermissionMatrix.Scope.ALLIANCE, AllianceRole.OFFICER.tier(), "TRANSFER_LEADER"))
                .as("副盟主能转让的话，盟主会被自己的副手取代").isFalse();
        assertThat(matrix.allows(PermissionMatrix.Scope.ALLIANCE, AllianceRole.ELDER.tier(), "TRANSFER_LEADER"))
                .isFalse();
        assertThat(matrix.allows(PermissionMatrix.Scope.ALLIANCE, AllianceRole.LEADER.tier(), "EXPAND_CAPACITY"))
                .as("扩容是最大的一笔公共资产支出，只有盟主能点").isTrue();
        assertThat(matrix.allows(PermissionMatrix.Scope.ALLIANCE, AllianceRole.MEMBER.tier(), "DONATE"))
                .as("捐献全员开放").isTrue();

        // B13 的国家层已经在表里，装配后立刻可用 —— 这就是「新增层级只加配置不改代码」
        assertThat(matrix.allows(PermissionMatrix.Scope.NATION, PermissionMatrix.Tier.LEADER, "DECLARE_WAR")).isTrue();
        assertThat(matrix.allows(PermissionMatrix.Scope.NATION, PermissionMatrix.Tier.MEMBER, "DECLARE_WAR")).isFalse();
        assertThat(matrix.allows(PermissionMatrix.Scope.NATION, PermissionMatrix.Tier.MEMBER, "JOIN_NATIONAL_RALLY"))
                .isTrue();
    }

    @Test
    @DisplayName("permissionsOf 给出可直接下发给客户端的结论列表")
    void permissionsOfIsClientReady() {
        PermissionMatrix matrix = assembler.permissions();
        List<String> squadLeader = List.copyOf(
                matrix.permissionsOf(PermissionMatrix.Scope.SQUAD, PermissionMatrix.Tier.LEADER));
        assertThat(squadLeader).contains("KICK_MEMBER", "START_RALLY", "EDIT_ANNOUNCEMENT",
                "INVITE_MEMBER", "CALL_FOR_HELP", "TRANSFER_LEADER", "DISBAND_SQUAD");
        assertThat(matrix.permissionsOf(PermissionMatrix.Scope.SQUAD, PermissionMatrix.Tier.MEMBER))
                .containsExactlyInAnyOrder("INVITE_MEMBER", "CALL_FOR_HELP");
        assertThat(matrix.permissionCount(PermissionMatrix.Scope.SQUAD)).isEqualTo(7);
        // 13 而不是 12：`9964488` 给联盟加了 `perm_alliance_set_role`（任命官员那一步的权限位），
        // 这张表的行数就是结论的数量 —— 改表必须同时改这里，这道断言是"有人悄悄加位"的哨兵。
        // 它为什么曾经长期是红的却没人看见：`scripts/check.sh` 与 `test-client.sh` 都不跑 Java 套件，
        // 只有 `scripts/build.sh`（= CI 的 "Full build and tests" 那一步）会跑到。
        assertThat(matrix.permissionCount(PermissionMatrix.Scope.ALLIANCE)).isEqualTo(13);
    }

    // ---------- 互助 / 聊天 / 集结 ----------

    @Test
    @DisplayName("禁止项：互助的每日额度与总上限都从 global 装配，单次比例小于总上限")
    void helpRules() {
        HelpLedger.Rules rules = assembler.helpRules();
        assertThat(rules.dailyLimit()).isEqualTo(20);
        assertThat(rules.speedupCapFixed()).as("定点 0.50").isEqualTo(5000L);
        assertThat(rules.bonusPerHelpFixed()).as("定点 0.01，不得被二次放大").isEqualTo(100L);

        HelpLedger ledger = new HelpLedger(rules);
        for (int i = 0; i < 20; i++) {
            assertThat(ledger.help("h", "t:" + i, 1000L)).isNotNull();
        }
        assertThat(ledger.help("h", "t:20", 1000L)).as("每日 20 次用完").isNull();

        // 60 个人帮同一个人也只能到 50%
        for (int i = 0; i < 60; i++) {
            ledger.help("helper" + i, "same", 1000L);
        }
        assertThat(ledger.speedupOf("same")).isEqualTo(5000L);
    }

    @Test
    @DisplayName("验收9：聊天限流按装配出的规则，同内容 10 秒内第 4 次被拦")
    void chatRulesEnforceAcceptance9() {
        ChatRateLimiter.Rules rules = assembler.chatRules();
        assertThat(rules.maxPerWindow()).isEqualTo(3);
        assertThat(rules.windowMillis()).isEqualTo(10_000L);
        assertThat(rules.maxKeysPerPlayer()).as("与本地历史上限同值：跟踪键不该比能显示的消息还多")
                .isEqualTo(200);

        ChatRateLimiter limiter = new ChatRateLimiter(rules);
        assertThat(limiter.check("p", "招人", 0L).allowed()).isTrue();
        assertThat(limiter.check("p", "招人", 1000L).allowed()).isTrue();
        assertThat(limiter.check("p", "招人", 2000L).allowed()).isTrue();
        assertThat(limiter.check("p", "招人", 3000L).allowed()).as("验收 9：第 4 次被拦截").isFalse();
    }

    @Test
    @DisplayName("集结：下限 2 人，三层级的人数上限分别来自 global.RALLY_MAX_SIZE_*")
    void rallyRules() {
        assertThat(assembler.squadRallyRules().minMembers()).isEqualTo(2);
        assertThat(assembler.allianceRallyRules().minMembers()).isEqualTo(2);
        assertThat(assembler.rallyMaxSize(Rally.Scope.SQUAD)).isEqualTo(5);
        assertThat(assembler.rallyMaxSize(Rally.Scope.ALLIANCE)).isEqualTo(20);
        assertThat(assembler.rallyMaxSize(Rally.Scope.NATION)).isEqualTo(50);

        // 用装配出的规则发起一次真实集结，证明口径能跑通
        Rally rally = Rally.initiate("r1", Rally.Scope.SQUAD, "s1", "leader",
                java.util.Map.of("unit_infantry_t1", 100L), java.util.List.of(),
                assembler.rallyMaxSize(Rally.Scope.SQUAD),
                20 * 60 * 1000L, 0L, assembler.squadRallyRules(), 100L, 200L, "PLAYER_CITY");
        assertThat(rally.prepareMillis()).as("20 分钟落在 [10, 30] 分钟区间内，不被夹").isEqualTo(1_200_000L);
        rally.join("a", java.util.Map.of("unit_infantry_t1", 50L), java.util.List.of());
        Rally.Departure departure = rally.depart(rally.prepareUntil());
        assertThat(departure.totalTroops()).isEqualTo(150L);
        assertThat(departure.memberCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("验收5 的推送时间预算从 global 装配（埋点用它做报警阈值）")
    void attackPushDeadline() {
        assertThat(assembler.attackPushDeadlineMillis()).isEqualTo(3000L);
    }
}
