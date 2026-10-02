package com.ironoath.config;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.GachaCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.config.cfg.MatchRuleCfg;
import com.ironoath.config.cfg.RolePermissionCfg;
import com.ironoath.config.cfg.SeasonCfg;
import com.ironoath.config.cfg.UnitCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：B02 全表验收 —— 表齐备性、合规硬要求，以及把 C00 设计公理变成可执行断言。
 * 依赖：JUnit 5 + AssertJ、生成的 cfg 类型；读仓库内真实的 contract/config。
 *
 * <p>本类的价值不在于「检查数据对不对」，而在于<b>把几条写在文档里的设计公理变成会失败的测试</b>：
 * <ul>
 *   <li>C00 公理二「保护规则不超过三条」→ 断言 match_rule 的 PROTECTION 行恰好 3 条</li>
 *   <li>C00 公理一「SSR 不得比 R 强 2 倍以上」→ 断言武将有效战力基数比值</li>
 *   <li>合规「抽卡必须概率公示且四档之和为 100%」→ 断言定点概率之和恰为 10000 且公示文案非空</li>
 * </ul>
 * 写在文档里的约束会被遗忘，写成断言的约束不会。
 */
class ConfigTablesAcceptanceTest {

    /** B02 §1 要求的 17 张表。 */
    private static final Set<String> REQUIRED_17 = Set.of(
            "building", "resource", "unit", "hero", "skill", "tech", "item",
            "mapmonster", "chapter", "quest", "alliance_tech", "shop", "gacha",
            "season", "activity", "match_rule", "role_permission");

    /**
     * B02 §1 要求预留结构的表。
     *
     * <p><b>预留表会随批次推进逐个退出这个集合</b>：退出条件是「对应批次开始往里填数据」。
     * squad_config 与 alliance_config 在 B10 退出，四张 bot_* 在 B11 退出，
     * nation_config 在 B13 退出（都已移入 {@link #FILLED_BY_LATER_BATCH}）。
     *
     * <p><b>本集合现在是空的，这是预期的</b>：注释里写过「它变空的过程就是全部批次
     * 交付完毕的过程」，而 B02 当初预留的 7 张表已经全部被后续批次填上了数据。
     * 保留这个集合而不是删掉它，是因为 B14~B16 仍可能新增预留表，
     * 届时应当加回这里，而不是把断言整段删掉。
     *
     * <p><b>holiday（2026-09-19 加入，防沉迷的法定节假日日期表）</b>：它与上面那批的区别是
     * **数据由运营按年填、不来自任何开发批次** —— 空结构 + 明确的"还没填"就是它的常态。
     * 判定口径同一条：表里一旦有数据，就不该再留在预留态。
     */
    private static final Set<String> RESERVED = Set.of("holiday");

    /**
     * B02 建结构、由后续批次填数据的表（B10/B11/B13 已填，所以不再是预留表）。
     *
     * <p>单独列出来而不是并进 REQUIRED_17：REQUIRED_17 是 B02 验收标准的原文口径
     * （「B02 §1 要求的 17 张表」），往里加东西等于改验收标准。
     */
    private static final Set<String> FILLED_BY_LATER_BATCH = Set.of(
            "squad_config", "alliance_config",
            "bot_archetype", "bot_name", "bot_chat", "bot_schedule",
            "nation_config");

    private static ConfigRegistry registry;

    @BeforeAll
    static void load() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("验收1：B02 要求的 17 张表 + 预留表全部通过启动期全量校验")
    void allRequiredTablesLoadAndValidate() {
        assertThat(registry.tableNames()).containsAll(REQUIRED_17);
        assertThat(registry.tableNames()).containsAll(RESERVED);
        assertThat(registry.tableNames()).containsAll(FILLED_BY_LATER_BATCH);
        // 预留表必须被识别为预留，否则加载器会把空表当成「导出脚本失败」
        assertThat(registry.reservedTables()).containsExactlyInAnyOrderElementsOf(RESERVED);
        for (String name : RESERVED) {
            assertThat(registry.rawTable(name).rows())
                    .as("预留表 %s 本批次不填数据", name).isEmpty();
            assertThat(registry.rawTable(name).isReserved()).isTrue();
        }
        for (String name : REQUIRED_17) {
            assertThat(registry.rawTable(name).rows())
                    .as("业务表 %s 不得为空", name).isNotEmpty();
            assertThat(registry.rawTable(name).isReserved()).isFalse();
        }
        // 已经由后续批次填上数据的表，必须摘掉 RESERVED 标记：
        // 留着标记的话加载器会跳过对它们的空表检查，而更重要的是
        // 「这张表还是预留的」这个信号会误导下一个读代码的人以为里面没有数据
        for (String name : FILLED_BY_LATER_BATCH) {
            assertThat(registry.rawTable(name).rows())
                    .as("表 %s 已由后续批次填充，不得再是空的", name).isNotEmpty();
            assertThat(registry.rawTable(name).isReserved())
                    .as("表 %s 有数据却仍标着 RESERVED", name).isFalse();
        }
    }

    @Test
    @DisplayName("合规硬要求：每个卡池四档概率之和精确等于定点 1.0，且概率公示文案非空")
    void gachaProbabilitiesSumToOneAndDiscloseText() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            long sum = pool.ssrChance() + pool.srChance() + pool.rChance() + pool.nChance();
            assertThat(sum)
                    .as("卡池 %s 的四档概率之和必须精确等于 100%%（定点 10000），实际=%s",
                            pool.id(), FixedPoint.format(sum))
                    .isEqualTo(FixedPoint.ONE);

            assertThat(pool.disclosureText())
                    .as("卡池 %s 必须含概率公示文案（合规硬要求）", pool.id())
                    .isNotBlank();
            // 公示文案必须把四档概率都写出来，否则等于没公示
            assertThat(pool.disclosureText())
                    .as("卡池 %s 的公示文案必须逐档写明概率", pool.id())
                    .contains("SSR").contains("SR").contains("R").contains("N");
            assertThat(pool.disclosureText())
                    .as("卡池 %s 的公示文案必须写明保底次数", pool.id())
                    .contains(String.valueOf(pool.ssrPity()))
                    .contains(String.valueOf(pool.srPity()));

            assertThat(pool.ssrPity()).as("SSR 保底必须为正").isPositive();
            assertThat(pool.srPity()).as("SR 保底不得严于 SSR 保底").isLessThanOrEqualTo(pool.ssrPity());
        }
    }

    @Test
    @DisplayName("C00 公理二可执行化：保护规则恰好 3 条，多一条就让这个测试失败")
    void exactlyThreeProtectionRules() {
        List<MatchRuleCfg> protections = registry.all(MatchRuleCfg.class).stream()
                .filter(r -> r.kind() == MatchRuleCfg.Kind.PROTECTION)
                .toList();
        assertThat(protections)
                .as("C00 公理二：只保留三条必要保护（新手保护期、连续受害护盾、护盾期禁主动攻击）。"
                        + "超过三条就要问自己：这条规则会阻止哪个社交行为？")
                .hasSize(3);
        assertThat(protections).extracting(MatchRuleCfg::id).containsExactlyInAnyOrder(
                "mr_protection_newcomer", "mr_protection_victim_shield", "mr_protection_shield_no_attack");
        // 三条保护都必须禁止主动攻击：护盾不付对价就会变成免费的进攻准备期
        for (MatchRuleCfg p : protections) {
            assertThat(p.blocksActiveAttack())
                    .as("保护规则 %s 必须禁止护盾期主动攻击", p.id()).isTrue();
        }
    }

    @Test
    @DisplayName("C00 公理一可执行化：SSR/SR 战力比落在 [1.5,1.7]，SSR/R 不超过 2.0")
    void heroRarityPowerRatiosWithinBounds() {
        // 有效战力基数 = 三维之和 × 成长率；满级比值等于基数比值（maxLevel 全稀有度统一为 100）
        double ssr = effectiveBase(HeroCfg.Rarity.SSR);
        double sr = effectiveBase(HeroCfg.Rarity.SR);
        double r = effectiveBase(HeroCfg.Rarity.R);
        double n = effectiveBase(HeroCfg.Rarity.N);

        assertThat(ssr / sr).as("B02 验收5：SSR/SR 必须落在 [1.5, 1.7]").isBetween(1.5d, 1.7d);
        assertThat(ssr / r).as("B02 禁止项：SSR/R 不得做到 2 倍以上（B06 依赖此约束）").isLessThanOrEqualTo(2.0d);
        assertThat(ssr).isGreaterThan(sr);
        assertThat(sr).isGreaterThan(r);
        assertThat(r).isGreaterThan(n);

        // 满级上限必须统一，否则等级差会把比值放大到违规（SSR 100 级 / SR 80 级会到 2.59 倍）
        for (HeroCfg hero : registry.all(HeroCfg.class)) {
            assertThat(hero.maxLevel())
                    .as("武将 %s 的满级上限必须与全稀有度统一，否则战力比会被等级差扭曲", hero.id())
                    .isEqualTo(100L);
        }
        // 同稀有度的三维总量必须相等：分布只决定适配哪种编队，不决定强度上限
        for (HeroCfg.Rarity rarity : HeroCfg.Rarity.values()) {
            List<HeroCfg> ofRarity = registry.all(HeroCfg.class).stream()
                    .filter(h -> h.rarity() == rarity).toList();
            assertThat(ofRarity).isNotEmpty();
            long first = totalAttrs(ofRarity.get(0));
            for (HeroCfg h : ofRarity) {
                assertThat(totalAttrs(h))
                        .as("%s 武将 %s 的三维总量必须与同稀有度其他人相同，否则玩家只会练分布最优的那一个，卡池深度归零",
                                rarity, h.id())
                        .isEqualTo(first);
            }
        }
    }

    private static double effectiveBase(HeroCfg.Rarity rarity) {
        HeroCfg sample = registry.all(HeroCfg.class).stream()
                .filter(h -> h.rarity() == rarity)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("稀有度 " + rarity + " 没有武将"));
        return FixedPoint.toBigDecimal(FixedPoint.mul(
                FixedPoint.of(totalAttrs(sample)), sample.growthRate())).doubleValue();
    }

    private static long totalAttrs(HeroCfg hero) {
        return hero.might() + hero.command() + hero.wisdom();
    }

    @Test
    @DisplayName("社交权限不变量：求助与邀请必须全员开放，任何审批环节都会让社交意愿流失")
    void helpAndInviteAreOpenToAllMembers() {
        for (RolePermissionCfg perm : registry.all(RolePermissionCfg.class)) {
            if (perm.permission().equals("CALL_FOR_HELP") || perm.permission().equals("INVITE_MEMBER")
                    || perm.permission().equals("JOIN_NATIONAL_RALLY")
                    || perm.permission().equals("RESEARCH_TECH")) {
                assertThat(perm.allowMember())
                        .as("权限 %s（%s）必须对普通成员开放：这是玩家主动想为组织做的事，"
                                + "加审批就等于消灭它", perm.scope(), perm.permission())
                        .isTrue();
            }
            // 三个范围内 Leader 必须拥有全部权限，否则会出现「没人能做的操作」
            assertThat(perm.allowLeader())
                    .as("权限 %s（%s）必须对 Leader 开放", perm.scope(), perm.permission())
                    .isTrue();
        }
        // 国家级不可逆操作必须收窄到 Leader 独有。
        // **WITHDRAW_TREASURY 不在这一组里了**（2026-09-13 裁决 C16）：B13 §2 本来就把「国库支出」
        // 同时给了国王（无限制）与首相（限额），原先把它算作"国主独有"是让表去否定规范文本。
        // 摘出来不等于放松守卫 —— 见下面那段，它改守的是另外两件事。
        for (String sensitive : List.of("DECLARE_WAR", "APPOINT_OFFICE")) {
            RolePermissionCfg perm = registry.get(RolePermissionCfg.class, "perm_nation_" + sensitive.toLowerCase());
            assertThat(perm.allowOfficer()).as("%s 必须收窄到国主独有", sensitive).isFalse();
            assertThat(perm.allowMember()).as("%s 必须收窄到国主独有", sensitive).isFalse();
        }

        // **SET_NATIONAL_POLICY 也摘出来了**（2026-09-30 裁决 A1）：B13 §2 把「国策提案」给了内政官、
        // B21 块③ 写「提案来源限国王/内政官」，而本表原先 allowOfficer=false 是在让表去否定两份规范文本
        // —— 与 2026-09-13 从这一组里摘掉 WITHDRAW_TREASURY（同样是「表否定 B13 §2」）是同一种错。
        // 摘出来不等于放松守卫：**allowMember 仍然必须为 false**（普通成员不能提案），
        // 而且真正的闸不在这一格上 —— 提案不花钱也不耗国库，闸在投票那一侧
        // （相对 50% 门槛 + 参与下限）。下面那一条守着的就是「不得对全员开放」。
        RolePermissionCfg setPolicy =
                registry.get(RolePermissionCfg.class, "perm_nation_set_national_policy");
        assertThat(setPolicy.allowLeader())
                .as("国策至少要国主能提，否则没人能发起讨论").isTrue();
        assertThat(setPolicy.allowOfficer())
                .as("A1 之后国王与四类官员都可提案（内政官 4 席的权限在 B13 §2 里是明写的）")
                .isTrue();
        assertThat(setPolicy.allowMember())
                .as("放开到官员档不等于放开到全员：普通成员只能投票，不能提案").isFalse();

        RolePermissionCfg withdraw =
                registry.get(RolePermissionCfg.class, "perm_nation_withdraw_treasury");
        assertThat(withdraw.allowOfficer())
                .as("C16 之后首相这一档必须真的能支取，否则 B13 §2 那句「国库支出（限额）」是装饰品")
                .isTrue();
        assertThat(withdraw.allowMember())
                .as("放开到官职档不等于放开到全员：普通成员仍然支取不了国库").isFalse();
        // 放开 OFFICER 的代价由额度夹住，所以这里必须能读到那个比例，且它落在 0~1 之间。
        // 判据是定点：10000 = 1.0。超过 1 就是"官职一周能花掉比一周税收还多"，不叫限额
        long ratio = registry.fixedParam("NATION_OFFICER_SPEND_WEEKLY_RATIO");
        assertThat(ratio)
                .as("国库支取放开到 OFFICER 档的前提是有周限额；比例必须存在且在 0~1 之间")
                .isBetween(0L, 10_000L);
    }

    @Test
    @DisplayName("集结加成取得到且是定点 1000：fixedParam 本身就是在验它被声明成 DECIMAL")
    void rallyBonusMustBeDeclaredAsDecimal() {
        // 这条红过一次（2026-09-30 #19 装配那一格）：参数声明成 LONG + value=1000，
        // `fixedParam` 在运行时报「类型是 LONG，不能按 DECIMAL 读取」——
        // 而**到达处理把异常吞掉**，于是那一仗静默不结算：不掠夺、不写战报，
        // 症状是「集结打过去什么都没发生」，与「集结没生效」完全分不开
        // （当时是靠断言消息里那三个数字才定位到的：守方共-0、战报数=0）。
        //
        // **定点度是 10000，所以「1000」在 DECIMAL 里就是 "10.00"**，不是 "1000"。
        // 声明一旦被改成别的，下面这行会直接抛 —— 而真正兜住后果的是
        // `RallyDepartureTest.rallySpoilsAreSplitByCommitment`（它会当场红）。
        assertThat(registry.fixedParam("RALLY_ATTACK_BONUS_FIXED"))
                .as("+10% = 定点 1000（裁决 A10 取自 balance-sim --rally 实测曲线）").isEqualTo(1_000L);
    }

    @Test
    @DisplayName("集结加成落在有效区间里：负数会让集结变成削弱，大到离谱会让均势必胜")
    void rallyBonusStaysInAPlausibleRange() {
        long bonus = registry.fixedParam("RALLY_ATTACK_BONUS_FIXED");
        // 实测结论（balance-sim --rally，600 局/点）：+15% 就让均势变成必胜，
        // 集结从「要不要赌」退化成「走流程」。所以上限钉在 +15%。
        assertThat(bonus).as("0 = 这一格没做；负数 = 集结反而削弱进攻方").isPositive();
        assertThat(bonus).as("超过 +15% 实测会让均势必胜，超出就不再是「赌一把」")
                .isLessThanOrEqualTo(1_500L);
    }

    @Test
    @DisplayName("五分钟体验：1 级野怪战力必须落在新号的可攻击区间内，首战必胜")
    void firstMonsterIsBeatableByNewPlayer() {
        long newPlayerPower = registry.longParam("INIT_MATCH_POWER");
        long minRatio = registry.fixedParam("PVP_POWER_MIN_RATIO");
        long maxRatio = registry.fixedParam("PVP_POWER_MAX_RATIO");
        long lowBound = FixedPoint.mul(newPlayerPower, minRatio);
        long highBound = FixedPoint.mul(newPlayerPower, maxRatio);

        MapmonsterCfg first = registry.get(MapmonsterCfg.class, "mapmonster_lv01");
        assertThat(first.power())
                .as("1 级野怪战力必须落在新号 matchPower 的 [%s, %s] 区间内，否则首战打不到或打不赢",
                        lowBound, highBound)
                .isBetween(lowBound, highBound);
    }

    @Test
    @DisplayName("野怪表覆盖 1~50 级，战力与体力消耗随等级单调不减")
    void monsterTableCoversAllLevels() {
        List<MapmonsterCfg> monsters = registry.all(MapmonsterCfg.class);
        assertThat(monsters).hasSize(50);
        for (int level = 1; level <= 50; level++) {
            assertThat(registry.hasTable("mapmonster")).isTrue();
            assertThat(monsters.get(level - 1).level()).isEqualTo(level);
        }
        for (int i = 1; i < monsters.size(); i++) {
            MapmonsterCfg prev = monsters.get(i - 1);
            MapmonsterCfg cur = monsters.get(i);
            assertThat(cur.power()).isGreaterThan(prev.power());
            assertThat(cur.staminaCost()).isGreaterThanOrEqualTo(prev.staminaCost());
            // 总兵力必须递增。注意不能断言单一兵种数量递增：野怪在等级边界会切换兵种构成
            // （1~8 纯步兵 → 9~20 步骑 7:3 → 21~34 步骑弓 5:2:3 → 35+ 加攻城器 4:2:2:2），
            // 单一兵种占比下降是设计意图，总兵力始终保持 10 × 1.25^(n-1) 递增
            assertThat(totalUnits(cur))
                    .as("%d 级野怪总兵力应不低于 %d 级", cur.level(), prev.level())
                    .isGreaterThanOrEqualTo(totalUnits(prev));
        }
        // 攻城器只出现在 35 级及以上：玩家要到主城 8 级才有攻城工坊，早于此出现攻城器野怪就打不过了
        for (MapmonsterCfg m : monsters) {
            if (m.level() <= 34) {
                assertThat(m.siegeCount()).as("%d 级野怪不应带攻城器", m.level()).isZero();
            } else {
                assertThat(m.siegeCount()).as("%d 级野怪应带攻城器", m.level()).isPositive();
            }
        }
        assertThat(registry.get(MapmonsterCfg.class, "mapmonster_lv01").cavalryCount())
                .as("教学段（1~8 级）应是纯步兵，玩家此时只有兵营").isZero();
    }

    private static long totalUnits(MapmonsterCfg m) {
        return m.infantryCount() + m.cavalryCount() + m.archerCount() + m.siegeCount();
    }

    @Test
    @DisplayName("每一季的时间轴都连续无空隙、无重叠，且各季等长、总长与主城 40 级的养成节奏对齐")
    void seasonPhasesAreContiguous() {
        // 2026-10-03：season 表从 season_01 五行扩到 season_01..season_05 共 25 行（#750 / #751 裁决），
        // 所以「全表恰好 5 行」这个前提不再成立。断言随之改成**逐季**校验 —— 它比原来更严：
        // 原来只查一季的连续性，现在每一季都查，且要求各季的阶段数与总长彼此一致。
        // 一致性正是「按天数推进」能成立的前提（SeasonRulesAssembler 用第一季的长度算第几季）。
        Map<String, List<SeasonCfg>> bySeason = registry.all(SeasonCfg.class).stream()
                .collect(Collectors.groupingBy(
                        r -> r.id().substring(0, r.id().indexOf("_phase_")),
                        TreeMap::new,
                        Collectors.toList()));

        assertThat(bySeason.keySet())
                .as("season 表里的赛季前缀（#750 裁决 SEASON_COUNT=5 ⇒ 应有 5 个，且补零两位故字典序=时间序）")
                .hasSize(5)
                .containsExactly("season_01", "season_02", "season_03", "season_04", "season_05");

        Long expectedTotal = null;
        for (Map.Entry<String, List<SeasonCfg>> entry : bySeason.entrySet()) {
            List<SeasonCfg> phases = entry.getValue().stream()
                    .sorted((a, b) -> Long.compare(a.phaseNo(), b.phaseNo()))
                    .toList();
            assertThat(phases)
                    .as("%s 的阶段数", entry.getKey()).hasSize(5);
            assertThat(phases.get(0).startDayOffset())
                    .as("%s 必须从第 0 天起（startDayOffset 是相对开服的天数偏移，赛季内自算）", entry.getKey())
                    .isZero();
            for (int i = 1; i < phases.size(); i++) {
                SeasonCfg prev = phases.get(i - 1);
                SeasonCfg cur = phases.get(i);
                assertThat(cur.startDayOffset())
                        .as("%s 阶段 %d 必须紧接阶段 %d 结束，不能有空隙或重叠",
                                entry.getKey(), cur.phaseNo(), prev.phaseNo())
                        .isEqualTo(prev.startDayOffset() + prev.durationDays());
            }
            long totalDays = phases.stream().mapToLong(SeasonCfg::durationDays).sum();
            assertThat(totalDays).as("%s 的赛季总长应为 45 天", entry.getKey()).isEqualTo(45L);
            if (expectedTotal == null) {
                expectedTotal = totalDays;
            } else {
                assertThat(totalDays)
                        .as("各季必须等长：SeasonRulesAssembler 用第一季的长度算「现在是第几季」，"
                                + "不等长会让跨季那几天落在错误的季里")
                        .isEqualTo(expectedTotal);
            }
        }
    }

    @Test
    @DisplayName("兵种与克制矩阵自洽：每条克制关系的双方都是真实存在的兵种类型")
    void counterMatrixReferencesRealUnitTypes() {
        Set<UnitCfg.Type> realTypes = Set.of(UnitCfg.Type.values());
        assertThat(realTypes).hasSize(4);
        // unit_counter 的 attacker/defender 是枚举，编译期已保证合法；
        // 这里校验的是「每个兵种至少参与一条克制关系」，否则该兵种在克制网里是孤岛
        for (UnitCfg.Type type : realTypes) {
            boolean asAttacker = registry.all(com.ironoath.config.cfg.UnitCounterCfg.class).stream()
                    .anyMatch(c -> c.attacker().name().equals(type.name()));
            boolean asDefender = registry.all(com.ironoath.config.cfg.UnitCounterCfg.class).stream()
                    .anyMatch(c -> c.defender().name().equals(type.name()));
            assertThat(asAttacker || asDefender)
                    .as("兵种 %s 必须至少参与一条克制关系，否则它在战斗里没有策略定位", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("预留表的结构已定稿：字段齐备，B11/B10/B13 可直接填数据而不必改表结构")
    void reservedTablesHaveFinalizedStructure() {
        // C06 公理六的四项可验证要求必须都有对应字段，否则 B11 无法落地「Bot 像人」
        assertThat(DeclaredFields.of(configDir(), "bot_archetype"))
                .contains("reactionDelayMaxSec", "helpDelayMaxSec", "suboptimalChanceMax", "growthFactorMax");

        // 社交三级的人数上限字段必须存在（B00 铁律三：小队 5→10、联盟 30→150、国家 200→800）
        assertThat(DeclaredFields.of(configDir(), "squad_config")).contains("memberCap", "unlockMainLevel");
        assertThat(DeclaredFields.of(configDir(), "alliance_config")).contains("memberCap", "territoryCap");
        // 国家官职数量：B00 铁律二要求 Bot 不得担任国家官职，任命时需要这个上限
        assertThat(DeclaredFields.of(configDir(), "nation_config"))
                .contains("memberCap", "officeCount", "treasuryCap", "warCooldownHours");

        // Bot 聊天语料必须能按场景与等级分层，否则一句话在所有场合通用会一眼假
        assertThat(DeclaredFields.of(configDir(), "bot_chat")).contains("scene", "minCityLevel");
        // Bot 作息必须能区分工作日与周末
        assertThat(DeclaredFields.of(configDir(), "bot_schedule")).contains("hourOfDay", "isWeekendOnly");
    }

    /** 读取某张表 fieldTypes 中声明的字段名。预留表 rows 为空，只能从声明校验结构。 */
    private static final class DeclaredFields {
        static Set<String> of(Path dir, String table) {
            try {
                var root = com.ironoath.common.json.JsonUtils.readTree(
                        Files.readString(dir.resolve(table + ".json"), java.nio.charset.StandardCharsets.UTF_8));
                Set<String> names = new java.util.LinkedHashSet<>();
                root.get("fieldTypes").fieldNames().forEachRemaining(names::add);
                return names;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取表失败: " + table, e);
            }
        }
    }

    private static Path configDir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("contract").resolve("config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("未找到 contract/config");
    }

    @Test
    @DisplayName("unit 表每行的展示名都以 T<tier> 结尾：军队行只印 name，不再由客户端拼档位前缀")
    void unitNamesCarryTheirTier() {
        // 视图 ArmyPanelView 现在直接印 row.name（客户端不自己翻译表文案，B00 铁律）。
        // 表里一旦又出现「重步兵」这种不带档位的名字，T1 行就没有档位、T2~T5 有；
        // 而客户端若再加前缀，T2~T5 会印成「T2 重步兵 T2」—— 这一族的成因就是表自身不一致，
        // 所以把它做成门，而不是靠人记得对齐（收口清单里"档位名统一"那条）。
        for (UnitCfg unit : registry.all(UnitCfg.class)) {
            assertThat(unit.name())
                    .as("兵种 %s 的展示名要自带档位（tier=%d ⇒ 以 T%d 结尾），否则军队行会缺档位或重复档位",
                            unit.id(), unit.tier(), unit.tier())
                    .endsWith("T" + unit.tier());
        }
    }
}
