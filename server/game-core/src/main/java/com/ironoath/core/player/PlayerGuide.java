package com.ironoath.core.player;

/**
 * 职责：新手引导在<b>玩家存档</b>上的那两位状态（B18 §一.2「进度落存档，不是本地存储」）。
 * 依赖：无（纯数据，随 {@link PlayerSave} 一起持久化）。
 *
 * <p><b>为什么只有两个字段</b>：引导的「做到没有」从来不在这里 —— 它读任务账本（见
 * {@code GuideScript}）。存档上只留「该显示哪一步」与「还显不显示」这两件<b>无法从别处推出</b>的事。
 * 把步骤文案、判据、各步完成时刻都存一份，就是在七个地方抄同一张表。
 *
 * <p><b>为什么用 {@code stepIndex} 而不是步骤 id</b>：id 是表里的主键，表改一次它就变一次；
 * 而序号是「第几步」这一件事的本体，脚本热更后按序号续传正是期望行为（改文案续得上，删步骤不漏步）。
 *
 * @param stepIndex 当前该做的步序号（从 1 起）；0 = 从未开始
 * @param finishedAt 走完（或跳完）的时刻；null = 还没走完。
 *                   <b>必须是显式的一位，不能靠「序号超过最后一步」推</b>：脚本热更新增一步之后，
 *                   「已经走完 7 步的老号」会被推成「还有第 8 步没做」，引导又开一遍 ——
 *                   而裁决③明写老号不该被打扰
 */
public record PlayerGuide(int stepIndex, Long finishedAt) {

    public PlayerGuide {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("引导步序号不得为负，实际=" + stepIndex);
        }
        if (finishedAt != null && finishedAt <= 0L) {
            // 0 会被当成「1970 年走完过」，而那是不可信的读法：要么给真时刻，要么给 null
            throw new IllegalArgumentException("结束时刻必须为正的服务端时间戳，或为 null 表示未结束");
        }
    }

    /** 从未开始过引导 —— 老存档读成它（与 {@link PlayerPvp#empty()} 同一条读法）。 */
    public static PlayerGuide empty() {
        return new PlayerGuide(0, null);
    }

    /** 已经开始（包括走完）：这类玩家不再受「只对新号」那道入口闸门约束。 */
    public boolean started() {
        return stepIndex >= 1;
    }

    /** 走完或跳完 —— 读路径据此把 nextStepIndex 下发成 null，客户端就不再弹引导。 */
    public boolean finished() {
        return finishedAt != null;
    }
}
