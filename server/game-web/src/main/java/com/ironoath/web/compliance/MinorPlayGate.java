package com.ironoath.web.compliance;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.HolidayCfg;
import com.ironoath.core.policy.MinorCurfew;
import com.ironoath.web.pay.MinorPaymentPolicy;

/**
 * 职责：登录时的未成年时段校验（B15 §合规、上线检查清单 §二 3）。
 * 依赖：`configs`（窗口两端 + 节假日表）、`MinorPaymentPolicy`（年龄三态）、`MinorCurfew`（规则本体）。
 *
 * <p><b>为什么复用支付那条年龄源</b>：它回答的是同一个事实 ——"这个账号是不是未成年"；
 * 支付问它是为了限额、游玩问它是为了时段。**两处必须同源**：以后接实名时只换一个实现，
 * 而不是"支付认得未成年、防沉迷不认得"。
 *
 * <p><b>三态照抄支付的纪律</b>：不知道 ⇒ 放行 + WARN（合规项没生效不是一条安静的默认值）、
 * 成年 ⇒ 放行、确认未成年 ⇒ 按窗口判。把"不知道"折叠成"未成年"会把整服拦在门外。
 *
 * <p><b>时间从参数进来</b>：登录那头传 `timeService.serverNow()`，用例里传固定时刻 ——
 * 否则这个类的用例就得"跑在 20:30 才绿"，而那种用例没人会去跑。
 */
@Component
public class MinorPlayGate {

    private static final Logger LOG = LoggerFactory.getLogger(MinorPlayGate.class);

    private static final DateTimeFormatter FRIENDLY = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ConfigRegistry configs;
    private final MinorPaymentPolicy ageSource;

    public MinorPlayGate(ConfigRegistry configs, MinorPaymentPolicy ageSource) {
        this.configs = configs;
        this.ageSource = ageSource;
    }

    /**
     * 未成年且不在可玩窗口时抛 {@link ErrorCode#MINOR_CURFEW}。
     *
     * @param now 服务端时刻（由调用方注入，便于用例固定时刻）
     */
    public void requirePlayable(String playerId, long now) {
        Boolean minor = ageSource.minorFlagOf(playerId);
        if (minor == null) {
            LOG.warn("未成年时长限制未生效：账号 {} 拿不到年龄（实名认证未接入），本次放行。"
                    + "接上实名之前，这条合规项只在测试里成立", playerId);
            return;
        }
        if (!minor) {
            return;
        }
        Set<String> holidays = new LinkedHashSet<>();
        for (HolidayCfg row : configs.all(HolidayCfg.class)) {
            holidays.add(row.id());
        }
        MinorCurfew.Verdict verdict = MinorCurfew.evaluate(now, holidays,
                configs.longParam("MINOR_PLAY_WINDOW_START_HOUR"),
                configs.longParam("MINOR_PLAY_WINDOW_END_HOUR"));
        if (verdict.allowed()) {
            return;
        }
        String next = ZonedDateTime.ofInstant(Instant.ofEpochMilli(verdict.nextAllowedAt()), DayKey.CALENDAR_ZONE)
                .format(FRIENDLY);
        throw new BizException(ErrorCode.MINOR_CURFEW, verdict.reason() + "，下次可玩：" + next);
    }
}
