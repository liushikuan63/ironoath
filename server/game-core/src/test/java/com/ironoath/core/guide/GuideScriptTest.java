package com.ironoath.core.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.guide.GuideScript.Action;
import com.ironoath.core.guide.GuideScript.Judge;
import com.ironoath.core.guide.GuideScript.Outcome;
import com.ironoath.core.guide.GuideScript.Status;
import com.ironoath.core.guide.GuideScript.Step;
import com.ironoath.core.player.PlayerGuide;

/**
 * 职责：引导推进规则的用例（B18 验收 5 与验收 8 的规则面）。
 * 依赖：无（纯 core 规则 + {@link PlayerGuide}）。
 *
 * <p><b>"判据达成没有"在这里是一个传进来的布尔</b>：那一位的真实来源是任务账本，
 * 由 {@code GuideAppService} 去读（见 {@code GuideEndpointTest} 的端到端那条）。
 * 本类只验"拿到判定之后规则怎么走"，两件事分开才不必起 Spring 就能测拒绝分支。
 */
class GuideScriptTest {

    private static final PlayerGuide AT_1 = new PlayerGuide(1, null);

    /** 三步：强制、强制、可跳 —— 与 guide.json 的裁决②同形（1/2 强制，3 可跳）。 */
    private static GuideScript script() {
        return GuideScript.of(List.of(
                new Step("g1", 1, false, Judge.QUEST_DONE, "q1"),
                new Step("g2", 2, false, Judge.QUEST_CLAIMED, "q2"),
                new Step("g3", 3, true, Judge.QUEST_DONE, "q3")));
    }

    @Test
    @DisplayName("从没开始过的号：第一号步就是第 1 步，而不是 0 号或空")
    void untouchedPlayerStartsAtOne() {
        Outcome held = script().advance(PlayerGuide.empty(), "g1", Action.COMPLETE, false);
        assertThat(held.status()).isEqualTo(Status.HELD);
        assertThat(held.stepIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("验收 5：客户端喊\"我做完了\"而判据不成立 —— 不推进，也不是错误")
    void fakeCompleteDoesNotAdvance() {
        Outcome held = script().advance(AT_1, "g1", Action.COMPLETE, false);
        assertThat(held.status()).isEqualTo(Status.HELD);
        assertThat(held.advanced()).as("HELD 绝不能被算成推进过").isFalse();
        assertThat(held.finished()).isFalse();
    }

    @Test
    @DisplayName("判据成立才推进，且推进到的是下一号而不是原地")
    void judgedCompleteAdvances() {
        Outcome advanced = script().advance(AT_1, "g1", Action.COMPLETE, true);
        assertThat(advanced.status()).isEqualTo(Status.ADVANCED);
        assertThat(advanced.stepIndex()).isEqualTo(2);
        assertThat(advanced.advanced()).isTrue();
    }

    @Test
    @DisplayName("验收 8：skippable=false 的步上报 SKIP 被拒，且位置不动")
    void mandatoryStepRejectsSkip() {
        Outcome rejected = script().advance(AT_1, "g1", Action.SKIP, true);
        assertThat(rejected.status()).isEqualTo(Status.NOT_SKIPPABLE);
        assertThat(rejected.stepIndex()).as("被拒之后仍在原步，否则跳过就变成免费推进了")
                .isEqualTo(1);
        assertThat(rejected.advanced()).isFalse();
    }

    @Test
    @DisplayName("可跳的步不看判据：SKIP 就是推进（跳过一条没做完的步正是它的语义）")
    void skippableStepIgnoresTheJudge() {
        Outcome advanced = script().advance(new PlayerGuide(3, null), "g3", Action.SKIP, false);
        assertThat(advanced.status()).isEqualTo(Status.FINISHED);
        assertThat(advanced.finished()).isTrue();
    }

    @Test
    @DisplayName("越过最后一步即结束；结束之后再上报只回\"已结束\"，不重放任何一步")
    void lastStepFinishesAndReplayIsInert() {
        GuideScript script = script();
        Outcome finished = script.advance(new PlayerGuide(3, null), "g3", Action.COMPLETE, true);
        assertThat(finished.status()).isEqualTo(Status.FINISHED);

        Outcome replay = script.advance(new PlayerGuide(3, 1_760_000_000_000L), "g3",
                Action.COMPLETE, true);
        assertThat(replay.status()).isEqualTo(Status.ALREADY_FINISHED);
        assertThat(replay.finished()).isTrue();
        assertThat(replay.advanced()).as("重投不该再算一次推进").isFalse();
    }

    @Test
    @DisplayName("不按序上报（跳着报后面的步）被拒 —— 不校验顺序就能刷进度")
    void outOfOrderIsRejected() {
        Outcome rejected = script().advance(AT_1, "g3", Action.SKIP, true);
        assertThat(rejected.status()).isEqualTo(Status.OUT_OF_ORDER);
        assertThat(rejected.stepIndex()).as("被拒时位置不动").isEqualTo(1);
    }

    @Test
    @DisplayName("上报一个脚本里没有的 id：说的是\"客户端手里是旧脚本\"，与\"时机不对\"分开")
    void unknownStepIsReportedAsVersionMismatch() {
        assertThat(script().advance(AT_1, "guide_from_an_older_script", Action.COMPLETE, true).status())
                .isEqualTo(Status.STEP_NOT_FOUND);
        assertThat(script().advance(AT_1, null, Action.COMPLETE, true).status())
                .as("null 也是\"没有这一步\"，不是崩").isEqualTo(Status.STEP_NOT_FOUND);
    }

    // ---------- 装配期的自检 ----------

    @Test
    @DisplayName("空脚本、跳号、重复 id 都在构造期就拒 —— 症状留给运行时就找不回来了")
    void malformedScriptsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> GuideScript.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> GuideScript.of(List.of(
                new Step("g1", 1, false, Judge.QUEST_DONE, "q1"),
                new Step("g3", 3, true, Judge.QUEST_DONE, "q3"))))
                .as("存档存的是序号，脚本跳号会让续传落到一个不存在的位置")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("连续");
        assertThatThrownBy(() -> GuideScript.of(List.of(
                new Step("g1", 1, false, Judge.QUEST_DONE, "q1"),
                new Step("g1", 2, true, Judge.QUEST_DONE, "q2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("没有判据的步骤永远不会完成 —— 构造期就不接受缺判据的行")
    void stepWithoutJudgeCannotBeBuilt() {
        assertThatThrownBy(() -> new Step("g1", 1, false, null, "q1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有判据");
        assertThatThrownBy(() -> new Step("g1", 1, false, Judge.QUEST_DONE, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标任务");
    }
}
