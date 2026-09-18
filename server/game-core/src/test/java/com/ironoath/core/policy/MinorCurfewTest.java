package com.ironoath.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZonedDateTime;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.DayKey;

/**
 * 职责：未成年时长限制的**边界**（规则的全文就是这些边界）。
 * 依赖：无（纯函数）。
 *
 * <p>用 `ZonedDateTime` 造时刻而不是写死 epoch 数字：写死一个毫秒数等于把"这是不是 20:00"这件事
 * 交给读者的心算，而这条规则错的代价是合规事故。
 */
class MinorCurfewTest {

    private static final long START = 20L;
    private static final long END = 21L;

    /** 2026-09-18（周五）UTC+8 的某个时刻。 */
    private static long at(int hour, int minute) {
        return ZonedDateTime.of(2026, 9, 18, hour, minute, 0, 0, DayKey.CALENDAR_ZONE)
                .toInstant().toEpochMilli();
    }

    private static long expectAt(int dayOffset, int hour) {
        return ZonedDateTime.of(2026, 9, 18, 0, 0, 0, 0, DayKey.CALENDAR_ZONE)
                .plusDays(dayOffset).withHour(hour)
                .toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("窗口是左闭右开：19:59 拒、20:00 放、20:59 放、21:00 拒（写成闭区间会多放一秒）")
    void windowBoundaries() {
        assertThat(MinorCurfew.evaluate(at(19, 59), Set.of(), START, END).allowed()).isFalse();
        assertThat(MinorCurfew.evaluate(at(20, 0), Set.of(), START, END).allowed()).isTrue();
        assertThat(MinorCurfew.evaluate(at(20, 59), Set.of(), START, END).allowed()).isTrue();
        assertThat(MinorCurfew.evaluate(at(21, 0), Set.of(), START, END).allowed()).isFalse();
    }

    @Test
    @DisplayName("拒绝时给出具体的「什么时候再来」：未到窗口给今天 20:00，已过窗口给明天 20:00")
    void deniedVerdictTellsWhenToComeBack() {
        MinorCurfew.Verdict before = MinorCurfew.evaluate(at(19, 59), Set.of(), START, END);
        assertThat(before.nextAllowedAt()).as("还没到 20 点 ⇒ 今天 20:00").isEqualTo(expectAt(0, 20));

        MinorCurfew.Verdict after = MinorCurfew.evaluate(at(21, 30), Set.of(), START, END);
        assertThat(after.nextAllowedAt()).as("已经过了 21 点 ⇒ 明天 20:00").isEqualTo(expectAt(1, 20));
        assertThat(after.reason()).as("原因要能直接给玩家看").contains("20:00").contains("21:00");

        assertThat(MinorCurfew.evaluate(at(20, 30), Set.of(), START, END).nextAllowedAt())
                .as("可玩时没有「下次」这回事").isZero();
    }

    @Test
    @DisplayName("法定节假日整天可玩：窗口与时刻都拦不住它")
    void holidaysAreOpenAllDay() {
        Set<String> holiday = Set.of(DayKey.of(at(3, 0)));

        assertThat(MinorCurfew.evaluate(at(3, 0), holiday, START, END).allowed())
                .as("凌晨三点，但今天是法定节假日").isTrue();
        assertThat(MinorCurfew.evaluate(at(22, 30), holiday, START, END).allowed()).isTrue();
    }

    @Test
    @DisplayName("节假日「不知道」就按平常日判：空集合不许当成「天天放假」")
    void emptyHolidaySetMeansOrdinaryDay() {
        assertThat(MinorCurfew.evaluate(at(15, 0), Set.of(), START, END).allowed())
                .as("表里一个节假日都没有时，下午三点照样不许玩").isFalse();
    }

    @Test
    @DisplayName("窗口参数非法直接抛：0 <= start < end <= 24，写反了要在启动前就炸")
    void invalidWindowThrows() {
        assertThatThrownBy(() -> MinorCurfew.evaluate(at(20, 0), Set.of(), 21L, 20L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinorCurfew.evaluate(at(20, 0), Set.of(), 20L, 25L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
