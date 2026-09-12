package com.ironoath.common.time;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：客户端时间偏移的加权移动平均跟踪器（B01 验收 7）。
 * 依赖：game-common 的 FixedPoint（定点数，无 double）。
 *
 * <p>为什么需要它：单次 {@code /time/sync} 采到的 offset 含网络单程延迟，直接采用会让客户端时间
 * 在每次同步时来回跳，进而导致产出倒计时抖动。做法是多次采样 + 加权移动平均 + 剔除抖动极值。
 *
 * <p>本类是<b>纯 Java</b> 的，客户端 {@code core/TimeSync.ts} 是同一算法的 TS 镜像。
 * 放在服务端一份的目的是：偏移平滑是影响产出显示与结算纠偏的算法，必须能被 JUnit 验证
 * （铁律 2：客户端玩法逻辑不 import cc，同一套逻辑双端都能跑单测）。
 *
 * <p>注意：本类<b>只影响显示与预测</b>。服务端结算永远用 {@link TimeService#serverNow()}，
 * 所以即使客户端把 offset 算错也无法作弊。
 */
public final class TimeOffsetTracker {

    /** 首个样本的权重为 1（直接采纳），后续样本按 alpha 平滑。 */
    private final long alphaFixed;

    /** RTT 超过「历史最佳 RTT × jitterFactor」的样本直接丢弃，视为网络抖动极值。 */
    private final long jitterFactorFixed;

    /** 历史最佳 RTT，毫秒。越小代表越干净的网络环境。 */
    private long bestRttMs;

    private long offsetMs;
    private boolean initialized;
    private int acceptedSamples;
    private int rejectedSamples;

    /**
     * @param alphaFixed         平滑系数（定点，0~10000）。建议 2000（0.2）：5 次采样后收敛到 67%
     * @param jitterFactorFixed  抖动剔除倍数（定点）。建议 30000（3.0）：RTT 超过最佳值 3 倍即丢弃
     * @param initialBestRttMs   初始最佳 RTT 估计，毫秒。首次采样前用它兜底
     */
    public TimeOffsetTracker(long alphaFixed, long jitterFactorFixed, long initialBestRttMs) {
        if (alphaFixed <= 0L || alphaFixed > FixedPoint.ONE) {
            throw new IllegalArgumentException(
                    "alpha 必须落在 (0, 1.0] 的定点区间，实际=" + FixedPoint.format(alphaFixed));
        }
        if (jitterFactorFixed < FixedPoint.ONE) {
            throw new IllegalArgumentException(
                    "jitterFactor 不得小于 1.0，否则所有样本都会被丢弃，实际="
                            + FixedPoint.format(jitterFactorFixed));
        }
        if (initialBestRttMs <= 0L) {
            throw new IllegalArgumentException("initialBestRttMs 必须为正数，实际=" + initialBestRttMs);
        }
        this.alphaFixed = alphaFixed;
        this.jitterFactorFixed = jitterFactorFixed;
        this.bestRttMs = initialBestRttMs;
    }

    /**
     * 提交一次采样。
     *
     * <p>单程延迟补偿：服务端返回的 {@code rawOffset = 服务端收到请求时刻 - 客户端发出请求时刻}，
     * 而客户端真正需要的是「同一瞬间的 serverNow - clientNow」。两者相差恰好一个单程延迟，
     * 即 rtt/2。不补偿会让客户端时间系统性超前一个单程延迟（RTT 200ms 时超前 100ms），
     * 表现为产出倒计时始终比服务端早结束。
     *
     * @param rttMs     本次请求的往返时延（客户端测量：收到响应时刻 - 发出请求时刻）
     * @param rawOffset 服务端返回的 offset 原值
     */
    public void offer(long rttMs, long rawOffset) {
        if (rttMs < 0L) {
            throw new IllegalArgumentException("rttMs 不得为负：" + rttMs);
        }
        long threshold = FixedPoint.round(FixedPoint.mul(FixedPoint.of(bestRttMs), jitterFactorFixed));
        if (initialized && rttMs > threshold) {
            // 抖动极值：这次采样大概率被一次卡顿污染，丢弃而不是拉偏整条平均线
            rejectedSamples++;
            return;
        }
        long compensated = rawOffset - rttMs / 2L;
        if (rttMs > 0L && rttMs < bestRttMs) {
            bestRttMs = rttMs;
        }
        if (!initialized) {
            offsetMs = compensated;
            initialized = true;
        } else {
            // 指数加权移动平均：offset += (compensated - offset) * alpha，全程定点
            long delta = FixedPoint.mul(compensated - offsetMs, alphaFixed);
            offsetMs += delta;
        }
        acceptedSamples++;
    }

    /** 当前平滑后的偏移。未采样过时返回 0（此时客户端应视为「未校准」，不做任何本地结算预测）。 */
    public long offsetMs() {
        return offsetMs;
    }

    /** 是否已完成至少一次有效采样。 */
    public boolean isCalibrated() {
        return initialized;
    }

    /** 把客户端本地时间换算为服务端时间基准。未校准时原样返回，避免用错误的 offset 误导表现层。 */
    public long serverNow(long clientNowMs) {
        return initialized ? clientNowMs + offsetMs : clientNowMs;
    }

    public int acceptedSamples() {
        return acceptedSamples;
    }

    public int rejectedSamples() {
        return rejectedSamples;
    }

    public long bestRttMs() {
        return bestRttMs;
    }
}
