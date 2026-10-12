package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;

/**
 * 职责：钉住「日切时刻」在配置表与代码里是同一个值（B03 §3 / B12 §五）。
 * 依赖：Spring Boot Test（只为拿到已加载的 ConfigRegistry）；test profile。
 *
 * <p><b>为什么必须有这条断言</b>：{@code DayKey} 在 game-common，读不到配置表，
 * 所以 {@code city_rule_daily_reset_hour_utc} 只是一个<b>声明值</b>，改它并不会真的挪动重置时刻。
 * 表说一套、代码做一套比两边都没定更糟：运营会以为改表就能把日切调到凌晨 5 点，
 * 而玩家看到的行为一动不动，且没有任何测试会红。
 *
 * <p>2026-10-13 用户裁决：北京时间04:00切日，时区仍为UTC+8；表声明UTC20:00。
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyResetZoneParityTest {

    private static final String PARAM_ID = "city_rule_daily_reset_hour_utc";

    @Autowired private ConfigRegistry configs;

    @Test
    @DisplayName("DayKey 的北京时间04:00与表声明的UTC20:00是同一切点")
    void dayKeyZoneMatchesTheDeclaredResetHour() {
        long resetHourUtc = declaredResetHourUtc();
        assertThat(DayKey.CALENDAR_ZONE).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(Math.floorMod(DayKey.RESET_HOUR - DayKey.CALENDAR_ZONE.getTotalSeconds() / 3600, 24))
                .as("%s声明的UTC切点必须与代码相同", PARAM_ID)
                .isEqualTo((int) resetHourUtc);
    }

    @Test
    @DisplayName("北京时间04:00换业务日，午夜不重置")
    void dayFlipsAtFourBeijing() {
        assertThat(DayKey.of(Instant.parse("2026-09-08T16:00:00Z").toEpochMilli()))
                .as("北京时间次日午夜仍属前一业务日")
                .isEqualTo("20260908");
        assertThat(DayKey.of(Instant.parse("2026-09-08T19:59:59.999Z").toEpochMilli()))
                .isEqualTo("20260908");
        assertThat(DayKey.of(Instant.parse("2026-09-08T20:00:00.000Z").toEpochMilli()))
                .as("北京时间04:00进入新业务日")
                .isEqualTo("20260909");
    }

    /** city_rule 与 global 结构相同（键值型），但没有按 id 取行的公开入口，这里按表扫一遍。 */
    private long declaredResetHourUtc() {
        List<JsonNode> rows = configs.rawTable("city_rule").rows();
        for (JsonNode row : rows) {
            GlobalCfg cfg = GlobalCfg.from(row);
            if (PARAM_ID.equals(cfg.id())) {
                return cfg.asLong();
            }
        }
        throw new IllegalStateException("city_rule 表里必须存在 " + PARAM_ID
                + "：它是日限次重置时刻的唯一声明处");
    }
}
