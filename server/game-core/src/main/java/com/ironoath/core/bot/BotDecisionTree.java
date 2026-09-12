package com.ironoath.core.bot;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

/**
 * 职责：Bot 的行为树 —— 把「它此刻能看到什么」翻译成「它这一步做什么」（B11 §三）。
 * 依赖：game-common 的 Rng 与 FixedPoint（纯 Java，零框架）。
 *
 * <p><b>本类只决策，不执行</b>。执行走 {@code BotScheduler.World}（由 game-web 实现并转调
 * <b>与真人完全相同的 service</b> —— 就是那个接口的 {@code execute}，不是某个叫 ActionPort 的类：
 * 全仓没有 {@code BotActionPort} 这个类型）。B11 的头号铁律是「Bot 必须走与真人完全相同的 service 层，
 * 禁止直改数据库、禁止免资源/免冷却等特权捷径」。把决策与执行分开，正是为了让这条铁律
 * 能被静态检查：本类里不存在任何仓储或钱包引用，所以它想开捷径也没有入口。
 *
 * <p><b>当前状态：这套东西还没有跑在生产里</b> —— game-web 没有 {@code BotScheduler.World} 的实现，
 * {@code BotRegistry.register} 也没有生产调用点（注册表恒空 ⇒ 世界上不存在任何 Bot，
 * {@code processDue} 同样没有驱动方）。缺口与切分见收口清单 #77。
 *
 * <p><b>优先级顺序照抄 §三 的行为树</b>，但第 4 条（收到攻击事件）被提到了第 1 位。
 * 原文的顺序是「建造 → 训练 → 打野 → 受击反应 → 联盟 → 迁城」，
 * 而把受击反应放在建造之后意味着：一个正在被抢的 Bot 会先把手里的资源花掉再考虑挨打这件事，
 * 那既不像人（真人被打了会立刻看战报），也浪费了资源（升级中途被打断会损失进度）。
 * 受击是唯一的**外部中断**，其余六条都是自发行为 —— 中断优先于自发，这是行为树的通例。
 *
 * <p><b>失误率是「像人」的关键</b>（§三 明写）：以 mistakeRate 的概率不选最优的那一条，
 * 而是选下一条可执行的。永远最优的 Bot 一眼假，而玩家分辨不出「它为什么升错建筑」，
 * 只会觉得这个邻居玩得比自己随便 —— 那正是想要的效果。
 */
public final class BotDecisionTree {

    /** 一次决策能做的动作。取值与 §三 行为树的七个分支对应。 */
    public enum Action {
        /** 升级建筑。偏向哪一类由 playStyle 决定（种田型偏资源建筑，好战型偏军事建筑） */
        UPGRADE_BUILDING,
        /** 训练兵力 */
        TRAIN_TROOPS,
        /** 打野 */
        HUNT_MONSTER,
        /** 占领资源点采集 */
        GATHER_RESOURCE,
        /** 受击反应：反击 / 迁城 / 求援 / 龟缩，具体哪一种由 aggression 决定 */
        REACT_ATTACK,
        /** 联盟捐献 */
        ALLIANCE_DONATE,
        /** 联盟一键帮助 */
        ALLIANCE_HELP,
        /** 响应集结 */
        JOIN_RALLY,
        /**
         * 主动掠袭真人（B11 §一「劫掠者会掠夺」、§六「邻里关系：互相小规模攻伐」）。
         * <b>目标不在决策里选</b>：决策只管"这一步想去抢"，找谁抢由执行侧走
         * {@code TargetSearchService}（圈层、活跃窗口、护盾、免战一并继承真人口径）。
         */
        RAID,
        /** 在联盟频道说一句话（§四「事件触发模板句库」，句库来自 bot_chat 表） */
        SEND_CHAT,
        /**
         * 申请加入一个联盟（§六「联盟填充：开服期联盟不至于只有你一个人」）。
         * 没有这条分支的话，"在联盟中"那一支永远不成立 —— 捐献/帮助/集结/聊天会变成
         * 只有测试里才走得到的死代码（收口清单 #94）。
         */
        SEEK_ALLIANCE,
        /** 随机迁城 */
        RELOCATE_CITY,
        /** 这一步什么都不做（资源不够、队列满、或者单纯在「发呆」） */
        IDLE
    }

    /**
     * Bot 此刻能看到的世界状态。
     *
     * <p><b>全部是布尔与计数，没有一个是数值判定结果</b>：
     * 「资源够不够」由调用方（game-web 的适配器）查真实钱包后填进来，
     * 本类不自己算 —— 那样就会有两套「够不够」的口径，而 Bot 的那一套迟早会和真人分叉。
     */
    public record WorldState(
            boolean underAttack,
            boolean hasFreeBuildQueue,
            boolean canAffordBuilding,
            boolean populationFull,
            boolean canAffordTraining,
            boolean hasStamina,
            boolean hasFreeMarchSlot,
            boolean inAlliance,
            boolean inSquad,
            boolean powerBehindAverage,
            long idleMillis) {
    }

    /**
     * 一次决策的结果。
     *
     * @param action      做什么
     * @param suboptimal  这一步是不是「次优决策」（失误）。埋点要它：
     *                    验收 2 的盲测需要知道失误率实际落在哪个区间
     * @param delayMillis 拟人延迟。受击反应 3~30 秒、联盟求助 10~120 秒，
     *                    <b>禁止 0ms</b>（§四）。IDLE 与自发行为的延迟为 0，
     *                    因为它们本来就是 Bot 主动发起的，没有「响应」可言
     * @param reason      为什么选这一条。只进日志，不下发 ——
     *                    下发就等于告诉客户端「这个玩家是 Bot」（禁止项：不要把 isBot 下发）
     */
    public record Decision(Action action, boolean suboptimal, long delayMillis, String reason) {
        public Decision {
            if (action == null) {
                throw new IllegalArgumentException("action 不得为 null");
            }
            if (delayMillis < 0) {
                throw new IllegalArgumentException("delayMillis 不得为负，实际=" + delayMillis);
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason 不得为空：没有理由的决策无法排查"
                        + "「这个 Bot 为什么做了这件事」，而那是调拟人化参数时唯一能看的线索");
            }
        }
    }

    /** 「长时间无互动」的门槛。§三 第 6 条的触发条件，属行为常量而不是游戏数值。 */
    private static final long IDLE_RELOCATE_MILLIS = 6L * 3600L * 1000L;

    /** 迁城的概率（定点 0.15）。太高会让地图上的邻居频繁搬家，「常驻周边」的生活感就没了。 */
    private static final long RELOCATE_CHANCE_FIXED = 1500L;

    /**
     * 做一次决策。
     *
     * @param profile 该 Bot 的人格参数（失误率、反应延迟、AI 倾向）
     * @param state   它此刻能看到的世界
     * @param rng     随机源（按 botId + tick 序号 fork，保证可复现）
     * @param now     服务端当前时刻
     */
    public Decision decide(BotProfile profile, WorldState state, Rng rng, long now) {
        if (profile == null || state == null || rng == null) {
            throw new IllegalArgumentException("profile / state / rng 都不得为 null");
        }
        boolean mistake = roll(rng, profile.persona().mistakeRateFixed());

        // 分支 1（原文第 4 条）：受击反应。外部中断优先于一切自发行为
        if (state.underAttack()) {
            long delay = between(rng, profile.persona().reactionDelayMinSec(),
                    profile.persona().reactionDelayMaxSec()) * 1000L;
            return new Decision(Action.REACT_ATTACK, mistake, delay,
                    "受到攻击，" + (delay / 1000L) + " 秒后反应（aggression="
                            + FixedPoint.format(profile.ai().aggression()) + "）");
        }

        // 分支 2（原文第 1 条）：有空闲建造队列且资源够 → 升级建筑
        if (state.hasFreeBuildQueue() && state.canAffordBuilding()) {
            return pick(mistake, rng, Action.UPGRADE_BUILDING, Action.TRAIN_TROOPS,
                    "有空闲建造队列且资源足够");
        }

        // 分支 3（原文第 2 条）：人口未满且资源够 → 训练
        if (!state.populationFull() && state.canAffordTraining()) {
            return pick(mistake, rng, Action.TRAIN_TROOPS, Action.UPGRADE_BUILDING,
                    "人口未满且资源足够");
        }

        // 分支 4（原文第 3 条）：体力与行军队列空闲 → 打野 / 采集 / 掠袭
        //
        // 掠袭是 2026-09-12（收口清单 #94）按 §一 与 §六 补进来的：行为树七条里没有它，
        // 但两种原型的定位写着它（劫掠者「会掠夺」、邻居「偶尔互相攻伐」）。
        // **触发概率直接用 aggression** —— 与分支 5 用 sociability 触发社交是完全同一条做法：
        // 表里的这一列本来就是"愿意打仗的程度"，另发明一个"掠夺概率"列等于把同一个维度存两处。
        // 掷不中就照旧按 greed 打野/采集，所以低攻击性的 Bot 行为与改动前完全一致。
        if (state.hasStamina() && state.hasFreeMarchSlot()) {
            if (roll(rng, profile.ai().aggression())) {
                return new Decision(Action.RAID, mistake, 0L, "体力与队列空闲，且本次掠袭倾向命中（aggression="
                        + FixedPoint.format(profile.ai().aggression()) + "）");
            }
            Action preferred = roll(rng, profile.ai().greed())
                    ? Action.GATHER_RESOURCE : Action.HUNT_MONSTER;
            Action fallback = preferred == Action.GATHER_RESOURCE
                    ? Action.HUNT_MONSTER : Action.GATHER_RESOURCE;
            return pick(mistake, rng, preferred, fallback, "体力与行军队列空闲（greed="
                    + FixedPoint.format(profile.ai().greed()) + "）");
        }

        // 分支 5（原文第 5 条）：在联盟中 → 捐献 / 一键帮助 / 响应集结 / 聊天，按 sociability 触发
        if (state.inAlliance() && roll(rng, profile.ai().sociability())) {
            long delay = between(rng, 10L, 120L) * 1000L;
            int choice = (int) rng.range(0, 3);
            Action action = switch (choice) {
                case 0 -> Action.ALLIANCE_DONATE;
                case 1 -> Action.ALLIANCE_HELP;
                case 2 -> Action.JOIN_RALLY;
                default -> Action.SEND_CHAT;
            };
            // 捐献与集结不需要「响应延迟」—— 它们不是对求助的回应。
            // 帮助与聊天同理：聊天不是"回应"，它按自己的限流节奏走（频率由 ChatRateLimiter 管）
            return new Decision(action, mistake,
                    action == Action.ALLIANCE_HELP ? delay : 0L,
                    "在联盟中且社交倾向命中（sociability="
                            + FixedPoint.format(profile.ai().sociability()) + "）");
        }

        // 分支 5.5（§六「联盟填充」）：不在联盟里 → 按社交倾向去申请一个。
        // 没有它，"在联盟中"那一支永远不成立（Bot 无路可入盟），
        // 捐献/帮助/集结/聊天四件事会变成只有测试走得到的死代码。
        // 节流与目标选择都在执行侧（本地 6 小时一次 + 挑一个还有人位的联盟）。
        if (!state.inAlliance() && roll(rng, profile.ai().sociability())) {
            return new Decision(Action.SEEK_ALLIANCE, mistake, 0L,
                    "不在联盟中且社交倾向命中（sociability="
                            + FixedPoint.format(profile.ai().sociability()) + "）");
        }

        // 分支 6（原文第 6 条）：长时间无互动 → 按概率随机迁城
        if (state.idleMillis() >= IDLE_RELOCATE_MILLIS && roll(rng, RELOCATE_CHANCE_FIXED)) {
            return new Decision(Action.RELOCATE_CITY, mistake, 0L,
                    "已 " + (state.idleMillis() / 3600_000L) + " 小时无互动，随机迁城");
        }

        // 原文第 7 条「战力落后 → 追赶补偿」不是一个动作，而是 tick 频率的调节，
        // 见 {@link #catchUpTickMultiplier}。§五 明写「通过提高 tick 频率与产出加速成长，
        // 而非直接改数值」—— 直接改数值就是 B11 禁止的特权捷径。
        return new Decision(Action.IDLE, false, 0L, state.powerBehindAverage()
                ? "无可执行动作（战力落后，本次 tick 频率已提高，见 catchUpTickMultiplier）"
                : "无可执行动作");
    }

    /**
     * 战力落后时的 tick 频率倍率（§五 追赶补偿）。
     *
     * <p><b>只提高频率，不改任何数值</b>：落后的 Bot 多做几次决策，
     * 于是它多升几级建筑、多训几批兵 —— 成长仍然是走真实 service 换来的，
     * 与真人没有区别，只是玩得更勤。直接给它加战力就是特权捷径（§十 禁止项）。
     *
     * @return 定点倍率（×10000）。不落后时为 1.0
     */
    public long catchUpTickMultiplier(BotProfile profile, boolean powerBehindAverage) {
        if (!powerBehindAverage) {
            return FixedPoint.SCALE;
        }
        // 1.5 倍而不是 3 倍：倍率太高会让追赶期的 Bot 在几次 tick 内跨过整个圈层，
        // 而圈层跨越本该是几周的养成过程 —— 那会让真人看到「昨天打不过的邻居今天被我碾压」。
        // 再乘上该 Bot 自己的成长系数：成长慢的 Bot 需要更勤快才追得上，
        // 成长快的那个即使落后也不该被推得太猛
        long boost = FixedPoint.parse("1.5");
        // mul 的结果已经是定点数，不能再 round —— round 是「定点 → 整数」，
        // 再调一次会把 1.5 变成 2，而倍率是要乘回 tick 间隔的，变成整数就失去了意义
        return FixedPoint.mul(boost, profile.growthFactorFixed());
    }

    /**
     * 选最优还是次优。
     *
     * <p>失误的表现是「选了下一条可执行的动作」而不是「随机乱选」：
     * 乱选会产生「资源不够却去训练」这种不可能的行为，那一眼就是脚本；
     * 而次优选择产生的行为是合法的，只是不够聪明 —— 那才是真人会犯的错。
     */
    private static Decision pick(boolean mistake, Rng rng, Action best, Action fallback, String reason) {
        Action chosen = mistake ? fallback : best;
        return new Decision(chosen, mistake, 0L,
                reason + (mistake ? "（失误：本应选 " + best + "，实际选了 " + chosen + "）" : ""));
    }

    /** 按定点概率掷一次。 */
    private static boolean roll(Rng rng, long chanceFixed) {
        if (chanceFixed <= 0) {
            return false;
        }
        if (chanceFixed >= FixedPoint.SCALE) {
            return true;
        }
        return rng.range(0, FixedPoint.SCALE - 1) < chanceFixed;
    }

    /** 在 [min, max] 秒内均匀取一个整数。min == max 时直接返回它。 */
    private static long between(Rng rng, long min, long max) {
        if (max <= min) {
            return min;
        }
        return rng.range(min, max);
    }
}
