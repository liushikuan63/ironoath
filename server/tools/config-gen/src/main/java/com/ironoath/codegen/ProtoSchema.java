package com.ironoath.codegen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把 contract/proto/*.schema.json 解析成生成器内部的中立模型。
 * 依赖：无（纯数据 + Jackson 节点读取）。
 *
 * <p>先解析成中立模型再分语言输出，是为了让 Java 与 TS 两份产物<b>必然来自同一次解析</b>：
 * 如果各自解析 Schema，两边的类型映射规则一旦有细微差别，CI 的一致性检查就形同虚设。
 */
public final class ProtoSchema {

    /** 一个字段的双端类型。 */
    public record Field(String name, String javaType, String tsType, String comment) {
    }

    /**
     * 字符串枚举 → Java enum + TS 字面量联合类型。
     *
     * @param sourceTable 若声明了 {@code x-enum-source.table}，则枚举取值必须与该配置表的 id 集合
     *                    完全一致，由生成器在 CI 中强制校验；null 表示无此约束
     */
    public record EnumDef(String name, List<String> values, String comment, String sourceTable) {
        public EnumDef {
            values = List.copyOf(values);
        }
    }

    /** 对象 → Java record + TS interface。 */
    public record ObjectDef(String name, List<Field> fields, String comment) {
        public ObjectDef {
            fields = List.copyOf(fields);
        }
    }

    /** 一份 Schema 文件的全部内容。 */
    public record Document(String sourceFile, String javaPackage, String tsModule,
                           List<EnumDef> enums, List<ObjectDef> objects) {
        public Document {
            enums = List.copyOf(enums);
            objects = List.copyOf(objects);
        }
    }

    private final Map<String, String> defKinds = new LinkedHashMap<>();

    private ProtoSchema() {
    }

    /** 解析一份 Schema。 */
    public static Document parse(String sourceFile, JsonNode root) {
        JsonNode defs = root.get("$defs");
        if (defs == null || !defs.isObject() || defs.isEmpty()) {
            throw new CodeGenException("Schema[" + sourceFile + "] 缺少 $defs，没有可生成的类型");
        }
        String javaPackage = text(root, "x-java-package",
                "Schema[" + sourceFile + "] 缺少 x-java-package，无法确定 Java 包名");
        String tsModule = text(root, "x-ts-module",
                "Schema[" + sourceFile + "] 缺少 x-ts-module，无法确定 TS 模块名");

        ProtoSchema schema = new ProtoSchema();
        // 第一遍：登记每个 def 是枚举还是对象，供第二遍解析 $ref 时判断引用目标
        Iterator<String> names = defs.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode def = defs.get(name);
            schema.defKinds.put(name, def.has("enum") ? "ENUM" : "OBJECT");
        }

        List<EnumDef> enums = new ArrayList<>();
        List<ObjectDef> objects = new ArrayList<>();
        // 第二遍：按声明顺序生成，保证输出字节稳定（CI 用 diff 校验同步性）
        Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode def = e.getValue();
            String comment = def.hasNonNull("description") ? def.get("description").asText() : "";
            if (def.has("enum")) {
                List<String> values = new ArrayList<>();
                for (JsonNode v : def.get("enum")) {
                    if (!v.isTextual()) {
                        throw new CodeGenException(
                                "Schema[" + sourceFile + "] 的枚举 " + name + " 含非字符串取值: " + v);
                    }
                    values.add(v.asText());
                }
                if (values.isEmpty()) {
                    throw new CodeGenException("Schema[" + sourceFile + "] 的枚举 " + name + " 没有取值");
                }
                JsonNode enumSource = def.get("x-enum-source");
                String sourceTable = (enumSource != null && enumSource.hasNonNull("table"))
                        ? enumSource.get("table").asText()
                        : null;
                enums.add(new EnumDef(name, values, comment, sourceTable));
            } else {
                objects.add(new ObjectDef(name, schema.parseFields(sourceFile, name, def), comment));
            }
        }
        return new Document(sourceFile, javaPackage, tsModule, enums, objects);
    }

    private List<Field> parseFields(String sourceFile, String defName, JsonNode def) {
        JsonNode props = def.get("properties");
        if (props == null || !props.isObject()) {
            throw new CodeGenException("Schema[" + sourceFile + "] 的对象 " + defName + " 缺少 properties");
        }
        JsonNode required = def.get("required");
        List<String> requiredNames = new ArrayList<>();
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                requiredNames.add(r.asText());
            }
        }

        List<Field> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String fieldName = e.getKey();
            JsonNode spec = e.getValue();
            boolean isRequired = requiredNames.contains(fieldName);
            String comment = spec.hasNonNull("description") ? spec.get("description").asText() : "";
            TypePair type = resolveType(sourceFile, defName + "." + fieldName, spec);
            // 非必填字段在 Java 侧用装箱类型表达可空，TS 侧加 | null
            if (!isRequired) {
                type = type.asNullable();
            }
            fields.add(new Field(fieldName, type.javaType(), type.tsType(), comment));
        }
        if (fields.isEmpty()) {
            throw new CodeGenException("Schema[" + sourceFile + "] 的对象 " + defName + " 没有任何字段");
        }
        return fields;
    }

    /** 双端类型对。 */
    private record TypePair(String javaType, String tsType) {
        TypePair asNullable() {
            String j = switch (javaType) {
                case "long" -> "Long";
                case "int" -> "Integer";
                case "boolean" -> "Boolean";
                case "double" -> "Double";
                default -> javaType;   // 引用类型本身可为 null
            };
            String t = (tsType.endsWith("| null") || "unknown".equals(tsType))
                    ? tsType
                    : tsType + " | null";
            return new TypePair(j, t);
        }
    }

    private TypePair resolveType(String sourceFile, String location, JsonNode spec) {
        // $ref 引用
        if (spec.has("$ref")) {
            String ref = spec.get("$ref").asText();
            String prefix = "#/$defs/";
            if (!ref.startsWith(prefix)) {
                throw new CodeGenException("Schema[" + sourceFile + "] " + location
                        + " 使用了不支持的 $ref 形式: " + ref + "（只支持 #/$defs/ 内部引用）");
            }
            String target = ref.substring(prefix.length());
            String kind = defKinds.get(target);
            if (kind == null) {
                throw new CodeGenException("Schema[" + sourceFile + "] " + location
                        + " 引用了未定义的 $defs/" + target);
            }
            TypePair pair = new TypePair(target, target);
            // JSON Schema 2020-12 允许 $ref 与 type 并存，
            // {"$ref": "#/$defs/X", "type": ["object","null"]} 就是「可空的引用」。
            // 忽略这个 type 的后果是静默的：Java record 字段变成非空、TS 类型不带 | null，
            // 于是服务端下发 null 时客户端拿到一个类型系统认为不可能出现的值 ——
            // 不报编译错，只在运行时炸，而且炸的位置离真正的原因很远。
            return declaresNull(spec) ? pair.asNullable() : pair;
        }

        // Map：object + propertyNames + additionalProperties
        if (spec.has("propertyNames") && spec.has("additionalProperties")
                && spec.get("additionalProperties").isObject()) {
            TypePair key = resolveType(sourceFile, location + ".key", spec.get("propertyNames"));
            TypePair value = resolveType(sourceFile, location + ".value", spec.get("additionalProperties"));
            return new TypePair("Map<" + key.javaType() + ", " + value.javaType() + ">",
                    "Record<" + key.tsType() + ", " + value.tsType() + ">");
        }

        // 可空联合类型：["integer", "null"]
        JsonNode typeNode = spec.get("type");
        if (typeNode != null && typeNode.isArray()) {
            List<String> types = new ArrayList<>();
            for (JsonNode t : typeNode) {
                types.add(t.asText());
            }
            boolean nullable = types.remove("null");
            if (types.size() != 1) {
                throw new CodeGenException("Schema[" + sourceFile + "] " + location
                        + " 的 type 数组只支持「单一类型 + null」，实际=" + types);
            }
            TypePair pair = resolvePrimitive(sourceFile, location, types.get(0), formatOf(spec));
            return nullable ? pair.asNullable() : pair;
        }

        if (typeNode == null) {
            // 无类型声明（如 ApiResult.data）：Java 用 Object，TS 用 unknown
            return new TypePair("Object", "unknown");
        }

        String type = typeNode.asText();
        if ("array".equals(type)) {
            JsonNode items = spec.get("items");
            if (items == null) {
                throw new CodeGenException("Schema[" + sourceFile + "] " + location + " 的数组缺少 items");
            }
            TypePair item = resolveType(sourceFile, location + "[]", items);
            // TS 里 `A | B[]` 会被解析成 `A | (B[])`，所以元素类型是联合时必须加括号。
            // items: {"type": ["string","null"]} 要生成 (string | null)[]；
            // 生成成 string | null[] 不会报编译错（它是「一个字符串，或一个全是 null 的数组」），
            // 只会在调用方写 equips.map(...) 时才炸 —— 属于最难发现的一类生成器 bug。
            // Java 侧没有这个问题：List<String> 本来就能装 null。
            String itemTs = item.tsType();
            String arrayTs = itemTs.contains("|") ? "(" + itemTs + ")[]" : itemTs + "[]";
            return new TypePair("List<" + item.javaType() + ">", arrayTs);
        }
        return resolvePrimitive(sourceFile, location, type, formatOf(spec));
    }

    private static String formatOf(JsonNode spec) {
        return spec.hasNonNull("format") ? spec.get("format").asText() : "";
    }

    /**
     * 该 schema 是否把 null 声明为合法取值。
     *
     * <p>只认 {@code "type": [..., "null"]} 这一种写法：项目里的可空字段一律用它
     * （见 world / stage / bag 各 schema），不支持 {@code anyOf} / {@code oneOf} 组合 ——
     * 支持多种写法会让同一个语义有两种表达方式，而两种表达方式的生成结果一旦有细微差别，
     * 就变成了只有作者本人才知道的隐含规则。
     */
    private static boolean declaresNull(JsonNode spec) {
        JsonNode typeNode = spec.get("type");
        if (typeNode == null || !typeNode.isArray()) {
            return false;
        }
        for (JsonNode t : typeNode) {
            if ("null".equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 基础类型映射。Java 与 TS 的对应关系只在这里定义一次，保证双端必然一致。 */
    private TypePair resolvePrimitive(String sourceFile, String location, String type, String format) {
        return switch (type) {
            case "string" -> new TypePair("String", "string");
            case "boolean" -> new TypePair("boolean", "boolean");
            case "integer" -> "int64".equals(format)
                    ? new TypePair("long", "number")
                    : new TypePair("int", "number");
            case "number" -> new TypePair("double", "number");
            case "object" -> throw new CodeGenException("Schema[" + sourceFile + "] " + location
                    + " 是内联对象：请把它抽成 $defs 中的独立定义后再引用（否则双端类型无法对齐）");
            default -> throw new CodeGenException("Schema[" + sourceFile + "] " + location
                    + " 使用了不支持的类型: " + type);
        };
    }

    private static String text(JsonNode node, String field, String errorMessage) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new CodeGenException(errorMessage);
        }
        return v.asText();
    }
}
