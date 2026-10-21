package com.ironoath.web.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;

/** 登录时段拒绝必须在生产剥离 detail 后仍告诉玩家下一次可玩时刻。 */
class MinorCurfewResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void curfewTimeSurvivesProductionDetailRedaction() {
        String next = "当前不在可玩时段，下次可玩：2026-10-21 20:00";
        Result<Void> response = handler.handleBiz(new BizException(ErrorCode.MINOR_CURFEW, next))
                .withoutDetail();

        assertThat(response.code()).isEqualTo(15013);
        assertThat(response.msg()).isEqualTo(next);
        assertThat(response.detail()).isNull();
    }

    @Test
    void otherBusinessErrorsKeepTheirFixedPublicMessage() {
        Result<Void> response = handler.handleBiz(new BizException(ErrorCode.PARAM_INVALID,
                "内部字段名 playerSecret" )).withoutDetail();

        assertThat(response.msg()).isEqualTo(ErrorCode.PARAM_INVALID.msg());
        assertThat(response.msg()).doesNotContain("playerSecret");
        assertThat(response.detail()).isNull();
    }
}
