package com.ironoath.web;

import com.ironoath.web.dto.generated.ItemRarity;
import com.ironoath.web.dto.generated.RewardType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：契约枚举与内部枚举的一致性。
 * 依赖：JUnit 5 + AssertJ；只比对枚举常量，不需要容器。
 *
 * <p><b>为什么需要这个测试</b>：这两个枚举各有一份「协议侧」和一份「内部侧」的定义，
 * 而生成器无法自动校验它们 —— 协议侧来自 contract/proto 的 JSON Schema，
 * 内部侧来自 game-core 的手写枚举与 contract/config 的 fieldTypes。
 * 一旦漂移，症状是服务端下发的字符串在客户端解析不出来（TS 侧变成 undefined），
 * 或者服务端 {@code valueOf} 直接抛 IllegalArgumentException 变成 500。
 * 两种都不是「配置写错」那种一眼能看出的问题，所以用断言钉住。
 *
 * <p>顺序也在断言范围内：{@link ItemRarity} 的声明顺序就是背包排序的口径
 * （越靠后越稀有 ⇒ 排越前），改顺序等于改排序规则。
 */
class ContractEnumParityTest {

    @Test
    @DisplayName("协议 RewardType 与 game-core 的 RewardType 常量完全一致（含顺序）")
    void rewardTypeMatchesCore() {
        List<String> contract = Arrays.stream(RewardType.values()).map(Enum::name).toList();
        List<String> core = Arrays.stream(com.ironoath.core.reward.RewardType.values())
                .map(Enum::name).toList();
        assertThat(contract)
                .as("协议侧 RewardType 来自 bag.schema.json，内部侧来自 game-core；"
                        + "两者漂移会让开箱产出在客户端解析不出来")
                .containsExactlyElementsOf(core);
    }

    @Test
    @DisplayName("协议 RewardType 与 chest_drop 表声明的 rewardType 取值一致")
    void rewardTypeMatchesChestDropTable() {
        List<String> contract = Arrays.stream(RewardType.values()).map(Enum::name).toList();
        List<String> table = Arrays.stream(com.ironoath.config.cfg.ChestDropCfg.RewardType.values())
                .map(Enum::name).toList();
        assertThat(contract)
                .as("chest_drop 表的 ENUM 与协议枚举必须是同一套取值，"
                        + "否则配置里写得进去、下发时却翻译不出来")
                .containsExactlyElementsOf(table);
    }

    @Test
    @DisplayName("协议 ItemRarity 与 item 表的 rarity 枚举一致，且声明顺序是稀有度升序")
    void itemRarityMatchesItemTableAndIsAscending() {
        List<String> contract = Arrays.stream(ItemRarity.values()).map(Enum::name).toList();
        List<String> table = Arrays.stream(com.ironoath.config.cfg.ItemCfg.Rarity.values())
                .map(Enum::name).toList();
        assertThat(contract).containsExactlyElementsOf(table);
        // 背包排序键直接用 ordinal 的反序，所以「N 在前、SSR 在后」这个顺序本身就是规则
        assertThat(contract).containsExactly("N", "R", "SR", "SSR");
    }
}
