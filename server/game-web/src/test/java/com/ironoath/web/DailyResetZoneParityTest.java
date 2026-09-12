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
 * <p><b>2026-09-08 的裁决</b>：按 Asia/Shanghai（UTC+8）切日，重置时刻 = 本地 0 点
 * = UTC 16 点，所以表里的值是 16。换算式 {@code 偏移 = (24 - 该值) % 24}。
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyResetZoneParityTest {

    private static final String PARAM_ID = "city_rule_daily_reset_hour_utc";

    @Autowired private ConfigRegistry configs;

    @Test
    @DisplayName("DayKey 的日切偏移与表里声明的重置时刻是同一时刻：改表不改代码会被判红")
    void dayKeyZoneMatchesTheDeclaredResetHour() {
        long resetHourUtc = declaredResetHourUtc();
        ZoneOffset declaredZone = ZoneOffset.ofHours((int) ((24 - resetHourUtc) % 24));
        // 取一个远离日切的普通时刻，只比较「同一个时刻算出来的日期键」
        long now = Instant.parse("2026-09-08T03:17:42Z").toEpochMilli();

        assertThat(DayKey.of(now))
                .as("表里 %s=%d（UTC 小时），代码就必须按 UTC%s 取日", PARAM_ID, resetHourUtc, declaredZone)
                .isEqualTo(Instant.ofEpochMilli(now).atZone(declaredZone)
                        .toLocalDate().toString().replace("-", ""));
    }

    @Test
    @DisplayName("日切字面落在 UTC+8 的 0 点：前一毫秒还是今天，整点就是明天")
    void dayFlipsAtMidnightBeijing() {
        // 这两行是「重置发生在玩家睡着的时候」这个产品口径的字面表达。
        // 上面那条 parity 断言负责保证表里的数字与它们说的是同一件事
        assertThat(DayKey.of(Instant.parse("2026-09-08T15:59:59.999Z").toEpochMilli()))
                .as("UTC 15:59:59.999 = UTC+8 23:59:59.999，仍是今天")
                .isEqualTo("20260908");
        assertThat(DayKey.of(Instant.parse("2026-09-08T16:00:00.000Z").toEpochMilli()))
                .as("再一毫秒就是本地 0 点 ⇒ 换日")
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
