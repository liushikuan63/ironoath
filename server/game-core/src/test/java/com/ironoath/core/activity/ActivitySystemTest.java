package com.ironoath.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.quest.GoalType;

/**
 * 职责：活动核心（窗口 / 进度 / 连续签到 / 领取判定）的单测 —— B17 验收 3~7 的机器判据。
 * 依赖：game-core 自身。<b>零框架、零容器、零数据库</b>（B00 铁律 2）。
 *
 * <p>每个用例都对着 B17 的一条验收；判据能失败这件事本身也钉在这里 ——
 * 例如把「同日重复登录只算一天」改成"每次登录 +1"，第 4 个用例当场变红。
 */
class ActivitySystemTest {

    /** 开服时刻：2026-09-01 00:00（UTC+8 的自然日零点，让日切的断言好读）。 */
    private static final long OPEN = at("2026-09-01");
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static long at(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(DayKey.CALENDAR_ZONE).toInstant().toEpochMilli();
    }

    private static List<ActivityProgress.Def> defs() {
        return List.of(
                new ActivityProgress.Def("activity_login_7d", ActivityType.LOGIN_STREAK,
                        ActivityCondition.LOGIN_DAYS, 7L, 7L),
                new ActivityProgress.Def("activity_login_30d", ActivityType.LOGIN_STREAK,
                        ActivityCondition.LOGIN_DAYS, 30L, 30L),
                new ActivityProgress.Def("activity_monster_hunt", ActivityType.KILL_MONSTER,
                        ActivityCondition.KILL_MONSTER_TOTAL, 50L, 3L),
                new ActivityProgress.Def("activity_rally_week", ActivityType.JOIN_RALLY,
                        ActivityCondition.JOIN_RALLY_TOTAL, 5L, 7L),
                new ActivityProgress.Def("activity_donate_week", ActivityType.DONATE,
                        ActivityCondition.DONATE_TOTAL, 2000L, 7L),
                new ActivityProgress.Def("activity_pvp_win", ActivityType.PVP_WIN,
                        ActivityCondition.PVP_WIN_TOTAL, 10L, 3L),
                new ActivityProgress.Def("activity_build_sprint", ActivityType.UPGRADE_BUILDING,
                        ActivityCondition.UPGRADE_COUNT, 10L, 5L),
                new ActivityProgress.Def("activity_squad_help", ActivityType.HELP_SQUAD,
                        ActivityCondition.HELP_COUNT, 30L, 7L));
    }

    private static ActivityProgress opened() {
        return ActivityProgress.open("P1", defs(), OPEN);
    }

    // ---------- 窗口 ----------

    @Test
    @DisplayName("窗口按锚点一轮一轮轮换：第 n 轮 = [锚 + n×时长, 锚 + (n+1)×时长)，一轮结束自动开下一轮")
    void windowRounds() {
        // 剿匪令 3 天一轮
        ActivityWindow.Span round0 = ActivityWindow.current(OPEN, 3L, OPEN);
        assertThat(round0.startAt()).isEqualTo(OPEN);
        assertThat(round0.endAt()).isEqualTo(OPEN + 3 * DAY_MS);
        // 第 2 天的 23:59 仍在这一轮
        assertThat(ActivityWindow.current(OPEN, 3L, OPEN + 3 * DAY_MS - 1).startAt()).isEqualTo(OPEN);
        // 恰好到终点：新的一轮已经开始了（半开区间，边界归下一轮 —— 否则终点那一刻会同时属于两轮）
        ActivityWindow.Span round1 = ActivityWindow.current(OPEN, 3L, OPEN + 3 * DAY_MS);
        assertThat(round1.startAt()).isEqualTo(OPEN + 3 * DAY_MS);
        assertThat(round1.endAt()).isEqualTo(OPEN + 6 * DAY_MS);
        // 第 7 轮（第 21 天）
        ActivityWindow.Span round7 = ActivityWindow.current(OPEN, 3L, OPEN + 21 * DAY_MS + 5);
        assertThat(round7.startAt()).isEqualTo(OPEN + 21 * DAY_MS);
    }

    @Test
    @DisplayName("时长 ≤ 0 的活动没有窗口终点（常驻），不编一个天文数字当结束时刻")
    void constantActivityHasNoEnd() {
        ActivityWindow.Span span = ActivityWindow.current(OPEN, 0L, OPEN + 999 * DAY_MS);
        assertThat(span.endAt()).isNull();
        assertThat(span.expired(OPEN + 999 * DAY_MS)).isFalse();
    }

    @Test
    @DisplayName("锚点在将来时按第 0 轮处理而不是抛错：那一刻活动确实还没开始")
    void futureAnchorIsRoundZero() {
        long future = OPEN + 10 * DAY_MS;
        ActivityWindow.Span span = ActivityWindow.current(future, 7L, OPEN);
        assertThat(span.startAt()).isEqualTo(future);
        assertThat(span.endAt()).isEqualTo(future + 7 * DAY_MS);
    }

    @Test
    @DisplayName("LOGIN_STREAK 两行用玩家首登做锚，其余六行用开服时刻（B17 §五① 的双锚点）")
    void loginStreakIsPlayerAnchored() {
        ActivityProgress progress = opened();
        long firstLogin = OPEN + 4 * DAY_MS + 3 * 60 * 60 * 1000L;

        assertThat(progress.anchorOf("activity_login_7d", firstLogin)).isEqualTo(firstLogin);
        assertThat(progress.anchorOf("activity_monster_hunt", firstLogin)).isEqualTo(OPEN);

        // 开服第 5 天才进来的新号：他的第七天在 firstLogin + 7 天之后，而不是开服第 7 天
        long now = firstLogin + 6 * DAY_MS;
        assertThat(progress.windowOf("activity_login_7d", now, firstLogin).endAt())
                .isEqualTo(firstLogin + 7 * DAY_MS);
        // 剿匪令 3 天一轮：now 距开服 10 天 3 小时 ⇒ 第 3 轮（起点 = 开服 + 9 天）
        assertThat(progress.windowOf("activity_monster_hunt", now, firstLogin).startAt())
                .isEqualTo(OPEN + 9 * DAY_MS);
    }

    @Test
    @DisplayName("拿不到玩家首登时刻时退化为开服锚（不抛错，但也不是静默的：anchorOf 会体现出来）")
    void missingPlayerAnchorFallsBackToOpen() {
        ActivityProgress progress = opened();
        assertThat(progress.anchorOf("activity_login_7d", 0L)).isEqualTo(OPEN);
    }

    // ---------- 连续签到 ----------

    @Test
    @DisplayName("同一自然日重复登录只算一天（幂等）")
    void sameDayLoginCountsOnce() {
        ActivityProgress progress = opened();
        long d1Morning = at("2026-09-10");
        progress.rollover(d1Morning, d1Morning);
        progress.onEvent(login(d1Morning));
        // 先连到第 2 天，把进度做到 2 —— 进度停在 1 时，"不重算"与"归零重数成 1"是分不开的
        progress.onEvent(login(d1Morning + DAY_MS));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(2L);

        // 第 2 天里再登录两次（含深夜 23:59，仍是同一个自然日）：进度必须还是 2
        progress.onEvent(login(d1Morning + DAY_MS + 6 * 60 * 60 * 1000L));
        progress.onEvent(login(d1Morning + 2 * DAY_MS - 1));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(2L);
    }

    @Test
    @DisplayName("连续登录：隔天 +1，断一天归零重数（B17 §五② 的连续语义）")
    void streakIncrementsAndResets() {
        ActivityProgress progress = opened();
        long d1 = at("2026-09-10");
        progress.rollover(d1, d1);
        progress.onEvent(login(d1));
        progress.onEvent(login(d1 + DAY_MS));
        progress.onEvent(login(d1 + 2 * DAY_MS));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(3L);

        // 第四天没登录，第五天回来：从 1 重新数
        progress.onEvent(login(d1 + 4 * DAY_MS));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(1L);

        // 再连上一天 → 2（断签之后重新累计）
        progress.onEvent(login(d1 + 5 * DAY_MS));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(2L);
    }

    @Test
    @DisplayName("连续七天正好到第 7 天给奖：进度 7 即达标（验收 5）")
    void sevenConsecutiveDaysReachGoal() {
        ActivityProgress progress = opened();
        long d1 = at("2026-09-10");
        for (int i = 0; i < 7; i++) {
            long now = d1 + i * DAY_MS;
            progress.rollover(now, d1);
            progress.onEvent(login(now));
        }
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(7L);
        assertThat(progress.blockOf("activity_login_7d", d1 + 6 * DAY_MS, d1))
                .isEqualTo(ActivityProgress.ClaimBlock.NONE);
    }

    @Test
    @DisplayName("跨窗口清掉连续计数：不能靠跨轮凑够七天")
    void streakDoesNotSpanWindows() {
        ActivityProgress progress = opened();
        long firstLogin = at("2026-09-10");
        // 在第 0 轮连了 6 天（第 7 天落在下一轮里）
        for (int i = 0; i < 6; i++) {
            long now = firstLogin + i * DAY_MS;
            progress.rollover(now, firstLogin);
            progress.onEvent(login(now));
        }
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(6L);

        long nextWindowDay = firstLogin + 7 * DAY_MS;
        progress.rollover(nextWindowDay, firstLogin);
        // 窗口换了：上一轮的连续天数不带进新一轮
        assertThat(progress.entry("activity_login_7d").value()).isZero();
        progress.onEvent(login(nextWindowDay));
        assertThat(progress.entry("activity_login_7d").value()).isEqualTo(1L);

        // 新一轮里只连了 1 天：未达标 ⇒ RUNNING（NOT_REACHED 那一档），而不是 EXPIRED
        assertThat(progress.phaseOf("activity_login_7d", nextWindowDay, firstLogin))
                .isEqualTo(ActivityProgress.Phase.RUNNING);
    }

    @Test
    @DisplayName("上一轮有进展但没领的行：读取后标 EXPIRED、记录还在、不可领，直到新一轮有进展才被覆盖")
    void pendingRowStaysExpiredUntilNewProgress() {
        ActivityProgress progress = opened();
        long inFirstWindow = OPEN + DAY_MS;
        progress.rollover(inFirstWindow, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 30L, inFirstWindow));

        // 第 4 天：剿匪令已经换到第 2 轮（[开服+3天, 开服+6天)）
        long later = OPEN + 4 * DAY_MS;
        progress.syncOnRead(later, 0L);
        assertThat(progress.phaseOf("activity_monster_hunt", later, 0L))
                .isEqualTo(ActivityProgress.Phase.EXPIRED);
        assertThat(progress.blockOf("activity_monster_hunt", later, 0L))
                .isEqualTo(ActivityProgress.ClaimBlock.EXPIRED);
        assertThatThrownBy(() -> progress.claim("activity_monster_hunt", later, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXPIRED");
        // 记录不删：上一轮的 30 还在（这就是"审计与断点续算都要它"）
        assertThat(progress.entry("activity_monster_hunt").value()).isEqualTo(30L);
        // 也不进红点：不可领的行不该让角标亮
        assertThat(progress.claimableCount(later, 0L)).isZero();

        // 新一轮里打了一只怪：事件路径把它推进到当前窗口，进度从 1 重新长
        progress.rollover(later, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 1L, later));
        assertThat(progress.entry("activity_monster_hunt").windowStart()).isEqualTo(OPEN + 3 * DAY_MS);
        assertThat(progress.entry("activity_monster_hunt").value()).isEqualTo(1L);
        assertThat(progress.phaseOf("activity_monster_hunt", later, 0L))
                .isEqualTo(ActivityProgress.Phase.RUNNING);
    }

    @Test
    @DisplayName("上一轮已领过的行读取时直接进入新一轮：没有待展示的欠账，就不该在列表里留 EXPIRED")
    void claimedRowSlidesIntoNextWindowOnRead() {
        ActivityProgress progress = opened();
        long inFirstWindow = OPEN + DAY_MS;
        progress.rollover(inFirstWindow, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 50L, inFirstWindow));
        progress.claim("activity_monster_hunt", inFirstWindow, 0L);

        long later = OPEN + 4 * DAY_MS;
        progress.syncOnRead(later, 0L);
        assertThat(progress.phaseOf("activity_monster_hunt", later, 0L))
                .isEqualTo(ActivityProgress.Phase.RUNNING);
        assertThat(progress.entry("activity_monster_hunt").value()).isZero();
        assertThat(progress.entry("activity_monster_hunt").claimed()).isFalse();
    }

    // ---------- 累加型 ----------

    @Test
    @DisplayName("累加型按事件增量长，并截断到目标值（面板上不会出现 200/20 这种数）")
    void accumulatingProgressIsCappedAtGoal() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 30L, now));
        assertThat(progress.entry("activity_monster_hunt").value()).isEqualTo(30L);

        // 再杀 40 只：目标 50，进度停在 50 而不是 70
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 40L, now));
        assertThat(progress.entry("activity_monster_hunt").value()).isEqualTo(50L);
        assertThat(progress.blockOf("activity_monster_hunt", now, 0L))
                .isEqualTo(ActivityProgress.ClaimBlock.NONE);
    }

    @Test
    @DisplayName("七类条件各订阅自己那一类事件：同类事件只推进订阅它的行（验收 2 的接线段）")
    void eachConditionListensToItsOwnEvent() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.ALLIANCE_DONATE, null, 500L, now));
        progress.onEvent(GameEvent.progress("P1", GoalType.PVP_WIN, null, 1L, now));
        progress.onEvent(GameEvent.progress("P1", GoalType.HELP_SQUAD, null, 2L, now));

        assertThat(progress.entry("activity_donate_week").value()).isEqualTo(500L);
        assertThat(progress.entry("activity_pvp_win").value()).isEqualTo(1L);
        assertThat(progress.entry("activity_squad_help").value()).isEqualTo(2L);
        // 没订阅的那几类一动不动 —— 少一条「不该动的动了」的断言，串号就查不出来
        assertThat(progress.entry("activity_monster_hunt").value()).isZero();
        assertThat(progress.entry("activity_rally_week").value()).isZero();
    }

    @Test
    @DisplayName("别人的事件不推进我的账本（总线按类型派发、不带玩家过滤，账本必须自己认人）")
    void otherPlayersEventsAreIgnored() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);
        progress.onEvent(GameEvent.progress("P2", GoalType.KILL_MONSTER, "m1", 10L, now));
        assertThat(progress.entry("activity_monster_hunt").value()).isZero();
    }

    @Test
    @DisplayName("七类条件订阅的都是累加型目标：活动只认增量事件（状态型快照会让进度变成「加一遍当前库存」）")
    void everyConditionSubscribesToAccumulatingGoal() {
        for (ActivityCondition condition : ActivityCondition.values()) {
            assertThat(condition.goalType().accumulates())
                    .as("%s 订阅的 %s 必须是累加型：Def 的构造会拒掉状态型",
                            condition, condition.goalType())
                    .isTrue();
        }
        // 七个条件覆盖七个不同的目标类型 —— 两条条件订阅同一个目标时，事件会同时推进两行（多半是笔误）
        assertThat(java.util.Arrays.stream(ActivityCondition.values())
                .map(ActivityCondition::goalType).distinct().count())
                .isEqualTo(ActivityCondition.values().length);
    }

    // ---------- 领取 ----------

    @Test
    @DisplayName("领取三种拒绝各有各的 block：未达标 / 已过期 / 已领过（验收 3、4 的判定面）")
    void claimBlocksAreDistinct() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);

        // 未达标
        assertThat(progress.blockOf("activity_monster_hunt", now, 0L))
                .isEqualTo(ActivityProgress.ClaimBlock.NOT_REACHED);
        assertThatThrownBy(() -> progress.claim("activity_monster_hunt", now, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NOT_REACHED");

        // 达标后领一次，再领就是已领过
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 50L, now));
        progress.claim("activity_monster_hunt", now, 0L);
        assertThat(progress.entry("activity_monster_hunt").claimed()).isTrue();
        assertThat(progress.blockOf("activity_monster_hunt", now, 0L))
                .isEqualTo(ActivityProgress.ClaimBlock.ALREADY_CLAIMED);
        assertThatThrownBy(() -> progress.claim("activity_monster_hunt", now, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ALREADY_CLAIMED");

        // 第 4 天（剿匪令已进入第 2 轮）：已领过的行读取时直接滚进新一轮 ⇒ NOT_REACHED，
        // 不是 EXPIRED（没有待展示的欠账就不该标过期 —— EXPIRED 的用例见 pendingRowStaysExpiredUntilNewProgress）
        long later = OPEN + 4 * DAY_MS;
        progress.syncOnRead(later, 0L);
        assertThat(progress.blockOf("activity_monster_hunt", later, 0L))
                .isEqualTo(ActivityProgress.ClaimBlock.NOT_REACHED);
        assertThat(progress.entry("activity_monster_hunt").claimed()).isFalse();
    }

    @Test
    @DisplayName("进度与领取状态都随窗口轮换归零，但记录不删（验收 4：过期不清记录）")
    void rolloverResetsButKeepsEntries() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);
        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 50L, now));
        progress.claim("activity_monster_hunt", now, 0L);
        long firstWindowStart = progress.entry("activity_monster_hunt").windowStart();

        long nextWindow = OPEN + 4 * DAY_MS;
        progress.rollover(nextWindow, 0L);
        ActivityProgress.Entry entry = progress.entry("activity_monster_hunt");
        assertThat(entry.value()).isZero();
        assertThat(entry.claimed()).isFalse();
        // 剿匪令 3 天一轮：开服+4天 落在 [开服+3天, 开服+6天)，所以新的窗口起点是 开服+3天
        assertThat(entry.windowStart()).isEqualTo(OPEN + 3 * DAY_MS);
        assertThat(entry.windowStart()).isNotEqualTo(firstWindowStart);
        // 记录还在：8 行一条不少
        assertThat(progress.entries()).hasSize(8);
    }

    @Test
    @DisplayName("红点数与领取判定同源：claimableCount 领完立刻减一（验收 8 的判定面）")
    void claimableCountSharesOneJudgement() {
        ActivityProgress progress = opened();
        long now = OPEN + DAY_MS;
        progress.rollover(now, 0L);
        assertThat(progress.claimableCount(now, 0L)).isZero();

        progress.onEvent(GameEvent.progress("P1", GoalType.KILL_MONSTER, "m1", 50L, now));
        progress.onEvent(GameEvent.progress("P1", GoalType.PVP_WIN, null, 10L, now));
        assertThat(progress.claimableCount(now, 0L)).isEqualTo(2);

        progress.claim("activity_monster_hunt", now, 0L);
        assertThat(progress.claimableCount(now, 0L)).isEqualTo(1);
        assertThat(progress.blockOf("activity_monster_hunt", now, 0L).blocked()).isTrue();
    }

    @Test
    @DisplayName("定义为空 / 目标为 0 / 重复行号 都在开账本时就抛")
    void invalidDefinitionsFailLoudly() {
        assertThatThrownBy(() -> ActivityProgress.open("P1", List.of(), OPEN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("活动定义为空");
        assertThatThrownBy(() -> new ActivityProgress.Def("a", ActivityType.KILL_MONSTER,
                ActivityCondition.KILL_MONSTER_TOTAL, 0L, 3L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goalValue");
        assertThatThrownBy(() -> ActivityProgress.open("P1",
                List.of(defs().get(0), defs().get(0)), OPEN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }

    private static GameEvent login(long at) {
        return GameEvent.progress("P1", GoalType.LOGIN_DAY, null, 1L, at);
    }
}
