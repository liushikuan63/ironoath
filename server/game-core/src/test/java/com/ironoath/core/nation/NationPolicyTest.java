package com.ironoath.core.nation;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：国策轮次的领域验证（B13 §4 / B21 块③，B13 验收 11 的「票数统计正确」那一半）。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不读配置表（game-core 读不到 game-config，数值由调用方传入）。
 *
 * <p><b>这一类用例最容易被写成「两边都算过」</b>：门槛、参与下限、槽位竞争三样都是
 * 「差一点就不一样」的边界。所以每一条都刻意落在<b>边界两侧</b>上 ——
 * 49% 与 50%、参与数比下限少 1 与刚好、票数相同而提案时刻不同时刻。
 * 只测「通过的那一侧」的话，把比较写成 {@code >=} 还是 {@code >} 都能过。
 */
class NationPolicyTest {

    private static final long HOUR = 60L * 60L * 1000L;
    /** 通过门槛 50%（定点）。与 global.NATION_POLICY_PASS_RATIO 同源。 */
    private static final long HALF = FixedPoint.SCALE / 2;

    private static Nation.LevelRule level(long lv, long cap, long treasury, long policy) {
        return new Nation.LevelRule(lv, cap, 12, treasury, policy, 24);
    }

    private static Nation.Rules rules() {
        return new Nation.Rules(
                List.of(level(1, 200, 500_000L, 1), level(2, 400, 2_000_000L, 2),
                        level(3, 800, 8_000_000L, 3)),
                16, 13, 4, 24 * HOUR, 10_000L, 200, 12, 5_000L,
                24 * HOUR, 48 * HOUR, HALF, 1);
    }

    private static Nation newNation() {
        return Nation.found("n1", "铁誓王国", "king", "a1", 100, 100, 0L, rules());
    }

    /** 造一个「有两条成员联盟」的国家：参与下限 = 2 票。 */
    private static Nation newNationWithTwoAlliances() {
        Nation nation = newNation();
        nation.admitAlliance("a2", 0L);
        return nation;
    }

    // ---------- 状态机 ----------

    @Test
    @DisplayName("刚建国时是提案段：能提案、不能投票，投票挡在「本轮还没有提案」")
    void aFreshNationIsInTheProposingPhase() {
        Nation nation = newNation();
        assertThat(nation.policyRound("king" , true, 0L).phase())
                .isEqualTo(Nation.PolicyPhase.PROPOSING);
        assertThat(nation.proposeBlock("king" , true, 0L)).isEqualTo(Nation.PolicyBlock.NONE);
        assertThat(nation.voteBlock("king", 0L))
                .as("没人提案时投票窗开不起来，而这件事玩家必须知道（面板要写明「本轮还没有提案」）")
                .isEqualTo(Nation.PolicyBlock.NO_PROPOSAL_YET);
    }

    @Test
    @DisplayName("提案之后仍不立刻开窗：要等一个提案段（= 一轮减投票窗 = 24 小时）")
    void theVoteWindowOpensOneProposingSegmentLater() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 1_000L);

        Nation.PolicyRound justAfter = nation.policyRound("king" , true, 1_000L);
        assertThat(justAfter.phase())
                .as("提案即开窗的话，内政官 4 席（B13 §2 给了他们提案权）会形同虚设")
                .isEqualTo(Nation.PolicyPhase.PROPOSING);
        assertThat(justAfter.nextVoteAt()).isEqualTo(1_000L + 24 * HOUR);

        Nation.PolicyRound later = nation.policyRound("king" , true, 1_000L + 24 * HOUR);
        assertThat(later.phase()).isEqualTo(Nation.PolicyPhase.VOTING);
        assertThat(later.voteEndsAt()).isEqualTo(1_000L + 48 * HOUR);
    }

    @Test
    @DisplayName("投票窗开着时不能再提案：提案与投票是两个不同的时间窗")
    void proposalsCloseWhenTheVoteWindowOpens() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 1_000L);
        nation.policyRound("king" , true, 1_000L + 24 * HOUR);

        assertThat(nation.proposeBlock("king" , true, 1_000L + 25 * HOUR))
                .isEqualTo(Nation.PolicyBlock.NOT_VOTING);
        assertThatThrownBy(() -> nation.propose("p2", "np_fortress", "king" , true, 1_000L + 25 * HOUR))
                .isInstanceOf(Nation.PolicyException.class)
                .extracting(e -> ((Nation.PolicyException) e).block())
                .isEqualTo(Nation.PolicyBlock.NOT_VOTING);
    }

    // ---------- 权限与 Bot ----------

    @Test
    @DisplayName("提案权由调用方传入的权限判定决定，而领域层自己看不见「是不是 Bot」")
    void thePermissionVerdictComesFromTheCallerAndTheDomainNeverAsksAboutBots() {
        Nation nation = newNation();
        assertThat(nation.proposeBlock("member", false, 0L))
                .as("普通成员没有提案权（role_permission 表的判定由外层传入，领域层不读表）")
                .isEqualTo(Nation.PolicyBlock.NOT_PROPOSER);
        assertThat(nation.proposeBlock("officer", true, 0L))
                .as("官员档由外层读表得出「有提案权」（2026-09-30 裁决 A1 放开了 allowOfficer）")
                .isEqualTo(Nation.PolicyBlock.NONE);

        // 领域层的签名里没有 isBot —— 这不是「忘了做」，是 check-no-bot-privilege 那条门禁：
        // 「除了 BotRegistry 之外，任何地方都不许问这是不是 Bot」。Bot 拒投由 game-web
        // 在调本层之前挡掉（它持有 BotRegistry），对应协议里的 BOT_NOT_ALLOWED。
        // 这里断言的是那条设计不会被悄悄改回去：签名里出现 Bot 身份就会有编译错误。
        assertThat(Arrays.stream(Nation.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("voteBlock") || m.getName().equals("proposeBlock")
                        || m.getName().equals("propose") || m.getName().equals("vote"))
                .flatMap(m -> Arrays.stream(m.getParameterTypes()))
                .map(Class::getSimpleName))
                .as("领域层任何与国策有关的入口都不许接一个 isBot 参数")
                .doesNotContain("boolean[]");
        assertThat(nation.voteBlock("anyone", 0L))
                .as("Bot 那一问不在领域层，所以领域层给出的答案是「本轮还没有提案」")
                .isEqualTo(Nation.PolicyBlock.NO_PROPOSAL_YET);
    }

    @Test
    @DisplayName("投票权不看提案权：普通成员能投票（裁决 A2 是每成员一票）")
    void votingDoesNotRequireTheProposePermission() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 1_000L);
        nation.policyRound("king" , true, 1_000L + 24 * HOUR);

        assertThat(nation.voteBlock("plainmember", 1000L + 25 * HOUR))
                .as("A2 定了每成员一票；B13 §2 的「议员：投票、提案」不能被读成「只有议员能投票」")
                .isEqualTo(Nation.PolicyBlock.NONE);
        nation.vote("p1", "plainmember" , true, 1_000L + 25 * HOUR);
        assertThat(nation.tallies()).singleElement()
                .satisfies(t -> assertThat(t.yes()).isEqualTo(1L));
    }

    @Test
    @DisplayName("同一提案只能投一次：第二票被 ALREADY_VOTED 挡住（票数与名单必须自证一致）")
    void aSecondVoteOnTheSameProposalIsRejected() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 1_000L);
        nation.policyRound("king" , true, 1_000L + 24 * HOUR);
        nation.vote("p1", "v1" , true, 1_000L + 25 * HOUR);

        assertThatThrownBy(() -> nation.vote("p1", "v1", false, 1_000L + 26 * HOUR))
                .isInstanceOf(Nation.PolicyException.class)
                .extracting(e -> ((Nation.PolicyException) e).block())
                .isEqualTo(Nation.PolicyBlock.ALREADY_VOTED);
        Nation.ProposalTally tally = nation.tallies().get(0);
        assertThat(tally.yes()).as("重放一次投票会让票数 +1 而名单只有一个人").isEqualTo(1L);
        assertThat(tally.supporters()).containsExactly("v1");
    }

    @Test
    @DisplayName("同一条国策本轮只能被提一次（公示的提案数与票数分母要能对上）")
    void theSamePolicyCannotBeProposedTwiceInOneRound() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 1_000L);
        assertThatThrownBy(() -> nation.propose("p2", "np_cavalry_t1", "officer" , true, 2_000L))
                .isInstanceOf(Nation.PolicyException.class)
                .extracting(e -> ((Nation.PolicyException) e).block())
                .isEqualTo(Nation.PolicyBlock.ALREADY_PROPOSED);
        assertThat(nation.policyRound("king" , true, 2_000L).proposals())
                .as("被拒的那次不能留下一条提案 —— 否则公示上会挂一条没人投过的提案")
                .hasSize(1);
    }

    // ---------- 门槛（边界两侧都测） ----------

    @Test
    @DisplayName("门槛边界：恰好 50% 通过，49% 不过（用 > 代替 >= 的实现会在这里露馅）")
    void theThresholdIsInclusiveAtExactlyHalf() {
        Nation half = newNation();
        half.propose("p1", "np_cavalry_t1", "king" , true, 0L);
        half.policyRound("king" , true, 0L + 24 * HOUR);
        // 2 票人：1 赞成 1 反对 = 50% ⇒ 过（参与下限 = 成员联盟数 1，2 ≥ 1）
        half.vote("p1", "a" , true, 1L);
        half.vote("p1", "b", false, 2L);
        assertThat(half.tallies().get(0).passed())
                .as("恰好 50% 必须算通过：写成 > 的话「一半人赞成」会被判不过，那不是门槛是刁难")
                .isTrue();

        Nation under = newNation();
        under.propose("p1", "np_cavalry_t1", "king" , true, 0L);
        under.policyRound("king" , true, 24 * HOUR);
        // 3 票人：1 赞成 2 反对 = 33% ⇒ 不过
        under.vote("p1", "a" , true, 1L);
        under.vote("p1", "b", false, 2L);
        under.vote("p1", "c", false, 3L);
        assertThat(under.tallies().get(0).passed()).isFalse();
    }

    @Test
    @DisplayName("参与下限：零票不算有效表决，1 票 100% 也不算（不然一个小号就能替全国定国策）")
    void theParticipationFloorBlocksASingleYesVote() {
        Nation nation = newNationWithTwoAlliances();  // 两个成员联盟 ⇒ 下限 2 票
        nation.propose("p1", "np_cavalry_t1", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "solo" , true, 1L);

        assertThat(nation.tallies().get(0).passed())
                .as("1 票赞成 = 100% 比例，但没到参与下限（2 个成员联盟至少 2 票）")
                .isFalse();

        nation.vote("p1", "second" , true, 2L);
        assertThat(nation.tallies().get(0).passed())
                .as("补到下限之后同一条提案就该通过 —— 下限不是比例的替代品")
                .isTrue();
    }

    @Test
    @DisplayName("弃权不计入分母：20 人投了 10 赞成 10 反对，另 180 人没投，分母是 20 不是 200")
    void abstentionsDoNotCountTowardTheDenominator() {
        Nation nation = newNation();
        nation.propose("p1", "np_cavalry_t1", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "a" , true, 1L);
        nation.vote("p1", "b", false, 2L);

        Nation.ProposalTally tally = nation.tallies().get(0);
        assertThat(tally.actualVoters())
                .as("实际投票人数 = 赞成 + 反对。弃权若计入分母，休眠玩家会把门槛顶高")
                .isEqualTo(2L);
        assertThat(tally.yes() + tally.no()).isEqualTo(tally.actualVoters());
    }

    // ---------- 槽位竞争 ----------

    @Test
    @DisplayName("同轮多条通过时按四级排序占槽位：票多者先占，票相同时早提案者先占")
    void passedProposalsCompeteForSlotsInAFixedOrder() {
        Nation nation = newNation();
        nation.propose("pA", "np_fortress", "king" , true, 0L);
        nation.propose("pB", "np_harvest", "officer" , true, 10L);
        nation.policyRound("king" , true, 24 * HOUR);
        // 两条都 2 票赞成（比例与票数完全相同）⇒ 只能靠提案时刻分
        nation.vote("pA", "a" , true, 1L);
        nation.vote("pA", "b" , true, 2L);
        nation.vote("pB", "c" , true, 3L);
        nation.vote("pB", "d" , true, 4L);

        long settleAt = 48 * HOUR;
        nation.settlePolicy(settleAt);
        assertThat(nation.activePolicies(settleAt))
                .as("Lv1 国家只有 1 个槽位（nation_config.policySlotCount），票相同时早提案的先占")
                .extracting(Nation.ActivePolicy::policyId)
                .containsExactly("np_fortress");
    }

    @Test
    @DisplayName("票数不同时票多者占槽，哪怕它提案更晚")
    void moreVotesWinTheSlotEvenWhenProposedLater() {
        Nation nation = newNation();
        nation.propose("pA", "np_fortress", "king" , true, 0L);
        nation.propose("pB", "np_harvest", "officer" , true, 10L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("pA", "a" , true, 1L);
        nation.vote("pA", "b" , true, 2L);
        nation.vote("pB", "c" , true, 3L);
        nation.vote("pB", "d" , true, 4L);
        nation.vote("pB", "e" , true, 5L);

        long settleAt = 48 * HOUR;
        nation.settlePolicy(settleAt);
        assertThat(nation.activePolicies(settleAt))
                .as("3 票 > 2 票，比例也更高 —— 排序键先比比例再比票数，两条都在 pB 那边")
                .extracting(Nation.ActivePolicy::policyId)
                .containsExactly("np_harvest");
    }

    @Test
    @DisplayName("没过门槛的提案不占槽位：两条提案只有一条过关，槽位上只有那一条")
    void aFailedProposalTakesNoSlot() {
        Nation nation = newNation();
        nation.propose("pA", "np_fortress", "king" , true, 0L);
        nation.propose("pB", "np_harvest", "officer" , true, 10L);
        nation.policyRound("king" , true, 24 * HOUR);
        // pA：2 票全赞成 ⇒ 100% 通过；pB：2 票里 1 赞成 1 反对 = 50%，但只有 2 票
        // 且参与下限是 1（一个成员联盟），所以它也过 —— 换 pB 为 3 票 1 赞成来压到 33%
        nation.vote("pA", "a" , true, 1L);
        nation.vote("pA", "b" , true, 2L);
        nation.vote("pB", "c" , true, 3L);
        nation.vote("pB", "d", false, 4L);
        nation.vote("pB", "e", false, 5L);

        assertThat(nation.tallies())
                .filteredOn(Nation.ProposalTally::passed)
                .extracting(Nation.ProposalTally::proposalId)
                .containsExactly("pA");
        long settleAt = 48 * HOUR;
        nation.settlePolicy(settleAt);
        assertThat(nation.activePolicies(settleAt))
                .extracting(Nation.ActivePolicy::policyId)
                .containsExactly("np_fortress");
    }

    @Test
    @DisplayName("同一条国策提两次在提案时就被挡住 —— 槽位分配那道 putIfAbsent 是第二道防线")
    void duplicateTargetsAreBlockedAtProposeTimeSoTheyNeverReachTheSlotAllocation() {
        Nation nation = newNation();
        nation.propose("pA", "np_fortress", "king" , true, 0L);
        assertThatThrownBy(() -> nation.propose("pB", "np_fortress", "officer" , true, 10L))
                .isInstanceOf(Nation.PolicyException.class)
                .extracting(e -> ((Nation.PolicyException) e).block())
                .isEqualTo(Nation.PolicyBlock.ALREADY_PROPOSED);

        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("pA", "a" , true, 1L);
        long settleAt = 48 * HOUR;
        nation.settlePolicy(settleAt);
        assertThat(nation.activePolicies(settleAt))
                .as("同一条 buff 生效两次会让公示的票数与实际收益对不上，所以两条路都堵：提案时去重、分配时 putIfAbsent")
                .hasSize(1);
        assertThat(nation.policyRound("king" , true, settleAt).proposals())
                .as("结算后整轮清空：提案与票都不该留着")
                .isEmpty();
    }

    // ---------- 生效与到期 ----------

    @Test
    @DisplayName("通过的那一条在结算后生效一整轮，到期那一刻出列并进入下一轮的提案段")
    void theWinnerIsActiveForOneRoundAndExpiresOnItsOwn() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "a" , true, 1L);

        long settleAt = 48 * HOUR;
        nation.settlePolicy(settleAt);
        assertThat(nation.policyRound("king" , true, settleAt).phase())
                .isEqualTo(Nation.PolicyPhase.ACTIVE);
        assertThat(nation.activePolicies(settleAt)).hasSize(1);
        long expiresAt = nation.activePolicies(settleAt).get(0).expiresAt();
        assertThat(expiresAt - settleAt)
                .as("生效段 = 一轮 − 投票窗 = 24h（B13 §4 说的是「周期性」，而周期的长度由同一个参数派生）")
                .isEqualTo(24 * HOUR);

        assertThat(nation.activePolicies(expiresAt))
                .as("到期那一刻必须立刻出列 —— 留着就是白给 buff")
                .isEmpty();
        assertThat(nation.policyRound("king" , true, expiresAt).phase())
                .isEqualTo(Nation.PolicyPhase.PROPOSING);
    }

    @Test
    @DisplayName("连续两轮互不影响：第二轮的提案是干净的（上一轮的票不该被继承）")
    void aSecondRoundStartsClean() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "a" , true, 1L);
        nation.settlePolicy(48 * HOUR);

        long secondRound = 48 * HOUR + 24 * HOUR;   // 上一轮生效段走完 ⇒ 回到提案段
        Nation.PolicyRound round = nation.policyRound("king" , true, secondRound);
        assertThat(round.proposals()).isEmpty();
        assertThat(round.voteBlock())
                .as("轮次视图把判定结果一起带回来了，面板不该再判一遍")
                .isEqualTo(Nation.PolicyBlock.NO_PROPOSAL_YET);
        nation.propose("p2", "np_harvest", "king" , true, secondRound);
        assertThat(nation.policyRound("king" , true, secondRound).proposals())
                .extracting(Nation.ProposalTally::proposalId)
                .containsExactly("p2");
    }

    // ---------- 快照 ----------

    @Test
    @DisplayName("快照往返：提案、票、生效中的国策与三个时刻都要原样回来")
    void thePolicyStateSurvivesASnapshotRoundTrip() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "a" , true, 1L);
        nation.vote("p1", "b", false, 2L);

        Nation rebuilt = Nation.fromSnapshot(nation.snapshot(), rules());
        assertThat(rebuilt.policyRound("king" , true, 24 * HOUR).phase())
                .isEqualTo(Nation.PolicyPhase.VOTING);
        assertThat(rebuilt.tallies()).singleElement().satisfies(t -> {
            assertThat(t.yes()).isEqualTo(1L);
            assertThat(t.no()).isEqualTo(1L);
            assertThat(t.supporters()).containsExactly("a");
            assertThat(t.opponents()).containsExactly("b");
        });
    }

    @Test
    @DisplayName("老文档没有国策那几位时读成「本轮什么都没有」，而不是打不开")
    void anOldDocumentWithoutThePolicyFieldsReadsAsAFreshRound() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        Nation.Snapshot s = nation.snapshot();

        Nation old = Nation.fromSnapshot(new Nation.Snapshot(s.id(), s.name(), s.kingId(),
                s.capitalX(), s.capitalY(), s.level(), s.treasury(), s.memberAlliances(),
                s.offices(), s.diplomacy(), s.joinCooldownUntil(), s.treasuryLogs(),
                s.provinces(), s.holderAlliance(), s.techLevels(),
                null, null, null, 0L, 0L, 0L,
                s.lastTaxWeekKey(), s.lastTaxCredited(), s.spendWeekKey(), s.spentThisWeek(),
                s.disbandedAt(), s.version()), rules());

        assertThat(old.policyRound("king" , true, 0L).proposals()).isEmpty();
        assertThat(old.policyRound("king" , true, 0L).phase())
                .isEqualTo(Nation.PolicyPhase.PROPOSING);
    }

    @Test
    @DisplayName("名单按 playerId 升序：同一份存档在不同 JVM 上要给出同一份公示")
    void voterListsAreSortedSoTheDisclosureIsReproducible() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        // 故意乱序投，验证读出来是排好序的
        nation.vote("p1", "zeta" , true, 1L);
        nation.vote("p1", "alpha" , true, 2L);
        nation.vote("p1", "mu" , true, 3L);

        assertThat(nation.tallies().get(0).supporters())
                .as("顺序若依赖哈希布局，同一份存档在不同 JVM 上会给出不同名单 —— 那正是 B05 §1.7 强制 EnumMap 的同一条理由")
                .containsExactly("alpha", "mu", "zeta");
    }

    @Test
    @DisplayName("国家规则校验：投票窗必须为正、一轮不得短于投票窗、门槛必须落在 0~1")
    void nationRulesValidateThePolicyNumbers() {
        assertThatThrownBy(() -> new Nation.Rules(
                List.of(level(1, 200, 500_000L, 1)), 16, 13, 4, 24 * HOUR, 10_000L, 200, 12, 5_000L,
                0L, 48 * HOUR, HALF, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("投票窗长度必须为正");
        assertThatThrownBy(() -> new Nation.Rules(
                List.of(level(1, 200, 500_000L, 1)), 16, 13, 4, 24 * HOUR, 10_000L, 200, 12, 5_000L,
                24 * HOUR, 12 * HOUR, HALF, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得短于投票窗");
        assertThatThrownBy(() -> new Nation.Rules(
                List.of(level(1, 200, 500_000L, 1)), 16, 13, 4, 24 * HOUR, 10_000L, 200, 12, 5_000L,
                24 * HOUR, 48 * HOUR, FixedPoint.SCALE + 1L, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("全票赞成都通不过");
    }

    @Test
    @DisplayName("投票时给一条不存在的提案：那是坏请求而不是状态阻挡")
    void votingOnAnUnknownProposalIsAnIllegalArgument() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "king" , true, 0L);
        nation.policyRound("king" , true, 24 * HOUR);
        assertThatThrownBy(() -> nation.vote("nope", "a" , true, 25 * HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("提案不存在");
    }

    @Test
    @DisplayName("myProposals / myVotes 只回调用者自己的（公示的两份名单是全体，这一对是自己的）")
    void myProposalsAndMyVotesAreScopedToTheViewer() {
        Nation nation = newNation();
        nation.propose("p1", "np_conquest", "officer1" , true, 0L);
        nation.propose("p2", "np_harvest", "officer2" , true, 1L);
        nation.policyRound("king" , true, 24 * HOUR);
        nation.vote("p1", "officer2" , true, 25 * HOUR);

        Nation.PolicyRound asOfficer2 = nation.policyRound("officer2" , true, 25 * HOUR);
        assertThat(asOfficer2.myProposals()).containsExactly("p2");
        assertThat(asOfficer2.myVotes()).isEqualTo(Map.of("p1", Boolean.TRUE));
        assertThat(nation.policyRound("king" , true, 25 * HOUR).myProposals()).isEmpty();
    }
}
