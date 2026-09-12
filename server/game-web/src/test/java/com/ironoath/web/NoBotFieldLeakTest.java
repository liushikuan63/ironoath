package com.ironoath.web;

import com.ironoath.web.dto.generated.WorldEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：断言「Bot 标识绝不下发给客户端」（B07 禁止项 ×2 + B11 合规）。
 * 依赖：JUnit + AssertJ + 反射 + 读仓库内的 contract/proto。
 *
 * <p><b>这条禁令在 B07 里出现了两次</b>（禁止项列表里一次、WorldEntity 的注释里一次），
 * B11 还会再要求一次「前 7 天不做任何 Bot 标识」。出现这么多次说明它容易被违反 ——
 * 而违反它的成本是合规事故，不是 bug：人机混排而不告知，监管认定的是欺骗消费者。
 *
 * <p><b>为什么用反射扫全部协议而不只是看 WorldEntity</b>：
 * 只断言一个类的话，下一个批次的某个人在 MarchView 或 CityView 上加一个
 * {@code isBot} 字段就不会被发现。合规红线要守的是「整个协议面」，不是某一个类。
 *
 * <p><b>字段不存在比字段恒为 false 更安全</b>：一个恒为 false 的 {@code isBot}
 * 迟早会被某个客户端拿去写 {@code if (!isBot) { 显示真人标记 }}，
 * 那一刻合规事故就发生了，而代码评审时它看起来完全无害。
 */
class NoBotFieldLeakTest {

    /**
     * 被禁止的字段名片段（小写比较）。
     *
     * <p>不只禁 {@code isBot}：{@code bot}/{@code npc}/{@code isHuman}/{@code artificial}
     * 都能表达同一件事，换个名字就绕过断言是没有任何意义的。
     */
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "bot", "npc", "ishuman", "artificial", "isai", "robot");

    @Test
    @DisplayName("所有下发协议里都不存在任何 Bot 标识字段（反射扫描生成物）")
    void noProtocolRecordCarriesBotMarker() throws IOException {
        Path generatedDir = Path.of("src/main/java/com/ironoath/web/dto/generated");
        assertThat(Files.isDirectory(generatedDir))
                .as("生成物目录必须存在（先跑 npm run gen）").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(generatedDir)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertThat(sources).as("协议生成物不该为空").isNotEmpty();
            for (Path source : sources) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                String lower = text.toLowerCase(Locale.ROOT);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    // 只查「字段声明」的形状：record 组件与 private 字段。
                    // 直接查子串会误伤注释里对这条禁令的说明（本类的 Javadoc 就提到了 isBot）
                    if (containsFieldNamed(lower, fragment)) {
                        violations.add(source.getFileName() + " 含疑似 Bot 标识字段（片段 \"" + fragment + "\"）");
                    }
                }
            }
        }
        assertThat(violations)
                .as("B07 禁止项 + B11 合规：Bot 标识绝不下发给客户端。"
                        + "字段不存在比字段恒为 false 更安全 —— 存在的字段迟早会被客户端拿去用")
                .isEmpty();
    }

    @Test
    @DisplayName("WorldEntity 的字段清单里没有任何 Bot 标识，且字段数量没有偷偷膨胀")
    void worldEntityHasNoBotField() {
        RecordComponent[] components = WorldEntity.class.getRecordComponents();
        assertThat(components).isNotNull();
        List<String> names = new ArrayList<>();
        for (RecordComponent component : components) {
            String name = component.getName();
            names.add(name);
            String lower = name.toLowerCase(Locale.ROOT);
            for (String fragment : FORBIDDEN_FRAGMENTS) {
                assertThat(lower).as("WorldEntity 的字段 %s 触犯了 Bot 标识禁令", name)
                        .doesNotContain(fragment);
            }
        }
        // 钉住字段清单：B07 明写 WorldEntity 是「精简结构」，字段膨胀会直接推高 payload
        // （验收 5 的 20KB 上限），而每次膨胀都是「顺手加一个字段」造成的，没人会主动去量
        assertThat(names).containsExactly("id", "type", "x", "y", "level",
                "ownerName", "allianceTag", "marchStatus", "resourceType", "load");
    }

    @Test
    @DisplayName("契约源文件里也不得声明 Bot 字段（生成物是从 schema 来的，源头必须干净）")
    void contractSchemasHaveNoBotField() throws IOException {
        Path schemaDir = Path.of("../../contract/proto");
        if (!Files.isDirectory(schemaDir)) {
            schemaDir = Path.of("contract/proto");
        }
        assertThat(Files.isDirectory(schemaDir)).as("找不到 contract/proto 目录").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(schemaDir)) {
            for (Path schema : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String text = Files.readString(schema, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    if (containsFieldNamed(text, fragment)) {
                        violations.add(schema.getFileName() + " 声明了疑似 Bot 标识字段（片段 \"" + fragment + "\"）");
                    }
                }
            }
        }
        assertThat(violations).as("协议源头必须干净，否则下一次 npm run gen 就会把 Bot 字段生成出来")
                .isEmpty();
    }

    /**
     * 判断文本里是否存在「名字含该片段」的字段声明。
     *
     * <p>匹配两种形状：JSON Schema 的 {@code "xxxbot":} 与 Java record 的 {@code String xxxBot;}。
     * 不匹配注释与描述文本 —— 那些地方提到 bot 是在<b>说明这条禁令</b>，
     * 按子串一刀切会把说明本身判成违规，于是没人敢在注释里写清楚为什么禁，
     * 下一个人就会重新踩一遍。
     */
    private static boolean containsFieldNamed(String lowerText, String fragment) {
        // JSON: "somebot": 或 "isbot" :
        if (lowerText.matches("(?s).*\"[a-z0-9_]*" + fragment + "[a-z0-9_]*\"\\s*:.*")) {
            return true;
        }
        // Java record 组件: String someBot, / long isBot; —— 用类型名 + 标识符的形状来识别
        return lowerText.matches("(?s).*\\b(string|long|int|boolean|integer|list<[^>]*>|map<[^>]*>)\\s+"
                + "[a-z0-9_]*" + fragment + "[a-z0-9_]*\\s*[,;)].*");
    }
}
