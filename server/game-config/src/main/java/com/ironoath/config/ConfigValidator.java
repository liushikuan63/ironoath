package com.ironoath.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 职责：配置表全量校验器 —— 声明式规则驱动，<b>一次跑完收集全部错误</b>。
 * 依赖：game-common 的 JsonUtils（纯 Java，零框架）。
 *
 * <p>为什么自研而不用 Jakarta Validation（B01 开放问题 2 的决定）：
 * Jakarta Validation 默认 fail-fast，要聚合全部错误得额外写 ExecutableValidator + 反射遍历，
 * 且报错信息是「约束注解名」而非「第几行哪个字段为什么错」。配置表的消费者是策划，
 * 报错必须能直接照着改，所以这里用声明式规则 + 全量收集。
 *
 * <h2>规则语法（写在每张表的 {@code fieldTypes} 里）</h2>
 * <pre>
 *   STRING            必填非空字符串
 *   STRING_KEY        必填主键，只允许 [A-Za-z0-9_]
 *   LONG              必填整数（JSON number，禁止写成小数）
 *   LONG_NONNEG       必填非负整数
 *   LONG_POS          必填正整数
 *   DECIMAL           必填小数，<b>必须写成十进制字符串</b>（如 "1.18"），最多 4 位小数
 *   DECIMAL_NONNEG    同上且非负
 *   BOOL              必填布尔
 *   ENUM:A,B,C        必填字符串，取值限定在集合内
 *   REF:tableName     必填字符串，且必须存在于 tableName 表的 id 中（跨表校验）
 *   AUTO:fieldName    类型由同一行的 fieldName 决定（global 表的 value/valueType 用）
 *   JSON              任意结构，只校验存在性
 *   前缀 ?            表示可选，例如 "?STRING"
 * </pre>
 *
 * <p>DECIMAL 强制字符串是刻意的：JSON number 是 IEEE-754 double，写 {@code 1.18} 实际存的是
 * 1.1799999999999999378…，直接违反铁律 5「禁止 float/double 参与结算」。在源头堵住比事后补救便宜。
 */
public final class ConfigValidator {

    /** 定点数最多支持的小数位数，与 FixedPoint.SCALE = 10000 对应。 */
    private static final int MAX_DECIMAL_SCALE = 4;

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    /** 表信封的保留字段，不参与「未声明字段」检查。 */
    private static final Set<String> ENVELOPE_FIELDS = Set.of("table", "version", "comment", "fieldTypes", "rows");

    private ConfigValidator() {
    }

    /** 一条校验错误。location 形如 {@code resource#WOOD.basePerHour}。 */
    public record Issue(String location, String message) {
        @Override
        public String toString() {
            return "[" + location + "] " + message;
        }
    }

    /**
     * 校验单张表（不含跨表 REF）。
     *
     * @param tableName 期望的表名，必须与 JSON 中的 table 字段一致（也即文件名去后缀）
     * @param root      表 JSON 根节点
     * @return 全部错误项；空列表表示通过
     */
    public static List<Issue> validateTable(String tableName, JsonNode root) {
        List<Issue> issues = new ArrayList<>();

        if (root == null || !root.isObject()) {
            issues.add(new Issue(tableName, "表根节点必须是 JSON 对象"));
            return issues;
        }

        // ---------- 信封字段 ----------
        JsonNode tableNode = root.get("table");
        if (tableNode == null || !tableNode.isTextual()) {
            issues.add(new Issue(tableName + ".table", "缺失或非字符串：必须声明 table 字段且等于文件名"));
        } else if (!tableNode.asText().equals(tableName)) {
            issues.add(new Issue(tableName + ".table",
                    "表名不一致：文件为 " + tableName + "，JSON 中声明为 " + tableNode.asText()));
        }

        JsonNode versionNode = root.get("version");
        if (versionNode == null) {
            issues.add(new Issue(tableName + ".version", "缺失：配置表必须带 version 字段（热更与回滚依据）"));
        } else if (!versionNode.isIntegralNumber()) {
            issues.add(new Issue(tableName + ".version", "必须是整数，实际=" + versionNode));
        } else if (versionNode.asLong() < 1L) {
            issues.add(new Issue(tableName + ".version", "必须 >= 1，实际=" + versionNode.asLong()));
        }

        JsonNode commentNode = root.get("comment");
        if (commentNode != null && !commentNode.isTextual()) {
            issues.add(new Issue(tableName + ".comment", "必须是字符串"));
        }

        JsonNode fieldTypesNode = root.get("fieldTypes");
        if (fieldTypesNode == null || !fieldTypesNode.isObject() || fieldTypesNode.isEmpty()) {
            issues.add(new Issue(tableName + ".fieldTypes",
                    "缺失或为空：每张表必须声明字段规则，否则无法校验（见 ConfigValidator 规则语法）"));
            return issues;
        }

        JsonNode rowsNode = root.get("rows");
        if (rowsNode == null || !rowsNode.isArray()) {
            issues.add(new Issue(tableName + ".rows", "缺失或非数组"));
            return issues;
        }
        // 预留表（status=RESERVED）只定稿结构、数据由后续批次填充，允许空 rows。
        // 不给这条豁免的话，预留表就必须塞假数据 —— 而假数据迟早会被当成真数据用。
        boolean reserved = root.hasNonNull("status") && "RESERVED".equals(root.get("status").asText());
        if (rowsNode.isEmpty() && !reserved) {
            issues.add(new Issue(tableName + ".rows",
                    "不得为空数组：空表通常意味着导出脚本没跑成功。若确实是预留表，请声明 \"status\": \"RESERVED\""));
            return issues;
        }

        Map<String, Rule> rules = parseRules(tableName, fieldTypesNode, issues);

        // ---------- 逐行校验 ----------
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < rowsNode.size(); i++) {
            JsonNode row = rowsNode.get(i);
            if (!row.isObject()) {
                issues.add(new Issue(tableName + "[" + i + "]", "行必须是 JSON 对象"));
                continue;
            }
            String rowId = extractRowId(tableName, i, row, seenIds, issues);
            String locationPrefix = rowId == null ? tableName + "[" + i + "]" : tableName + "#" + rowId;

            validateRowAgainstRules(locationPrefix, row, rules, issues);
            validateNoUndeclaredFields(locationPrefix, row, rules, issues);
        }
        return issues;
    }

    /**
     * 跨表 REF 校验：必须在所有表都解析完成后调用。
     *
     * @param tables 表名 → 表内容
     * @return 全部错误项
     */
    public static List<Issue> validateReferences(Map<String, JsonNode> tables) {
        List<Issue> issues = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            JsonNode root = entry.getValue();
            JsonNode fieldTypesNode = root.get("fieldTypes");
            JsonNode rowsNode = root.get("rows");
            if (fieldTypesNode == null || rowsNode == null || !rowsNode.isArray()) {
                continue;   // 结构性错误已由 validateTable 报出，这里不重复
            }
            List<String[]> refs = collectRefRules(fieldTypesNode);
            if (refs.isEmpty()) {
                continue;
            }
            for (int i = 0; i < rowsNode.size(); i++) {
                JsonNode row = rowsNode.get(i);
                if (!row.isObject()) {
                    continue;
                }
                String rowId = row.hasNonNull("id") ? row.get("id").asText() : "[" + i + "]";
                for (String[] ref : refs) {
                    String field = ref[0];
                    String targetTable = ref[1];
                    JsonNode value = row.get(field);
                    if (value == null || value.isNull()) {
                        continue;   // 缺失/可选由 validateTable 负责
                    }
                    if (!value.isTextual()) {
                        continue;
                    }
                    if (!tableContainsId(tables.get(targetTable), value.asText())) {
                        issues.add(new Issue(tableName + "#" + rowId + "." + field,
                                "外键不存在：" + targetTable + " 表中找不到 id=" + value.asText()));
                    }
                }
            }
        }
        return issues;
    }

    // ---------- 内部：规则解析 ----------

    private record Rule(boolean optional, String kind, String arg) {
        boolean isRef() {
            return "REF".equals(kind);
        }
    }

    private static Map<String, Rule> parseRules(String tableName, JsonNode fieldTypesNode, List<Issue> issues) {
        Map<String, Rule> rules = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = fieldTypesNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String field = e.getKey();
            JsonNode specNode = e.getValue();
            if (!specNode.isTextual()) {
                issues.add(new Issue(tableName + ".fieldTypes." + field, "字段规则必须是字符串，实际=" + specNode));
                continue;
            }
            Rule rule = parseRule(tableName + ".fieldTypes." + field, specNode.asText(), issues);
            if (rule != null) {
                rules.put(field, rule);
            }
        }
        if (!rules.containsKey("id")) {
            issues.add(new Issue(tableName + ".fieldTypes.id", "必须声明 id 字段规则（通常为 STRING_KEY）"));
        }
        return rules;
    }

    private static final Set<String> KNOWN_KINDS = Set.of(
            "STRING", "STRING_KEY", "LONG", "LONG_NONNEG", "LONG_POS",
            "DECIMAL", "DECIMAL_NONNEG", "BOOL", "ENUM", "REF", "AUTO", "JSON");

    private static Rule parseRule(String location, String spec, List<Issue> issues) {
        String s = spec.trim();
        if (s.isEmpty()) {
            issues.add(new Issue(location, "字段规则不得为空"));
            return null;
        }
        boolean optional = false;
        if (s.startsWith("?")) {
            optional = true;
            s = s.substring(1).trim();
        }
        String kind = s;
        String arg = null;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            kind = s.substring(0, colon).trim();
            arg = s.substring(colon + 1).trim();
        }
        if (!KNOWN_KINDS.contains(kind)) {
            issues.add(new Issue(location, "未知的字段规则类型: " + kind + "（合法取值见 ConfigValidator 类注释）"));
            return null;
        }
        if (("ENUM".equals(kind) || "REF".equals(kind) || "AUTO".equals(kind)) && (arg == null || arg.isEmpty())) {
            issues.add(new Issue(location, kind + " 规则必须带参数，例如 ENUM:A,B / REF:resource / AUTO:valueType"));
            return null;
        }
        if ("ENUM".equals(kind)) {
            Set<String> values = new LinkedHashSet<>();
            for (String v : arg.split(",")) {
                if (!v.isBlank()) {
                    values.add(v.trim());
                }
            }
            if (values.isEmpty()) {
                issues.add(new Issue(location, "ENUM 规则至少需要一个可选值"));
                return null;
            }
            return new Rule(optional, kind, String.join(",", values));
        }
        return new Rule(optional, kind, arg);
    }

    // ---------- 内部：逐行校验 ----------

    private static String extractRowId(String tableName, int index, JsonNode row,
                                       Set<String> seenIds, List<Issue> issues) {
        JsonNode idNode = row.get("id");
        if (idNode == null || idNode.isNull()) {
            issues.add(new Issue(tableName + "[" + index + "].id", "缺失：每行必须有 id 主键"));
            return null;
        }
        if (!idNode.isTextual()) {
            issues.add(new Issue(tableName + "[" + index + "].id", "必须是字符串，实际=" + idNode));
            return null;
        }
        String id = idNode.asText();
        if (id.isBlank()) {
            issues.add(new Issue(tableName + "[" + index + "].id", "不得为空字符串"));
            return null;
        }
        if (!KEY_PATTERN.matcher(id).matches()) {
            issues.add(new Issue(tableName + "#" + id + ".id",
                    "主键只允许字母、数字与下划线，实际=" + id));
        }
        if (!seenIds.add(id)) {
            issues.add(new Issue(tableName + "#" + id + ".id", "主键重复：同一张表内 id 必须唯一"));
        }
        return id;
    }

    private static void validateRowAgainstRules(String prefix, JsonNode row, Map<String, Rule> rules,
                                                List<Issue> issues) {
        for (Map.Entry<String, Rule> e : rules.entrySet()) {
            String field = e.getKey();
            // id 已由 extractRowId 专门校验（缺失/非字符串/空串/字符集/重复）。
            // 这里再按规则校验一次会把同一个问题报两条，让策划以为是两个错误
            if ("id".equals(field)) {
                continue;
            }
            Rule rule = e.getValue();
            JsonNode value = row.get(field);
            String location = prefix + "." + field;

            if (value == null || value.isNull()) {
                if (!rule.optional()) {
                    issues.add(new Issue(location, "缺失：该字段为必填（规则 " + describe(rule) + "）"));
                }
                continue;
            }
            checkType(location, value, rule, row, issues);
        }
    }

    private static String describe(Rule rule) {
        return (rule.optional() ? "?" : "") + rule.kind() + (rule.arg() == null ? "" : ":" + rule.arg());
    }

    private static void checkType(String location, JsonNode value, Rule rule,
                                  JsonNode row, List<Issue> issues) {
        switch (rule.kind()) {
            case "STRING" -> {
                if (!value.isTextual()) {
                    issues.add(new Issue(location, "必须是字符串，实际=" + value));
                } else if (value.asText().isBlank()) {
                    issues.add(new Issue(location, "字符串不得为空或全空白"));
                }
            }
            case "STRING_KEY" -> {
                if (!value.isTextual()) {
                    issues.add(new Issue(location, "必须是字符串，实际=" + value));
                } else if (!KEY_PATTERN.matcher(value.asText()).matches()) {
                    issues.add(new Issue(location,
                            "只允许字母、数字与下划线（作为代码生成标识符使用），实际=" + value.asText()));
                }
            }
            case "LONG", "LONG_NONNEG", "LONG_POS" -> {
                if (!value.isNumber()) {
                    issues.add(new Issue(location, "必须是整数（JSON number），实际=" + value));
                } else if (!value.isIntegralNumber()) {
                    issues.add(new Issue(location,
                            "整数字段不得写成小数，实际=" + value + "；若确需小数请改用 DECIMAL 规则并写成字符串"));
                } else {
                    long v = value.asLong();
                    if ("LONG_NONNEG".equals(rule.kind()) && v < 0L) {
                        issues.add(new Issue(location, "必须 >= 0，实际=" + v));
                    }
                    if ("LONG_POS".equals(rule.kind()) && v <= 0L) {
                        issues.add(new Issue(location, "必须 > 0，实际=" + v));
                    }
                }
            }
            case "DECIMAL", "DECIMAL_NONNEG" -> checkDecimal(location, value, "DECIMAL_NONNEG".equals(rule.kind()), issues);
            case "BOOL" -> {
                if (!value.isBoolean()) {
                    issues.add(new Issue(location, "必须是布尔值（true/false），实际=" + value));
                }
            }
            case "ENUM" -> {
                Set<String> allowed = Set.of(rule.arg().split(","));
                if (!value.isTextual()) {
                    issues.add(new Issue(location, "必须是字符串，实际=" + value));
                } else if (!allowed.contains(value.asText())) {
                    issues.add(new Issue(location,
                            "取值非法：" + value.asText() + "，允许值=" + allowed));
                }
            }
            case "REF" -> {
                if (!value.isTextual()) {
                    issues.add(new Issue(location, "外键必须是字符串，实际=" + value));
                }
                // 存在性由 validateReferences 跨表检查
            }
            case "AUTO" -> checkAutoType(location, value, rule, row, issues);
            case "JSON" -> {
                // 任意结构，只校验存在性
            }
            default -> issues.add(new Issue(location, "内部错误：未实现的规则类型 " + rule.kind()));
        }
    }

    private static void checkDecimal(String location, JsonNode value, boolean nonNegative, List<Issue> issues) {
        if (!value.isTextual()) {
            issues.add(new Issue(location,
                    "小数字段必须写成十进制字符串（如 \"1.18\"），实际=" + value
                            + "。JSON number 是 double，会引入二进制误差，违反「禁止 float/double 参与结算」"));
            return;
        }
        String text = value.asText().trim();
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException e) {
            issues.add(new Issue(location, "不是合法的十进制数：\"" + text + "\""));
            return;
        }
        if (decimal.stripTrailingZeros().scale() > MAX_DECIMAL_SCALE) {
            issues.add(new Issue(location,
                    "小数位数超过定点精度上限 " + MAX_DECIMAL_SCALE + " 位，实际=\"" + text + "\""));
        }
        if (nonNegative && decimal.signum() < 0) {
            issues.add(new Issue(location, "必须 >= 0，实际=\"" + text + "\""));
        }
    }

    /** AUTO 规则：值类型由同一行的另一个字段（如 valueType）决定。 */
    private static void checkAutoType(String location, JsonNode value, Rule rule,
                                      JsonNode row, List<Issue> issues) {
        String typeField = rule.arg();
        JsonNode typeNode = row.get(typeField);
        if (typeNode == null || !typeNode.isTextual()) {
            issues.add(new Issue(location,
                    "无法确定类型：同行的 " + typeField + " 字段缺失或非字符串"));
            return;
        }
        switch (typeNode.asText()) {
            case "LONG" -> {
                if (!value.isIntegralNumber()) {
                    issues.add(new Issue(location,
                            typeField + "=LONG 时 value 必须是整数，实际=" + value));
                }
            }
            case "DECIMAL" -> checkDecimal(location, value, false, issues);
            case "BOOL" -> {
                if (!value.isBoolean()) {
                    issues.add(new Issue(location, typeField + "=BOOL 时 value 必须是布尔值，实际=" + value));
                }
            }
            case "STRING" -> {
                if (!value.isTextual()) {
                    issues.add(new Issue(location, typeField + "=STRING 时 value 必须是字符串，实际=" + value));
                } else if (value.asText().isBlank()) {
                    issues.add(new Issue(location, typeField + "=STRING 时 value 不得为空"));
                }
            }
            default -> issues.add(new Issue(prefixOf(location) + "." + typeField,
                    "未知的 valueType: " + typeNode.asText() + "，允许值=[LONG, DECIMAL, BOOL, STRING]"));
        }
    }

    private static String prefixOf(String location) {
        int dot = location.lastIndexOf('.');
        return dot < 0 ? location : location.substring(0, dot);
    }

    private static void validateNoUndeclaredFields(String prefix, JsonNode row, Map<String, Rule> rules,
                                                   List<Issue> issues) {
        Iterator<String> names = row.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (ENVELOPE_FIELDS.contains(name)) {
                continue;
            }
            if (!rules.containsKey(name)) {
                issues.add(new Issue(prefix + "." + name,
                        "未在 fieldTypes 中声明的字段：疑似拼写错误或忘记同步表结构声明"));
            }
        }
    }

    private static List<String[]> collectRefRules(JsonNode fieldTypesNode) {
        List<String[]> refs = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = fieldTypesNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) {
                continue;
            }
            String spec = e.getValue().asText().trim();
            if (spec.startsWith("?")) {
                spec = spec.substring(1).trim();
            }
            if (spec.startsWith("REF:")) {
                refs.add(new String[]{e.getKey(), spec.substring(4).trim()});
            }
        }
        return refs;
    }

    private static boolean tableContainsId(JsonNode tableRoot, String id) {
        if (tableRoot == null) {
            return false;
        }
        JsonNode rows = tableRoot.get("rows");
        if (rows == null || !rows.isArray()) {
            return false;
        }
        for (JsonNode row : rows) {
            if (row.hasNonNull("id") && row.get("id").asText().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 把错误项格式化成可直接打进启动日志的多行文本。 */
    public static String render(List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (Issue issue : issues) {
            sb.append("  - ").append(issue).append('\n');
        }
        return sb.toString();
    }

    /** 供上层聚合使用：把 JSON 文本解析为 JsonNode，解析失败也作为一条错误返回。 */
    public static JsonNode parseOrCollect(String tableName, String json, List<Issue> issues) {
        try {
            return JsonUtils.readTree(json);
        } catch (RuntimeException e) {
            issues.add(new Issue(tableName, "JSON 解析失败：" + rootCauseMessage(e)));
            return null;
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
