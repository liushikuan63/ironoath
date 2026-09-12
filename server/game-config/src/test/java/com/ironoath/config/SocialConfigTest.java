package com.ironoath.config;

import com.ironoath.config.cfg.AllianceConfigCfg;
import com.ironoath.config.cfg.RolePermissionCfg;
import com.ironoath.config.cfg.SquadConfigCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：把 B10 文档里明写的社交数值钉成会失败的断言，并检查表与表之间不打架。
 * 依赖：JUnit 5 + AssertJ；读仓库里的真实表，不启动容器。
 *
 * <p><b>为什么必须有这一层</b>：SocialSystemTest 的夹具是手抄的（5→8→10、30→50→80→120→150、
 * 扩容 20000×1.8、帮助 1%、聊天 3 次/10 秒）。手抄夹具的风险在 B05 已经踩过一次 ——
 * 那次是手抄克制矩阵漏了 CAVALRY→INFANTRY，导致整批胜率数据全是假的，而测试全绿。
 * 本类的作用就是让「表值 == 文档值 == 夹具值」这条链断在任何一环时都变红。
 *
 * <p><b>另一类断言是跨表一致性</b>：squad_config.rallyCapacity 必须等于
 * global.RALLY_MAX_SIZE_SQUAD，因为战斗内核读 global 而面板读 squad_config，
 * 两者不一致的表现是「面板说能进 5 人，实际只能进 3 人」—— 而这两个数在不同文件里，
 * 改其中一个的人不会想到另一个。
 */
class SocialConfigTest {

    private static ConfigRegistry registry;
    private static List<SquadConfigCfg> squads;
    private static List<AllianceConfigCfg> alliances;
    private static List<RolePermissionCfg> permissions;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        squads = registry.all(SquadConfigCfg.class);
        alliances = registry.all(AllianceConfigCfg.class);
        permissions = registry.all(RolePermissionCfg.class);
    }

    // ---------- 小队（B10 §1） ----------

    @Test
    @DisplayName("小队人数扩展 5→8→10，第二档的门槛是队长主城 8 级（B10 §1 明写）")
    void squadMemberCapLadder() {
        assertThat(squads).hasSize(3);
        Map<Long, SquadConfigCfg> byLevel = new java.util.HashMap<>();
        for (SquadConfigCfg row : squads) {
            byLevel.put(row.squadLevel(), row);
        }
        assertThat(byLevel.get(1L).memberCap()).isEqualTo(5L);
        assertThat(byLevel.get(2L).memberCap()).isEqualTo(8L);
        assertThat(byLevel.get(3L).memberCap()).isEqualTo(10L);

        assertThat(byLevel.get(1L).unlockMainLevel()).as("解锁：主城 5 级").isEqualTo(5L);
        assertThat(byLevel.get(1L).unlockDayOffset()).as("解锁：开服 D1 ⇒ 偏移 0").isEqualTo(0L);
        assertThat(byLevel.get(2L).unlockMainLevel()).as("第二档门槛在队长主城 8 级").isEqualTo(8L);
    }

    @Test
    @DisplayName("小队互助每次 1%，与联盟帮助同值；集结容量必须等于 global.RALLY_MAX_SIZE_SQUAD")
    void squadHelpAndRallyMatchGlobal() {
        long expectedBonus = registry.fixedParam("ALLIANCE_HELP_SPEED_BONUS");
        long expectedRally = registry.longParam("RALLY_MAX_SIZE_SQUAD");
        for (SquadConfigCfg row : squads) {
            assertThat(row.helpSpeedBonus())
                    .as("B10 §1 与 §2 都写「每次减 1%」：小队略弱靠人数差与总上限实现，不靠单次比例")
                    .isEqualTo(expectedBonus)
                    .isEqualTo(100L);
            assertThat(row.rallyCapacity())
                    .as("战斗内核读 global，面板读 squad_config，两处不一致玩家就会看到对不上的数字")
                    .isEqualTo(expectedRally)
                    .isEqualTo(5L);
        }
    }

    @Test
    @DisplayName("小队商店在 Lv1 未解锁、Lv2 起解锁（先让「一起打野→拿小队币」这条链跑一天）")
    void squadShopUnlocksAtLevel2() {
        for (SquadConfigCfg row : squads) {
            assertThat(row.shopUnlock()).as("Lv%d", row.squadLevel()).isEqualTo(row.squadLevel() >= 2);
        }
    }

    // ---------- 联盟（B10 §2） ----------

    @Test
    @DisplayName("联盟人数扩展 30(Lv1)→50(Lv3)→80(Lv5)→120(Lv7)→150(Lv9)，只登记奇数级")
    void allianceMemberCapLadder() {
        assertThat(alliances).hasSize(5);
        List<AllianceConfigCfg> sorted = new ArrayList<>(alliances);
        sorted.sort((a, b) -> Long.compare(a.allianceLevel(), b.allianceLevel()));

        long[] expectedLevels = {1, 3, 5, 7, 9};
        long[] expectedCaps = {30, 50, 80, 120, 150};
        for (int i = 0; i < sorted.size(); i++) {
            assertThat(sorted.get(i).allianceLevel())
                    .as("偶数级不登记：登记它们就得编造四个文档里没有的数字").isEqualTo(expectedLevels[i]);
            assertThat(sorted.get(i).memberCap()).isEqualTo(expectedCaps[i]);
        }
    }

    @Test
    @DisplayName("联盟解锁：主城 10 级 + 开服 D3；每日捐献 3 档；集结容量等于 global.RALLY_MAX_SIZE_ALLIANCE")
    void allianceUnlockAndDonation() {
        long expectedRally = registry.longParam("RALLY_MAX_SIZE_ALLIANCE");
        for (AllianceConfigCfg row : alliances) {
            assertThat(row.unlockMainLevel()).isEqualTo(10L);
            assertThat(row.unlockDayOffset()).as("开服 D3 ⇒ 偏移 2").isEqualTo(2L);
            assertThat(row.donationDailyCap()).as("B10 §2：每日 3 档（免费/资源/金币）").isEqualTo(3L);
            assertThat(row.rallyCapacity()).isEqualTo(expectedRally).isEqualTo(20L);
        }
    }

    @Test
    @DisplayName("领地上限与科技加成随等级单调不减（递减会让升级变成惩罚）")
    void allianceTerritoryAndTechBonusAreMonotonic() {
        List<AllianceConfigCfg> sorted = new ArrayList<>(alliances);
        sorted.sort((a, b) -> Long.compare(a.allianceLevel(), b.allianceLevel()));
        long previousTerritory = 0L;
        long previousBonus = -1L;
        for (AllianceConfigCfg row : sorted) {
            assertThat(row.territoryCap()).isGreaterThanOrEqualTo(previousTerritory);
            assertThat(row.techCapBonus()).isGreaterThanOrEqualTo(previousBonus);
            previousTerritory = row.territoryCap();
            previousBonus = row.techCapBonus();
        }
        assertThat(sorted.get(0).techCapBonus()).as("Lv1 不加成，取 alliance_tech 表值原样").isZero();
        assertThat(sorted.get(sorted.size() - 1).techCapBonus())
                .as("Lv9 的 techCapBonus=1.0 ⇒ 科技上限翻倍").isEqualTo(10000L);
    }

    // ---------- 三个开放问题（B10 §五） ----------

    @Test
    @DisplayName("B10 §五 的三个开放问题按建议值落地：建盟 500 金币、每日帮助 20 次、小队币 150/日")
    void openQuestionsAdoptSuggestedValues() {
        assertThat(registry.longParam("ALLIANCE_CREATE_COST_GOLD")).isEqualTo(500L);
        assertThat(registry.longParam("HELP_DAILY_LIMIT")).isEqualTo(20L);
        assertThat(registry.longParam("SQUAD_COIN_DAILY_QUEST")).isBetween(100L, 200L);
    }

    @Test
    @DisplayName("禁止项「小队互助与联盟帮助不得简单叠加」的三个配套数值都到位")
    void helpAntiStackParameters() {
        // 共用一个每日额度（而不是各给一份），并且有总上限
        assertThat(registry.longParam("HELP_DAILY_LIMIT")).isPositive();
        long cap = registry.fixedParam("HELP_SPEEDUP_TOTAL_CAP");
        assertThat(cap).as("总上限必须 <= 100%，否则一次升级能被帮成负时长").isPositive().isLessThanOrEqualTo(10000L);
        assertThat(cap).isEqualTo(5000L);

        long perHelp = registry.fixedParam("ALLIANCE_HELP_SPEED_BONUS");
        assertThat(perHelp).as("单次比例必须小于总上限，否则「一键帮助全部」只会生效一次")
                .isPositive().isLessThan(cap);
        // 20 次 × 1% = 20%，够不到 50% 的总上限 —— 这是刻意的：
        // 上限约束的是「150 人一起帮同一个人」，不是「一个人帮 20 次」
        assertThat(perHelp * registry.longParam("HELP_DAILY_LIMIT")).isLessThan(cap);
    }

    @Test
    @DisplayName("聊天限流：3 次 / 10 秒（验收 9 要求第 4 次被拦），本地保留 200 条")
    void chatRateLimitParameters() {
        assertThat(registry.longParam("CHAT_RATE_LIMIT_COUNT")).isEqualTo(3L);
        assertThat(registry.longParam("CHAT_RATE_LIMIT_WINDOW_SECONDS")).isEqualTo(10L);
        assertThat(registry.longParam("CHAT_LOCAL_HISTORY_MAX")).isEqualTo(200L);
        assertThat(registry.longParam("ATTACK_PUSH_DEADLINE_MS")).as("验收 5：3 秒内推送").isEqualTo(3000L);
    }

    @Test
    @DisplayName("集结下限 2 人，且准备时长区间与文档的分钟级预期相容")
    void rallyParameters() {
        assertThat(registry.longParam("RALLY_MIN_SIZE")).isEqualTo(2L);
        assertThat(registry.longParam("RALLY_PREPARE_MIN_SECONDS"))
                .as("下限必须 >= 2 人集结所需的最短协同时间").isPositive();
        assertThat(registry.longParam("RALLY_PREPARE_MAX_SECONDS"))
                .isGreaterThanOrEqualTo(registry.longParam("RALLY_PREPARE_MIN_SECONDS"));
    }

    @Test
    @DisplayName("捐献三档：免费档产出为正（否则零氪玩家对联盟毫无贡献，会被当成负担踢掉）")
    void donateTiers() {
        assertThat(registry.longParam("DONATE_TIER_FREE_FUND")).isPositive();
        assertThat(registry.longParam("DONATE_TIER_FREE_CONTRIBUTION")).isPositive();
        assertThat(registry.longParam("DONATE_TIER_RESOURCE_FUND"))
                .isGreaterThan(registry.longParam("DONATE_TIER_FREE_FUND"));
        assertThat(registry.longParam("DONATE_TIER_GOLD_FUND"))
                .isGreaterThan(registry.longParam("DONATE_TIER_RESOURCE_FUND"));
        // 资金与贡献值必须同比例：只涨一条会让玩家在「为盟里做」和「为自己攒」之间感到被设计
        long freeRatio = registry.longParam("DONATE_TIER_FREE_FUND")
                / Math.max(1L, registry.longParam("DONATE_TIER_FREE_CONTRIBUTION"));
        long goldRatio = registry.longParam("DONATE_TIER_GOLD_FUND")
                / Math.max(1L, registry.longParam("DONATE_TIER_GOLD_CONTRIBUTION"));
        assertThat(goldRatio).as("三档的资金/贡献值比例应当一致").isEqualTo(freeRatio);

        long dailyTotal = registry.longParam("DONATE_TIER_FREE_FUND")
                + registry.longParam("DONATE_TIER_RESOURCE_FUND")
                + registry.longParam("DONATE_TIER_GOLD_FUND");
        assertThat(dailyTotal).as("ALLIANCE_EXPAND_COST_BASE 的 why 引用了「每日约 3200 资金」")
                .isEqualTo(3200L);
    }

    @Test
    @DisplayName("扩容消耗的几何增长 1.8，四次合计与文档口径一致")
    void expandCostParameters() {
        assertThat(registry.longParam("ALLIANCE_EXPAND_COST_BASE")).isEqualTo(20000L);
        assertThat(registry.fixedParam("ALLIANCE_EXPAND_COST_GROWTH")).isEqualTo(18000L);
        assertThat(registry.longParam("ALLIANCE_DISBAND_PROTECT_SECONDS"))
                .as("验收 7：解散保护期").isPositive();
        assertThat(registry.fixedParam("ALLIANCE_TECH_COST_GROWTH")).isEqualTo(11500L);
    }

    // ---------- 权限矩阵（B10 §4、验收 4） ----------

    @Test
    @DisplayName("权限矩阵覆盖 squad 与 alliance 两个 scope，且 (scope, permission) 无重复")
    void permissionTableIsWellFormed() {
        assertThat(permissions).isNotEmpty();
        Set<RolePermissionCfg.Scope> scopes = EnumSet.noneOf(RolePermissionCfg.Scope.class);
        Map<RolePermissionCfg.Scope, Set<String>> seen = new EnumMap<>(RolePermissionCfg.Scope.class);
        for (RolePermissionCfg row : permissions) {
            scopes.add(row.scope());
            Set<String> byScope = seen.computeIfAbsent(row.scope(), k -> new java.util.HashSet<>());
            assertThat(byScope.add(row.permission()))
                    .as("权限位重复定义：%s/%s —— 两条规则意味着判定结果取决于遍历顺序",
                            row.scope(), row.permission())
                    .isTrue();
            assertThat(row.permissionName()).as("每个权限位都要有中文名供 UI 展示").isNotBlank();
        }
        assertThat(scopes).as("B10 §4 要求 squad 与 alliance 两个层级都在表里")
                .contains(RolePermissionCfg.Scope.SQUAD, RolePermissionCfg.Scope.ALLIANCE);
    }

    @Test
    @DisplayName("B10 §4 点名的关键权限位在表里都有，且首领档一律允许")
    void keyPermissionsExistAndLeadersCanDoEverything() {
        Map<String, RolePermissionCfg> squadPerms = new java.util.HashMap<>();
        Map<String, RolePermissionCfg> alliancePerms = new java.util.HashMap<>();
        for (RolePermissionCfg row : permissions) {
            if (row.scope() == RolePermissionCfg.Scope.SQUAD) {
                squadPerms.put(row.permission(), row);
            } else if (row.scope() == RolePermissionCfg.Scope.ALLIANCE) {
                alliancePerms.put(row.permission(), row);
            }
        }
        // B10 §4 表格：squad 的关键权限是「发起集结、踢人、改公告」
        for (String permission : List.of("START_RALLY", "KICK_MEMBER", "EDIT_ANNOUNCEMENT")) {
            assertThat(squadPerms).as("小队缺关键权限位 %s", permission).containsKey(permission);
            assertThat(squadPerms.get(permission).allowLeader()).isTrue();
        }
        // alliance 的关键权限是「审核、集结、建堡垒、踢人、转让」
        for (String permission : List.of("APPROVE_APPLICATION", "START_RALLY", "SET_TERRITORY",
                "KICK_MEMBER", "TRANSFER_LEADER")) {
            assertThat(alliancePerms).as("联盟缺关键权限位 %s", permission).containsKey(permission);
            assertThat(alliancePerms.get(permission).allowLeader()).isTrue();
        }
        // 转让只能盟主做：干部能转让的话，盟主会被自己的副手取代
        assertThat(alliancePerms.get("TRANSFER_LEADER").allowOfficer()).isFalse();
        assertThat(alliancePerms.get("TRANSFER_LEADER").allowMember()).isFalse();
    }

    @Test
    @DisplayName("队员/成员档不能踢人：熟人圈子里踢人是队长的责任，不能下放到每个人")
    void membersCannotKick() {
        for (RolePermissionCfg row : permissions) {
            if ("KICK_MEMBER".equals(row.permission())) {
                assertThat(row.allowMember())
                        .as("%s 的成员档不应有踢人权限", row.scope()).isFalse();
            }
        }
    }
}
