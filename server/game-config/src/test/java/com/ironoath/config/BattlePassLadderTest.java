package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.BattlePassCfg;
import com.ironoath.config.cfg.BattlePassSeasonCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.config.cfg.ShopCfg;

/**
 * 职责：守住 B24 裁决② 那几条**数值口径**：20 档、每档 150 分、打满 3000 分、付费线价值 ≈ 3000 金币等值。
 * 依赖：仓库内真实的 {@code contract/config}（battle_pass / shop / item / resource 四张表）。
 *
 * <p><b>为什么这些数必须是断言而不是文档里的一句话</b>：它们是"买断定价"这件事的全部依据 ——
 * 玩家掏 30 元买到的东西值多少，只能从这张表算出来。一次调价或一次改档就会让它变成
 * 一句过期的承诺，而没有任何东西会报错。折算锚是**商店货架价**（B24 裁决④ 把回收价撤下之后的
 * 唯一锚，与 B19 礼包同一套口径）：玩家在商店里看得见那个数，所以折算出来的等值可自查。
 */
class BattlePassLadderTest {

    /** 付费线总价值的目标（裁决②：与月卡同档，3000 金币等值）。 */
    private static final long PAID_TARGET_GOLD = 3000L;
    /** 允许的浮动（±10%）：调价与换档都被允许，但"价值凑不满"必须当场看得见。 */
    private static final double TOLERANCE = 0.10;

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private List<BattlePassCfg> tiers() {
        return configs.all(BattlePassCfg.class).stream()
                .sorted(Comparator.comparingLong(BattlePassCfg::tier))
                .toList();
    }

    /** 这个 id 在 resource 或 item 表里存不存在（战令的奖励只允许这两种）。 */
    private boolean exists(String id) {
        return existsIn(ResourceCfg.class, id) || existsIn(ItemCfg.class, id);
    }

    private boolean existsIn(Class<?> type, String id) {
        try {
            configs.get(type, id);
            return true;
        } catch (ConfigException e) {
            return false;
        }
    }

    /** 一件道具的金币等值 = 商店金币货架上的那一行价格。没有金币价的道具没法估值，当场炸。 */
    private long valueOf(String itemId, long count) {
        for (ShopCfg row : configs.all(ShopCfg.class)) {
            if (row.priceCurrency() == ShopCfg.PriceCurrency.GOLD && itemId.equals(row.itemId())) {
                return row.price() * count;
            }
        }
        throw new AssertionError("道具 " + itemId + " 在金币货架上没有价，无法折算等值："
                + "战令奖励的价值口径只能锚在玩家看得见的价格上（B24 裁决④）");
    }

    @Test
    @DisplayName("20 档、每档 150 分、打满正好 3000 分（与买断定价同源）")
    void twentyTiersOfHundredFiftyPointsEach() {
        List<BattlePassCfg> rows = tiers();
        assertThat(rows).as("裁决②：每 5 级一档共 20 档").hasSize(20);
        long expected = 0L;
        for (int i = 0; i < rows.size(); i++) {
            expected += 150L;
            assertThat(rows.get(i).tier()).as("档位号必须连续从 1 开始（客户端按它回传）").isEqualTo(i + 1L);
            assertThat(rows.get(i).requiredPoints())
                    .as("第 %d 档的累计分必须是 %d", i + 1, expected).isEqualTo(expected);
        }
        assertThat(rows.get(rows.size() - 1).requiredPoints())
                .as("打满 20 档 = 3000 分，与买断定价同一个数").isEqualTo(3000L);
    }

    @Test
    @DisplayName("付费线总价值 ≈ 3000 金币等值（±10%），且严格高于免费线")
    void paidTrackIsWorthAboutThreeThousandGold() {
        long free = 0L;
        long paid = 0L;
        for (BattlePassCfg row : tiers()) {
            free += valueOfFree(row);
            paid += valueOfPaid(row);
        }
        long low = (long) (PAID_TARGET_GOLD * (1 - TOLERANCE));
        long high = (long) (PAID_TARGET_GOLD * (1 + TOLERANCE));
        assertThat(paid).as("付费线总价值 %d 金币等值，目标 %d ±10%%（锚是商店货架价）", paid, PAID_TARGET_GOLD)
                .isBetween(low, high);
        assertThat(paid).as("付费线必须明显高于免费线，否则买断没有理由").isGreaterThan(free * 3);
    }

    private long valueOfFree(BattlePassCfg row) {
        return row.freeRewardType() == BattlePassCfg.FreeRewardType.RESOURCE
                ? row.freeRewardCount()
                : valueOf(row.freeRewardId(), row.freeRewardCount());
    }

    private long valueOfPaid(BattlePassCfg row) {
        return row.paidRewardType() == BattlePassCfg.PaidRewardType.RESOURCE
                ? row.paidRewardCount()
                : valueOf(row.paidRewardId(), row.paidRewardCount());
    }

    @Test
    @DisplayName("每一条奖励都指向真实存在的资源或道具（查不到就是配置故障，玩家会领到一个空奖励）")
    void everyRewardPointsAtSomethingReal() {
        for (BattlePassCfg row : tiers()) {
            assertThat(exists(row.freeRewardId()))
                    .as("第 %d 档免费奖励 %s 在 resource / item 两张表里都查不到", row.tier(), row.freeRewardId())
                    .isTrue();
            assertThat(exists(row.paidRewardId()))
                    .as("第 %d 档付费奖励 %s 在 resource / item 两张表里都查不到", row.tier(), row.paidRewardId())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("每个赛季都配了战令限定外观，且指向真实存在的头像框（发货时查不到就没东西可发）")
    void everySeasonHasItsFrame() {
        List<BattlePassSeasonCfg> rows = configs.all(BattlePassSeasonCfg.class);
        assertThat(rows).as("本赛季必须有至少一行：买了战令立即到账的限定框就来自这里").isNotEmpty();
        for (BattlePassSeasonCfg row : rows) {
            assertThat(existsIn(com.ironoath.config.cfg.AvatarFrameCfg.class, row.frameId()))
                    .as("赛季 %s 的限定框 %s 在 avatar_frame 表里不存在", row.id(), row.frameId())
                    .isTrue();
        }
    }
}
