package com.ironoath.core.city;

import com.ironoath.common.num.FixedPoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：资源惰性结算 —— B00 陷阱 2 与 B03 禁止项的核心落地。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架）。
 *
 * <p><b>服务端不跑任何定时器</b>。产出不靠 {@code @Scheduled} 每秒扫全表，
 * 而是存 {@code lastSettleTime}，读取时按时间差一次算清：
 * <pre>
 *   产出 = (now - lastSettleTime) / 3600 × 每小时产出
 * </pre>
 * 一万个玩家挂机和一百个玩家在线的计算成本完全相同 —— 只在有人读的时候算一次。
 * 用定时器扫表的话，成本随在线人数线性增长，且扫表期间的写入会与玩家操作打架。
 *
 * <p>全程定点 long，禁止 double（B03 禁止项）。
 * 时间差换算用「毫秒 × 每小时产量 ÷ 3600000」而不是先除成小时再乘：
 * 先除会丢掉不足一小时的零头，玩家挂机 59 分钟就一无所获。
 */
public final class ResourceSettlement {

    /** 一小时的毫秒数。 */
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private ResourceSettlement() {
    }

    /**
     * 单一资源的结算结果。
     *
     * @param current      结算后的持有量（已受容量上限约束）
     * @param produced     本次实际入账的产量（被上限截断后的值，不是理论产量）
     * @param cappedAt     何时达到上限（毫秒时间戳）；未达上限为 null。UI 据此显示「已满」红标
     * @param overflow     因超出上限而浪费的产量。<b>这部分永久损失</b>，不结转
     * @param lastSettle   新的结算基准时刻
     * @param heldOverCap  结算<b>前</b>的持有量就已超过容量上限。正常路径永远为 false，
     *                     true 说明别处漏了封顶（容量被调小、或旧存档带着新规则下超量的存货）。
     *                     <b>本方法刻意不抛异常</b>（见 {@link #settle} 的理由），
     *                     所以这个状态只能靠标志位冒泡给调用方去 WARN ——
     *                     不然「别处漏了封顶」就永久静默，只表现为玩家仓库莫名停止产出
     */
    public record Result(long current, long produced, Long cappedAt, long overflow, long lastSettle,
                         boolean heldOverCap) {

        public Result {
            if (current < 0L || produced < 0L || overflow < 0L) {
                throw new IllegalArgumentException("结算结果不得为负：current=" + current
                        + ", produced=" + produced + ", overflow=" + overflow);
            }
        }

        /** 是否已满仓（产出已停止）。B03 验收 11 的判定点。 */
        public boolean isFull() {
            return cappedAt != null;
        }
    }

    /**
     * 结算单一资源。
     *
     * @param current     结算前持有量
     * @param cap         仓储上限
     * @param perHour     每小时产量（整数，不是定点数 —— 产量的最小粒度就是 1 单位资源）
     * @param lastSettle  上次结算时刻（服务端毫秒时间戳）
     * @param now         当前服务端时刻，由调用方从 TimeService 取（铁律 5：不用本地时钟）
     */
    public static Result settle(long current, long cap, long perHour, long lastSettle, long now) {
        if (current < 0L) {
            throw new IllegalArgumentException("current 不得为负：" + current);
        }
        if (cap < 0L) {
            throw new IllegalArgumentException("cap 不得为负：" + cap);
        }
        if (perHour < 0L) {
            throw new IllegalArgumentException("perHour 不得为负：" + perHour);
        }
        if (now < lastSettle) {
            // 时间倒退说明服务端时钟被回拨或多实例不同步。
            // 不结算、不推进 lastSettle：推进了会永久吃掉这段时间的产量
            return new Result(current, 0L, current >= cap ? lastSettle : null, 0L, lastSettle, false);
        }
        long elapsedMs = now - lastSettle;
        if (current > cap) {
            // 持有量超过上限 ⇒ 别处漏了封顶（最常见的是仓库降级或容量配置下调）。
            // 这里刻意不抛异常也不清仓：
            // ① settle 是读路径（打开城内界面、登录快照）都会调的纯函数，抛异常等于
            //    「改小一次容量配置就把玩家挡在登录界面外」，而异常本身修不了任何东西；
            // ② 把 current 夹回 cap 是销毁玩家的存货 —— 比超容量本身严重得多的错。
            // 正确处置是「保留存货、停止产出、推进基准时刻」，并把这种状态标出来让调用方告警
            return new Result(current, 0L, now, theoreticalOutput(perHour, elapsedMs), now, true);
        }
        if (perHour == 0L || elapsedMs == 0L) {
            return new Result(current, 0L, current >= cap ? now : null, 0L, now, false);
        }

        long room = cap - current;
        if (room <= 0L) {
            // 已满仓：产出停止，lastSettle 仍然推进到 now。
            // 不推进的话，玩家腾空仓库的瞬间会一次性拿到满仓期间的全部产量（B03 验收 11 要求产出停止）
            return new Result(current, 0L, lastSettle, theoreticalOutput(perHour, elapsedMs), now, false);
        }

        long output = theoreticalOutput(perHour, elapsedMs);
        if (output <= room) {
            // lastSettle 只推进到「已入账产量对应的那一刻」，不足 1 单位的时间零头留到下次结算。
            // 这是零成本的：lastSettle 本身就是那个亚单位累加器，不需要额外字段。
            // 若直接推进到 now，损失是「每次结算不到 1 单位」而不是「每小时不到 1 单位」——
            // 客户端每 30 秒拉一次面板就是每小时 120 次结算，能吃掉 5%~10% 的产量。
            // 对体力这类 perHour 很小的资源更是致命的：每 6 分钟恢复 1 点，
            // 玩家每 5 分钟上线一次就永远恢复不了任何一点
            long consumedMs = millisProducing(output, perHour);
            return new Result(current + output, output, null, 0L,
                    Math.min(lastSettle + consumedMs, now), false);
        }

        // 产出超过剩余空间：截断到满仓，并反推是何时满的（UI 显示「已满 X 小时」）。
        // 这里 lastSettle 推进到 now 是对的：从 cappedAt 起玩家就是满仓状态，
        // 那段时间本来就不该结转（否则腾空仓库的瞬间会一次性拿到满仓期间的全部产量）
        long cappedAt = lastSettle + millisToFill(room, perHour);
        return new Result(cap, room, Math.min(cappedAt, now), output - room, now, false);
    }

    /**
     * 理论产量 = 每小时产量 × 经过毫秒 ÷ 3600000，向下取整。
     *
     * <p>向下取整是刻意的：不足 1 单位的产量不给，否则「反复读取」会变成刷资源的手段
     * （每次读取都四舍五入进位 1 单位，读一万次就多出一万资源）。
     *
     * <p><b>零头不会丢失</b>：调用方把 lastSettle 只推进到已入账产量对应的那一刻
     * （见 {@link #millisProducing}），剩下的时间差自然结转进下一次结算。
     * 这里曾经把 lastSettle 直接推到 now，理由写的是「保留零头需要额外存一个亚单位累加器」——
     * 那个理由是错的，不推进 lastSettle 就是零成本的累加器；
     * 而代价也被低估了：损失是<b>每次结算</b>不到 1 单位，不是每小时不到 1 单位。
     */
    private static long theoreticalOutput(long perHour, long elapsedMs) {
        // perHour × elapsedMs 可能溢出 long（perHour 很大且离线很久），用 BigInteger 兜底
        try {
            return Math.multiplyExact(perHour, elapsedMs) / MILLIS_PER_HOUR;
        } catch (ArithmeticException overflow) {
            return java.math.BigInteger.valueOf(perHour)
                    .multiply(java.math.BigInteger.valueOf(elapsedMs))
                    .divide(java.math.BigInteger.valueOf(MILLIS_PER_HOUR))
                    .longValueExact();
        }
    }

    /**
     * 反推产出 output 单位用了多少毫秒，<b>向下取整</b>。
     *
     * <p>方向必须与 {@link #millisToFill} 相反：那个向上取整（宁可晚报满也不要提前报满），
     * 这个向下取整（宁可少结转一点时间也不要多结转）。
     * 多结转 1 毫秒意味着凭空多算了一点产量，反复结算就会累积成可刷的漏洞；
     * 少结转则只是把零头留到下一次，总量守恒。
     */
    private static long millisProducing(long output, long perHour) {
        if (perHour <= 0L || output <= 0L) {
            return 0L;
        }
        try {
            return Math.multiplyExact(output, MILLIS_PER_HOUR) / perHour;
        } catch (ArithmeticException overflow) {
            return java.math.BigInteger.valueOf(output)
                    .multiply(java.math.BigInteger.valueOf(MILLIS_PER_HOUR))
                    .divide(java.math.BigInteger.valueOf(perHour))
                    .longValueExact();
        }
    }

    /** 反推填满 room 需要多少毫秒（向上取整，宁可晚一点报满也不要提前报满）。 */
    private static long millisToFill(long room, long perHour) {
        if (perHour <= 0L) {
            return Long.MAX_VALUE;
        }
        long numerator = room * MILLIS_PER_HOUR;
        if (numerator < 0L) {
            // 溢出：说明 room 极大，实际永远不会满
            return Long.MAX_VALUE;
        }
        return (numerator + perHour - 1L) / perHour;
    }

    /**
     * 批量结算全部资源。
     *
     * @param states    资源 id → [current, cap, perHour, lastSettle]，由调用方从存档与配置组装
     * @param now       当前服务端时刻
     * @return 资源 id → 结算结果，顺序与入参一致
     */
    public static Map<String, Result> settleAll(Map<String, long[]> states, long now) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : states.entrySet()) {
            long[] s = e.getValue();
            if (s.length != 4) {
                throw new IllegalArgumentException("资源 " + e.getKey()
                        + " 的状态数组必须是 [current, cap, perHour, lastSettle] 四个元素，实际=" + s.length);
            }
            results.put(e.getKey(), settle(s[0], s[1], s[2], s[3], now));
        }
        return results;
    }

    /** 定点百分比格式化，供日志与错误详情使用。 */
    public static String percent(long fixed) {
        return FixedPoint.format(FixedPoint.mul(fixed, FixedPoint.of(100))) + "%";
    }
}
