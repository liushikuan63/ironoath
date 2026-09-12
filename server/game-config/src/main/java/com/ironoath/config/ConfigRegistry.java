package com.ironoath.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ironoath.common.config.CurveKind;
import com.ironoath.common.config.CurveParams;
import com.ironoath.common.config.CurveSource;
import com.ironoath.common.config.CurveUnit;
import com.ironoath.common.config.GlobalParamSource;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.config.model.GlobalCfg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 职责：配置表注册中心 —— 加载、全量校验、强类型装配、热更（B02 输入输出契约）。
 * 依赖：game-common（JsonUtils / FixedPoint / CurveSource / GlobalParamSource）、生成的 cfg 类型。
 *
 * <p>两阶段设计，顺序不可颠倒：
 * <ol>
 *   <li><b>校验</b>：{@link ConfigValidator} 按 fieldTypes 逐行检查原始 JSON，聚合<b>全部</b>错误。
 *       这一步能报出「字段类型不对」「枚举值非法」「外键不存在」，因为它面对的是 JsonNode。</li>
 *   <li><b>装配</b>：校验通过后才用 Jackson 把行反序列化成生成的 record。
 *       如果先装配，Jackson 会在第一个坏字段上抛异常，就退化成 fail-fast，
 *       策划又得「改一个字段、重启一次」。</li>
 * </ol>
 *
 * <p>装配用的 mapper 关闭了 {@code FAIL_ON_UNKNOWN_PROPERTIES}：配置行刻意携带
 * {@code why} / {@code todo} / {@code source} 三个<b>只给人看</b>的字段（C00 公理三：数值可解释），
 * 它们不进入生成的类型。未知字段的检测由第 1 阶段对着 fieldTypes 做，比对着 record 做更准确。
 *
 * <p>本类实现 game-common 的 {@link CurveSource} 与 {@link GlobalParamSource} 端口，
 * 于是 game-core / game-battle 能在不依赖 game-config 的前提下做到配置驱动（依赖倒置）。
 */
public final class ConfigRegistry implements CurveSource, GlobalParamSource {

    /** 只给人看、不进入生成类型的字段。与 ConfigCodeEmitter.DOC_ONLY_FIELDS 保持一致。 */
    private static final Set<String> DOC_ONLY_FIELDS = Set.of("why", "todo", "source");

    /** 装配用 mapper：允许文档字段不出现在 record 里。 */
    private static final ObjectMapper ROW_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /**
     * 原始表（校验、诊断、以及 B16 配置清单的内容指纹）。
     *
     * <p><b>必须能被热更替换</b>：曾经这里是构造期的不可变副本，注释写着「只服务于校验与诊断」——
     * 而 B16 的配置清单正是从 {@code rawTable().rows()} 算内容 hash 的，
     * 于是热更之后清单里的 hash 永远停留在旧内容，客户端永远认为自己是最新的，
     * 症状是「我明明改了表却看不到效果」，那是配置表最难查的一类事故。
     *
     * <p>{@code volatile} + 整份替换而不是可变 Map：读方要么看到完整的旧快照，
     * 要么看到完整的新快照，不会撞上「改了一半」的中间态。
     */
    private volatile Map<String, RawConfigTable> rawTables;

    /** 强类型表缓存。热更时整表替换，读方持有的旧引用依然是一致快照。 */
    private final Map<Class<?>, ConfigTable<?>> typedTables = new ConcurrentHashMap<>();

    /**
     * global 表的键值索引。<b>同样必须能被热更替换</b>：热更 global 之后
     * {@code longParam} 若仍返回旧值，那么「灰度比例改配置即生效」就是一句空话 ——
     * 而线上出事时把灰度调回 0% 恰恰是不能等发版的那一类操作。
     */
    private volatile Map<String, GlobalCfg> globals;

    private ConfigRegistry(Map<String, RawConfigTable> rawTables) {
        this.rawTables = Map.copyOf(rawTables);
        this.globals = Map.copyOf(parseGlobals(this.rawTables.get(TABLE_GLOBAL)));
    }

    /** global 表是键值型（value 的类型由同行 valueType 决定），无法生成静态 record，手工建模。 */
    private static Map<String, GlobalCfg> parseGlobals(RawConfigTable globalTable) {
        Map<String, GlobalCfg> parsed = new LinkedHashMap<>();
        for (JsonNode row : globalTable.rows()) {
            GlobalCfg cfg = GlobalCfg.from(row);
            parsed.put(cfg.id(), cfg);
        }
        return parsed;
    }

    /** 表名常量，避免各处散落字符串字面量。 */
    public static final String TABLE_RESOURCE = "resource";
    public static final String TABLE_CURVE = "curve";
    public static final String TABLE_GLOBAL = "global";

    // ---------- 加载入口 ----------

    /**
     * 从目录加载全部 {@code *.json} 配置表。表名 = 文件名去后缀。
     *
     * @throws ConfigException 当目录不存在、必需表缺失，或任一字段校验失败（携带全部错误）
     */
    public static ConfigRegistry loadFromDirectory(Path dir) {
        Path resolved = resolveConfigDir(dir);
        Map<String, String> jsonByTable = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(resolved)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith("_") && !n.startsWith(".");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path p : sorted) {
                String name = p.getFileName().toString();
                name = name.substring(0, name.length() - ".json".length());
                jsonByTable.put(name, Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置目录失败: " + resolved.toAbsolutePath(), e);
        }
        if (jsonByTable.isEmpty()) {
            throw new ConfigException("配置目录中没有任何 JSON 表: " + resolved.toAbsolutePath());
        }
        return loadFromJson(jsonByTable);
    }

    /**
     * 从「表名 → JSON 文本」加载。单测用这个入口，不需要真实文件。
     *
     * @throws ConfigException 携带<b>全部</b>校验错误
     */
    public static ConfigRegistry loadFromJson(Map<String, String> jsonByTable) {
        List<ConfigValidator.Issue> issues = new ArrayList<>();
        Map<String, JsonNode> roots = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : jsonByTable.entrySet()) {
            JsonNode root = ConfigValidator.parseOrCollect(e.getKey(), e.getValue(), issues);
            if (root != null) {
                roots.put(e.getKey(), root);
            }
        }
        // 单表校验：全部跑完再判失败，绝不 fail-fast
        for (Map.Entry<String, JsonNode> e : roots.entrySet()) {
            issues.addAll(ConfigValidator.validateTable(e.getKey(), e.getValue()));
        }
        // 跨表外键校验：必须在所有表解析完成后进行
        issues.addAll(ConfigValidator.validateReferences(roots));

        for (String required : requiredTables()) {
            if (!roots.containsKey(required)) {
                issues.add(new ConfigValidator.Issue(required, "必需的表缺失：请检查配置目录"));
            }
        }
        if (!issues.isEmpty()) {
            throw new ConfigException(
                    "配置表校验失败，服务端拒绝启动。请一次性修正下列全部问题后重启",
                    issues.stream().map(ConfigValidator.Issue::toString).toList());
        }

        Map<String, RawConfigTable> tables = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : roots.entrySet()) {
            tables.put(e.getKey(), RawConfigTable.of(e.getKey(), e.getValue()));
        }
        return new ConfigRegistry(tables);
    }

    /**
     * 必须存在的表。
     *
     * <p>只有这三张是「服务端起不来就没法玩」的：资源定义、成长曲线、全局常量。
     * 其余表（章节、商店、赛季…）缺失时对应功能不可用，但不该让整个服务端拒绝启动 ——
     * 否则任何一张后期表出问题都会变成全站故障。
     */
    private static Set<String> requiredTables() {
        return Set.of(TABLE_RESOURCE, TABLE_CURVE, TABLE_GLOBAL);
    }

    /**
     * 解析配置目录。相对路径会逐级向上回溯查找 {@code contract/config}，
     * 因为 {@code mvn spring-boot:run}（工作目录 server/game-web）与 {@code java -jar}
     * （工作目录仓库根）的工作目录不同，回溯让两种方式都能跑通。
     */
    private static Path resolveConfigDir(Path dir) {
        Path direct = dir.toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path cursor = direct;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("contract").resolve("config").normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new ConfigException("配置目录不存在，且向上回溯 6 层仍未找到 contract/config：" + direct);
    }

    // ---------- B02 契约：泛型访问 ----------

    /**
     * 加载（或复用已加载的）强类型表。
     *
     * <p>表名由类型名反推：{@code BuildingCfg} → {@code building}，
     * {@code AllianceTechCfg} → {@code alliance_tech}。这条约定由生成器与本方法共同遵守，
     * 因此不需要额外维护一张「类 → 表名」的映射表（那种映射表一定会和实际不同步）。
     */
    @SuppressWarnings("unchecked")
    public <T> ConfigTable<T> load(String name, Class<T> type) {
        return (ConfigTable<T>) typedTables.computeIfAbsent(type, t -> buildTypedTable(name, t));
    }

    /** 按 id 取配置。找不到抛 {@link ConfigException}，绝不返回 null。 */
    public <T> T get(Class<T> type, String id) {
        return tableOf(type).get(id);
    }

    /** 取全表（不可变 List，顺序与配置表一致）。 */
    public <T> List<T> all(Class<T> type) {
        return tableOf(type).rows();
    }

    /**
     * 热更单表：构建新表成功后<b>原子替换</b>引用（B02 验收 9）。
     *
     * <p>进行中的请求若已持有旧 {@link ConfigTable} 引用，会继续用旧数据跑完 ——
     * 这是刻意的：读到一致快照远好于读到改了一半的表。
     * 下一次请求通过 {@link #load} 拿到的就是新表。
     *
     * @param name 表名
     * @param type 生成的配置类型
     * @param json 新的表 JSON 全文
     * @throws ConfigException 新表校验失败时抛出，<b>旧表保持不变</b>（热更失败不能把服务打挂）
     */
    public <T> void reload(String name, Class<T> type, String json) {
        List<ConfigValidator.Issue> issues = new ArrayList<>();
        JsonNode root = ConfigValidator.parseOrCollect(name, json, issues);
        if (root != null) {
            issues.addAll(ConfigValidator.validateTable(name, root));
        }
        if (!issues.isEmpty()) {
            throw new ConfigException("配置表[" + name + "]热更校验失败，已保留旧版本",
                    issues.stream().map(ConfigValidator.Issue::toString).toList());
        }
        RawConfigTable raw = RawConfigTable.of(name, root);
        ConfigTable<T> built = assemble(name, raw, type);
        // 替换顺序：原始表 → global 索引 → 强类型视图。
        // 这个顺序下的中间态是「清单已经在广告新 hash，而业务代码还用旧数值」，
        // 它只持续到下一个赋值，且客户端下载新表后自然收敛。
        // 反过来的中间态是「服务端已用新数值，而清单还在广告旧 hash」——
        // 客户端因此认为自己是最新的、永远不下载新表，那是一种不会自愈的双端分歧。
        Map<String, RawConfigTable> nextRaw = new LinkedHashMap<>(rawTables);
        nextRaw.put(name, raw);
        rawTables = Map.copyOf(nextRaw);
        if (TABLE_GLOBAL.equals(name)) {
            globals = Map.copyOf(parseGlobals(raw));
        }
        typedTables.put(type, built);
    }

    /** 丢弃某类型的强类型缓存，下次访问时重新装配。用于配置目录整体重载后的清理。 */
    public <T> void invalidate(Class<T> type) {
        typedTables.remove(type);
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigTable<T> tableOf(Class<T> type) {
        ConfigTable<T> table = (ConfigTable<T>) typedTables.get(type);
        if (table != null) {
            return table;
        }
        return load(tableNameOf(type), type);
    }

    private <T> ConfigTable<T> buildTypedTable(String name, Class<T> type) {
        RawConfigTable raw = rawTable(name);
        return assemble(name, raw, type);
    }

    private <T> ConfigTable<T> assemble(String name, RawConfigTable raw, Class<T> type) {
        List<T> rows = new ArrayList<>(raw.rows().size());
        Map<String, T> byId = new LinkedHashMap<>();
        for (JsonNode rowNode : raw.rows()) {
            T row;
            try {
                row = ROW_MAPPER.treeToValue(rowNode, type);
            } catch (IOException e) {
                // 走到这里说明校验器与生成器的规则理解不一致 —— 那是工具链 bug，不是数据 bug
                throw new ConfigException("配置表[" + name + "]装配成 " + type.getSimpleName()
                        + " 失败（校验已通过，疑似生成器与校验器规则不一致）：" + e.getMessage(), e);
            }
            rows.add(row);
            byId.put(rowNode.get("id").asText(), row);
        }
        return new ConfigTable<>(name, raw.version(), rows, byId);
    }

    /** {@code AllianceTechCfg} → {@code alliance_tech}。生成器 pascalCase 的逆变换。 */
    static String tableNameOf(Class<?> type) {
        String simple = type.getSimpleName();
        if (simple.endsWith("Cfg")) {
            simple = simple.substring(0, simple.length() - 3);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---------- 原始表与诊断 ----------

    /** 取原始表（校验、诊断、生成器用）。业务代码应优先用 {@link #get}。 */
    public RawConfigTable rawTable(String name) {
        RawConfigTable t = rawTables.get(name);
        if (t == null) {
            throw new ConfigException("配置表[" + name + "]不存在，已加载的表=" + rawTables.keySet());
        }
        return t;
    }

    public boolean hasTable(String name) {
        return rawTables.containsKey(name);
    }

    public Set<String> tableNames() {
        return rawTables.keySet();
    }

    /** 所有表的版本指纹，形如 {@code curve@1,global@1,resource@1}。热更比对与客户端版本协商用。 */
    public String fingerprint() {
        return rawTables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "@" + e.getValue().version())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    public int maxVersion() {
        return rawTables.values().stream().mapToInt(RawConfigTable::version).max().orElse(0);
    }

    /** 全部预留表名（结构已定稿、数据待后续批次填充）。启动日志会列出来提醒。 */
    public List<String> reservedTables() {
        return rawTables.values().stream()
                .filter(RawConfigTable::isReserved)
                .map(RawConfigTable::name)
                .sorted()
                .toList();
    }

    // ---------- resource / curve 便捷访问 ----------

    /** 取资源配置。找不到抛异常，绝不返回 null。 */
    public ResourceCfg getResource(String id) {
        return get(ResourceCfg.class, id);
    }

    /** 全部资源，顺序与配置表一致。 */
    public List<ResourceCfg> allResources() {
        return all(ResourceCfg.class);
    }

    public Set<String> resourceIds() {
        return rawTable(TABLE_RESOURCE).ids();
    }

    @Override
    public CurveParams curve(String curveId) {
        return toParams(get(CurveCfg.class, curveId));
    }

    @Override
    public boolean hasCurve(String curveId) {
        return hasTable(TABLE_CURVE) && rawTable(TABLE_CURVE).has(curveId);
    }

    /**
     * 生成的表内枚举 → game-common 的领域枚举。
     *
     * <p>为什么要转一层：{@code CurveCfg.Kind} 是「配置表结构」的一部分（由 fieldTypes 生成），
     * {@code CurveKind} 是 game-common 里的领域契约。让 game-core 直接依赖生成的枚举，
     * 就等于让 game-core 依赖 game-config，违反分层规则。按名字映射是最便宜的桥。
     */
    private static CurveParams toParams(CurveCfg cfg) {
        return new CurveParams(
                cfg.id(),
                CurveKind.valueOf(cfg.kind().name()),
                cfg.base(),
                cfg.ratio(),
                cfg.exponent(),
                CurveUnit.valueOf(cfg.unit().name()));
    }

    // ---------- global（实现 GlobalParamSource 端口） ----------

    public GlobalCfg getGlobal(String id) {
        GlobalCfg cfg = globals.get(id);
        if (cfg == null) {
            throw new ConfigException("全局参数不存在: id=" + id + "，现有=" + globals.keySet());
        }
        return cfg;
    }

    @Override
    public long longParam(String id) {
        return getGlobal(id).asLong();
    }

    @Override
    public long fixedParam(String id) {
        return getGlobal(id).asFixed();
    }

    @Override
    public boolean boolParam(String id) {
        return getGlobal(id).asBool();
    }

    @Override
    public String stringParam(String id) {
        return getGlobal(id).asString();
    }

    @Override
    public boolean hasParam(String id) {
        return globals.containsKey(id);
    }

    /** 所有带 TODO(需确认) 标记的参数，启动时打进日志提醒（B00：不确定处显式标注，不要静默假设）。 */
    public List<GlobalCfg> pendingConfirmations() {
        return rawTable(TABLE_GLOBAL).rows().stream()
                .map(GlobalCfg::from)
                .filter(GlobalCfg::isPendingConfirmation)
                .toList();
    }

    /** 供生成器与测试复用。 */
    public static JsonNode parse(String json) {
        return JsonUtils.readTree(json);
    }

    /** 供测试断言：确认文档字段确实没有进入生成类型。 */
    static Set<String> docOnlyFields() {
        return DOC_ONLY_FIELDS;
    }
}
