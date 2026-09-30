package com.ironoath.core.nation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：国家科技的领域规则（B20 块③ 验收 5 的那三条：国库扣减、核销流水、上限与前置）。
 * 依赖：纯 Java（{@link Nation} 与它的 {@link Nation.Rules}），零框架、零配置表。
 *
 * <p><b>为什么花费是参数而不是在这里算</b>：领域层不读配置表（铁律 1），所以曲线与基数由 web 层算好传进来。
 * 本类因此不能验"1050 这个数对不对"（那是 {@code NationTechCostTest} 的活），
 * 验的是<b>拿到一个数之后国库存不进、上限越过、等级不够时各说什么</b> —— 那些是唯一写在 core 里的判定。
 *
 * <p><b>断的都是"哪一枚拦截"而不是"抛没抛"</b>：四种失败玩家的下一步完全不同
 * （等下周额度 / 拉联盟进来 / 等国库进钱 / 换一行研究），把它们混成一枚 IllegalStateException
 * 就是让 web 层去猜，而猜错的表现是一个看得懂的提示配一个做不到的动作。
 */
class NationTechTest {

    private static final String GRAIN = "nt_agri_grain";

    private static Nation.LevelRule level(long lv, long cap, long treasury) {
        return new Nation.LevelRule(lv, cap, 12, treasury, 1, 24);
    }

    private static Nation.Rules rules() {
        long hour = 60 * 60 * 1000L;
        return new Nation.Rules(
                List.of(level(1, 200, 500_000L), level(2, 400, 2_000_000L), level(3, 800, 8_000_000L)),
                16, 13, 4, 24 * hour,
                10_000L,                     // 每盟每周税收
                200, 12,
                5_000L,                      // 官员周支出比例（定点 0.5）
                24 * hour, 48 * hour,       // 国策：投票窗 24h、一轮 48h
                5_000L, 1);                  // 通过门槛 50%（定点）、参与下限每盟 1 人
    }

    /**
     * 建国：发起人所在联盟入籍。
     *
     * <p>{@link Nation#found} 的第 7 个参数是<b>时刻</b>不是启动资金 —— 建出来的国家国库是 0，
     * 所以这里显式拨 1000 作为夹具的已知余额（几条断言读的就是这个数）。
     */
    private static Nation newNation() {
        Nation nation = Nation.found("n1", "工造王国", "king", "a1", 100, 100, 1_000L, rules());
        nation.bindAllianceLeaderLookup(allianceId -> "leader_" + allianceId);
        nation.deposit("king", "test_grant", 1_000L, "启动资金", 1_000L);
        return nation;
    }

    private static Nation.TreasuryLog lastLog(Nation nation) {
        List<Nation.TreasuryLog> logs = nation.treasuryLogs();
        assertThat(logs).as("花公共钱必须留痕，否则这一笔无从追责").isNotEmpty();
        return logs.get(logs.size() - 1);
    }

    @Test
    @DisplayName("研究一级 = 扣国库 + 写 sink:NATIONAL_TECH 流水 + 抬等级，三件事在同一步里完成")
    void researchPaysTheTreasuryAndLevelsUpInOneStep() {
        Nation nation = newNation();
        nation.deposit("king", "war_loot", 20_000L, "拨款给科技", 1_000L);
        long before = nation.treasury();

        Nation.TechResearch done = nation.researchTech(GRAIN, 1_050L, 20, 1, "king", 1L);

        assertThat(done.techId()).isEqualTo(GRAIN);
        assertThat(done.level()).as("从缺失（读成 0 级）涨到 1 级").isEqualTo(1);
        assertThat(done.cost()).isEqualTo(1_050L);
        assertThat(done.treasuryAfter()).isEqualTo(before - 1_050L);
        assertThat(nation.treasury())
                .as("返回的余额与聚合自己的余额必须是同一个数：两处各读一次就会有一个是旧的")
                .isEqualTo(before - 1_050L);
        assertThat(nation.techLevel(GRAIN)).isEqualTo(1);
        assertThat(nation.techLevels())
                .as("账本里只有研究过的那一行（不给 0 占位留位置）")
                .containsExactly(Map.entry(GRAIN, 1));
        Nation.TreasuryLog log = lastLog(nation);
        assertThat(log.payee()).as("验收 5 要的核销落点").isEqualTo("sink:NATIONAL_TECH");
        assertThat(log.amount()).isEqualTo(1_050L);
        assertThat(log.reason()).as("用途里得写明是哪一行").contains(GRAIN);
        assertThat(log.balanceAfter()).as("流水要能自证连贯：这一行的余额等于聚合的余额")
                .isEqualTo(nation.treasury());
    }

    @Test
    @DisplayName("拦截判定按「永久事实 → 这一行的门槛 → 钱」排，面板与写路径共用这一个判定")
    void theBlockJudgmentPutsThePermanentFactsFirst() {
        Nation nation = newNation();

        assertThat(nation.techBlock("king", 1L, 20, 20, 3, 999_999L))
                .as("满级是永久事实，不该被「国家还没到 3 级」或「没钱」盖住")
                .isEqualTo(Nation.TechBlock.MAX_LEVEL);
        assertThat(nation.techBlock("king", 1L, 0, 20, 2, 999_999L))
                .as("等级门槛是这一行自己的事，比国库余额更该先告诉玩家")
                .isEqualTo(Nation.TechBlock.NATION_LEVEL_LOW);
        assertThat(nation.techBlock("king", 1L, 0, 20, 1, 999_999L))
                .as("新建国的国库只有 1000，这一笔付不起").isEqualTo(Nation.TechBlock.TREASURY_LOW);
        assertThat(nation.techBlock("king", 1L, 0, 20, 1, 900L))
                .as("付得起就不该编造一个理由").isNull();
    }

    @Test
    @DisplayName("非国王的研究花的是同一周的国库额度；国王不受此限；没收税的那一周额度是 0")
    void anOfficerResearchesInsideTheSameWeeklyPoolAsAnyOtherSpend() {
        Nation nation = newNation();
        nation.collectTax(1L, 1_100L);
        long cap = nation.officerWeeklySpendCap(1L);
        assertThat(cap).as("一个成员联盟 × 每盟 10000，再乘比例 0.5").isEqualTo(5_000L);

        assertThat(nation.techBlock("minister", 1L, 0, 20, 1, cap + 1L))
                .as("比周额度多一块钱就该报超限，而不是报国库不足——两者的下一步完全不同")
                .isEqualTo(Nation.TechBlock.OFFICER_WEEKLY_LIMIT);
        assertThat(nation.techBlock("king", 1L, 0, 20, 1, cap + 1L))
                .as("国王不受此限，余额也够，所以放行").isNull();
        assertThat(nation.techBlock("minister", 2L, 0, 20, 1, 1L))
                .as("下周的额度要等下周的税入库才有：先收税才有得花，所以哪怕只花 1 块也被挡")
                .isEqualTo(Nation.TechBlock.OFFICER_WEEKLY_LIMIT);
    }

    @Test
    @DisplayName("免费、零上限、空行 id 是配置或调用错误：抛 IllegalArgument，且不留半个状态")
    void aZeroPriceOrCapIsAProgrammingError() {
        Nation nation = newNation();

        assertThatThrownBy(() -> nation.researchTech(GRAIN, 0L, 20, 1, "king", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("研究花费必须为正");
        assertThatThrownBy(() -> nation.researchTech(GRAIN, 900L, 0, 1, "king", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表给的上限与前置必须为正");
        assertThatThrownBy(() -> nation.researchTech("  ", 900L, 20, 1, "king", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("techId 不得为空");

        assertThat(nation.techLevels()).as("三条失败都没有留下半个账本").isEmpty();
        assertThat(nation.treasury()).as("更没有扣过钱").isEqualTo(1_000L);
    }

    @Test
    @DisplayName("四种失败各带自己的 block：web 层不必比对文案就能分到四枚码")
    void everyRefusalCarriesItsBlockSoTheWebLayerNeverSniffsMessages() {
        Nation nation = newNation();
        nation.deposit("king", "war_loot", 20_000L, "拨款", 1_000L);

        nation.researchTech(GRAIN, 900L, 1, 1, "king", 1L);   // 这一行的上限就是 1 级
        assertThatThrownBy(() -> nation.researchTech(GRAIN, 900L, 1, 1, "king", 1L))
                .isInstanceOf(Nation.TechResearchException.class)
                .extracting(e -> ((Nation.TechResearchException) e).block())
                .isEqualTo(Nation.TechBlock.MAX_LEVEL);
        assertThatThrownBy(() -> nation.researchTech("nt_mil_train", 900L, 20, 2, "king", 1L))
                .extracting(e -> ((Nation.TechResearchException) e).block())
                .isEqualTo(Nation.TechBlock.NATION_LEVEL_LOW);
        assertThatThrownBy(() -> nation.researchTech("nt_com_march", 99_999L, 20, 1, "king", 1L))
                .extracting(e -> ((Nation.TechResearchException) e).block())
                .isEqualTo(Nation.TechBlock.TREASURY_LOW);
        assertThatThrownBy(() -> nation.researchTech("nt_fort_build", 1L, 20, 1, "minister", 1L))
                .as("没结过税的这一周官员额度是 0，所以这条先撞限额而不是余额")
                .extracting(e -> ((Nation.TechResearchException) e).block())
                .isEqualTo(Nation.TechBlock.OFFICER_WEEKLY_LIMIT);
        assertThat(nation.techLevel(GRAIN)).as("被拒的几次都没有偷偷抬等级").isEqualTo(1);
    }

    @Test
    @DisplayName("被拒的那一笔不许占掉本周额度：先判定、后记账（原来的顺序会漏钱给一次失败的支出）")
    void aRefusedSpendLeavesTheWeeklyAllowanceIntact() {
        Nation nation = newNation();
        nation.collectTax(1L, 1_100L);                       // 入库 10000 ⇒ 官员周额度 5000
        nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.WAR_BOOST), 10_000L,
                "把余额花掉只留 1000", 1L);
        assertThat(nation.treasury()).as("起手 1000 + 周税 10000 - 10000").isEqualTo(1_000L);

        // 直接打 spend：researchTech 会先用 techBlock 判掉、根本走不到这里，
        // 而缺陷就长在 spend 的记账顺序上（旧代码把金额加进本周已花之后才查余额）
        assertThatThrownBy(() -> nation.spend("minister",
                Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 2_000L,
                "额度够（2000 ≤ 5000）但余额不够（1000 < 2000）的一笔", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("国库资金不足");

        // 这一条就是修的理由：失败的一笔把额度吃掉的话，症状是"没花成的事也扣了本周额度"，
        // 而内存存储里 Nation 是活对象 —— 改了就是改了，不会因为是副本而看不出来
        assertThat(nation.snapshot().spentThisWeek()).as("失败的一笔不许占额度").isZero();
        assertThat(nation.treasury()).as("余额也没动").isEqualTo(1_000L);
        assertThat(nation.techLevels()).as("经 techBlock 被挡的研究不留半个账本").isEmpty();
    }

    @Test
    @DisplayName("快照里带回来的脏等级（0 级、空白 id）读成「没研究过」，而不是把占位留在账本里")
    void dirtyLedgerEntriesAreDroppedOnRead() {
        Nation nation = newNation();
        Map<String, Integer> dirty = new LinkedHashMap<>();
        dirty.put(GRAIN, 3);
        dirty.put("nt_mil_train", 0);
        dirty.put(" ", 5);
        Nation.Snapshot snap = nation.snapshot();

        Nation rebuilt = Nation.fromSnapshot(new Nation.Snapshot(snap.id(), snap.name(), snap.kingId(),
                snap.capitalX(), snap.capitalY(), snap.level(), snap.treasury(), snap.memberAlliances(),
                snap.offices(), snap.diplomacy(), snap.joinCooldownUntil(), snap.treasuryLogs(),
                snap.provinces(), snap.holderAlliance(), dirty,
                snap.policyProposals(), snap.policyVotes(), snap.activePolicies(),
                snap.policyNextVoteAt(), snap.policyVoteOpenedAt(), snap.policyVoteEndsAt(),
                snap.lastTaxWeekKey(),
                snap.lastTaxCredited(), snap.spendWeekKey(), snap.spentThisWeek(),
                snap.disbandedAt(), snap.version()), rules());

        assertThat(rebuilt.techLevel(GRAIN)).as("干净的一行原样回来").isEqualTo(3);
        assertThat(rebuilt.techLevel("nt_mil_train"))
                .as("0 级读成没研究过（与 PlayerTech.levelOf 同一条读法）").isZero();
        assertThat(rebuilt.techLevels()).as("脏 id 不进账本，所以它也折不进加成")
                .containsExactly(Map.entry(GRAIN, 3));
    }

    @Test
    @DisplayName("老文档没有这一位时读成空账本：国家照样打得开，只是没人研究过科技")
    void anOldDocumentWithoutTheLedgerReadsAsEmpty() {
        Nation nation = newNation();
        Nation.Snapshot snap = nation.snapshot();

        Nation old = Nation.fromSnapshot(new Nation.Snapshot(snap.id(), snap.name(), snap.kingId(),
                snap.capitalX(), snap.capitalY(), snap.level(), snap.treasury(), snap.memberAlliances(),
                snap.offices(), snap.diplomacy(), snap.joinCooldownUntil(), snap.treasuryLogs(),
                snap.provinces(), snap.holderAlliance(), null,
                null, null, null,
                0L, 0L, 0L, snap.lastTaxWeekKey(),
                snap.lastTaxCredited(), snap.spendWeekKey(), snap.spentThisWeek(),
                snap.disbandedAt(), snap.version()), rules());

        assertThat(old.techLevels()).isEmpty();
        assertThat(old.techLevel(GRAIN)).isZero();
        assertThat(old.treasury()).as("缺一个可选字段不许连累其他状态").isEqualTo(1_000L);
    }
}
