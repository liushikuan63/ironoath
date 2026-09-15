package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;

/**
 * 职责：配置热更运营日志的逐参数差异（上线检查清单 §二 12「概率不得暗改，变更要留痕」的判定依据）。
 * 依赖：真实的 {@link RawConfigTable}（不 mock 解析，否则测的就是夹具而不是口径）。
 *
 * <p><b>这一档防的形状很具体</b>：监管问的是"SSR 概率从多少改成了多少"，
 * 而表级"哪张表的 hash 变了"答不上这个问题。如果本类的字段级差异算错或漏算，
 * 日志会<b>照样打出来、看起来像留了痕</b> —— 那比没有日志更糟，因为它会让所有人以为这条闭环了。
 */
class ConfigChangeAuditTest {

    private static RawConfigTable table(String json) {
        JsonNode root = JsonUtils.readTree(json);
        return RawConfigTable.of(root.get("name").asText(), root);
    }

    private static Map<String, RawConfigTable> only(RawConfigTable t) {
        Map<String, RawConfigTable> out = new LinkedHashMap<>();
        out.put(t.name(), t);
        return out;
    }

    @Test
    @DisplayName("改了参数的值：报出「哪一行哪一个字段 从多少 → 到多少」，而不是只报这张表变了")
    void reportsFieldLevelChange() {
        RawConfigTable before = table("""
                {"name":"gacha","version":3,"rows":[{"id":"pool_basic","ssrRate":"12","cost":1}]}\
                """);
        RawConfigTable after = table("""
                {"name":"gacha","version":3,"rows":[{"id":"pool_basic","ssrRate":"20","cost":1}]}\
                """);

        List<ConfigChangeAudit.TableChanges> diffs = ConfigChangeAudit.between(
                only(before), only(after), Map.of("gacha", "hash-aaaa"), Map.of("gacha", "hash-bbbb"));

        assertThat(diffs).hasSize(1);
        ConfigChangeAudit.TableChanges d = diffs.get(0);
        assertThat(d.changed()).containsExactly(
                new ConfigChangeAudit.FieldChange("pool_basic", "ssrRate", "\"12\"", "\"20\""));
        assertThat(d.addedRows()).isEmpty();
        assertThat(d.removedRows()).isEmpty();
        String line = d.format(20);
        assertThat(line).as("日志必须能直接答「从多少改到多少」：%s", line)
                .contains("pool_basic.ssrRate:\"12\"→\"20\"")
                .contains("变更处数=1")
                .contains("hash=hash-aaaa→hash-bbbb");
        assertThat(line).as("没超出 cap 时不该出现截断标记").doesNotContain("未列出");
    }

    @Test
    @DisplayName("新增与删除行分开报：它们不是「改了某个字段」")
    void addedAndRemovedRowsAreSeparate() {
        RawConfigTable before = table("""
                {"name":"item","version":6,"rows":[{"id":"i_old","count":1},{"id":"i_keep","count":2}]}\
                """);
        RawConfigTable after = table("""
                {"name":"item","version":6,"rows":[{"id":"i_keep","count":2},{"id":"i_new","count":3}]}\
                """);

        var d = ConfigChangeAudit.between(only(before), only(after),
                Map.of("item", "h1"), Map.of("item", "h2")).get(0);

        assertThat(d.addedRows()).containsExactly("i_new");
        assertThat(d.removedRows()).containsExactly("i_old");
        assertThat(d.changed()).isEmpty();
        assertThat(d.totalChanges()).as("增 + 删 = 两处，不是零处").isEqualTo(2);
        assertThat(d.format(20)).contains("新增 i_new").contains("删除 i_old");
    }

    @Test
    @DisplayName("什么都没改 ⇒ 一行都不打：审计日志里混进「没变的表」会让真改动被噪音埋掉")
    void unchangedTableProducesNothing() {
        RawConfigTable t = table("""
                {"name":"global","version":37,"rows":[{"id":"PERF_MIN_FPS","value":"50"}]}\
                """);

        assertThat(ConfigChangeAudit.between(only(t), only(t),
                Map.of("global", "same"), Map.of("global", "same"))).isEmpty();
    }

    @Test
    @DisplayName("行没动但 hash 动了（只改注释或版本号）仍然要留痕：那同样是「这张表被换了」")
    void hashOnlyChangeIsStillRecorded() {
        String rows = """
                {"name":"stage","version":2,"rows":[{"id":"s1","stars":3}]}""";
        String bumped = """
                {"name":"stage","version":3,"rows":[{"id":"s1","stars":3}]}""";

        var diffs = ConfigChangeAudit.between(only(table(rows)), only(table(bumped)),
                Map.of("stage", "h1"), Map.of("stage", "h2"));

        assertThat(diffs).as("字段级没有任何变化，但表确实被换了").hasSize(1);
        assertThat(diffs.get(0).changed()).isEmpty();
        assertThat(diffs.get(0).format(20)).contains("版本=2→3");
    }

    @Test
    @DisplayName("明细被截断时必须写出「另有几处未列出」，而总数永远在行首")
    void truncationIsAnnounced() {
        StringBuilder beforeRows = new StringBuilder();
        StringBuilder afterRows = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            beforeRows.append(i == 0 ? "" : ",").append("{\"id\":\"r").append(i).append("\",\"v\":").append(i).append("}");
            afterRows.append(i == 0 ? "" : ",").append("{\"id\":\"r").append(i).append("\",\"v\":").append(i + 1).append("}");
        }
        RawConfigTable before = table("{\"name\":\"unit\",\"version\":2,\"rows\":[" + beforeRows + "]}");
        RawConfigTable after = table("{\"name\":\"unit\",\"version\":2,\"rows\":[" + afterRows + "]}");

        var d = ConfigChangeAudit.between(only(before), only(after),
                Map.of("unit", "h1"), Map.of("unit", "h2")).get(0);

        assertThat(d.totalChanges()).isEqualTo(25);
        String line = d.format(20);
        assertThat(line).as("行首要给出真实总数，截断只能藏明细不能藏事实：%s", line).contains("变更处数=25");
        assertThat(line).contains("另有 5 处未列出");
    }

    @Test
    @DisplayName("缺字段与 null 都读成空：两者对玩家效果一样，分成两列只会让日志多出噪音")
    void missingAndNullValuesAreTheSame() {
        RawConfigTable withNull = table("""
                {"name":"hero","version":1,"rows":[{"id":"h1","skill":null}]}\
                """);
        RawConfigTable withoutField = table("""
                {"name":"hero","version":1,"rows":[{"id":"h1"}]}\
                """);

        assertThat(ConfigChangeAudit.between(only(withNull), only(withoutField),
                Map.of("hero", "h"), Map.of("hero", "h")))
                .as("null ↔ 缺字段 不算一次改动").isEmpty();

        var d = ConfigChangeAudit.between(only(withoutField),
                only(table("{\"name\":\"hero\",\"version\":1,\"rows\":[{\"id\":\"h1\",\"skill\":7}]}")),
                Map.of("hero", "h1"), Map.of("hero", "h2")).get(0);
        assertThat(d.changed().get(0).before()).as("原来没配 ⇒ 空串，日志里看得见是「从无到有」")
                .isEmpty();
    }
}
