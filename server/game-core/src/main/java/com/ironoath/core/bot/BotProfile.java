package com.ironoath.core.bot;

import java.util.List;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：一个 Bot 实例的完整人格参数（B11 §二 数据模型）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架）。
 *
 * <p><b>本类只是数据，没有任何 {@code isBot} 分支</b>。B11 的头号禁止项是
 * 「不要为 Bot 写任何 if (isBot) 的特权分支」，验收 8 还要求这条能被静态检查出来
 * （{@code scripts/check-no-bot-privilege.sh}）。所以 Bot 的「特殊性」全部表达为参数差异
 * （成长系数、延迟、失误率），而不是代码路径差异 —— Bot 与真人走完全相同的 service 层。
 *
 * <p><b>参数在原型的区间内独立取值</b>（§四 成长参差）：同一原型的两个 Bot 会有不同的
 * growthFactor / reactionDelay / mistakeRate。整齐划一的 Bot 一眼就是批量生成的，
 * 而 B11 禁止项明写「不要让 Bot 战力整齐划一」。
 *
 * <p><b>所有比率都是定点 long</b>（B00 禁止 float/double 参与结算）：
 * aggression / greed / sociability / activeness / mistakeRate / growthFactor 全是 ×10000。
 * 用 double 的话，「0.05 的失误率」在累积几千次 tick 后会漂出可观测的偏差，
 * 而 Bot 的成长曲线正是 B11 用来验证数值的那把尺子 —— 尺子自己不准，量出来的都是假的。
 */
public record BotProfile(
        String botId,
        String archetypeId,
        AiProfile ai,
        Persona persona,
        long growthFactorFixed) {

    /**
     * AI 倾向（B11 §二 AiProfile）。四个维度都是定点 0~1.0。
     *
     * @param aggression   攻击性：决定被打了以后是反击还是龟缩
     * @param greed        贪婪度：倾向采集还是倾向打仗
     * @param sociability  社交倾向：加入联盟、聊天、响应集结的概率
     * @param activeness   活跃度：决定 tick 频率
     */
    public record AiProfile(long aggression, long greed, long sociability, long activeness) {
        public AiProfile {
            requireRatio(aggression, "aggression");
            requireRatio(greed, "greed");
            requireRatio(sociability, "sociability");
            requireRatio(activeness, "activeness");
        }
    }

    /**
     * 拟人化人格（B11 §二 BotPersona）。
     *
     * @param nameSeed           名字生成种子。用它而不是直接存名字，是为了让同一个 Bot
     *                           在任何一次重建存档时都得到同一个名字 —— 名字会变是最容易被
     *                           察觉的「这不是同一个人」信号
     * @param avatarId           头像 id
     * @param cityNameSeed       城名种子，同上
     * @param activeHours        活跃小时（0~23）。来自 bot_schedule 按 LOGIN 权重抽出的高峰时段
     * @param reactionDelayMinSec 被攻击后的最短响应秒数。**下界必须 >= 3**（验收 9 的区间下界）
     * @param reactionDelayMaxSec 最长响应秒数。禁止 0ms 反应 —— 那是 Bot 最容易暴露的一处
     * @param mistakeRateFixed   次优决策概率（定点）。§三 明写「这是像人的关键」
     */
    public record Persona(long nameSeed, long avatarId, long cityNameSeed,
                          List<Integer> activeHours,
                          long reactionDelayMinSec, long reactionDelayMaxSec,
                          long mistakeRateFixed) {
        public Persona {
            if (activeHours == null || activeHours.isEmpty()) {
                throw new IllegalArgumentException("activeHours 不得为空：没有活跃时段的 Bot 永远不会 tick，"
                        + "那等于地图上多了一具尸体");
            }
            for (int hour : activeHours) {
                if (hour < 0 || hour > 23) {
                    throw new IllegalArgumentException("activeHours 的取值必须在 [0,23]，实际=" + hour);
                }
            }
            activeHours = List.copyOf(activeHours);
            if (reactionDelayMinSec < 3) {
                throw new IllegalArgumentException("reactionDelayMin 必须 >= 3 秒（B11 验收 9 的区间下界），实际="
                        + reactionDelayMinSec + "。禁止 0ms 反应：那是 Bot 最容易暴露的一处");
            }
            if (reactionDelayMaxSec < reactionDelayMinSec) {
                throw new IllegalArgumentException("reactionDelay 区间非法：min=" + reactionDelayMinSec
                        + " > max=" + reactionDelayMaxSec);
            }
            if (reactionDelayMaxSec > 30) {
                throw new IllegalArgumentException("reactionDelayMax 必须 <= 30 秒（B11 验收 9 的区间上界），实际="
                        + reactionDelayMaxSec);
            }
            requireRatio(mistakeRateFixed, "mistakeRate");
        }
    }

    public BotProfile {
        if (botId == null || botId.isBlank()) {
            throw new IllegalArgumentException("botId 不得为空");
        }
        if (archetypeId == null || archetypeId.isBlank()) {
            throw new IllegalArgumentException("archetypeId 不得为空：原型决定行为倾向，缺了它决策树无从加权");
        }
        if (ai == null || persona == null) {
            throw new IllegalArgumentException("ai 与 persona 都不得为 null");
        }
        // 成长系数区间来自 global.BOT_GROWTH_FACTOR_MIN/MAX（0.7~1.3）。
        // 上界贴着 1.3 是硬约束：再高就会让个别 Bot 超出 B08 的圈层上限，
        // 而禁止项明写「不要让 Bot 突破 B08 的战力圈层规则」
        if (growthFactorFixed <= 0) {
            throw new IllegalArgumentException("growthFactor 必须为正定点数，实际=" + growthFactorFixed);
        }
    }

    /** 成长系数的真实值（仅供日志与埋点，不得参与结算 —— 结算走定点）。 */
    public String growthFactorText() {
        return FixedPoint.format(growthFactorFixed);
    }

    private static void requireRatio(long value, String field) {
        if (value < 0 || value > FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + value);
        }
    }
}
