package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：暴虐值系统（B08 §4）—— 累积、每日衰减、档位判定、防刷判定。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>这不是惩罚系统，是「给弱者制造反击的靶子和动机」</b>。
 * 大佬打得越狠，全服打他的理由越充分、奖励越高（围剿令 +15% 攻击、公敌档全服广播 + 共享奖励）。
 * 把它理解成惩罚就会做错：惩罚的思路是给施暴者减收益，
 * 而 B08 的头号禁止项正是「不要实现向下收益衰减」——
 * 施暴者的收益一分都不能少，多出来的只是<b>被全服盯上的风险</b>。
 * 收益照拿、风险照担，玩家自己选，这才是生态；扣收益是系统在替玩家做道德判断。
 *
 * <p><b>与 C01 的关系</b>：C01「错误三：降低虐菜收益 → 杀死社交的起点」说的就是这件事。
 * 暴虐值的全部作用是把「一个人被反复欺负」转成「全服有理由围剿施暴者」，
 * 它增加的是冲突的<b>参与者数量</b>，不是减少冲突的<b>收益</b>。
 */
public final class Tyranny {

    /** 暴虐档位。名称来自 B08 §4 的表格。 */
    public enum Level {
        /** 0 ~ level1-1：无效果。 */
        COMMONER,
        /** level1 ~ level2-1：强横。坐标全地图可见（不再受迷雾保护），可被任何人搜索。 */
        TYRANT,
        /** level2 ~ level3-1：暴虐。全服「围剿令」：任何人攻击他 +15% 攻击，击溃者获额外奖励。 */
        BRUTE,
        /** level3+：公敌。坐标每 6 小时全服广播；击溃方获全服公告 + 全服共享奖励。 */
        PUBLIC_ENEMY
    }

    /**
     * 暴虐值规则。全部来自 global 表（铁律 1：不硬编码）。
     *
     * @param thresholdFixed    战力比 R 超过此值才开始累积（TYRANNY_THRESHOLD = 1.5）
     * @param perUnit           战力比每超出势均力敌(1.0) 一个单位累积多少点（TYRANNY_PER_UNIT = 100）
     * @param crushMultiplier   击溃时的倍数（TYRANNY_CRUSH_MULTIPLIER = 2）
     * @param decayPerDayFixed  每日衰减比例（BRUTALITY_DAILY_DECAY = 0.20）
     * @param level1            强横档阈值（BRUTALITY_TYRANT_THRESHOLD = 100）
     * @param level2            暴虐档阈值（BRUTALITY_BRUTE_THRESHOLD = 300）
     * @param level3            公敌档阈值（BRUTALITY_ENEMY_THRESHOLD = 600）
     */
    public record Rules(long thresholdFixed,
                        long perUnit,
                        long crushMultiplier,
                        long decayPerDayFixed,
                        long level1,
                        long level2,
                        long level3) {

        public Rules {
            if (thresholdFixed < FixedPoint.ONE) {
                // 阈值低于 1.0 意味着「只要我比你强就开始记暴虐」。
                // 而圈层区间本身就是 [0.5x, 2.0x]，R 在 0.5~2.0 之间全是合法对抗 ——
                // 那样一半的合法战斗都会被记暴虐，等于变相禁止强者打弱者（B08 头号禁止项）
                throw new IllegalArgumentException("暴虐阈值必须 >= 1.0（定点 " + FixedPoint.ONE
                        + "），否则区间内的正常对抗也会被记成暴虐，实际=" + thresholdFixed);
            }
            if (perUnit < 1L) {
                throw new IllegalArgumentException("perUnit 必须 >= 1，否则暴虐值永远涨不起来：" + perUnit);
            }
            if (crushMultiplier < 1L) {
                throw new IllegalArgumentException("击溃倍数必须 >= 1（击溃不该比不击溃更轻），实际="
                        + crushMultiplier);
            }
            if (decayPerDayFixed < 0L || decayPerDayFixed >= FixedPoint.SCALE) {
                throw new IllegalArgumentException("每日衰减必须落在 [0, 1.0) 的定点区间，实际="
                        + decayPerDayFixed + "。衰减 100% 等于暴虐值当天清零，档位机制形同虚设");
            }
            if (level1 < 1L || level2 <= level1 || level3 <= level2) {
                throw new IllegalArgumentException("三档阈值必须严格递增且为正：level1=" + level1
                        + ", level2=" + level2 + ", level3=" + level3);
            }
        }
    }

    private Tyranny() {
    }

    /**
     * 一次攻击结算产生的暴虐值增量。
     *
     * <p>公式（B08 §4，全程定点 long）：
     * <pre>
     *   R = attackerPower / targetPower
     *   R &lt;= threshold  ⇒ 0（闸门）
     *   否则 delta = (R - 1.0) × perUnit，击溃时再乘 crushMultiplier
     * </pre>
     *
     * <p><b>口径裁定</b>：B08 §4 的伪代码写的是 {@code (R - threshold) × perUnit}，
     * 但同一份文档的验收 6 要求「R=2.0 未击溃 +100，击溃 +200；R=1.2 不累积」。
     * 两者矛盾 —— 按伪代码，threshold=1.5 时 R=2.0 只得 50。这里以验收标准为准：
     * threshold 只是<b>闸门</b>（超过它才值得记录），累积基数是<b>与势均力敌的偏离量</b> (R - 1.0)。
     * 这样口径也更自洽：R=2.0 的碾压恰好 +100 = 强横档门槛，
     * 即「赢一场两倍战力的碾压就足以让全服看见你」，而 R=1.2 这种区间内的正常对抗一分不记。
     *
     * @param attackerMatchPower 攻方匹配战力
     * @param targetMatchPower   守方匹配战力，必须为正（0 战力目标不构成一次可记暴虐的攻击）
     * @param crushed            是否击溃（把对方打到接近全灭）
     * @return 增量，可能为 0（区间内的正常对抗）
     */
    public static long accumulate(long attackerMatchPower, long targetMatchPower,
                                  boolean crushed, Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (attackerMatchPower < 0L || targetMatchPower < 0L) {
            throw new IllegalArgumentException("战力不得为负：attacker=" + attackerMatchPower
                    + ", target=" + targetMatchPower);
        }
        if (targetMatchPower == 0L) {
            // 打一个 0 战力的目标不构成「以强凌弱」的记录：R 会是无穷大。
            // 这种目标要么是死号要么是刚建的小号，而搜索候选池已经把 48h 不活跃的剔掉了（B08 §8），
            // 所以走到这里说明调用方绕过了候选池，应当拒绝而不是记一个天文数字
            throw new IllegalArgumentException("目标战力为 0，无法计算战力比。"
                    + "这通常意味着调用方绕过了目标搜索的候选池过滤");
        }
        long ratio = FixedPoint.div(FixedPoint.of(attackerMatchPower), FixedPoint.of(targetMatchPower));
        if (ratio <= rules.thresholdFixed()) {
            return 0L;
        }
        long excess = FixedPoint.sub(ratio, FixedPoint.ONE);
        long delta = FixedPoint.round(FixedPoint.mul(excess, FixedPoint.of(rules.perUnit())));
        return crushed ? delta * rules.crushMultiplier() : delta;
    }

    /**
     * 每日自然衰减（B08 §4：每日衰减 20%）。
     *
     * <p><b>衰减是必须的</b>：不衰减的话，一个玩了半年的老玩家的暴虐值只会单调增长，
     * 迟早永久停在公敌档 —— 那时「公敌」不再是一个由近期行为决定的状态，
     * 而是一个摘不掉的标签，围剿也就失去了「他最近太狠了」这个动机。
     *
     * @param days 距上次衰减过了多少天。惰性结算口径：不跑定时器，读取时按天数补算
     */
    public static long decay(long tyranny, int days, Rules rules) {
        if (tyranny < 0L) {
            throw new IllegalArgumentException("暴虐值不得为负：" + tyranny);
        }
        if (days < 0) {
            throw new IllegalArgumentException("天数不得为负（服务端时钟回拨应当按 0 天处理）：" + days);
        }
        if (tyranny == 0L || days == 0) {
            return tyranny;
        }
        long retained = FixedPoint.sub(FixedPoint.ONE, rules.decayPerDayFixed());
        long current = tyranny;
        for (int i = 0; i < days; i++) {
            // 逐日衰减而不是 retained^days 一次算完：后者需要定点幂运算，
            // 而逐日循环的天数极少（玩家不会几百天不上线，且上线时按天补算即可）。
            // 用 truncate 而不是 round：一个单调收缩的序列若每步四舍五入，
            // 会在小数上停住不动（1 × 0.8 = 0.8 → round → 1），
            // 于是暴虐值永远衰减不到 0，「公敌」会变成摘不掉的标签。
            // 向下取整保证序列严格收缩到 0
            current = FixedPoint.truncate(FixedPoint.mul(FixedPoint.of(current), retained));
            if (current == 0L) {
                return 0L;
            }
        }
        return current;
    }

    /** 档位判定。 */
    public static Level levelOf(long tyranny, Rules rules) {
        if (tyranny < 0L) {
            throw new IllegalArgumentException("暴虐值不得为负：" + tyranny);
        }
        if (tyranny >= rules.level3()) {
            return Level.PUBLIC_ENEMY;
        }
        if (tyranny >= rules.level2()) {
            return Level.BRUTE;
        }
        if (tyranny >= rules.level1()) {
            return Level.TYRANT;
        }
        return Level.COMMONER;
    }

    /**
     * 该档位是否让坐标对全服可见（不再受迷雾保护）。
     *
     * <p>强横档起就可见：这是「大佬成为全服靶子」的第一步，
     * 也是弱者能够组织反击的前提 —— 看不见人在哪，一切反击都无从谈起。
     */
    public static boolean exposesCoordinate(Level level) {
        return level != Level.COMMONER;
    }

    /** 该档位是否触发全服围剿令（攻击他 +BONUS_SIEGE_PUBLIC_ENEMY）。 */
    public static boolean triggersCrusade(Level level) {
        return level == Level.BRUTE || level == Level.PUBLIC_ENEMY;
    }

    /** 该档位是否触发全服广播（每 6 小时）。 */
    public static boolean triggersBroadcast(Level level) {
        return level == Level.PUBLIC_ENEMY;
    }

    /**
     * 防刷判定（B08 §4）：同一对玩家互相攻击不计暴虐值；单日对同一目标只计一次。
     *
     * <p><b>为什么需要这两条</b>：暴虐值带来的是「被全服围剿 + 击溃者拿额外奖励」，
     * 于是它同时是一个可被刷的奖励源。两个小号轮流让大号打，
     * 就能把大号推上公敌档，再由第三个号去「围剿」拿全服共享奖励 ——
     * 一条完整的套利链。这两条判定把链条的第一环掐断：
     * 互打不计 ⇒ 大号无法靠小号刷暴虐；单日单次 ⇒ 刷的速度被限死。
     *
     * @param pairSeenBefore    这一对（无序）此前是否已经互相攻击过
     * @param targetCountedToday 今天是否已经对该目标记过暴虐
     */
    public static boolean shouldCount(boolean pairSeenBefore, boolean targetCountedToday) {
        return !pairSeenBefore && !targetCountedToday;
    }

    /**
     * 「无序对」的键。
     *
     * <p>必须无序：A 打 B 与 B 打 A 是同一对关系，用有序键会让「互相攻击」这条判定失效
     * （A→B 记一次、B→A 记一次，两次都算「首次」）。
     */
    public static String pairKey(String playerIdA, String playerIdB) {
        if (playerIdA == null || playerIdA.isBlank() || playerIdB == null || playerIdB.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        return playerIdA.compareTo(playerIdB) <= 0
                ? playerIdA + "|" + playerIdB
                : playerIdB + "|" + playerIdA;
    }

    /** 「某日对某目标」的键。 */
    public static String dailyTargetKey(String playerId, String targetId, String dayKey) {
        if (playerId == null || playerId.isBlank() || targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("playerId 与 targetId 都不得为空");
        }
        if (dayKey == null || dayKey.isBlank()) {
            throw new IllegalArgumentException("dayKey 不得为空");
        }
        return dayKey + "|" + playerId + ">" + targetId;
    }
}
