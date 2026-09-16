package com.ironoath.core.guide;

import com.ironoath.core.player.PlayerGuide;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 职责：新手引导脚本的<b>推进规则</b>（B18 §一.2）—— 顺序校验、可跳校验、按状态判定是否推进。
 * 依赖：无（纯 Java，判据以 {@code boolean} 传入，不碰存储与 Spring）。
 *
 * <p><b>为什么规则在 core 而不是 AppService 里</b>：B18 验收 5 与验收 8 要的都是「拒绝」这一类
 * 行为（伪造上报不推进、不可跳的步被拒），而它们挂在 Spring 里就只能起容器测。
 * 与 {@code ActivityProgress.ClaimBlock} 同一族做法：core 返回一个说清原因的 {@link Status}，
 * web 只负责把它换成错误码。
 *
 * <p><b>三条贯穿本类的纪律</b>：
 * <ol>
 *   <li><b>客户端只有上报权，没有裁决权</b>：{@code COMPLETE} 只是「请检查我」，
 *       推不推理由 {@code judgeReached} 决定 —— 那一位是调用方读任务账本算出来的，
 *       不是玩家说的（B00 铁律 3）。</li>
 *   <li><b>没达成不是错误</b>：玩家点了「我做完了」而主城还没升级是<b>正常路径</b>，
 *       返回 {@link Status#HELD} 让引导留在这一步等他。把它做成错误码，客户端就要为每天都发生的事
 *       写失败分支，埋点里的失败率也会失真（这也是 {@code GUIDE_STEP_*} 三个码里
 *       没有「状态未达成」这一位的原因）。</li>
 *   <li><b>必须按序</b>：只认当前那一步的上报。不校验顺序的话，把第 1 步重复上报就能反复推进 ——
 *       引导本身不发奖，所以后果是「跳过中间步骤」而不是刷资源，但顺序一破，
 *       「断线续传回到当前步骤」（验收 2）就无从谈起。</li>
 * </ol>
 *
 * <p><b>本类不读配置表</b>：步骤由 web 的装配器从 {@code guide.json} 构造。热更之后重建这一个对象，
 * 规则本身不热更 —— 规则改一行是改代码，那是应该发包的。
 */
public final class GuideScript {

    /** 一步的完成判据。两个取值都读任务账本，不各写一个查询（见 {@code guide.json} 的 designNote）。 */
    public enum Judge {
        /** 对应任务的<b>目标</b>已达成（进度 ≥ 目标值）。 */
        QUEST_DONE,
        /** 对应任务的<b>奖</b>已领 —— 第 2 步（领奖选将）用它：目标达成不等于奖到手。 */
        QUEST_CLAIMED
    }

    /** 玩家对一步做了什么。与契约 {@code GuideAction} 同形。 */
    public enum Action {
        /** 「我做完了」 —— 服务端按状态裁决。 */
        COMPLETE,
        /** 「跳过」 —— 只有 {@code skippable=true} 的步允许。 */
        SKIP
    }

    /** 推进结果。{@code stepIndex} 一律是<b>结果状态下该做的步序号</b>（0 只出现在空脚本上）。 */
    public enum Status {
        /** 判定成立（或跳过被允许），已推进到下一步。 */
        ADVANCED,
        /** 走到了最后一步之后：引导结束。与 ADVANCED 分开，因为调用方要写结束时刻。 */
        FINISHED,
        /** 判据还没达成 —— 留在当前步，不是错误。 */
        HELD,
        /** 上报的 id 根本不在这份脚本里（多半是客户端还拿着旧版本脚本）。 */
        STEP_NOT_FOUND,
        /** 这一步 {@code skippable=false}。 */
        NOT_SKIPPABLE,
        /** 上报的不是当前那一步。 */
        OUT_OF_ORDER,
        /** 已经走完了还在上报：幂等地回一句「结束了」，不重放任何一步。 */
        ALREADY_FINISHED
    }

    /**
     * @param stepIndex 结果状态下该做的步序号；{@code finished=true} 时无意义（调用方按结束处理）
     * @param finished  引导是否已走完
     */
    public record Outcome(Status status, int stepIndex, boolean finished) {

        static Outcome of(Status status, int stepIndex) {
            return new Outcome(status, stepIndex, false);
        }

        static Outcome of(Status status, int stepIndex, boolean finished) {
            return new Outcome(status, stepIndex, finished);
        }

        /** 本次上报是否真的改变了进度（HELD 与被拒都是 false）。 */
        public boolean advanced() {
            return status == Status.ADVANCED || status == Status.FINISHED;
        }
    }

    /**
     * 一步里<b>参与规则</b>的那几位。文案、遮罩、高亮不在此列 —— 它们只往下发，不参与判定，
     * 放进 core 会让「改一句提示」看起来像在改规则。
     *
     * @param id          步骤主键（上报时按它定位）
     * @param stepIndex   序号，从 1 起且连续（构造期校验）
     * @param skippable   能不能跳
     * @param judge       完成判据
     * @param judgeTarget 判据读的那条任务 id
     */
    public record Step(String id, int stepIndex, boolean skippable, Judge judge, String judgeTarget) {

        public Step {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("步骤 id 不得为空");
            }
            if (stepIndex < 1) {
                throw new IllegalArgumentException("步骤序号必须从 1 起，实际=" + stepIndex + "（" + id + "）");
            }
            if (judge == null) {
                throw new IllegalArgumentException("步骤 " + id + " 没有判据：那一步永远不会完成");
            }
            if (judgeTarget == null || judgeTarget.isBlank()) {
                throw new IllegalArgumentException("步骤 " + id + " 的判据没有目标任务");
            }
        }
    }

    private final List<Step> steps;

    private GuideScript(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * 装配一份脚本。
     *
     * <p><b>序号必须从 1 连续</b>：存档上存的就是序号，中间断一号的表现是「玩家卡在永远续不上的那一步」
     * —— 存档写着 3、脚本里只有 1/2/4，而热更恰恰最容易删掉中间一步。构造期就拒，比运行时猜强。
     */
    public static GuideScript of(List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("引导脚本为空：那等于给所有人发一份永远推不动的引导");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.stepIndex() != i + 1) {
                throw new IllegalArgumentException("步骤序号必须从 1 连续，第 " + (i + 1) + " 行却是 "
                        + step.stepIndex() + "（" + step.id() + "）");
            }
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("步骤 id 重复：" + step.id());
            }
        }
        return new GuideScript(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public int size() {
        return steps.size();
    }

    /** 按 id 找一步（上报的 stepId 走这里；找不到即 {@link Status#STEP_NOT_FOUND}）。 */
    public Optional<Step> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return steps.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 按序号找一步。序号越界（含 0 与超过最后一步）返回空。 */
    public Optional<Step> byIndex(int stepIndex) {
        if (stepIndex < 1 || stepIndex > steps.size()) {
            return Optional.empty();
        }
        return Optional.of(steps.get(stepIndex - 1));
    }

    /**
     * 上报一步的结果，算出新位置。
     *
     * @param stored       存档上的进度（{@code null} 视为从未开始）
     * @param reportedId   玩家针对哪一步上报
     * @param action       做完还是跳过
     * @param judgeReached 该步的判据此刻是否成立 —— <b>由调用方读任务账本算出来</b>，
     *                     {@code SKIP} 时传什么都不看
     */
    public Outcome advance(PlayerGuide stored, String reportedId, Action action, boolean judgeReached) {
        PlayerGuide current = stored == null ? PlayerGuide.empty() : stored;
        if (current.finished()) {
            // 走完还在上报：回「结束了」而不是拒绝。幂等重投（弱网）与刷进度在这一个响应里分不开，
            // 而把重投判成错误会让客户端为一条无害的情况写失败分支
            return new Outcome(Status.ALREADY_FINISHED, steps.size(), true);
        }
        int position = Math.max(current.stepIndex(), 1);
        Step target = byId(reportedId).orElse(null);
        if (target == null) {
            return Outcome.of(Status.STEP_NOT_FOUND, position);
        }
        if (target.stepIndex() != position) {
            return Outcome.of(Status.OUT_OF_ORDER, position);
        }
        if (action == Action.SKIP) {
            if (!target.skippable()) {
                return Outcome.of(Status.NOT_SKIPPABLE, position);
            }
            return land(position, Status.ADVANCED);
        }
        if (!judgeReached) {
            return Outcome.of(Status.HELD, position);
        }
        return land(position, Status.ADVANCED);
    }

    /** 落在下一号上；越过最后一步就是结束。 */
    private Outcome land(int fromIndex, Status status) {
        int next = fromIndex + 1;
        if (next > steps.size()) {
            return new Outcome(Status.FINISHED, steps.size(), true);
        }
        return Outcome.of(status, next);
    }
}
