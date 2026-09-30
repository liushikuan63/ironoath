package com.ironoath.core.nation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：B13 国家与国战核心规则的单测 —— 验收 1（冲突规则）、2（入籍冷却）、
 * 3（官职席位）、5（国库日志）、6（积分制防偷家）、7（疲劳值）、10（全服目标只领一次）。
 * 依赖：JUnit 5 + AssertJ + game-core 的 nation 包（纯 Java，零框架）。
 *
 * <p>夹具数值抄自 contract/config（nation_config 三档 200/400/800、global 的冷却 24h、
 * 税收 10000/盟/周、日志保留 200 条、国战 3 小时、4 座关卡、三类积分 10/1/50、
 * 疲劳 5/1/上限 100、全服目标 50000 击杀）。「表值 == 文档值」由 game-config 侧断言，
 * 所以本类可以放心手抄 —— 手抄的那份若与表漂移，配置测试会先变红。
 */
class NationSystemTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    // ---------- 夹具 ----------

    private static Nation.LevelRule level(long lv, long cap, long treasury, long policy) {
        return new Nation.LevelRule(lv, cap, 12, treasury, policy, 24);
    }

    private static Nation.Rules rules() {
        return new Nation.Rules(
                List.of(level(1, 200, 500_000L, 1), level(2, 400, 2_000_000L, 2),
                        level(3, 800, 8_000_000L, 3)),
                16, 13,          // 主城 16 级 + 开服 D14（0-based 13）
                4,               // 单 kingdom 最多 4 个国家
                24 * HOUR,       // 入籍冷却 24h
                10_000L,         // 每盟每周税收
                200,             // 国库日志保留条数
                12,              // 固定官职席位
                // 0.5（定点）。两个成员联盟 ⇒ 周税入库 20000 ⇒ 非国王身份的周支出上限 10000
                5_000L,
                // 国策（B13 §4）：投票窗 24h、一轮 48h、通过门槛 50%（定点）、参与下限每盟 1 人
                24 * HOUR, 48 * HOUR, 5_000L, 1);
    }

    private static Nation newNation() {
        Nation nation = Nation.found("n1", "铁誓王国", "king", "a1", 100, 100, 1000L, rules());
        nation.bindAllianceLeaderLookup(allianceId -> "leader_" + allianceId);
        return nation;
    }

    private static WarScoreBoard.Rules warRules() {
        return new WarScoreBoard.Rules(3 * HOUR, 4, 10L, 1L, 50L, 5L, 1L, 100L, 50_000L);
    }

    private static WarScoreBoard newWar(long start) {
        WarScoreBoard board = new WarScoreBoard(warRules(), start);
        board.registerNation("n1");
        board.registerNation("n2");
        return board;
    }

    // ---------- 联盟 ⊂ 国家（禁止项：个人不单独入籍） ----------

    @Test
    @DisplayName("成员表的键是联盟而不是玩家：数据结构上就无法表达「个人单独入籍」")
    void membershipIsByAllianceNotByPlayer() {
        Nation nation = newNation();
        assertThat(nation.memberAllianceIds()).containsExactly("a1");
        assertThat(nation.memberAllianceCount()).isEqualTo(1);

        nation.admitAlliance("a2", 2000L);
        assertThat(nation.memberAllianceIds()).containsExactly("a1", "a2");
        assertThat(nation.hasAlliance("a2")).isTrue();
        assertThatThrownBy(() -> nation.admitAlliance("a2", 3000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已经是本国成员");
    }

    @Test
    @DisplayName("admitBlockFor 分开给出三条拒绝理由，而文案与 admitAlliance 抛出的那句同源")
    void admitBlockSeparatesReasonsSoTheWebCanMapCodes() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 2000L);

        // 1 级国的上限是 2 个联盟，所以第三个是"满了"而不是"冷却了"
        assertThat(nation.admitBlockFor("a3", 2000L))
                .as("满了要单独成一个原因：客户端要引导换一家，而不是让玩家等").get()
                .extracting(Nation.AdmitBlock::reason).isEqualTo(Nation.AdmitRejection.FULL);
        assertThat(nation.admitBlockFor("a3", 2000L).orElseThrow().message()).contains("上限 2 个");
        assertThat(nation.admitBlockFor("a2", 2000L).orElseThrow().reason())
                .as("重复入籍与名额满了是两件事").isEqualTo(Nation.AdmitRejection.ALREADY_MEMBER);

        nation.removeAlliance("a2", false, "leader_a2", 3000L);
        assertThat(nation.admitBlockFor("a2", 3000L + HOUR).orElseThrow().reason())
                .isEqualTo(Nation.AdmitRejection.COOLDOWN);
        assertThatThrownBy(() -> nation.admitAlliance("a2", 3000L + HOUR))
                .isInstanceOf(IllegalStateException.class)
                // 同源的文案：如果两处分开写，同一个玩家同一个动作会因为"走哪条路径"看到不同提示，
                // 而 web 层是按 block.message() 回给客户端的
                .hasMessage(nation.admitBlockFor("a2", 3000L + HOUR).orElseThrow().message())
                .hasMessageContaining("秒");
        assertThat(nation.admitBlockFor("a2", 3000L + DAY + 1L))
                .as("冷却过去就该重新收：这条表要能清空，否则一次退出等于永久除名").isEmpty();
    }

    @Test
    @DisplayName("建国者必须在某个联盟中：没有联盟的人建出来的国家是个空壳")
    void foundingRequiresAllianceMembership() {
        assertThatThrownBy(() -> Nation.found("n1", "空壳国", "king", null, 0, 0, 0L, rules()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须在某个联盟中");
        assertThatThrownBy(() -> Nation.found("n1", "空壳国", "king", " ", 0, 0, 0L, rules()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("建国前置：主城 16 级 + 开服 D14 + 在联盟中，差什么都要说明")
    void unlockCheckExplainsWhatIsMissing() {
        Nation.Rules rules = rules();
        assertThat(Nation.checkUnlock(16, 13, true, 0L, 0L, rules)).isNull();
        assertThat(Nation.checkUnlock(15, 13, true, 0L, 0L, rules)).contains("主城 16 级");
        assertThat(Nation.checkUnlock(16, 12, true, 0L, 0L, rules)).contains("开服第 14 天");
        assertThat(Nation.checkUnlock(16, 13, false, 0L, 0L, rules))
                .as("不在联盟中必须说清「国家由联盟整体加入」").contains("联盟");
        assertThat(Nation.checkUnlock(16, 13, true, 5000L, 1000L, rules)).contains("冷却");
    }

    @Test
    @DisplayName("可容纳的联盟数由人数上限推出：200→2、400→4、800→8（B13 §1 括号里的说明）")
    void allianceCapacityFollowsMemberCap() {
        Nation nation = newNation();
        assertThat(nation.maxAllianceCount()).isEqualTo(2);
        assertThat(nation.memberCap()).isEqualTo(200);
    }

    // ---------- 验收 2：入籍冷却 ----------

    @Test
    @DisplayName("验收2：联盟退出国家后 24h 内无法加入任何国家；主动退出与被开除同样触发冷却")
    void leavingNationTriggersJoinCooldown() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);

        nation.removeAlliance("a2", false, "leader_a2", 5000L);
        assertThat(nation.hasAlliance("a2")).isFalse();
        assertThat(nation.lastRemovalWasExpulsion()).isFalse();
        assertThat(nation.joinCooldownUntil("a2")).isEqualTo(5000L + DAY);

        // 冷却期内不能加入任何国家（包括同一个）
        assertThatThrownBy(() -> nation.admitAlliance("a2", 5000L + HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冷却");
        Nation other = Nation.found("n2", "另一个国", "king2", "b1", 0, 0, 0L, rules());
        other.bindAllianceLeaderLookup(id -> "leader_" + id);
        // 冷却记录落在国家上，所以别的国家也要能查到它 —— 由 service 层统一裁决
        assertThat(nation.joinCooldownUntil("a2")).isGreaterThan(5000L + HOUR);
        assertThat(other.hasAlliance("a2")).isFalse();

        // 冷却结束后可以再加入
        nation.admitAlliance("a2", 5000L + DAY + 1);
        assertThat(nation.hasAlliance("a2")).isTrue();
    }

    @Test
    @DisplayName("被开除与主动退出都触发冷却：主动退出若无冷却，就能在国战开始前换边")
    void expulsionAlsoTriggersCooldown() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.removeAlliance("a2", true, "king", 2000L);
        assertThat(nation.lastRemovalWasExpulsion()).isTrue();
        assertThat(nation.joinCooldownUntil("a2")).isEqualTo(2000L + DAY);
    }

    @Test
    @DisplayName("退盟的联盟若持有官职，官职一律收回：不在国里的联盟不该继续行使国家权力")
    void officesAreRevokedWhenAllianceLeaves() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.appoint("king", "general_1", "a2", Nation.Office.GENERAL);
        assertThat(nation.holdersOf(Nation.Office.GENERAL)).containsExactly("general_1");

        nation.removeAlliance("a2", true, "king", 2000L);
        assertThat(nation.holdersOf(Nation.Office.GENERAL))
                .as("联盟被开除后其成员的官职必须收回").isEmpty();
        assertThat(nation.officeOf("general_1")).isNull();
    }

    @Test
    @DisplayName("解散国家：所有成员联盟都进入冷却（与退出同一条规则）")
    void disbandPutsEveryMemberIntoCooldown() {
        Nation nation = newNation();
        // Lv1 的人数上限 200 ⇒ 最多 2 个联盟（建国者已占一个）
        nation.admitAlliance("a2", 1000L);
        assertThatThrownBy(() -> nation.admitAlliance("a3", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("联盟数已满");

        nation.disband("king", 9000L);
        assertThat(nation.isDisbanded()).isTrue();
        assertThat(nation.memberAllianceCount()).isZero();
        assertThat(nation.treasury()).isZero();
        for (String allianceId : List.of("a1", "a2")) {
            assertThat(nation.joinCooldownUntil(allianceId))
                    .as("解散也要让成员联盟进入冷却，否则「解散重建」就成了绕过冷却的手段")
                    .isEqualTo(9000L + DAY);
        }
        assertThatThrownBy(() -> nation.admitAlliance("a4", 10_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已解散");
        // 已解散的国家上任何变更都被 requireActive 挡下，包括再次解散
        assertThatThrownBy(() -> nation.disband("king", 10_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已解散");
        // 「只有国王能解散」要用一个尚未解散的国家才能验证
        Nation fresh = newNation();
        assertThatThrownBy(() -> fresh.disband("not_king", 10_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只有国王");
    }

    @Test
    @DisplayName("最后一个成员联盟走掉，国家当场算亡：不留一个零成员的活国")
    void lastMemberAllianceLeavingCollapsesTheNation() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.deposit("king", "tax_seed", 5_000L, "用例注资", 1200L);
        nation.appoint("king", "general_2", "a2", Nation.Office.GENERAL);

        nation.removeAlliance("a2", false, "leader_a2", 2000L);
        assertThat(nation.isDisbanded())
                .as("还剩建国联盟 a1 —— 走空这件事还没发生，不许提前算亡").isFalse();

        nation.removeAlliance("a1", false, "leader_a1", 3000L);
        assertThat(nation.isDisbanded())
                .as("成员联盟走空之后，「这个国还算不算存在」不能有第二种答案").isTrue();
        assertThat(nation.treasury()).as("亡国不许留着余额").isZero();
        assertThat(nation.holdersOf(Nation.Office.GENERAL))
                .as("走的是与国王主动解散同一个拆解入口，官职要一起清").isEmpty();
        // 核销日志的操作者必须是真正促成这件事的人。这里写 kingId 等于在账本上伪造一笔
        // 从未发生过的决定 —— 国王此刻什么都没做，他只是不再有人属于他的国
        assertThat(nation.treasuryLogs())
                .filteredOn(log -> "collapse_writeoff".equals(log.payee()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.operatorId()).isEqualTo("leader_a1");
                    assertThat(log.amount()).isEqualTo(5_000L);
                    assertThat(log.balanceAfter()).isZero();
                });
        // 算亡之后与国王主动解散同构：任何变更都被 requireActive 挡下
        assertThatThrownBy(() -> nation.admitAlliance("a3", 4000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已解散");
    }

    // ---------- 验收 3：官职席位 ----------

    @Test
    @DisplayName("验收3：席位严格按 B13 §2 —— 大将军 2 席、内政官与外交官各 4 席，满席即拒")
    void officeSeatsFollowTheDocumentedTable() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);

        nation.appoint("king", "g1", "a1", Nation.Office.GENERAL);
        nation.appoint("king", "g2", "a2", Nation.Office.GENERAL);
        assertThat(nation.holdersOf(Nation.Office.GENERAL)).containsExactly("g1", "g2");
        assertThatThrownBy(() -> nation.appoint("king", "g3", "a1", Nation.Office.GENERAL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("席位已满");

        for (int i = 0; i < 4; i++) {
            nation.appoint("king", "m" + i, "a1", Nation.Office.MINISTER);
        }
        assertThatThrownBy(() -> nation.appoint("king", "m4", "a1", Nation.Office.MINISTER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("席位已满");
    }

    @Test
    @DisplayName("国王只能转让产生、议员随盟主身份自动产生，两者都不能手动任命")
    void kingAndRepresentativeAreNotAppointable() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);

        assertThatThrownBy(() -> nation.appoint("king", "x", "a1", Nation.Office.KING))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能通过转让");
        assertThatThrownBy(() -> nation.appoint("king", "x", "a1", Nation.Office.REPRESENTATIVE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自动产生");

        // §2：议员每盟主 1 席，随成员联盟数变化
        assertThat(nation.holdersOf(Nation.Office.REPRESENTATIVE))
                .containsExactly("leader_a1", "leader_a2");
        nation.removeAlliance("a2", false, "leader_a2", 2000L);
        assertThat(nation.holdersOf(Nation.Office.REPRESENTATIVE)).containsExactly("leader_a1");
    }

    @Test
    @DisplayName("被任命者的联盟必须在本国内：让一个不在国里的人担任官职等于把权力交给外国人")
    void appointeeMustBelongToMemberAlliance() {
        Nation nation = newNation();
        assertThatThrownBy(() -> nation.appoint("king", "outsider", "a9", Nation.Office.DIPLOMAT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在本国内");
    }

    @Test
    @DisplayName("不能兼任两个固定官职：兼任会让「12 席」变成实际 6 个人")
    void noHoldingTwoOffices() {
        Nation nation = newNation();
        nation.appoint("king", "x", "a1", Nation.Office.GENERAL);
        assertThatThrownBy(() -> nation.appoint("king", "x", "a1", Nation.Office.MINISTER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能兼任");
        // 议员是随身份自动产生的，不算兼任
        assertThat(nation.officeOf("leader_a1")).isEqualTo(Nation.Office.REPRESENTATIVE);
    }

    @Test
    @DisplayName("王位转让：不能转给自己，转让后原国王不再持有王位")
    void abdication() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        assertThatThrownBy(() -> nation.abdicate("king", "king"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自己");
        assertThatThrownBy(() -> nation.abdicate("someone", "leader_a2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只有国王");

        nation.abdicate("king", "leader_a2");
        assertThat(nation.kingId()).isEqualTo("leader_a2");
        assertThat(nation.holdersOf(Nation.Office.KING)).containsExactly("leader_a2");
    }

    // ---------- 验收 5：国库日志 ----------

    @Test
    @DisplayName("验收5：每笔支出都有完整日志（谁/何时/支给谁/多少），且缺任一项就在签名层拒绝")
    void everyTreasurySpendIsLogged() {
        Nation nation = newNation();
        nation.setClock(1000L);
        nation.deposit("king", "war_loot", 100_000L, "国战战利品", 1000L);
        assertThat(nation.treasury()).isEqualTo(100_000L);

        nation.setClock(2000L);
        nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 30_000L,
                "研究国家科技·攻击", 1L);
        assertThat(nation.treasury()).isEqualTo(70_000L);

        List<Nation.TreasuryLog> logs = nation.treasuryLogs();
        assertThat(logs).hasSize(2);
        Nation.TreasuryLog income = logs.get(0);
        assertThat(income.amount())
                .as("入账必须记成正的规模：原先这里记的是 -credited，与支出那侧符号相反，"
                        + "把日志金额加总会得出「入账把钱抽走」的结论")
                .isEqualTo(100_000L);
        assertThat(income.payee()).as("入账时 payee 是来源").isEqualTo("war_loot");
        assertThat(income.balanceAfter()).isEqualTo(100_000L);
        assertThat(income.at()).isEqualTo(1000L);

        Nation.TreasuryLog spend = logs.get(1);
        assertThat(spend.operatorId()).as("谁").isEqualTo("king");
        assertThat(spend.payee()).as("支给谁：消耗性用途写成 sink:<用途> 的形态，「没有收款人」也要显式写出来")
                .isEqualTo("sink:NATIONAL_TECH");
        assertThat(spend.amount()).as("多少").isEqualTo(30_000L);
        assertThat(spend.reason()).as("为什么").isEqualTo("研究国家科技·攻击");
        assertThat(spend.balanceAfter()).isEqualTo(70_000L);
        assertThat(spend.at()).as("何时").isEqualTo(2000L);

        // 对账不变量：每一行的 balanceAfter 与前一行之差必须等于那一笔的实际变动量。
        // 单独断两行只挡住"这一行写错"，这条挡住"两行自洽但整体不平"
        long previous = 0L;
        for (Nation.TreasuryLog entry : logs) {
            long delta = entry.balanceAfter() - previous;
            assertThat(Math.abs(delta))
                    .as("每一行的余额变动幅度都应当等于它记下的那一笔规模：%s", entry)
                    .isEqualTo(entry.amount());
            previous = entry.balanceAfter();
        }
        assertThat(previous).as("最后一行的余额就是国库余额").isEqualTo(nation.treasury());

        // 缺 payee 或 reason 直接在签名层拒绝：没有「谁」和「为什么」的日志无法追责
        assertThatThrownBy(() -> Nation.Payee.toPlayer(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("支给谁");
        assertThatThrownBy(() -> nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.WAR_BOOST),
                100L, " ", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("用途");
        assertThatThrownBy(() -> nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.WAR_BOOST),
                0L, "y", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正");
        assertThatThrownBy(() -> nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.WAR_BOOST),
                999_999L, "y", 1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("资金不足");
    }

    @Test
    @DisplayName("C16：非国王身份的国库支出被周限额夹住，国王不受限")
    void officerSpendIsCappedPerWeekWhileKingIsNot() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.setClock(1500L);
        // 夹具：每盟每周 10000、两个成员联盟 ⇒ 本周入库 20000；比例 0.5 ⇒ 官员周额度 10000
        assertThat(nation.collectTax(1L, 1500L)).isEqualTo(20_000L);
        assertThat(nation.officerWeeklySpendCap(1L)).isEqualTo(10_000L);

        nation.spend("pm", Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 6_000L, "国策", 1L);
        assertThatThrownBy(() -> nation.spend("pm",
                Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 6_000L, "国策", 1L))
                .as("限额按**本周累计**算：单笔 6000 合法，不代表第二笔 6000 也合法")
                .isInstanceOf(Nation.OfficerSpendLimitException.class);
        assertThat(nation.treasury()).as("被拒的那一笔一分都不许扣").isEqualTo(14_000L);

        // 换周：额度重新给满，而分母换成了新一周的入库
        assertThat(nation.collectTax(2L, 8_000L)).isEqualTo(20_000L);
        nation.spend("pm", Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 6_000L, "国策", 2L);
        assertThat(nation.treasury()).isEqualTo(28_000L);

        nation.spend("king", Nation.Payee.toSink(Nation.Payee.Sink.WAR_BOOST), 28_000L, "国战增益", 2L);
        assertThat(nation.treasury()).as("B13 §2：国王的支取没有上限").isZero();
    }

    @Test
    @DisplayName("C16：限额的分母是「本周实际入库」而不是应收 —— 国库被容量截断时两者不等")
    void capUsesActualCreditNotTheoreticalIncome() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.setClock(1000L);
        // Lv1 容量 500000：先灌到只剩 5000 的空位
        nation.deposit("king", "war_loot", 495_000L, "国战战利品", 1000L);

        assertThat(nation.collectTax(1L, 1500L))
                .as("应收 20000，但只剩 5000 的位置 ⇒ 实收 5000").isEqualTo(5_000L);
        assertThat(nation.officerWeeklySpendCap(1L))
                .as("按应收算会给到 10000，那等于让官员花掉国家从没收到过的钱").isEqualTo(2_500L);
    }

    @Test
    @DisplayName("C16：本周没结税就没有额度，上周收了多少都不算")
    void noCreditThisWeekMeansNoAllowance() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 1000L);
        nation.collectTax(1L, 1500L);

        assertThat(nation.officerWeeklySpendCap(2L))
                .as("第 2 周还没结税 ⇒ 额度 0。让上周的入库继续给额度，就是每周都在花一笔没收到的钱")
                .isZero();
        assertThatThrownBy(() -> nation.spend("pm",
                Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 1L, "国策", 2L))
                .isInstanceOf(Nation.OfficerSpendLimitException.class);
    }

    @Test
    @DisplayName("国库日志有保留上限：超出丢最旧的，否则查询国库面板要全量载入几万条")
    void treasuryLogIsBounded() {
        Nation nation = newNation();
        nation.deposit("king", "initial", 10_000_000L, "初始资金", 0L);
        for (int i = 0; i < 250; i++) {
            nation.setClock(i);
            nation.spend("king", Nation.Payee.toPlayer("P" + i), 100L, "俸禄 " + i, 1L);
        }
        assertThat(nation.treasuryLogs()).hasSize(200);
        assertThat(nation.treasuryLogs().get(199).reason()).as("保留的是最近的").isEqualTo("俸禄 249");
    }

    @Test
    @DisplayName("国库容量随等级放大，入账超出容量时截断并照实返回（不能让税凭空消失）")
    void treasuryIsCappedByLevel() {
        Nation nation = newNation();
        assertThat(nation.treasuryCap()).isEqualTo(500_000L);
        long credited = nation.deposit("king", "loot", 900_000L, "一次性大额入账", 0L);
        assertThat(credited).as("超出容量的部分不入账，但要照实返回实际入账额").isEqualTo(500_000L);
        assertThat(nation.treasury()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("收税按周幂等：同一个 weekKey 重复调用不会重复收税（否则一次重放就能刷满国库）")
    void taxCollectionIsIdempotentPerWeek() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 0L);
        assertThat(nation.collectTax(1L, 1_000L)).as("2 个联盟 × 每周 10000").isEqualTo(20_000L);
        assertThat(nation.collectTax(1L, 1_500L)).as("同一周重复调用").isZero();
        assertThat(nation.collectTax(0L, 1_600L)).as("更早的周也不能补收").isZero();
        assertThat(nation.collectTax(2L, 2_000L)).isEqualTo(20_000L);
        assertThat(nation.treasury()).isEqualTo(40_000L);

        // 周税也要进日志：它是国库最大的一笔常规变动，没有审计行的账本对不平
        List<Nation.TreasuryLog> logs = nation.treasuryLogs();
        assertThat(logs).as("两次真实结算各一行，重复调用那两次不许留下行").hasSize(2);
        assertThat(logs.get(0).operatorId()).as("周税不是哪个玩家经手的，操作者必须是系统标识")
                .isEqualTo(Nation.OPERATOR_SYSTEM);
        assertThat(logs.get(0).at()).as("日志时刻取本次结算的时刻，不是周键").isEqualTo(1_000L);
        assertThat(logs.get(0).amount()).isEqualTo(20_000L);
        assertThat(logs.get(0).balanceAfter()).isEqualTo(20_000L);
        assertThat(logs.get(1).balanceAfter())
                .as("最后一行的余额必须等于国库余额（否则说明有人在改余额而不走日志）")
                .isEqualTo(nation.treasury());
    }

    // ---------- 外交与冲突规则（验收 1、12） ----------

    @Test
    @DisplayName("冲突规则4：外交关系优先于私人关系 —— mayAttackNation 的签名里没有任何私人关系参数")
    void diplomacyOverridesPersonalRelations() {
        Nation nation = newNation();
        nation.setDiplomacy("n2", Nation.Diplomacy.ALLIED);
        assertThat(nation.mayAttackNation("n2")).as("盟约不可互攻").isFalse();

        nation.setDiplomacy("n2", Nation.Diplomacy.HOSTILE);
        assertThat(nation.mayAttackNation("n2")).isTrue();

        nation.setDiplomacy("n3", Nation.Diplomacy.TRIBUTARY);
        assertThat(nation.mayAttackNation("n3")).as("C22：朝贡双向禁攻，记着这一档的一侧打不动对方").isFalse();

        // 中立默认可被宣战；未登记的关系也是中立
        assertThat(nation.mayAttackNation("n4")).isTrue();
        assertThat(nation.diplomacyWith("n4")).isEqualTo(Nation.Diplomacy.NEUTRAL);

        nation.setDiplomacy("n2", Nation.Diplomacy.NEUTRAL);
        assertThat(nation.diplomacyWith("n2")).as("设回中立等于删除登记").isEqualTo(Nation.Diplomacy.NEUTRAL);
        assertThatThrownBy(() -> nation.setDiplomacy("n1", Nation.Diplomacy.HOSTILE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("自己");
    }

    @Test
    @DisplayName("C21+C22：条约要两侧各自宣布才成立，成立之后双向禁攻")
    void treatyNeedsBothSidesAndThenBlocksBothDirections() {
        Nation liege = Nation.found("n-liege", "宗主国", "k1", "a1", 100, 100, 1000L, rules());
        Nation vassal = Nation.found("n-vassal", "藩属国", "k2", "a2", 120, 120, 1000L, rules());

        // 只有宗主单方面记着朝贡 ⇒ 不成立。这正是 C21 要消掉的形状：一边声明就能给对面挂免战牌
        liege.setDiplomacy("n-vassal", Nation.Diplomacy.TRIBUTARY);
        assertThat(Nation.treatyInForce(liege, vassal)).isFalse();
        assertThat(Nation.mayAttackEachOther(liege, vassal))
                .as("单边宣布不构成约束：藩属打不动宗主这件事，不是宗主声明出来的")
                .isTrue();

        // 对方也宣布同一个关系 ⇒ 成立，且双向都禁（C22：朝贡不是单向保护）
        vassal.setDiplomacy("n-liege", Nation.Diplomacy.TRIBUTARY);
        assertThat(Nation.treatyInForce(liege, vassal)).isTrue();
        assertThat(Nation.mayAttackEachOther(liege, vassal)).as("宗主打不动藩属").isFalse();
        assertThat(Nation.mayAttackEachOther(vassal, liege)).as("藩属也打不动宗主").isFalse();

        // 两侧不一致（一个 ALLIED 一个 TRIBUTARY）不算成立：那是两份不同的条约，不是一份
        vassal.setDiplomacy("n-liege", Nation.Diplomacy.ALLIED);
        assertThat(Nation.treatyInForce(liege, vassal))
                .as("双方各自宣布的必须是同一个关系才作数").isFalse();

        // 成立之后任一侧改回中立即解除 —— 撕约比结约便宜，这是"和平不该靠一次双边会议维持"的对称后果
        vassal.setDiplomacy("n-liege", Nation.Diplomacy.NEUTRAL);
        liege.setDiplomacy("n-vassal", Nation.Diplomacy.NEUTRAL);
        liege.setDiplomacy("n-vassal", Nation.Diplomacy.ALLIED);
        vassal.setDiplomacy("n-liege", Nation.Diplomacy.ALLIED);
        assertThat(Nation.treatyInForce(liege, vassal)).isTrue();
        vassal.setDiplomacy("n-liege", Nation.Diplomacy.HOSTILE);
        assertThat(Nation.mayAttackEachOther(liege, vassal))
                .as("被盟约挡住的一方单方面改敌对就能解约：结约要两个人，撕约只要一个")
                .isTrue();
    }

    // ---------- 验收 6：积分制防偷家 ----------

    @Test
    @DisplayName("验收6：三类积分分别计算正确（占领时长 / 击杀 / 占领建筑）")
    void threeScoreSourcesAreComputedSeparately() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");          // +50 建筑分
        board.beginSiege(0L);
        board.captureCapital("n1", 0L);             // +50 建筑分
        board.recordKill("n1", 1000);               // +1000 击杀分

        // 守到第 60 分钟：+60 × 10 = 600 占领分。
        // 中途查看用 snapshot（不结算占领分），settle 只能调一次
        WarScoreBoard.Score n1 = board.snapshot().get("n1");
        assertThat(n1.buildingScore()).isEqualTo(100L);
        assertThat(n1.killScore()).isEqualTo(1000L);

        WarScoreBoard.Result result = board.settle(60 * MINUTE);
        WarScoreBoard.Score settled = result.scores().get("n1");
        assertThat(settled.occupyScore()).as("占领 60 分钟 × 每分钟 10 分").isEqualTo(600L);
        assertThat(settled.total()).isEqualTo(1700L);
        assertThat(result.winnerId()).isEqualTo("n1");
    }

    @Test
    @DisplayName("验收6：最后一秒偷家无法翻盘 —— 守了 179 分钟的国家分数是偷家者的上百倍")
    void lastSecondCaptureCannotStealTheWin() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");
        board.captureGate("n2", "gate_2");
        board.beginSiege(0L);

        // n1 从第 0 分钟守到第 179 分钟
        board.captureCapital("n1", 0L);
        // n2 在最后一分钟偷家
        board.captureCapital("n2", 179 * MINUTE);

        WarScoreBoard.Result result = board.settle(180 * MINUTE);
        long n1Occupy = result.scores().get("n1").occupyScore();
        long n2Occupy = result.scores().get("n2").occupyScore();
        assertThat(n1Occupy).as("n1 守了 179 分钟").isEqualTo(179 * 10L);
        assertThat(n2Occupy).as("n2 只占了 1 分钟").isEqualTo(10L);
        assertThat(result.winnerId()).as("偷家翻不了盘").isEqualTo("n1");
        assertThat(n1Occupy).isGreaterThan(n2Occupy * 100L);
    }

    @Test
    @DisplayName("没有进攻资格（未占任何关卡）就不能开始王城战")
    void siegeRequiresGateQualification() {
        WarScoreBoard board = newWar(0L);
        assertThatThrownBy(() -> board.beginSiege(0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进攻资格");
        board.captureGate("n2", "gate_1");
        assertThat(board.isQualified("n2")).isTrue();
        assertThat(board.isQualified("n1")).isFalse();
        board.beginSiege(0L);
        assertThat(board.phase()).isEqualTo(WarScoreBoard.Phase.SIEGE);
    }

    @Test
    @DisplayName("关卡是排他的：一座关卡同时只能属于一方")
    void gatesAreExclusive() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");
        board.captureGate("n2", "gate_1");
        assertThat(board.gateCount("n1")).isZero();
        assertThat(board.gateCount("n2")).isEqualTo(1);
    }

    @Test
    @DisplayName("结算后不能再改积分，也不能重复结算（重复结算会让积分被算两遍）")
    void scoreboardIsImmutableAfterSettlement() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");
        board.beginSiege(0L);
        board.settle(HOUR);
        assertThat(board.phase()).isEqualTo(WarScoreBoard.Phase.SETTLED);
        assertThatThrownBy(() -> board.recordKill("n1", 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已结算");
        assertThatThrownBy(() -> board.settle(HOUR))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复结算");
    }

    @Test
    @DisplayName("同分时不给胜者：硬挑一个出来会让玩家觉得结果是被系统指定的")
    void tieHasNoWinner() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");
        board.captureGate("n2", "gate_2");
        board.beginSiege(0L);
        board.recordKill("n1", 100);
        board.recordKill("n2", 100);
        WarScoreBoard.Result result = board.settle(HOUR);
        assertThat(result.scores().get("n1").total()).isEqualTo(result.scores().get("n2").total());
        assertThat(result.winnerId()).as("平分不给胜者").isNull();
    }

    @Test
    @DisplayName("剩余时间绝不为负，且非王城战阶段为 0")
    void remainingSecondsIsNeverNegative() {
        WarScoreBoard board = newWar(0L);
        assertThat(board.remainingSeconds(0L)).as("筹备阶段").isZero();
        board.captureGate("n1", "gate_1");
        board.beginSiege(0L);
        assertThat(board.remainingSeconds(HOUR)).isEqualTo(2 * 3600L);
        assertThat(board.remainingSeconds(10 * HOUR)).as("超时后不得为负").isZero();
    }

    // ---------- 验收 7：疲劳值 ----------

    @Test
    @DisplayName("验收7：疲劳由行军与伤兵分别累积，到上限后不能再行军")
    void fatigueBlocksMarchingAtCap() {
        WarScoreBoard board = newWar(0L);
        assertThat(board.canMarch("p1")).isTrue();

        // 每次行军 5 点，20 次到顶
        assertThat(board.addFatigue("p1", 10, 0)).isEqualTo(50L);
        assertThat(board.canMarch("p1")).isTrue();
        assertThat(board.addFatigue("p1", 10, 0)).isEqualTo(100L);
        assertThat(board.canMarch("p1")).as("到上限后无法继续行军").isFalse();

        // 超出上限被截断，不会变成负数或溢出
        assertThat(board.addFatigue("p1", 100, 100)).isEqualTo(100L);
        assertThat(board.fatigueOf("p1")).isEqualTo(100L);
        assertThat(board.fatigueOf("unknown")).isZero();
        assertThat(board.canMarch("unknown")).isTrue();
    }

    @Test
    @DisplayName("伤兵也累积疲劳：只有行军疲劳的话，派小部队反复骚扰就不受惩罚")
    void woundedAlsoAccumulateFatigue() {
        WarScoreBoard board = newWar(0L);
        assertThat(board.addFatigue("p1", 0, 100)).as("100 个伤兵 × 1 点").isEqualTo(100L);
        assertThat(board.canMarch("p1")).isFalse();

        WarScoreBoard other = newWar(0L);
        assertThat(other.addFatigue("p2", 4, 80)).as("4 次行军 ×5 + 80 伤兵 ×1 = 100").isEqualTo(100L);
        assertThatThrownBy(() -> other.addFatigue("p3", -1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }

    // ---------- 验收 10：全服目标 ----------

    @Test
    @DisplayName("验收10：全服累计击杀达标后可领奖，且每人只能领一次")
    void serverGoalIsClaimableOncePerPlayer() {
        WarScoreBoard board = newWar(0L);
        board.captureGate("n1", "gate_1");
        board.beginSiege(0L);

        board.recordKill("n1", 49_999L);
        assertThat(board.serverGoalReached()).isFalse();
        assertThat(board.claimServerGoal("p1")).as("未达标不能领").isFalse();

        board.recordKill("n2", 1L);
        assertThat(board.totalKills()).isEqualTo(50_000L);
        assertThat(board.serverGoalReached()).isTrue();

        assertThat(board.claimServerGoal("p1")).isTrue();
        assertThat(board.claimServerGoal("p1")).as("每人只领一次").isFalse();
        assertThat(board.claimServerGoal("p2")).as("别人还能领").isTrue();
        assertThat(board.serverGoalClaimed()).isEqualTo(2);
        assertThat(board.settle(HOUR).serverGoalReached()).isTrue();
    }

    @Test
    @DisplayName("构造期校验：占领分为 0 就等于「守得住」没有回报，疲劳两项都为 0 就等于没有疲劳")
    void warRulesAreValidated() {
        assertThatThrownBy(() -> new WarScoreBoard.Rules(3 * HOUR, 4, 0L, 1L, 50L, 5L, 1L, 100L, 50_000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("占领分必须为正");
        assertThatThrownBy(() -> new WarScoreBoard.Rules(3 * HOUR, 4, 10L, 1L, 50L, 0L, 0L, 100L, 50_000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能同时为 0");
        assertThatThrownBy(() -> new WarScoreBoard.Rules(3 * HOUR, 0, 10L, 1L, 50L, 5L, 1L, 100L, 50_000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("关卡数");
        assertThatThrownBy(() -> new WarScoreBoard.Rules(0L, 4, 10L, 1L, 50L, 5L, 1L, 100L, 50_000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("时长必须为正");
    }

    @Test
    @DisplayName("国家规则构造期校验：国库容量与人数上限都必须随等级单调不减")
    void nationRulesAreValidated() {
        assertThatThrownBy(() -> new Nation.Rules(
                List.of(level(1, 400, 500_000L, 1), level(2, 200, 2_000_000L, 2)),
                16, 13, 4, DAY, 10_000L, 200, 12, 5_000L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("人数上限");
        assertThatThrownBy(() -> new Nation.Rules(
                List.of(level(1, 200, 2_000_000L, 1), level(2, 400, 500_000L, 2)),
                16, 13, 4, DAY, 10_000L, 200, 12, 5_000L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("国库容量必须随等级单调不减");
        assertThatThrownBy(() -> new Nation.Rules(List.of(level(1, 200, 500_000L, 1)),
                16, 13, 1, DAY, 10_000L, 200, 12, 5_000L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有一个国家就不存在外交");
        assertThatThrownBy(() -> new Nation.Rules(List.of(level(1, 200, 500_000L, 1)),
                16, 13, 4, DAY, 10_000L, 0, 12, 5_000L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留 0 条等于没有日志");
        // 比例 > 1 意味着官员一周能花掉比一周税收还多的钱，那已经不叫限额；
        // 而负数会让任何一次支出都被拒 —— 两种都不该等到运行时才发现
        assertThatThrownBy(() -> new Nation.Rules(List.of(level(1, 200, 500_000L, 1)),
                16, 13, 4, DAY, 10_000L, 200, 12, 10_001L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经不叫限额");
        assertThatThrownBy(() -> new Nation.Rules(List.of(level(1, 200, 500_000L, 1)),
                16, 13, 4, DAY, 10_000L, 200, 12, -1L, 24 * HOUR, 48 * HOUR, 5_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经不叫限额");
    }
}
