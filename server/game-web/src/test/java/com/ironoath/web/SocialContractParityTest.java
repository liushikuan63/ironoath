package com.ironoath.web;

import com.ironoath.web.dto.generated.Coord;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.dto.generated.StageUnit;
import com.ironoath.web.dto.generated.TargetType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：协议里那几份「同一形状抄两遍」的副本必须逐项对得上 —— social.schema.json 承诺的守卫。
 * 依赖：只读生成的 Java 类型与枚举，不起容器、不连数据库。
 *
 * <p><b>为什么会有第二份</b>：生成器只支持同文件 {@code $ref}，所以集结目标坐标、集结兵种条目
 * 这些形状在 social / world / stage 三个协议里各有一份拷贝。抄一遍不要紧，<b>抄完没人对账</b>才要命：
 * 漂移的症状是服务端下发的字段在客户端解析成 undefined，TS 侧一声不响，UI 只会空白。
 *
 * <p><b>为什么比的是生成的类型而不是 schema 文本</b>：schema 与生成物之间还隔着生成器，
 * 只比 schema 会漏掉「两份 schema 写法不同但生成结果相同」的假警报，也会漏掉生成器侧的漂移；
 * 直接比两份真正发出去的 Java 类型，一条断言覆盖整条链路。
 * 字段顺序刻意<b>不</b>在断言范围内：线格式按名取值，换顺序不改变任何行为。
 */
class SocialContractParityTest {

    /** 一份 record 的形状：字段名 -> 声明类型（带泛型，所以 List&lt;String&gt; 与 List&lt;Integer&gt; 分得开）。 */
    private static Map<String, String> shapeOf(Class<?> recordType) {
        Map<String, String> out = new LinkedHashMap<>();
        for (RecordComponent rc : recordType.getRecordComponents()) {
            out.put(rc.getName(), rc.getGenericType().getTypeName());
        }
        return out;
    }

    @Test
    @DisplayName("SocialCoord 与 world 的 Coord 字段名与类型逐项一致（地图格坐标抄了第二份）")
    void socialCoordMatchesWorldCoord() {
        Map<String, String> world = shapeOf(Coord.class);
        assertThat(shapeOf(SocialCoord.class))
                .as("Coord 有 %s，SocialCoord 却抄成了 %s —— 症状是集结目标坐标在一侧解析成 undefined",
                        world, shapeOf(SocialCoord.class))
                .isEqualTo(world);
        assertThat(world).as("两份都空说明取不到 record 组件，这条断言就是空转的").isNotEmpty();
    }

    @Test
    @DisplayName("RallyTroop 与 world 的 MarchUnit、stage 的 StageUnit 三份形状一致（同一个单位条目抄了三遍）")
    void rallyTroopMatchesMarchUnitAndStageUnit() {
        Map<String, String> march = shapeOf(MarchUnit.class);
        assertThat(march).as("对照组：MarchUnit 自己要有字段，否则三条断言全是空比").isNotEmpty();
        // 正向断言：钉住"这条比的就是 unitId+count 这一族"。只写「三份互等」的话，
        // 哪天手滑把两个类换成同一份拷贝，它会一直绿着什么也不校验。
        assertThat(march).as("行军单位条目的现行形状，比错了对象时这条先红")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "unitId", "java.lang.String", "count", "long"));
        assertThat(shapeOf(StageUnit.class)).as("stage 协议的 StageUnit 与 MarchUnit 同源").isEqualTo(march);
        assertThat(shapeOf(RallyTroop.class)).as("social 协议的 RallyTroop 与 MarchUnit 同源").isEqualTo(march);
    }

    @Test
    @DisplayName("SocialTargetType 与 world 的 TargetType 取值与顺序完全一致（集结到达后的行为分支靠它）")
    void socialTargetTypeMatchesWorldTargetType() {
        List<String> world = Arrays.stream(TargetType.values()).map(Enum::name).toList();
        List<String> social = Arrays.stream(SocialTargetType.values()).map(Enum::name).toList();
        assertThat(world).as("空枚举会让这条判据恒真").isNotEmpty();
        assertThat(social)
                .as("TargetType 在两侧各有一份：漂移时服务端认得的目标类型在客户端翻译不出来")
                .containsExactlyElementsOf(world);
        // 正向断言：这两份枚举确实含那几个取值 —— 只写「两边互等」时，比对错了对象也会一直绿
        assertThat(social).as("比错了枚举类时这条先红").contains("MONSTER", "PLAYER_CITY", "RESOURCE");
    }
}
