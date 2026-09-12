package com.ironoath.config;

import com.ironoath.config.cfg.ChestCfg;
import com.ironoath.config.cfg.ChestDropCfg;
import com.ironoath.config.cfg.ItemCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：宝箱三张表（item / chest / chest_drop）之间的跨表一致性。
 * 依赖：JUnit 5 + AssertJ、生成的 cfg 类型；读仓库内真实的 contract/config。
 *
 * <p><b>这个类存在的理由是配置校验器表达不了这些约束</b>：
 * <ul>
 *   <li>{@code chest_drop.rewardId} 指向哪张表由 {@code rewardType} 决定（RESOURCE→resource、
 *       ITEM→item、HERO_FRAGMENT→hero），而校验器的 {@code REF:} 规则只能写死一张表。
 *       打错的 rewardId 不会在启动期报错，只会在玩家开箱时炸。</li>
 *   <li>{@code item.stackMax} 与 {@code chest.maxBatchCount} 分属两张表，
 *       但前者小于后者会让「一次开 N 个」在物理上不可能 —— B04 验收 3 直接不可达。</li>
 *   <li>掉落项权重之和、保底与稀有项的配套关系，跨行才能判定。</li>
 * </ul>
 * 校验器管单行单列，跨表跨行的不变量只能用测试守。写成断言的约束不会被遗忘，
 * 写在 designNote 里的会。
 */
class ChestConfigConsistencyTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void load() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("每个宝箱的堆叠上限必须 ≥ 单次批量上限，否则「一次开 N 个」根本凑不出来")
    void stackMaxCoversMaxBatchCount() {
        for (ChestCfg chest : registry.all(ChestCfg.class)) {
            ItemCfg item = registry.get(ItemCfg.class, chest.id());
            assertThat(item.stackMax())
                    .as("宝箱 %s 的 stackMax(%d) 必须 ≥ maxBatchCount(%d)："
                                    + "否则玩家永远无法同时持有足够多的箱子，B04 验收 3 在配置层面就不可达",
                            chest.id(), item.stackMax(), chest.maxBatchCount())
                    .isGreaterThanOrEqualTo(chest.maxBatchCount());
            assertThat(item.type())
                    .as("chest 表登记的 %s 在 item 表里必须是 CHEST 类型", chest.id())
                    .isEqualTo(ItemCfg.Type.CHEST);
        }
    }

    @Test
    @DisplayName("每个宝箱都必须有掉落项，且权重全为正（空掉落表会让开箱在运行期才炸）")
    void everyChestHasPositiveWeightDrops() {
        for (ChestCfg chest : registry.all(ChestCfg.class)) {
            List<ChestDropCfg> drops = dropsOf(chest.id());
            assertThat(drops).as("宝箱 %s 在 chest_drop 表里必须至少有一项", chest.id()).isNotEmpty();
            long totalWeight = 0L;
            for (ChestDropCfg drop : drops) {
                assertThat(drop.weight()).as("掉落项 %s 的权重必须为正", drop.id()).isPositive();
                assertThat(drop.count()).as("掉落项 %s 的产出数量必须为正", drop.id()).isPositive();
                totalWeight += drop.weight();
            }
            assertThat(totalWeight).as("宝箱 %s 的权重合计", chest.id()).isPositive();
        }
    }

    @Test
    @DisplayName("配了保底阈值的宝箱必须真的有稀有项，否则保底永远无法兑现")
    void pityThresholdRequiresRareDrop() {
        for (ChestCfg chest : registry.all(ChestCfg.class)) {
            if (chest.pityThreshold() <= 0L) {
                continue;
            }
            assertThat(dropsOf(chest.id())).as("宝箱 %s 配了保底 %d 却没有任何 rare 项",
                            chest.id(), chest.pityThreshold())
                    .anyMatch(ChestDropCfg::rare);
        }
    }

    @Test
    @DisplayName("chest_drop.rewardId 按 rewardType 分派校验外键（校验器表达不了条件外键）")
    void rewardIdsResolveInTheirImpliedTable() {
        List<String> errors = new ArrayList<>();
        for (ChestDropCfg drop : registry.all(ChestDropCfg.class)) {
            boolean ok = switch (drop.rewardType()) {
                case RESOURCE -> registry.resourceIds().contains(drop.rewardId());
                case ITEM -> registry.hasTable("item") && registry.rawTable("item").has(drop.rewardId());
                // 碎片与整卡的 id 都是 hero 表的行 id（区别只在发放侧：碎片落道具、整卡落武将册）
                case HERO_FRAGMENT, HERO ->
                        registry.hasTable("hero") && registry.rawTable("hero").has(drop.rewardId());
                // 体力与特权还没有对应的配置表（B09 体力、B15 特权）。
                // 这里不是「跳过校验」而是「禁止配置」：一旦有人往掉落表里塞了这两类，
                // 下面的断言会立刻失败，而不是等到玩家开箱时才发现产出无处安放。
                case STAMINA, PRIVILEGE -> false;
            };
            if (!ok) {
                errors.add(drop.id() + "(" + drop.rewardType() + " → " + drop.rewardId() + ")");
            }
        }
        assertThat(errors)
                .as("这些掉落项的 rewardId 在它 rewardType 所指向的表里找不到，"
                        + "或者用到了尚未开放的奖励类型（STAMINA 属 B09、PRIVILEGE 属 B15）")
                .isEmpty();
    }

    @Test
    @DisplayName("掉落项的 rewardId 在同一个宝箱内不得重复，否则聚合结果会出现两行同名奖励")
    void rewardIdsAreUniqueWithinChest() {
        for (ChestCfg chest : registry.all(ChestCfg.class)) {
            Set<String> seen = new LinkedHashSet<>();
            List<String> duplicated = new ArrayList<>();
            for (ChestDropCfg drop : dropsOf(chest.id())) {
                if (!seen.add(drop.rewardId())) {
                    duplicated.add(drop.rewardId());
                }
            }
            assertThat(duplicated)
                    .as("宝箱 %s 的掉落表里这些 rewardId 出现了多行：ChestOpener 按 rewardId 聚合，"
                            + "重复行不会报错但会让权重被拆散、保底判定失准", chest.id())
                    .isEmpty();
        }
    }

    private static List<ChestDropCfg> dropsOf(String chestId) {
        List<ChestDropCfg> out = new ArrayList<>();
        for (ChestDropCfg row : registry.all(ChestDropCfg.class)) {
            if (row.chestId().equals(chestId)) {
                out.add(row);
            }
        }
        return out;
    }
}
