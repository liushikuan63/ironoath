package com.ironoath.web.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import org.junit.jupiter.api.Test;

/** 用真实 season 表证明王城窗口终点；只为夹具注入赛季锚点，不另抄阶段天数。 */
class CapitalWarWindowTest {

    private static final Path CONFIG = locateConfigDir();
    private static final long START = DayKey.startOfDayPlusDays(1_760_000_000_000L, 0);

    @Test
    void capitalWarEndsAtTheSeasonTablesExclusiveDay42Boundary() throws Exception {
        CapitalWarWindow window = enabledWindow();
        long end = DayKey.startOfDayPlusDays(START, 42);
        assertThat(window.currentEndAt(DayKey.startOfDayPlusDays(START, 29) + 1L))
                .isEqualTo(end);
        assertThat(window.openAt(DayKey.startOfDayPlusDays(START, 29) + 1L)).isTrue();
        assertThat(window.currentEndAt(end - 1L)).isEqualTo(end);
        assertThat(window.openAt(end)).isFalse();
        assertThatThrownBy(() -> window.currentEndAt(end))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.WAR_CAPITAL_WINDOW_CLOSED));
        assertThatThrownBy(() -> window.currentEndAt(DayKey.startOfDayPlusDays(START, 10)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.WAR_CAPITAL_WINDOW_CLOSED));
    }

    @Test
    void disabledSeasonKeepsDevelopmentWarFixtureWithoutInventingAnEnd() {
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(CONFIG);
        CapitalWarWindow window = new CapitalWarWindow(registry, new SeasonRulesAssembler(registry));
        assertThat(window.currentEndAt(START + 30L * 86_400_000L)).isNull();
        assertThat(window.openAt(START + 30L * 86_400_000L)).isTrue();
    }

    private static CapitalWarWindow enabledWindow() throws Exception {
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(CONFIG);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode global = (ObjectNode) mapper.readTree(Files.readString(CONFIG.resolve("global.json")));
        ArrayNode rows = (ArrayNode) global.get("rows");
        ObjectNode row = mapper.createObjectNode();
        row.put("id", "SEASON_START_AT");
        row.put("valueType", "LONG");
        row.put("value", START);
        row.put("unit", "毫秒时间戳");
        row.put("source", "B14 §一 测试注入");
        row.put("why", "只在本测试里启用赛季时间轴");
        rows.add(row);
        registry.reload("global", GlobalCfg.class, mapper.writeValueAsString(global));
        return new CapitalWarWindow(registry, new SeasonRulesAssembler(registry));
    }

    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isRegularFile(candidate.resolve("global.json"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }
}
