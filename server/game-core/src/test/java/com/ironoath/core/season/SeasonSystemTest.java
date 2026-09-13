package com.ironoath.core.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.DayKey;

/**
 * 职责：B14 赛季制核心规则的单测 —— 验收 2（结算幂等）、4（结算按快照）、
 * 6（段位降段保留进度）、8（阶段切换边界），以及 §3/§4 的排行榜与归档纪律。
 * 依赖：JUnit 5 + AssertJ + game-core 的 season 包（纯 Java，零框架）。
 *
 * <p><b>夹具用的时间轴是 B14 §一 文档给的 30 天版本</b>（备战 3 / 扩张 17 / 王城战 5 /
 * 结算 3 / 休赛 2），而不是 contract/config/season.json 里的 45 天版本。
 * 两者冲突，且冲突的不只是天数还有阶段划分，已记入待裁决清单 ——
 * 本类测的是「给定一张阶段表，机制是否正确」，所以用哪张表都不影响结论；
 * 而「哪张表是对的」是设计裁决，不是测试能回答的。
 */
class SeasonSystemTest {

    private static final long DAY = 86_400_000L;

    /** B14 §一 的 30 天时间轴：备战 1-3 / 扩张 4-20 / 王城战 21-25 / 结算 26-28 / 休赛 29-30。 */
    private static SeasonTimeline.Rules docTimeline() {
        return new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 3),
                new SeasonTimeline.Stage(2, SeasonTimeline.Phase.EXPAND, 3, 17),
                new SeasonTimeline.Stage(3, SeasonTimeline.Phase.CAPITAL_WAR, 20, 5),
                new SeasonTimeline.Stage(4, SeasonTimeline.Phase.SETTLE, 25, 3),
                new SeasonTimeline.Stage(5, SeasonTimeline.Phase.REST, 28, 2)),
                "s2026a");
    }

    private static SeasonTimeline timeline() {
        return new SeasonTimeline(docTimeline());
    }

    /** 六档段位门槛。 */
    private static SeasonTier.Rules tierRules(int demoteSteps, String progressKeep) {
        return new SeasonTier.Rules(
                new long[]{0L, 5_000L, 20_000L, 60_000L, 150_000L, 400_000L},
                demoteSteps, com.ironoath.common.num.FixedPoint.parse(progressKeep));
    }

    private static SeasonSettlement settlement(int rewardedTopN, long coinPerRank) {
        return new SeasonSettlement("s2026a", new SeasonSettlement.Rules(
                SeasonSettlement.Board.POWER, rewardedTopN, coinPerRank, 3));
    }

    // ---------- 验收 8：阶段切换边界 ----------

    @Test
    @DisplayName("赛季结束之后就是休赛：不必往表里补第六行，也不能开着写路径")
    void pastTheEndOfTheSeasonIsRest() {
        // 刻意用一张「没有 REST 行」的时间轴 —— 这就是定稿的 season 表形状（45 天五阶段）。
        // 补一行会把 45 天轴撑长，而时长已经裁决为以表为准
        SeasonTimeline.Rules noRest = new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 7),
                new SeasonTimeline.Stage(2, SeasonTimeline.Phase.EXPAND, 7, 7),
                new SeasonTimeline.Stage(3, SeasonTimeline.Phase.EXPAND, 14, 14),
                new SeasonTimeline.Stage(4, SeasonTimeline.Phase.CAPITAL_WAR, 28, 14),
                new SeasonTimeline.Stage(5, SeasonTimeline.Phase.SETTLE, 42, 3)), "s2026b");
        SeasonTimeline timeline = new SeasonTimeline(noRest);
        long start = 100_000L * DAY;

        assertThat(noRest.totalDays()).isEqualTo(45L);
        assertThat(timeline.stageAt(45)).as("越界仍返回 null：调用方要分得清「赛季内第几天」与「无赛季可依」")
                .isNull();
        assertThat(timeline.phaseAt(44)).isEqualTo(SeasonTimeline.Phase.SETTLE);
        assertThat(timeline.phaseAt(45)).as("第 46 天起就是休赛").isEqualTo(SeasonTimeline.Phase.REST);
        assertThat(timeline.phaseAtTime(start + 45 * DAY, start)).isEqualTo(SeasonTimeline.Phase.REST);
        assertThat(timeline.readOnly(start + 45 * DAY, start))
                .as("修前的 bug：stage 为 null 时 readOnly 返回 false，等于赛季一结束就重新开放写路径")
                .isTrue();
        assertThat(timeline.allowsPvp(start + 45 * DAY, start)).as("休赛期不打仗").isFalse();
        assertThat(timeline.phaseEndAt(start + 45 * DAY, start))
                .as("越界时倒计时指向赛季终点，而不是给 UI 一个凭空的外推时刻")
                .isEqualTo(DayKey.startOfDayPlusDays(start, 45));
    }

    @Test
    @DisplayName("验收8：第 3 天 23:59:59 属于备战期，第 4 天 00:00:00 属于扩张期")
    void phaseBoundaryIsExact() {
        SeasonTimeline timeline = timeline();
        long start = java.time.Instant.parse("2026-09-13T15:30:00Z").toEpochMilli();

        // 第 3 天（dayIndex=2）的最后一毫秒
        long lastOfThirdDay = java.time.Instant.parse("2026-09-15T15:59:59.999Z").toEpochMilli();
        assertThat(SeasonTimeline.dayIndexOf(lastOfThirdDay, start)).isEqualTo(2);
        assertThat(timeline.stageAtTime(lastOfThirdDay, start).phase())
                .isEqualTo(SeasonTimeline.Phase.PREPARE);

        // 第 4 天（dayIndex=3）的第一毫秒
        long firstOfFourthDay = java.time.Instant.parse("2026-09-15T16:00:00Z").toEpochMilli();
        assertThat(SeasonTimeline.dayIndexOf(firstOfFourthDay, start)).isEqualTo(3);
        assertThat(timeline.stageAtTime(firstOfFourthDay, start).phase())
                .as("左闭右开：第 4 天 00:00:00 就已经是扩张期").isEqualTo(SeasonTimeline.Phase.EXPAND);
    }

    @Test
    @DisplayName("赛季天数按 UTC+8 自然日推进：开赛当晚仍是第 0 天，零点进入第 1 天")
    void dayIndexFollowsCalendarMidnight() {
        long start = java.time.Instant.parse("2026-09-13T15:30:00Z").toEpochMilli();
        assertThat(SeasonTimeline.dayIndexOf(start, start)).isZero();
        assertThat(SeasonTimeline.dayIndexOf(
                java.time.Instant.parse("2026-09-13T15:59:59.999Z").toEpochMilli(), start))
                .as("开赛日 23:59:59 仍是第 0 天").isZero();
        assertThat(SeasonTimeline.dayIndexOf(
                java.time.Instant.parse("2026-09-13T16:00:00Z").toEpochMilli(), start))
                .as("北京时间零点后立刻进入第 1 天，不按开赛满 24 小时算").isEqualTo(1L);
    }

    @Test
    @DisplayName("五个阶段的语义各自成立：备战禁战、扩张开 PVP、王城战开王城、休赛只读")
    void phaseSemanticsAreEnforcedInData() {
        SeasonTimeline timeline = timeline();
        long start = 0L;

        assertThat(timeline.allowsPvp(DAY, start)).as("备战期禁战").isFalse();
        assertThat(timeline.allowsPvp(10 * DAY, start)).as("扩张期开放 PVP").isTrue();
        assertThat(timeline.allowsPvp(22 * DAY, start)).as("王城战也是 PVP").isTrue();
        assertThat(timeline.allowsPvp(26 * DAY, start)).as("结算期不开新战端").isFalse();
        assertThat(timeline.allowsPvp(29 * DAY, start)).as("休赛期不开新战端").isFalse();

        assertThat(timeline.allowsCapitalWar(10 * DAY, start)).isFalse();
        assertThat(timeline.allowsCapitalWar(22 * DAY, start)).as("只有王城战阶段开王城").isTrue();
        assertThat(timeline.readOnly(29 * DAY, start)).as("休赛期只读").isTrue();
        assertThat(timeline.readOnly(22 * DAY, start)).isFalse();
    }

    @Test
    @DisplayName("阶段倒计时给的是本阶段结束时刻，赛季结束后给赛季总结束时刻")
    void phaseEndAtIsComputable() {
        SeasonTimeline timeline = timeline();
        long start = java.time.Instant.parse("2026-09-13T15:30:00Z").toEpochMilli();
        assertThat(timeline.phaseEndAt(start + DAY, start)).as("备战期在第 3 天末结束")
                .isEqualTo(DayKey.startOfDayPlusDays(start, 3));
        assertThat(timeline.phaseEndAt(start + 40 * DAY, start))
                .as("超出赛季总天数时给赛季结束时刻")
                .isEqualTo(DayKey.startOfDayPlusDays(start, 30));
        assertThat(timeline.stageAt(40)).as("赛季已结束").isNull();
        assertThat(timeline.totalDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("阶段表有空洞或重叠时在构造期就炸：那会让「第 N 天属于哪个阶段」没有唯一答案")
    void timelineRejectsGapsAndOverlaps() {
        assertThatThrownBy(() -> new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 3),
                new SeasonTimeline.Stage(2, SeasonTimeline.Phase.EXPAND, 5, 10)), "s1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("空洞");
        assertThatThrownBy(() -> new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 5),
                new SeasonTimeline.Stage(2, SeasonTimeline.Phase.EXPAND, 3, 10)), "s1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重叠");
        assertThatThrownBy(() -> new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 2, 3)), "s1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须从第 0 天开始");
        assertThatThrownBy(() -> new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 3),
                new SeasonTimeline.Stage(3, SeasonTimeline.Phase.EXPAND, 3, 3)), "s1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("从 1 起连续");
        assertThatThrownBy(() -> new SeasonTimeline.Rules(List.of(
                new SeasonTimeline.Stage(1, SeasonTimeline.Phase.PREPARE, 0, 0)), "s1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("持续 0 天的阶段永远不会被命中");
    }

    // ---------- 验收 6：段位 ----------

    @Test
    @DisplayName("段位按 matchPower 划档，并给出距下一段还差多少")
    void tierPlacementFollowsMatchPower() {
        SeasonTier tiers = new SeasonTier(tierRules(1, "0.50"));
        assertThat(tiers.place(0).tier()).isEqualTo(SeasonTier.Tier.BRONZE);
        assertThat(tiers.place(4_999).tier()).isEqualTo(SeasonTier.Tier.BRONZE);
        assertThat(tiers.place(5_000).tier()).isEqualTo(SeasonTier.Tier.SILVER);
        assertThat(tiers.place(400_000).tier()).isEqualTo(SeasonTier.Tier.KING);
        assertThat(tiers.place(9_000_000).tier()).as("超过最高门槛仍是王者").isEqualTo(SeasonTier.Tier.KING);

        SeasonTier.Placement gold = tiers.place(25_000);
        assertThat(gold.tier()).isEqualTo(SeasonTier.Tier.GOLD);
        assertThat(gold.progressInTier()).as("25000 - 20000").isEqualTo(5_000L);
        assertThat(gold.powerToNext()).as("距铂金还差 60000 - 25000").isEqualTo(35_000L);
        assertThat(tiers.place(500_000).powerToNext()).as("已是最高段位").isZero();
        assertThat(SeasonTier.Tier.KING.displayName()).isEqualTo("王者");
    }

    @Test
    @DisplayName("验收6：赛季结束降 1 段，且保留一半段内进度（不清零也不全留）")
    void tierDemotionKeepsPartialProgress() {
        SeasonTier tiers = new SeasonTier(tierRules(1, "0.50"));
        // 门槛是 0/5k/20k/60k/150k/400k，所以 200000 落在钻石（150k~400k）
        SeasonTier.Placement diamond = tiers.place(200_000);
        assertThat(diamond.tier()).isEqualTo(SeasonTier.Tier.DIAMOND);
        assertThat(diamond.progressInTier()).as("200000 - 150000").isEqualTo(50_000L);

        SeasonTier.Placement next = tiers.demote(diamond);
        assertThat(next.tier()).as("降 1 段").isEqualTo(SeasonTier.Tier.PLATINUM);
        assertThat(next.progressInTier())
                .as("50000 × 0.5 = 25000，且不超过铂金段跨度 90000")
                .isEqualTo(25_000L);

        // 清零会让玩家觉得一个月的努力被抹掉，全留又等于没降段
        SeasonTier wipe = new SeasonTier(tierRules(1, "0.0"));
        assertThat(wipe.demote(diamond).progressInTier()).isZero();
        SeasonTier keep = new SeasonTier(tierRules(1, "1.0"));
        assertThat(keep.demote(diamond).progressInTier())
                .as("全保留也不能超过目标段位的跨度，否则等于升段")
                .isEqualTo(50_000L);
    }

    @Test
    @DisplayName("降 2 段、青铜不再降、王者降段后仍在榜内")
    void demotionHandlesEdges() {
        SeasonTier two = new SeasonTier(tierRules(2, "0.50"));
        assertThat(two.demote(two.place(400_000)).tier())
                .as("王者降 2 段：KING → DIAMOND → PLATINUM")
                .isEqualTo(SeasonTier.Tier.PLATINUM);

        SeasonTier one = new SeasonTier(tierRules(1, "0.50"));
        assertThat(one.demote(one.place(100)).tier()).as("青铜不能再降")
                .isEqualTo(SeasonTier.Tier.BRONZE);
        // 已是最低段位时不再降，但段内进度仍按比例保留（100 × 0.5 = 50）：
        // 青铜玩家也打了一整个赛季，把他的进度清零与「保留部分进度以降低挫败感」的设计意图相反
        assertThat(one.demote(one.place(100)).progressInTier()).isEqualTo(50L);
    }

    @Test
    @DisplayName("段位门槛必须严格递增，最低档必须为 0，降段数不得超过段位总数")
    void tierRulesAreValidated() {
        assertThatThrownBy(() -> new SeasonTier.Rules(new long[]{0, 5000, 5000, 60000, 150000, 400000}, 1, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("严格递增");
        assertThatThrownBy(() -> new SeasonTier.Rules(new long[]{100, 5000, 20000, 60000, 150000, 400000}, 1, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("最低段位的门槛必须为 0");
        assertThatThrownBy(() -> new SeasonTier.Rules(new long[]{0, 5000, 20000, 60000, 150000}, 1, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须有 6 个");
        assertThatThrownBy(() -> new SeasonTier.Rules(
                new long[]{0, 5000, 20000, 60000, 150000, 400000}, 6, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("降那么多等于把所有人清零");
        assertThatThrownBy(() -> new SeasonTier.Rules(
                new long[]{0, 5000, 20000, 60000, 150000, 400000}, -1, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("升段不是赛季结束该做的事");
    }

    // ---------- 验收 4：结算按快照 ----------

    @Test
    @DisplayName("验收4：最后一秒刷实时榜不影响结算 —— 结算取的是快照")
    void settlementUsesSnapshotNotLiveBoard() {
        SeasonSettlement settlement = settlement(3, 1000L);
        settlement.updateLive(SeasonSettlement.Board.POWER, "steady", "从头打到尾", 100_000);
        settlement.updateLive(SeasonSettlement.Board.POWER, "second", "第二名", 80_000);
        settlement.updateLive(SeasonSettlement.Board.POWER, "third", "第三名", 60_000);
        long snapshotAt = 1_000_000L;
        settlement.takeSnapshot(SeasonSettlement.Board.POWER, snapshotAt);

        // 快照之后有人攒了一整赛季的资源在最后一刻全部换成战力
        settlement.updateLive(SeasonSettlement.Board.POWER, "cheater", "最后一秒偷榜", 9_999_999);
        assertThat(settlement.liveRank(SeasonSettlement.Board.POWER, "cheater"))
                .as("实时榜上他确实是第一").isEqualTo(1);
        assertThat(settlement.snapshot(SeasonSettlement.Board.POWER).rankOf("cheater"))
                .as("快照榜上没有他").isZero();

        List<SeasonSettlement.Award> awards = settlement.settlePage(
                List.of("steady", "cheater"), playerId -> SeasonTier.Tier.GOLD);
        assertThat(awards.get(0).rank()).as("结算按快照，从头打到尾的人是第 1").isEqualTo(1);
        assertThat(awards.get(0).seasonCoin()).isEqualTo(3000L);
        assertThat(awards.get(1).rank()).as("刷分的人拿不到名次奖励").isZero();
        assertThat(awards.get(1).seasonCoin()).isZero();
    }

    @Test
    @DisplayName("快照没拍就结算直接拒绝：那是禁止项「不要让结算按实时榜」")
    void settlementRefusesWithoutSnapshot() {
        SeasonSettlement settlement = settlement(3, 1000L);
        settlement.updateLive(SeasonSettlement.Board.POWER, "p1", "p1", 100);
        assertThat(settlement.snapshotReady()).isFalse();
        assertThatThrownBy(() -> settlement.settlePage(List.of("p1"), id -> SeasonTier.Tier.BRONZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须先拍下");
    }

    @Test
    @DisplayName("快照不可重拍：可以重拍的快照无法作为申诉依据")
    void snapshotCannotBeRetaken() {
        SeasonSettlement settlement = settlement(3, 1000L);
        settlement.takeSnapshot(SeasonSettlement.Board.POWER, 1000L);
        assertThatThrownBy(() -> settlement.takeSnapshot(SeasonSettlement.Board.POWER, 2000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经拍过");
        assertThat(settlement.snapshot(SeasonSettlement.Board.POWER).snapshotAt())
                .as("原快照保持不变").isEqualTo(1000L);
    }

    @Test
    @DisplayName("从存储恢复快照：恢复后结算就按它算，而且不能再恢复也不能重拍（一季一榜一个依据）")
    void restoredSnapshotIsTheSettlementBasis() {
        // 这是「重启后可重放」的另一半：快照落库了，进程重来时要把库里那份恢复回来，
        // 否则本类会认为「没拍过」而重拍一张 —— 同一个赛季于是有了两个结算依据
        SeasonSettlement fresh = settlement(3, 1000L);
        fresh.updateLive(SeasonSettlement.Board.POWER, "p1", "甲", 100L);
        SeasonSettlement.Snapshot stored = new SeasonSettlement.Snapshot(
                SeasonSettlement.Board.POWER, 1234L,
                java.util.List.of(new SeasonSettlement.Entry("p1", "甲", 100L),
                        new SeasonSettlement.Entry("p2", "乙", 50L)));

        assertThat(fresh.restoreSnapshot(stored).snapshotAt()).isEqualTo(1234L);
        assertThat(fresh.snapshotReady()).as("恢复之后就算已就绪").isTrue();
        assertThat(fresh.settlePage(java.util.List.of("p2"), id -> SeasonTier.Tier.BRONZE).get(0).rank())
                .as("结算名次必须来自恢复进来的那份（乙在快照里是第 2 名，而实时榜里根本没有他）")
                .isEqualTo(2);

        assertThatThrownBy(() -> fresh.restoreSnapshot(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能再从存储恢复");
        assertThatThrownBy(() -> fresh.takeSnapshot(SeasonSettlement.Board.POWER, 9999L))
                .as("恢复过就不能重拍：库里的快照已经把那一刻冻住了")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经拍过");
    }

    @Test
    @DisplayName("实时榜按分数降序、同分按 id 稳定排序；分数只增不减")
    void liveBoardIsSortedAndStable() {
        SeasonSettlement settlement = settlement(3, 1000L);
        settlement.updateLive(SeasonSettlement.Board.POWER, "b", "b", 50);
        settlement.updateLive(SeasonSettlement.Board.POWER, "a", "a", 50);
        settlement.updateLive(SeasonSettlement.Board.POWER, "c", "c", 90);
        assertThat(settlement.liveBoard(SeasonSettlement.Board.POWER))
                .extracting(SeasonSettlement.Entry::id).containsExactly("c", "a", "b");
        // 更新同一个人不会让他出现两次
        settlement.updateLive(SeasonSettlement.Board.POWER, "a", "a", 200);
        assertThat(settlement.liveBoard(SeasonSettlement.Board.POWER))
                .extracting(SeasonSettlement.Entry::id).containsExactly("a", "c", "b");
        assertThat(settlement.liveRank(SeasonSettlement.Board.POWER, "a")).isEqualTo(1);
        assertThat(settlement.liveRank(SeasonSettlement.Board.POWER, "nobody")).isZero();
    }

    // ---------- 验收 2：结算幂等 ----------

    @Test
    @DisplayName("验收2：重复触发结算 3 次，奖励只发一次（幂等键 = seasonId + playerId）")
    void settlementIsIdempotent() {
        SeasonSettlement settlement = settlement(3, 1000L);
        settlement.updateLive(SeasonSettlement.Board.POWER, "p1", "p1", 300);
        settlement.updateLive(SeasonSettlement.Board.POWER, "p2", "p2", 200);
        settlement.takeSnapshot(SeasonSettlement.Board.POWER, 1000L);

        List<SeasonSettlement.Award> first = settlement.settlePage(
                List.of("p1", "p2"), id -> SeasonTier.Tier.GOLD);
        assertThat(first).allMatch(SeasonSettlement.Award::firstTime);
        assertThat(first.get(0).seasonCoin())
                .as("第 1 名 = coinPerRank × (rewardedTopN - 1 + 1) = 1000 × 3")
                .isEqualTo(3000L);
        assertThat(settlement.settledCount()).isEqualTo(2);
        long rewardsAfterFirst = settlement.distributedRewards();

        for (int round = 0; round < 2; round++) {
            List<SeasonSettlement.Award> replay = settlement.settlePage(
                    List.of("p1", "p2"), id -> SeasonTier.Tier.KING);
            assertThat(replay).as("第 %d 次重跑", round + 2).allMatch(award -> !award.firstTime());
            assertThat(replay.get(0).seasonCoin()).as("重跑不重新计算，结果与第一次一致")
                    .isEqualTo(first.get(0).seasonCoin());
            // 段位也不重算：重新计算的话，若期间实时榜变了，同一个玩家两次结算会得到不同结果
            assertThat(replay.get(0).tier()).isEqualTo(SeasonTier.Tier.GOLD);
        }
        assertThat(settlement.settledCount()).as("重跑不增加已结算人数").isEqualTo(2);
        assertThat(settlement.distributedRewards()).as("重跑不多发奖励").isEqualTo(rewardsAfterFirst);
    }

    @Test
    @DisplayName("分页结算：一页一页处理，任何一页都不需要载入全部玩家（禁止全表扫描）")
    void settlementIsPaginated() {
        SeasonSettlement settlement = settlement(5, 100L);
        for (int i = 0; i < 100; i++) {
            settlement.updateLive(SeasonSettlement.Board.POWER, "p" + i, "p" + i, 1000L - i);
        }
        settlement.takeSnapshot(SeasonSettlement.Board.POWER, 1000L);

        for (int page = 0; page < 10; page++) {
            List<String> ids = new java.util.ArrayList<>();
            for (int i = page * 10; i < page * 10 + 10; i++) {
                ids.add("p" + i);
            }
            List<SeasonSettlement.Award> awards = settlement.settlePage(ids, id -> SeasonTier.Tier.SILVER);
            assertThat(awards).hasSize(10);
        }
        assertThat(settlement.settledCount()).isEqualTo(100);
        // 前 5 名有奖，第 1 名拿 5×100，第 5 名拿 1×100
        SeasonSettlement replayed = settlement;
        assertThat(replayed.settlePage(List.of("p0"), id -> SeasonTier.Tier.SILVER).get(0).seasonCoin())
                .isEqualTo(500L);
        assertThat(replayed.settlePage(List.of("p4"), id -> SeasonTier.Tier.SILVER).get(0).seasonCoin())
                .isEqualTo(100L);
        assertThat(replayed.settlePage(List.of("p5"), id -> SeasonTier.Tier.SILVER).get(0).seasonCoin())
                .as("第 6 名超出 rewardedTopN").isZero();
    }

    // ---------- 验收 3/7：数据隔离与归档 ----------

    @Test
    @DisplayName("验收3：赛季数据落在 season_<id> 集合，玩家主存档只保留荣耀三项")
    void seasonDataIsIsolatedFromPlayerSave() {
        SeasonSettlement settlement = settlement(3, 1000L);
        assertThat(settlement.archiveCollection()).isEqualTo("season_s2026a");
        assertThat(settlement.seasonId()).isEqualTo("s2026a");

        com.ironoath.core.player.PlayerGlory glory = new com.ironoath.core.player.PlayerGlory(
                7, SeasonTier.Tier.DIAMOND, List.of("badge_s2026a_king_slayer"));
        assertThat(glory.gloryLevel()).isEqualTo(7);
        assertThat(glory.highestTier()).isEqualTo(SeasonTier.Tier.DIAMOND);
        assertThat(glory.badges()).containsExactly("badge_s2026a_king_slayer");
        assertThatThrownBy(() -> new com.ironoath.core.player.PlayerGlory(-1, SeasonTier.Tier.GOLD, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gloryLevel");
        assertThatThrownBy(() -> new com.ironoath.core.player.PlayerGlory(1, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("highestTier");
        // 读路径靠 isBlank 决定"这份缓存还没长出来"，所以它必须能区分空与有
        assertThat(com.ironoath.core.player.PlayerGlory.empty().isBlank()).isTrue();
        assertThat(glory.isBlank()).as("结算过一次就不算空白，哪怕等级只有 1").isFalse();
    }

    @Test
    @DisplayName("验收7：归档保留 3 个赛季，超出时返回可清理的最旧赛季（申诉需要历史数据）")
    void archiveKeepsConfiguredRetention() {
        SeasonSettlement settlement = settlement(3, 1000L);
        assertThat(settlement.archive("s1")).isEmpty();
        assertThat(settlement.archive("s2")).isEmpty();
        assertThat(settlement.archive("s3")).isEmpty();
        assertThat(settlement.archivedSeasons()).containsExactly("s1", "s2", "s3");

        assertThat(settlement.archive("s4")).as("超出保留 3 个，最旧的 s1 可清理").containsExactly("s1");
        assertThat(settlement.archive("s5")).containsExactly("s1", "s2");
        assertThatThrownBy(() -> settlement.archive(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为空");
        assertThatThrownBy(() -> new SeasonSettlement("s", new SeasonSettlement.Rules(
                SeasonSettlement.Board.POWER, 3, 100, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归档保留 0 个赛季等于没有归档");
    }

    @Test
    @DisplayName("结算规则构造期校验：依据榜、奖励名次数、归档保留数都不能缺")
    void settlementRulesAreValidated() {
        assertThatThrownBy(() -> new SeasonSettlement.Rules(null, 3, 100, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("snapshotBoard");
        assertThatThrownBy(() -> new SeasonSettlement.Rules(SeasonSettlement.Board.POWER, 0, 100, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没人有奖");
        assertThatThrownBy(() -> new SeasonSettlement.Rules(SeasonSettlement.Board.POWER, 3, -1, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coinPerRank");
        assertThatThrownBy(() -> new SeasonSettlement(" ", new SeasonSettlement.Rules(
                SeasonSettlement.Board.POWER, 3, 100, 3)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seasonId");
    }

    @Test
    @DisplayName("榜单条目的 id 不得为空、分数不得为负：一条脏数据会让整份榜单的名次都错")
    void entriesAreValidated() {
        assertThatThrownBy(() -> new SeasonSettlement.Entry(" ", "x", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id 不得为空");
        assertThatThrownBy(() -> new SeasonSettlement.Entry("a", "x", -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分数不得为负");
    }
}
