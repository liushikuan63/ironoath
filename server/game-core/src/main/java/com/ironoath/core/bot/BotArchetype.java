package com.ironoath.core.bot;

import java.util.List;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

/**
 * 职责：一个 Bot 原型的<b>孵化模板</b>（B11 §一 六种原型 × bot_archetype 表）。
 * 依赖：game-common 的 FixedPoint / Rng（纯 Java，零框架）。
 *
 * <p>表里给的是<b>区间</b>（成长系数 0.70~0.90、反应延迟 20~30 秒、失误率 0.15~0.20），
 * 孵化时在同一原型的区间内<b>独立取值</b> —— 这是 §四「成长参差」的落地：
 * 整齐划一的 Bot 一眼就是批量生成的，而 B11 禁止项明写「不要让 Bot 战力整齐划一」。
 *
 * <p><b>四个 AI 维度里，只有两个直接来自表</b>：{@code aggression} 与 {@code socialness} 是表里的列；
 * {@code greed} 与 {@code activeness} 是<b>派生的</b>，规则写在装配器
 * （{@code BotRulesAssembler}）并从这里读进来 —— greed 取 {@code playStyle}
 * （那张表本身就是「采集↔打仗」这条轴），activeness 取 {@code activeHoursPattern} 的覆盖度。
 * 之所以在注释里说清而不是假装表里本来就有：等表补齐这两列，装配器改读表即可，本类不用动。
 *
 * <p><b>本类不含任何 {@code isBot} 分支</b>（B11 头号禁止项）：它只产出「参数有差异的画像」，
 * 而 Bot 与真人走完全相同的 service 层。
 *
 * @param id                  原型 id（bot_archetype 表主键）
 * @param displayName         原型名（日志与埋点用，不下发给客户端）
 * @param shareFixed          该原型在孵化中的占比（定点）。来源 global.BOT_SHARE_*，六行合计 1.0
 * @param aggressionFixed     攻击性（定点）。来源 bot_archetype.aggression
 * @param socialnessFixed     社交倾向（定点）。来源 bot_archetype.socialness
 * @param powerFactorFixed    原型战力系数（定点）。来源 bot_archetype.powerFactor（2026-09-11 加列），
 *                            进入 {@link BotTuning#targetPower} 决定这个档位在验收带内的相对位置
 * @param greedFixed          贪婪度（定点）。装配器由 playStyle 派生
 * @param activenessFixed     活跃度（定点）。装配器由 activeHoursPattern 覆盖度派生
 * @param growthFactorMinFixed 成长系数下界（定点）。来源 bot_archetype.growthFactorMin
 * @param growthFactorMaxFixed 成长系数上界（定点）。来源 bot_archetype.growthFactorMax
 * @param reactionDelayMinSec 被攻击后最短响应秒数。来源 bot_archetype.reactionDelayMinSec
 * @param reactionDelayMaxSec 最长响应秒数。来源 bot_archetype.reactionDelayMaxSec
 * @param mistakeRateMinFixed 次优决策概率下界（定点）。来源 bot_archetype.suboptimalChanceMin
 * @param mistakeRateMaxFixed 次优决策概率上界（定点）。来源 bot_archetype.suboptimalChanceMax
 * @param activeHours         活跃小时（0~23），来源 bot_archetype.activeHoursPattern
 */
public record BotArchetype(
        String id,
        String displayName,
        long shareFixed,
        long aggressionFixed,
        long socialnessFixed,
        long powerFactorFixed,
        long greedFixed,
        long activenessFixed,
        long growthFactorMinFixed,
        long growthFactorMaxFixed,
        long reactionDelayMinSec,
        long reactionDelayMaxSec,
        long mistakeRateMinFixed,
        long mistakeRateMaxFixed,
        List<Integer> activeHours) {

    public BotArchetype {
        requireText(id, "id");
        requireText(displayName, "displayName");
        if (shareFixed <= 0L || shareFixed > FixedPoint.SCALE) {
            throw new IllegalArgumentException("share 必须落在 (0, 1.0] 的定点区间，实际=" + shareFixed
                    + "：占比为 0 的原型永远孵化不出来，它要么是表配错了，要么就该从表里删掉");
        }
        requireRatio(aggressionFixed, "aggression");
        requireRatio(socialnessFixed, "socialness");
        if (powerFactorFixed <= 0L) {
            throw new IllegalArgumentException("powerFactor 必须为正定点数，实际=" + powerFactorFixed
                    + "：它乘在真人均值上，为 0 会让这个档位的 Bot 一孵出来就是 0 战力");
        }
        requireRatio(greedFixed, "greed");
        requireRatio(activenessFixed, "activeness");
        requireGrowth(growthFactorMinFixed, "growthFactorMin");
        requireGrowth(growthFactorMaxFixed, "growthFactorMax");
        if (growthFactorMaxFixed < growthFactorMinFixed) {
            throw new IllegalArgumentException("成长系数区间非法：min=" + growthFactorMinFixed
                    + " > max=" + growthFactorMaxFixed);
        }
        if (reactionDelayMinSec < 3L || reactionDelayMaxSec > 30L
                || reactionDelayMaxSec < reactionDelayMinSec) {
            // 区间本身是 B11 验收 9 的判据（3~30 秒、无 0ms 响应），所以表配错要在这里就炸
            throw new IllegalArgumentException("反应延迟区间必须落在 [3,30] 秒且 min <= max，实际=["
                    + reactionDelayMinSec + "," + reactionDelayMaxSec + "]");
        }
        requireRatio(mistakeRateMinFixed, "suboptimalChanceMin");
        requireRatio(mistakeRateMaxFixed, "suboptimalChanceMax");
        if (mistakeRateMaxFixed < mistakeRateMinFixed) {
            throw new IllegalArgumentException("失误率区间非法：min=" + mistakeRateMinFixed
                    + " > max=" + mistakeRateMaxFixed);
        }
        if (activeHours == null || activeHours.isEmpty()) {
            throw new IllegalArgumentException("activeHours 不得为空：没有活跃时段的原型孵出来的 Bot "
                    + "永远不会 tick，那等于地图上多了一具尸体");
        }
        for (int hour : activeHours) {
            if (hour < 0 || hour > 23) {
                throw new IllegalArgumentException("activeHours 的取值必须在 [0,23]，实际=" + hour);
            }
        }
        activeHours = List.copyOf(activeHours);
    }

    /**
     * 按本原型孵化一份 Bot 画像。
     *
     * <p><b>区间内独立取值，全部来自传进来的 {@link Rng}</b>（铁律 4：随机必须可复现）：
     * 同一个种子重跑一次会得到同一批 Bot —— 客服拿着日志里的种子就能复现玩家看到的名字与战力。
     *
     * <p>名字/城名只存<b>种子</b>不存名字（{@link BotProfile.Persona} 的注释写了理由）：
     * 名字由调用方用 {@link BotNameGenerator} 从种子生成，而唯一性要在生成时对全服已占用的名字查重，
     * 所以「生成名字」与「孵化画像」是两步。
     *
     * @param botId    这个 Bot 的玩家 id（画像的身份，与存档同一个 id）
     * @param avatarId 头像 id。与真人新号同源（{@code global.INIT_AVATAR_ID}）——
     *                 Bot 不允许有「专属头像池」这类差异，那是一种显式的身份标识
     */
    public BotProfile spawnProfile(String botId, long avatarId, Rng rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng 不得为 null（随机必须可复现，禁止 Math.random）");
        }
        long nameSeed = rng.range(0L, 999_999L);
        long cityNameSeed = rng.range(0L, 999_999L);
        long growth = between(growthFactorMinFixed, growthFactorMaxFixed, rng);
        long reactionMin = rng.range(reactionDelayMinSec, reactionDelayMaxSec);
        // 上下界独立取值会让「min > max」有概率发生，所以第二个值从第一个值往上取
        long reactionMax = rng.range(reactionMin, reactionDelayMaxSec);
        long mistake = between(mistakeRateMinFixed, mistakeRateMaxFixed, rng);
        return new BotProfile(botId, id,
                new BotProfile.AiProfile(aggressionFixed, greedFixed, socialnessFixed, activenessFixed),
                new BotProfile.Persona(nameSeed, avatarId, cityNameSeed, activeHours,
                        reactionMin, reactionMax, mistake),
                growth);
    }

    /** 区间内均匀取一个定点值；区间退化成一点时直接返回它（表里某些行 min == max 是合法的）。 */
    private static long between(long min, long max, Rng rng) {
        if (max <= min) {
            return min;
        }
        return min + rng.range(0L, max - min);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }

    private static void requireRatio(long value, String field) {
        if (value < 0L || value > FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + value);
        }
    }

    private static void requireGrowth(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " 必须为正定点数，实际=" + value);
        }
    }
}
