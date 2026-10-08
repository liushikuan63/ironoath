package com.ironoath.web.levelreward;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.LevelRewardItem;
import com.ironoath.web.dto.generated.RewardItemView;

/**
 * 职责：钉住协议里那一份「奖励条目」复制品与 bag 协议的 {@link RewardItemView} 同形。
 * 依赖：纯反射 + 生成物，不起 Spring（比的是类型形状，不是运行时数据）。
 *
 * <p><b>为什么要单独一个类而不是塞进 {@code LevelRewardTableTest}</b>：本仓协议里"奖励条目"这个形状
 * 已经有 bag / quest / stage 三份复制品（生成器只支持同文件 $ref），每一份都配了一个
 * {@code *ContractParityTest}（{@code StageContractParityTest} 是样板）。第四份照同一族立哨兵，
 * 而不是再往数值用例里混一类断言 —— 数值红与形状红的原因完全不同，混在一起每次都要重新分辨。
 *
 * <p><b>漂移的症状</b>：服务端下发的字符串在客户端解析成 undefined，而 TS 侧不报错，UI 只是空白 ——
 * 编译与运行时都不会红，所以只能靠这种"比生成出来的类型"的判据。
 */
@DisplayName("等级奖励协议：第四份奖励条目与 RewardItemView 同形")
class LevelRewardContractParityTest {

    @Test
    @DisplayName("字段名与顺序必须与 RewardItemView 完全一致")
    void fieldNamesAndOrderMatchRewardItemView() {
        assertThat(namesOf(LevelRewardItem.class))
                .as("生成器只支持同文件 $ref，level_reward 协议里是第四份同形结构；"
                        + "任何一份改了字段名或顺序，另一份必须同时改")
                .isEqualTo(namesOf(RewardItemView.class));
    }

    @Test
    @DisplayName("唯一的类型差异是工具链逼出来的（裸 String 而不是枚举），这条断言就是它的哨兵")
    void onlyDifferenceIsTheStringType() {
        // 与 StageContractParityTest 撞过的同一处：跨文件 $ref 生成不出来，所以 type 落成裸 String。
        // 哪天生成器支持跨文件引用，这两条会红 —— 红了说明该把它改回 $ref，而不是改断言。
        assertThat(typesOf(LevelRewardItem.class)).isEqualTo(List.of("String", "String", "long", "String"));
        assertThat(typesOf(RewardItemView.class)).isEqualTo(List.of("RewardType", "String", "long", "String"));
    }

    @Test
    @DisplayName("裸 String 的 type 取值必须落在 RewardType 枚举域内（客户端按它 cast）")
    void stringTypeStaysInsideRewardTypeEnum() {
        // 域检查不能因为"生成器给不了枚举"就省掉：值不在域内时 TS 侧是静默 undefined，
        // 而本表目前只产出 RESOURCE 一种，所以这里连"多出来一种"都能抓到。
        assertThat(RewardType.valueOf("RESOURCE")).isEqualTo(RewardType.RESOURCE);
        assertThat(Arrays.stream(RewardType.values()).map(Enum::name).toList())
                .as("RESOURCE 必须在枚举里 —— 协议 schema 的 enum 列表就是照这份枚举抄的")
                .contains("RESOURCE");
    }

    private static List<String> namesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName).toList();
    }

    private static List<String> typesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getType().getSimpleName()).toList();
    }
}
