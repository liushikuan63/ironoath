package com.ironoath.config;

import com.ironoath.config.cfg.EquipCfg;
import com.ironoath.config.cfg.ItemCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：equip 表与 item 表的一致性（装备必须同时是道具）。
 * 依赖：JUnit 5 + AssertJ、生成的 cfg 类型；读仓库内真实的 contract/config。
 *
 * <p><b>这条约束是 B06 落地时才暴露的跨表缺口</b>：装备的属性定义在 equip 表，
 * 但「能不能被拥有」取决于它是不是 item 表的行 —— 背包的堆叠上限（PlayerBag 每次从 item 表读）、
 * 奖励发放器的入包、开箱掉落表的外键，全部以 item 表为准。
 * 装备不在 item 表里就永远发不出去，而这个缺口<b>不会报错</b>：
 * 配置校验器只看单表，两张表各自都合法，只有玩家真的拿到一件装备时才会炸。
 *
 * <p>所以把它变成断言：写在这里的约束不会被遗忘，写在 designNote 里的会。
 */
class EquipItemConsistencyTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void load() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("每件装备都必须在 item 表里有同 id 的行，否则它永远发不到玩家手上")
    void everyEquipIsAlsoAnItem() {
        List<String> missing = new ArrayList<>();
        for (EquipCfg equip : registry.all(EquipCfg.class)) {
            if (!registry.rawTable("item").has(equip.id())) {
                missing.add(equip.id());
            }
        }
        assertThat(missing)
                .as("这些装备在 item 表里没有对应的行：背包读不到 stackMax、发放器无法入包，"
                        + "装备会变成只能在配置里存在、玩家永远拿不到的东西")
                .isEmpty();
    }

    @Test
    @DisplayName("装备在 item 表里的类型/稀有度/槽位必须与 equip 表一致（两张表共用 id，不允许分叉）")
    void equipAndItemAgreeOnSharedFields() {
        for (EquipCfg equip : registry.all(EquipCfg.class)) {
            ItemCfg item = registry.get(ItemCfg.class, equip.id());
            assertThat(item.type()).as("装备 %s 在 item 表里必须是 EQUIP 类型", equip.id())
                    .isEqualTo(ItemCfg.Type.EQUIP);
            assertThat(item.rarity().name())
                    .as("装备 %s 的稀有度两张表必须一致，否则背包品质框与属性档位会对不上", equip.id())
                    .isEqualTo(equip.rarity().name());
            assertThat(item.effectTarget())
                    .as("装备 %s 在 item 表里的 effectTarget 应当写明槽位，便于客户端跳转与排查", equip.id())
                    .isEqualTo(equip.slot().name());
            assertThat(item.stackMax())
                    .as("装备 %s 的堆叠上限必须为正，否则一件都放不下", equip.id())
                    .isPositive();
        }
    }

    @Test
    @DisplayName("反向：item 表里所有 EQUIP 类型的行都必须在 equip 表里有属性定义")
    void everyEquipItemHasAttributes() {
        List<String> missing = new ArrayList<>();
        for (ItemCfg item : registry.all(ItemCfg.class)) {
            if (item.type() == ItemCfg.Type.EQUIP && !registry.rawTable("equip").has(item.id())) {
                missing.add(item.id());
            }
        }
        assertThat(missing)
                .as("这些道具标记为 EQUIP 却在 equip 表里没有属性定义：穿上去会读到 null 或直接报错")
                .isEmpty();
    }

    @Test
    @DisplayName("四个槽位都必须有装备可选，且每个套装都恰好有 4 件（否则 4 件套永远凑不齐）")
    void slotsAndSetsAreComplete() {
        for (EquipCfg.Slot slot : EquipCfg.Slot.values()) {
            assertThat(registry.all(EquipCfg.class)).as("%s 槽位必须有装备", slot)
                    .anyMatch(e -> e.slot() == slot);
        }
        for (var set : registry.all(com.ironoath.config.cfg.EquipSetCfg.class)) {
            long pieces = registry.all(EquipCfg.class).stream()
                    .filter(e -> set.id().equals(e.setId())).count();
            assertThat(pieces)
                    .as("套装 %s 必须恰好有 4 件（对应 4 个槽位），否则 4 件套效果永远无法触发，"
                            + "而配置里写了 pieces4Ratio 就是在承诺一个兑现不了的效果", set.id())
                    .isEqualTo(4L);
            // 4 件必须分布在 4 个不同槽位：同一槽位两件的话玩家只能穿一件，仍然凑不齐
            long distinctSlots = registry.all(EquipCfg.class).stream()
                    .filter(e -> set.id().equals(e.setId()))
                    .map(EquipCfg::slot).distinct().count();
            assertThat(distinctSlots).as("套装 %s 的 4 件必须分布在 4 个不同槽位", set.id())
                    .isEqualTo(4L);
        }
    }
}
