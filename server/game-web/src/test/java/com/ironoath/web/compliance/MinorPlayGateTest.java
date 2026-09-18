package com.ironoath.web.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.web.pay.MinorPaymentPolicy;

/**
 * 职责：登录时段闸的三态与窗口（上线检查清单 §二 3 的服务端半边）。
 * 依赖：Spring 上下文 + 一个 `@Primary` 的年龄源桩（生产那条今天是 UNKNOWN）。
 *
 * <p><b>为什么用桩而不是造一个真未成年</b>：实名数据源还没接（外部条件），
 * 生产实现恒返回 null —— 而这条闸的三种回答必须分别验，所以年龄从桩里来。
 */
@SpringBootTest
@ActiveProfiles("test")
class MinorPlayGateTest {

    /** 桩：用例自己决定"这个账号是不是未成年"。 */
    static final AtomicReference<Boolean> MINOR = new AtomicReference<>(null);

    @TestConfiguration
    static class StubAgeSource {
        @Bean
        @Primary
        MinorPaymentPolicy stubMinorPolicy() {
            return playerId -> MINOR.get();
        }
    }

    /** 2026-09-18（周五，平常日）UTC+8 的整点。 */
    private static long at(int hour) {
        return ZonedDateTime.of(2026, 9, 18, hour, 0, 0, 0, DayKey.CALENDAR_ZONE)
                .toInstant().toEpochMilli();
    }

    @Autowired private MinorPlayGate gate;

    @AfterEach
    void reset() {
        MINOR.set(null);
    }

    @Test
    @DisplayName("年龄不知道 ⇒ 放行：把「不知道」折叠成「未成年」会把整服拦在门外")
    void unknownAgeIsAllowed() {
        MINOR.set(null);
        assertThatCode(() -> gate.requirePlayable("P-1", at(15))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("成年 ⇒ 任何时刻放行")
    void adultIsAllowed() {
        MINOR.set(Boolean.FALSE);
        assertThatCode(() -> gate.requirePlayable("P-1", at(3))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("确认未成年：平常日 15:00 拒绝（15013），且提示里带下次可玩时刻")
    void minorOutsideWindowIsRejectedWithANextTime() {
        MINOR.set(Boolean.TRUE);

        assertThatThrownBy(() -> gate.requirePlayable("P-1", at(15)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.errorCode()).isEqualTo(ErrorCode.MINOR_CURFEW);
                    assertThat(biz.detail()).as("要给玩家一个能照着做的时间，而不是只说不行")
                            .contains("下次可玩：").contains("20:00");
                });
    }

    @Test
    @DisplayName("确认未成年：窗口内 20:30 放行")
    void minorInsideWindowIsAllowed() {
        MINOR.set(Boolean.TRUE);
        assertThatCode(() -> gate.requirePlayable("P-1", at(20) + 30 * 60_000L)).doesNotThrowAnyException();
    }
}
