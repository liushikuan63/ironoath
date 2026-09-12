package com.ironoath.config;

import com.ironoath.common.json.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：配置校验器单测 —— 覆盖 B01 验收 6（改坏配置必须一次性报出全部错误字段）。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器、不读真实文件（用内存 JSON 字符串）。
 */
class ConfigValidatorTest {

    /** 一张合法的最小表，作为各个破坏性用例的基线。 */
    private static final String VALID_TABLE = """
            {
              "table": "demo",
              "version": 1,
              "comment": "测试用表",
              "fieldTypes": {
                "id": "STRING_KEY",
                "name": "STRING",
                "level": "LONG_POS",
                "ratio": "DECIMAL_NONNEG",
                "kind": "ENUM:A,B,C",
                "enabled": "BOOL",
                "note": "?STRING"
              },
              "rows": [
                { "id": "ROW_1", "name": "第一行", "level": 3, "ratio": "1.18", "kind": "A", "enabled": true },
                { "id": "ROW_2", "name": "第二行", "level": 5, "ratio": "0.95", "kind": "B", "enabled": false, "note": "可选字段" }
              ]
            }
            """;

    private static List<ConfigValidator.Issue> validate(String json) {
        return ConfigValidator.validateTable("demo", JsonUtils.readTree(json));
    }

    private static List<String> messages(List<ConfigValidator.Issue> issues) {
        return issues.stream().map(ConfigValidator.Issue::toString).toList();
    }

    @Test
    @DisplayName("合法表校验通过，零错误")
    void validTablePasses() {
        assertThat(validate(VALID_TABLE)).isEmpty();
    }

    @Test
    @DisplayName("验收6：同时改坏多处（类型错 + 缺字段 + 枚举非法 + 小数写成 number），一次性报出全部")
    void reportsAllErrorsAtOnceInsteadOfFirstOnly() {
        String broken = """
                {
                  "table": "demo",
                  "version": 1,
                  "fieldTypes": {
                    "id": "STRING_KEY",
                    "name": "STRING",
                    "level": "LONG_POS",
                    "ratio": "DECIMAL_NONNEG",
                    "kind": "ENUM:A,B,C",
                    "enabled": "BOOL"
                  },
                  "rows": [
                    { "id": "ROW_1", "name": 12345, "level": 0, "ratio": 1.18, "kind": "D", "enabled": "yes" },
                    { "id": "ROW_2", "level": -3, "ratio": "1.000001", "kind": "A" },
                    { "id": "ROW_1", "name": "重复主键", "level": 1, "ratio": "1.0", "kind": "A", "enabled": true },
                    { "name": "缺主键", "level": 1, "ratio": "1.0", "kind": "A", "enabled": true },
                    { "id": "ROW_5", "name": "多余字段", "level": 1, "ratio": "1.0", "kind": "A", "enabled": true, "unexpected": 1 }
                  ]
                }
                """;

        List<ConfigValidator.Issue> issues = validate(broken);
        List<String> all = messages(issues);

        // 关键断言：不是只报第一条，而是全部报出
        assertThat(issues).hasSizeGreaterThanOrEqualTo(12);

        assertThat(all).anyMatch(s -> s.contains("demo#ROW_1.name") && s.contains("必须是字符串"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_1.level") && s.contains("必须 > 0"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_1.ratio") && s.contains("十进制字符串"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_1.kind") && s.contains("取值非法"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_1.enabled") && s.contains("布尔值"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_2.name") && s.contains("缺失"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_2.enabled") && s.contains("缺失"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_2.ratio") && s.contains("小数位数超过定点精度上限"));
        assertThat(all).anyMatch(s -> s.contains("主键重复"));
        assertThat(all).anyMatch(s -> s.contains("demo[3].id") && s.contains("缺失"));
        assertThat(all).anyMatch(s -> s.contains("demo#ROW_5.unexpected") && s.contains("未在 fieldTypes 中声明"));

        // 错误信息可直接打进启动日志，含定位与原因
        assertThat(ConfigValidator.render(issues)).contains("demo#ROW_1.name");
    }

    @Test
    @DisplayName("信封字段错误：表名不一致、version 缺失或非整数、rows 为空，均被拒绝")
    void envelopeErrorsAreRejected() {
        assertThat(messages(validate(VALID_TABLE.replace("\"table\": \"demo\"", "\"table\": \"other\""))))
                .anyMatch(s -> s.contains("表名不一致"));

        assertThat(messages(validate(VALID_TABLE.replace("\"version\": 1,", ""))))
                .anyMatch(s -> s.contains("version") && s.contains("缺失"));

        assertThat(messages(validate(VALID_TABLE.replace("\"version\": 1", "\"version\": 1.5"))))
                .anyMatch(s -> s.contains("version") && s.contains("必须是整数"));

        assertThat(messages(validate(VALID_TABLE.replace("\"version\": 1", "\"version\": 0"))))
                .anyMatch(s -> s.contains("必须 >= 1"));

        String emptyRows = VALID_TABLE.replaceAll("(?s)\"rows\": \\[.*]", "\"rows\": []\n}");
        assertThat(messages(validate(emptyRows)))
                .anyMatch(s -> s.contains("rows") && s.contains("不得为空数组"));

        assertThat(messages(validate(VALID_TABLE.replace("\"fieldTypes\"", "\"fieldType\""))))
                .anyMatch(s -> s.contains("fieldTypes") && s.contains("缺失或为空"));
    }

    @Test
    @DisplayName("整数字段写成小数、小数字段写成 JSON number，都被拒绝（守住定点数铁律）")
    void numericTypeConfusionIsRejected() {
        // LONG_POS 字段写成小数
        assertThat(messages(validate(VALID_TABLE.replace("\"level\": 3", "\"level\": 3.5"))))
                .anyMatch(s -> s.contains("整数字段不得写成小数"));

        // DECIMAL 字段写成 JSON number：这是最常见的「double 渗入结算」入口，必须堵住
        assertThat(messages(validate(VALID_TABLE.replace("\"ratio\": \"1.18\"", "\"ratio\": 1.18"))))
                .anyMatch(s -> s.contains("必须写成十进制字符串") && s.contains("违反"));
    }

    @Test
    @DisplayName("主键只允许字母数字下划线，因为它要参与代码生成")
    void keyCharsetIsEnforced() {
        assertThat(messages(validate(VALID_TABLE.replace("\"id\": \"ROW_1\"", "\"id\": \"ROW-1\""))))
                .anyMatch(s -> s.contains("只允许字母、数字与下划线"));
    }

    @Test
    @DisplayName("未知规则类型被拒绝，防止拼错规则名后校验静默失效")
    void unknownRuleKindIsRejected() {
        List<ConfigValidator.Issue> issues = ConfigValidator.validateTable("demo",
                JsonUtils.readTree(VALID_TABLE.replace("\"level\": \"LONG_POS\"", "\"level\": \"LONGG_POS\"")));
        assertThat(messages(issues)).anyMatch(s -> s.contains("未知的字段规则类型"));
    }

    @Test
    @DisplayName("跨表 REF 校验：外键不存在时报出具体表名与 id")
    void referenceValidationReportsMissingForeignKey() {
        String parent = """
                { "table": "resource", "version": 1,
                  "fieldTypes": { "id": "STRING_KEY" },
                  "rows": [ { "id": "WOOD" }, { "id": "IRON" } ] }
                """;
        String child = """
                { "table": "building", "version": 1,
                  "fieldTypes": { "id": "STRING_KEY", "costType": "REF:resource" },
                  "rows": [
                    { "id": "FARM", "costType": "WOOD" },
                    { "id": "MINE", "costType": "GOLD" }
                  ] }
                """;

        Map<String, com.fasterxml.jackson.databind.JsonNode> tables = Map.of(
                "resource", JsonUtils.readTree(parent),
                "building", JsonUtils.readTree(child));

        List<String> all = messages(ConfigValidator.validateReferences(tables));
        assertThat(all).anyMatch(s -> s.contains("building#MINE.costType")
                && s.contains("resource") && s.contains("GOLD"));
        assertThat(all).as("合法外键 WOOD 不应报错").noneMatch(s -> s.contains("FARM"));
    }

    @Test
    @DisplayName("AUTO 规则：value 的 JSON 类型必须与同行 valueType 匹配")
    void autoRuleChecksValueTypeConsistency() {
        String global = """
                { "table": "global", "version": 1,
                  "fieldTypes": {
                    "id": "STRING_KEY",
                    "valueType": "ENUM:LONG,DECIMAL,BOOL,STRING",
                    "value": "AUTO:valueType"
                  },
                  "rows": [
                    { "id": "OK_LONG", "valueType": "LONG", "value": 8 },
                    { "id": "OK_DECIMAL", "valueType": "DECIMAL", "value": "2.0" },
                    { "id": "BAD_LONG", "valueType": "LONG", "value": "8" },
                    { "id": "BAD_DECIMAL", "valueType": "DECIMAL", "value": 2.0 },
                    { "id": "BAD_TYPE", "valueType": "FLOAT", "value": 1.0 }
                  ] }
                """;
        List<String> all = messages(ConfigValidator.validateTable("global", JsonUtils.readTree(global)));

        assertThat(all).noneMatch(s -> s.contains("OK_LONG") || s.contains("OK_DECIMAL"));
        assertThat(all).anyMatch(s -> s.contains("BAD_LONG.value") && s.contains("必须是整数"));
        assertThat(all).anyMatch(s -> s.contains("BAD_DECIMAL.value") && s.contains("十进制字符串"));
        assertThat(all).anyMatch(s -> s.contains("BAD_TYPE.valueType") && s.contains("未知的 valueType"));
    }

    @Test
    @DisplayName("JSON 解析失败也作为一条错误返回，不抛异常中断整批校验")
    void parseFailureIsCollectedAsIssue() {
        List<ConfigValidator.Issue> issues = new java.util.ArrayList<>();
        var node = ConfigValidator.parseOrCollect("broken", "{ 这不是 JSON", issues);
        assertThat(node).isNull();
        assertThat(messages(issues)).anyMatch(s -> s.contains("broken") && s.contains("JSON 解析失败"));
    }
}
