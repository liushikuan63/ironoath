package com.ironoath.web;

import com.ironoath.web.dto.generated.BossMechanic;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.RewardItemView;
import com.ironoath.web.dto.generated.RewardType;
import com.ironoath.web.dto.generated.StageLoss;
import com.ironoath.web.dto.generated.StageReward;
import com.ironoath.web.dto.generated.StageUnit;
import com.ironoath.web.dto.generated.UnitRestriction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：钉住 stage 协议里那些「因为生成器限制而复制出来」的类型，与它们原本的形状一致。
 * 依赖：JUnit 5 + AssertJ + 反射；不需要容器。
 *
 * <p><b>为什么会有复制</b>：协议生成器只支持同文件的 {@code $ref}
 * （跨文件引用与可空 {@code $ref} 都会直接 FAIL，现有 8 份 schema 里两种写法都没有先例）。
 * 于是 stage 协议里的 {@code StageUnit} 与 {@code StageReward} 各有一份本地定义，
 * 形状与 army 协议的 {@code MarchUnit}、bag 协议的 {@code RewardItemView} 相同。
 *
 * <p><b>复制本身不是问题，复制而不校验才是</b>：漂移的症状是服务端下发的字符串
 * 在客户端解析成 {@code undefined} —— 而 TS 侧不会报错，UI 只会空白，
 * 或者服务端 {@code valueOf} 抛 IllegalArgumentException 变成 500。
 * 两种都不是「配置写错」那样一眼能看出的问题，所以用反射把字段名与类型逐一对上。
 *
 * <p>用反射而不是手写断言：手写的话新增一个字段就会忘记同步这里，
 * 而反射比对的是「两个 record 的完整形状」，加字段必然被发现。
 */
class StageContractParityTest {

    /** 只比字段名与顺序，不比类型（类型差异由具体用例单独说明）。 */
    private static List<String> fieldNamesOf(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** 把 record 的形状压成「字段名:类型简名」的列表，顺序敏感。 */
    private static List<String> shapeOf(Class<?> recordType) {
        RecordComponent[] components = recordType.getRecordComponents();
        assertThat(components).as("%s 必须是 record", recordType.getSimpleName()).isNotNull();
        List<String> shape = new ArrayList<>(components.length);
        for (RecordComponent component : components) {
            shape.add(component.getName() + ":" + component.getType().getSimpleName());
        }
        return shape;
    }

    @Test
    @DisplayName("StageUnit 与 MarchUnit 形状一致：两者都表示「unitId（含阶级）→ 数量」")
    void stageUnitMatchesMarchUnit() {
        assertThat(shapeOf(StageUnit.class))
                .as("生成器不支持跨文件 $ref，所以 stage 协议复制了一份 MarchUnit 的形状。"
                        + "两者漂移的话，客户端为行军写的渲染代码用到关卡上就会拿到 undefined")
                .isEqualTo(shapeOf(MarchUnit.class));
        assertThat(shapeOf(StageUnit.class)).containsExactly("unitId:String", "count:long");
    }

    @Test
    @DisplayName("StageLoss 只比 StageUnit 多一个展示名 name：共有字段必须逐位一致")
    void stageLossIsStageUnitPlusName() {
        // 结算里的损失行是**直接画给玩家看**的，而客户端没有 unit 表数据（B00 铁律），
        // 印 unitId 等于把内部编号端上屏 —— #255 建筑名 / #268 资源名 / #278 技能名 /
        // #281 碎片名 / #288 赛季行 id 同族第八处，修法是服务端把名字带下来。
        // 不复用 StageUnit 是因为它同时充当「玩家派出去的兵」的请求载荷：
        // 请求侧带一个客户端没有的字段，等于要求客户端上传它翻译不出来的东西。
        List<String> shared = shapeOf(StageUnit.class);
        assertThat(shapeOf(StageLoss.class).subList(0, shared.size()))
                .as("unitId/count 与 StageUnit 逐位一致，客户端那份行军渲染代码才可能直接复用")
                .isEqualTo(shared);
        assertThat(shapeOf(StageLoss.class))
                .as("StageLoss 只允许比 StageUnit 多一个 name；再加字段必须先说清谁在用")
                .containsExactly("unitId:String", "count:long", "name:String");
    }

    @Test
    @DisplayName("StageReward 与 RewardItemView 字段一致，且唯一的类型差异是被工具链逼出来的、有据可查")
    void stageRewardMatchesRewardItemView() {
        // 关卡奖励与开箱/任务奖励在客户端走同一套飘字组件（RewardToastQueue），
        // 字段名或个数不一致就会有两套渲染代码，而其中一套迟早没人维护
        assertThat(fieldNamesOf(StageReward.class))
                .as("字段名与顺序必须与 RewardItemView 完全一致")
                .isEqualTo(fieldNamesOf(RewardItemView.class));
        assertThat(shapeOf(StageReward.class))
                .containsExactly("type:String", "id:String", "count:long", "name:String");

        // 唯一的差异：RewardItemView.type 是枚举 RewardType，StageReward.type 是裸 String。
        // 这不是疏忽，是生成器只支持同文件 $ref 逼出来的 —— RewardType 定义在 bag.schema.json，
        // stage.schema.json 引用不到它，而在本地重新声明一个 enum 也只会得到另一个 Java 类型
        // （StageRewardType），仍然不等于 RewardType，却多了一个要同步的枚举。
        // 取舍是：用 String + 下面那条取值断言，换掉一个必然漂移的重复枚举。
        // 代价记在这里，客户端消费 StageReward.type 时需要一次断言/收窄，
        // 而 RewardItemView.type 不需要 —— 若哪天生成器支持跨文件引用，应当把它改回 $ref
        assertThat(shapeOf(RewardItemView.class))
                .as("RewardItemView 的 type 是枚举，这条断言就是上面那个差异的哨兵："
                        + "如果它哪天也变成了 String，说明有人动了 bag 协议，两边的取舍要重新评估")
                .containsExactly("type:RewardType", "id:String", "count:long", "name:String");
    }

    @Test
    @DisplayName("StageReward.type 的取值与 RewardType 枚举一致")
    void stageRewardTypeMatchesRewardTypeEnum() {
        // StageReward.type 是 string（生成器无法跨文件引用 RewardType 枚举），
        // 所以取值集合必须在这里对上：少一个值会让某种奖励在关卡里显示不出来，
        // 多一个值会让服务端下发一个客户端不认识的字符串
        List<String> rewardTypes = Arrays.stream(RewardType.values()).map(Enum::name).toList();
        // 2026-09-12：bag 协议新增 HERO（整卡武将，首日主线赠送用），这里必须同步 ——
        // 这份硬编码的期望值就是「两端取值集合一致」的哨兵；stage.schema.json 里那份
        // 字符串枚举也要一起加，否则关卡奖励里下发 HERO 时客户端解析不出来
        List<String> declared = List.of("RESOURCE", "ITEM", "HERO_FRAGMENT", "HERO",
                "STAMINA", "PRIVILEGE");
        assertThat(rewardTypes)
                .as("bag 协议的 RewardType 若增删了取值，stage 协议里那份字符串枚举必须同步")
                .containsExactlyElementsOf(declared);
    }

    @Test
    @DisplayName("协议枚举与 stage 表的 ENUM 声明一致：靠 valueOf 桥接，漂移只会在运行时炸")
    void protocolEnumsMatchTheStageTable() {
        assertThat(Arrays.stream(UnitRestriction.values()).map(Enum::name).toList())
                .as("unitRestriction 由 StageAppService 用 valueOf 从配置枚举翻译过来，"
                        + "漂移不会在启动期暴露，而是等到玩家点开某一关时抛 IllegalArgumentException")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(com.ironoath.config.cfg.StageCfg.UnitRestriction.values())
                                .map(Enum::name).toList());
        assertThat(Arrays.stream(BossMechanic.values()).map(Enum::name).toList())
                .as("bossMechanic 同上；而且它决定「这一关能不能打」——"
                        + "内核未实现的机制必须被识别出来并响亮拒绝，翻译成 null 就会静默放行")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(com.ironoath.config.cfg.StageCfg.BossMechanic.values())
                                .map(Enum::name).toList());
        assertThat(Arrays.stream(BossMechanic.values()).map(Enum::name).toList())
                .containsExactly("NONE", "REINFORCEMENT", "SHIELD_PHASE", "COUNTER_STRIKE");
    }
}
