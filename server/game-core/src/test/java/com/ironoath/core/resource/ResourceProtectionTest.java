package com.ironoath.core.resource;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：验证资源保护量与掠夺扣减（B04 §1 资源模型、验收 6）。
 * 依赖：无（纯 JUnit，不需要容器 —— 铁律 2）。
 *
 * <p>验收 6 的原文是「保护量内资源不可被掠夺（模拟掠夺，验证扣减正确）」。
 * 掠夺本身属 B07 大地图，但「能被抢走多少」这条规则属 B04 的资源模型，
 * 所以在这里用纯函数验证：不依赖行军、不依赖战斗，只验证扣减口径。
 */
class ResourceProtectionTest {

    private static final long RATIO_20 = FixedPoint.parse("0.20");

    @Test
    @DisplayName("验收6：保护量 = 容量 × 比例，且保护量内的资源一点都抢不走")
    void protectedAmountIsNotPlunderable() {
        long cap = 20_000L;
        long protection = ResourceProtection.protectedAmount(cap, RATIO_20);
        assertThat(protection).as("20000 × 20% = 4000").isEqualTo(4_000L);

        // 存量正好等于保护量：一点都抢不走
        assertThat(ResourceProtection.plunderable(protection, protection)).isZero();
        // 存量低于保护量：仍然一点都抢不走，而且不会出现负数
        assertThat(ResourceProtection.plunderable(1_000L, protection)).isZero();
        // 存量高于保护量：只有超出部分能被抢
        assertThat(ResourceProtection.plunderable(10_000L, protection)).isEqualTo(6_000L);
    }

    @Test
    @DisplayName("验收6：掠夺扣减按可掠夺量截断，存量永不为负（B04 禁止项）")
    void plunderTruncatesAndNeverGoesNegative() {
        long protection = 4_000L;
        // 攻方请求量超过守方可掠夺量：按可掠夺量截断
        assertThat(ResourceProtection.afterPlunder(10_000L, protection, 99_999L)).isEqualTo(4_000L);
        // 请求量小于可掠夺量：按请求量扣
        assertThat(ResourceProtection.afterPlunder(10_000L, protection, 1_000L)).isEqualTo(9_000L);
        // 存量全在保护范围内：请求多少都扣不动
        assertThat(ResourceProtection.afterPlunder(3_000L, protection, 3_000L)).isEqualTo(3_000L);
        // 存量为 0：不会扣成负数
        assertThat(ResourceProtection.afterPlunder(0L, protection, 5_000L)).isZero();
    }

    @Test
    @DisplayName("保护比例为 0 时全部可掠夺；为 1 时一点都抢不走（两个边界都必须可用）")
    void ratioBoundariesWork() {
        assertThat(ResourceProtection.protectedAmount(20_000L, 0L)).isZero();
        assertThat(ResourceProtection.plunderable(20_000L, 0L)).isEqualTo(20_000L);

        assertThat(ResourceProtection.protectedAmount(20_000L, FixedPoint.SCALE)).isEqualTo(20_000L);
        assertThat(ResourceProtection.plunderable(20_000L, 20_000L)).isZero();
    }

    @Test
    @DisplayName("保护量向下取整：宁少保护 1 点也不多保护 1 点")
    void protectedAmountRoundsDown() {
        // 3 × 10% = 0.3 ⇒ 向下取整为 0。若向上取整，容量 1 的资源也会凭空多出保护
        assertThat(ResourceProtection.protectedAmount(3L, FixedPoint.parse("0.10"))).isZero();
        // 20001 × 20% = 4000.2 ⇒ 4000
        assertThat(ResourceProtection.protectedAmount(20_001L, RATIO_20)).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("非法输入在调用点就炸：负数存量、比例越界、负数请求量都不允许静默通过")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ResourceProtection.protectedAmount(-1L, RATIO_20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cap");
        assertThatThrownBy(() -> ResourceProtection.protectedAmount(100L, FixedPoint.parse("1.20")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("取消了掠夺玩法");
        assertThatThrownBy(() -> ResourceProtection.plunderable(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("current");
        assertThatThrownBy(() -> ResourceProtection.afterPlunder(100L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("掠夺请求量");
    }
}
