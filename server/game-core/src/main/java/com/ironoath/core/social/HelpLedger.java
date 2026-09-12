package com.ironoath.core.social;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.DayKey;

/**
 * 职责：互助帮助的额度与加速上限台账（B10 §2 联盟互助、禁止项「不要让小队互助与联盟帮助简单叠加」）。
 * 依赖：game-common 的 FixedPoint 与 DayKey（纯 Java，零框架）。
 *
 * <p><b>本类管两条独立的约束，缺一条都会出事</b>：
 * <ol>
 *   <li><b>帮助方的每日额度</b>（global.HELP_DAILY_LIMIT）。小队互助与联盟帮助<b>共用</b>这一个额度 ——
 *       各给一份等于把加速总量翻倍，那正是禁止项说的「简单叠加」</li>
 *   <li><b>被帮助方的总加速上限</b>（global.HELP_SPEEDUP_TOTAL_CAP）。
 *       150 人的联盟每人帮一次就是 150%，一次升级直接归零 ——
 *       那样的话「有盟」与「无盟」的升级速度差好几倍，社交就从加成变成了门槛，
 *       与 C00 的生态优先冲突（不加入联盟的玩家不该被数值惩罚到无法游玩）</li>
 * </ol>
 *
 * <p><b>全程定点整数</b>（B00 禁止 float/double 参与结算）：加速比例以 ×10000 存，
 * 累加与封顶比较都在 long 上做。用 double 累加 20 次 0.01 会得到 0.19999999999999998，
 * 而玩家看到的「已加速 20%」与服务端算出的 19.99999% 会在某一次四舍五入上分叉。
 *
 * <p><b>每日额度按 DayKey 归档</b>：跨天必须清零，而 DayKey 是全项目唯一的日切键来源
 * （UTC yyyyMMdd）。自己拼日期字符串会让「每日」在两个系统里有两种边界。
 */
public final class HelpLedger {

    /**
     * @param dailyLimit        每人每日可帮助的次数。来源 global.HELP_DAILY_LIMIT
     * @param speedupCapFixed   单个目标可获得的合计加速上限（定点）。来源 global.HELP_SPEEDUP_TOTAL_CAP
     * @param bonusPerHelpFixed 每次帮助削减的比例（定点）。来源 squad_config.helpSpeedBonus /
     *                          global.ALLIANCE_HELP_SPEED_BONUS —— <b>两者同为 1%</b>，
     *                          「小队比联盟略弱」靠人数差与本上限实现，不靠单次比例
     */
    public record Rules(int dailyLimit, long speedupCapFixed, long bonusPerHelpFixed) {
        public Rules {
            if (dailyLimit < 1) {
                throw new IllegalArgumentException("dailyLimit 必须 >= 1，否则帮助功能等于不存在，实际=" + dailyLimit);
            }
            if (speedupCapFixed <= 0 || speedupCapFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("speedupCapFixed 必须落在 (0, 1.0] 的定点区间，实际="
                        + speedupCapFixed + "。超过 1.0 意味着一次升级能被帮成负时长");
            }
            if (bonusPerHelpFixed <= 0 || bonusPerHelpFixed > speedupCapFixed) {
                throw new IllegalArgumentException("bonusPerHelpFixed 必须落在 (0, speedupCap] 内，实际="
                        + bonusPerHelpFixed + "，上限=" + speedupCapFixed
                        + "。单次帮助就顶满上限的话，「一键帮助全部」只会生效一次");
            }
        }
    }

    /**
     * 一次帮助的结算结果。
     *
     * @param grantedFixed 实际授予的加速比例（定点）。<b>可能小于单次标准值</b> ——
     *                     触到总上限时只给剩余额度，照实返回而不是拒绝：
     *                     拒绝会让玩家以为「帮助失败」，而他其实帮上了一部分
     * @param capped       本次是否触到了总上限
     * @param totalFixed   该目标累计已获得的加速比例（定点）
     * @param remaining    帮助方今日剩余额度
     */
    public record Outcome(long grantedFixed, boolean capped, long totalFixed, int remaining) {
    }

    private final Rules rules;
    /** 帮助方 → (日切键 → 今日已帮次数) */
    private final Map<String, Map<String, Integer>> dailyCount = new HashMap<>();
    /** 帮助方 → 今日已帮过的目标键集合。同一条请求帮两次没有意义（验收 6 的「一键帮助」要跳过它们） */
    private final Map<String, Map<String, Set<String>>> helpedTargets = new HashMap<>();
    /** 被帮助的目标 → 已累计获得的加速比例（定点） */
    private final Map<String, Long> speedupGranted = new HashMap<>();

    public HelpLedger(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /** 某位玩家今日还剩几次帮助额度。 */
    public int remainingToday(String helperId, long now) {
        String day = DayKey.of(now);
        Map<String, Integer> byDay = dailyCount.get(helperId);
        int used = (byDay == null) ? 0 : byDay.getOrDefault(day, 0);
        return Math.max(0, rules.dailyLimit() - used);
    }

    /** 我是否已经帮过这一条。「一键帮助全部」据此跳过，否则会在同一个人身上重复消耗额度。 */
    public boolean alreadyHelped(String helperId, String targetKey, long now) {
        Map<String, Set<String>> byDay = helpedTargets.get(helperId);
        if (byDay == null) {
            return false;
        }
        Set<String> targets = byDay.get(DayKey.of(now));
        return targets != null && targets.contains(targetKey);
    }

    /**
     * 执行一次帮助：扣帮助方额度、给被帮助方加速（受总上限约束）。
     *
     * <p><b>判定与扣减在同一步完成</b>：先查额度再扣的话，并发下的两次请求会同时通过检查，
     * 帮助方就能超出每日额度（与 B00 禁止的「先查后改扣资源」同一类问题）。
     *
     * @param helperId  帮助方玩家 id
     * @param targetKey 被帮助的目标键。要能唯一标识「谁的哪一件事」，
     *                  例如 {@code playerId + ":" + requestId} —— 只用 playerId 的话，
     *                  同一个人同时升级两个建筑就只能被帮一次
     * @param now       服务端当前时刻
     * @return 结算结果；额度用完时返回 null（调用方据此报 SOCIAL_HELP_DAILY_LIMIT）
     */
    public Outcome help(String helperId, String targetKey, long now) {
        if (helperId == null || helperId.isBlank()) {
            throw new IllegalArgumentException("helperId 不得为空");
        }
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("targetKey 不得为空：只用 playerId 会让同一人的多件事共用一个上限");
        }
        String day = DayKey.of(now);
        Map<String, Integer> byDay = dailyCount.computeIfAbsent(helperId, k -> new HashMap<>());
        int used = byDay.getOrDefault(day, 0);
        if (used >= rules.dailyLimit()) {
            return null;
        }
        // 跨天时旧的「已帮过」集合必须作废：否则昨天的记录会让今天的「一键帮助」全部跳过
        Set<String> targets = helpedTargets
                .computeIfAbsent(helperId, k -> new HashMap<>())
                .computeIfAbsent(day, k -> new HashSet<>());
        byDay.put(day, used + 1);
        targets.add(targetKey);

        long already = speedupGranted.getOrDefault(targetKey, 0L);
        long room = rules.speedupCapFixed() - already;
        long granted = Math.min(rules.bonusPerHelpFixed(), Math.max(0L, room));
        boolean capped = granted < rules.bonusPerHelpFixed();
        long total = already + granted;
        speedupGranted.put(targetKey, total);
        return new Outcome(granted, capped, total, rules.dailyLimit() - (used + 1));
    }

    /** 某个目标累计已获得的加速比例（定点）。 */
    public long speedupOf(String targetKey) {
        return speedupGranted.getOrDefault(targetKey, 0L);
    }

    /**
     * 目标完成后清理它的加速记录。
     *
     * <p>不清理的话这张表只增不减 —— 每次升级、每次训练、每次治疗都会留下一条，
     * 一个月后就是几百万个 entry。这是内存泄漏，不是缓存。
     */
    public void forgetTarget(String targetKey) {
        speedupGranted.remove(targetKey);
    }

    /** 跨天清理某个玩家的日计数。与 {@link #forgetTarget} 同理，由日切流程调用。 */
    public void forgetHelper(String helperId) {
        dailyCount.remove(helperId);
        helpedTargets.remove(helperId);
    }

    public Rules rules() {
        return rules;
    }
}
