package com.ironoath.common.time;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：TimeService 与 TimeOffsetTracker 单测 —— 覆盖 B01 验收 7（时钟快/慢 5 分钟，误差 &lt; 1s）。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器、不 sleep（时间源完全由测试控制）。
 */
class TimeServiceTest {

    /** 模拟的服务端真实时间基准：2026-09-06 前后的一个毫秒时间戳。 */
    private static final long SERVER_BASE_MS = 1_788_000_000_000L;

    /** 5 分钟，验收 7 指定的时钟偏移量。 */
    private static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;

    /** 验收 7 的判定阈值：1 秒。 */
    private static final long TOLERANCE_MS = 1000L;

    /** 可控时间源，替代 sleep。 */
    private static final class FakeClock {
        private final AtomicLong now;

        FakeClock(long startMs) {
            this.now = new AtomicLong(startMs);
        }

        long millis() {
            return now.get();
        }

        void advance(long deltaMs) {
            now.addAndGet(deltaMs);
        }
    }

    @Test
    @DisplayName("serverNow 完全由注入的时间源决定，不读系统时钟")
    void serverNowFollowsInjectedClock() {
        FakeClock clock = new FakeClock(SERVER_BASE_MS);
        TimeService service = new TimeService(clock::millis);

        assertThat(service.serverNow()).isEqualTo(SERVER_BASE_MS);
        clock.advance(1234L);
        assertThat(service.serverNow()).isEqualTo(SERVER_BASE_MS + 1234L);
    }

    @Test
    @DisplayName("calibrate 返回 offset = serverNow - clientNow，且 syncAt 为服务端时间")
    void calibrateReturnsRawOffset() {
        FakeClock clock = new FakeClock(SERVER_BASE_MS);
        TimeService service = new TimeService(clock::millis);

        long clientTime = SERVER_BASE_MS - 120L;   // 客户端慢 120ms（含单程延迟）
        TimeService.TimeSync sync = service.calibrate(clientTime);

        assertThat(sync.offset()).isEqualTo(120L);
        assertThat(sync.syncAt()).isEqualTo(SERVER_BASE_MS);
    }

    @Test
    @DisplayName("构造参数校验：时间源为 null 或阈值为非正数时抛异常")
    void rejectsInvalidConstructorArgs() {
        assertThatThrownBy(() -> new TimeService(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeService(() -> 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeService(() -> 0L, -1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("极端偏移只告警不拒绝：伪造 clientTime 对玩家无收益，服务端从不信任它")
    void extremeSkewDoesNotThrow() {
        FakeClock clock = new FakeClock(SERVER_BASE_MS);
        TimeService service = new TimeService(clock::millis);

        TimeService.TimeSync sync = service.calibrate(0L);
        assertThat(sync.offset()).isEqualTo(SERVER_BASE_MS);
    }

    @Test
    @DisplayName("验收7：客户端时钟慢 5 分钟，多次校准后误差 < 1s")
    void slowClientClockConvergesWithinOneSecond() {
        assertConverges(-FIVE_MINUTES_MS);
    }

    @Test
    @DisplayName("验收7：客户端时钟快 5 分钟，多次校准后误差 < 1s")
    void fastClientClockConvergesWithinOneSecond() {
        assertConverges(FIVE_MINUTES_MS);
    }

    /**
     * 完整模拟一次真实的时间同步过程：
     * 客户端有固定时钟偏移 skew，网络有单程延迟，服务端时间持续前进，客户端连续采样 12 次。
     * 最终客户端用平滑后的 offset 推算服务端时间，误差必须小于 1 秒。
     */
    private void assertConverges(long skewMs) {
        FakeClock serverClock = new FakeClock(SERVER_BASE_MS);
        TimeService server = new TimeService(serverClock::millis);
        TimeOffsetTracker tracker = newTracker();

        long oneWayLatencyMs = 40L;
        for (int i = 0; i < 12; i++) {
            // 客户端发出请求时刻：服务端时间 + 客户端时钟偏移
            long serverSendMs = server.serverNow();
            long clientSendMs = serverSendMs + skewMs;

            // 请求经过一个单程延迟到达服务端，服务端此时才 calibrate
            serverClock.advance(oneWayLatencyMs);
            TimeService.TimeSync sync = server.calibrate(clientSendMs);

            // 响应再经过一个单程延迟回到客户端
            serverClock.advance(oneWayLatencyMs);
            long clientReceiveMs = server.serverNow() + skewMs;
            long rttMs = clientReceiveMs - clientSendMs;

            tracker.offer(rttMs, sync.offset());

            // 客户端用当前 offset 推算服务端时间，与真实服务端时间比较
            long estimated = tracker.serverNow(clientReceiveMs);
            long error = Math.abs(estimated - server.serverNow());
            assertThat(error)
                    .as("第 %d 次同步后误差应小于 %dms（skew=%dms），实际 %dms",
                            i + 1, TOLERANCE_MS, skewMs, error)
                    .isLessThan(TOLERANCE_MS);
        }
        assertThat(tracker.isCalibrated()).isTrue();
        assertThat(tracker.acceptedSamples()).isEqualTo(12);
    }

    @Test
    @DisplayName("未采样前 isCalibrated 为 false，serverNow 原样返回客户端时间（不做错误预测）")
    void uncalibratedTrackerDoesNotShiftTime() {
        TimeOffsetTracker tracker = newTracker();
        assertThat(tracker.isCalibrated()).isFalse();
        assertThat(tracker.offsetMs()).isZero();
        assertThat(tracker.serverNow(12345L)).isEqualTo(12345L);
    }

    @Test
    @DisplayName("抖动极值被剔除：RTT 超过历史最佳值 3 倍的样本不进入平均线")
    void jitterOutliersAreRejected() {
        TimeOffsetTracker tracker = newTracker();
        tracker.offer(50L, 1000L);      // 首次采样必然被接受，bestRtt 收敛到 50
        assertThat(tracker.bestRttMs()).isEqualTo(50L);

        tracker.offer(5000L, 999999L);  // RTT 5000 > 50×3 ⇒ 丢弃
        assertThat(tracker.rejectedSamples()).isEqualTo(1);
        assertThat(tracker.offsetMs()).isNotEqualTo(999999L);

        tracker.offer(60L, 1010L);      // 正常样本被接受
        assertThat(tracker.acceptedSamples()).isEqualTo(2);
    }

    @Test
    @DisplayName("加权移动平均按 alpha=0.2 收敛：偏移逐步逼近真值而非跳变")
    void weightedMovingAverageConverges() {
        TimeOffsetTracker tracker = newTracker();
        tracker.offer(20L, 1000L);
        long first = tracker.offsetMs();
        // 首次采样：offset = rawOffset - rtt/2 = 1000 - 10 = 990
        assertThat(first).isEqualTo(990L);

        tracker.offer(20L, 2000L);
        // 990 + (1990 - 990) * 0.2 = 1190
        assertThat(tracker.offsetMs()).isEqualTo(1190L);

        tracker.offer(20L, 2000L);
        // 1190 + (1990 - 1190) * 0.2 = 1350
        assertThat(tracker.offsetMs()).isEqualTo(1350L);

        for (int i = 0; i < 60; i++) {
            tracker.offer(20L, 2000L);
        }
        // 定点 EMA 的不动点带宽是 2：偏差为 2ms 时 round(2 × 0.2) = 0，迭代不再前进。
        // 所以稳态残差最大 2ms 而不是 0 —— 这远小于验收 7 要求的 1s，可以接受。
        assertThat(Math.abs(tracker.offsetMs() - 1990L)).isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("构造参数校验：alpha 必须落在 (0,1.0]，jitterFactor 不得小于 1.0")
    void trackerRejectsInvalidParams() {
        assertThatThrownBy(() -> new TimeOffsetTracker(0L, FixedPoint.of(3), 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeOffsetTracker(FixedPoint.ONE + 1L, FixedPoint.of(3), 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeOffsetTracker(FixedPoint.parse("0.2"), FixedPoint.ONE - 1L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeOffsetTracker(FixedPoint.parse("0.2"), FixedPoint.of(3), 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newTracker().offer(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toServerTime 静态换算与 tracker 换算一致")
    void staticConversionMatchesTracker() {
        TimeOffsetTracker tracker = newTracker();
        tracker.offer(0L, 5000L);
        assertThat(TimeService.toServerTime(10000L, 5000L)).isEqualTo(tracker.serverNow(10000L));
    }

    /** 参数取自 contract/config/global.json 的 TIME_SYNC_* 四项。 */
    private static TimeOffsetTracker newTracker() {
        return new TimeOffsetTracker(
                FixedPoint.parse("0.2"),      // TIME_SYNC_ALPHA
                FixedPoint.parse("3.0"),      // TIME_SYNC_JITTER_FACTOR
                150L);                        // TIME_SYNC_INITIAL_BEST_RTT_MS
    }
}
