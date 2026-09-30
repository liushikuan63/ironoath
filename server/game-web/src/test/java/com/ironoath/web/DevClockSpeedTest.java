package com.ironoath.web;

import com.ironoath.web.config.ClockSource;
import com.ironoath.web.config.DevClockSpeed;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：钉住 {@link DevClockSpeed} 的三条承重结论（国策 48 小时那一段能验全靠它）。
 * 依赖：纯逻辑（{@code readSpeed} 静态方法 + {@code virtualAt} 纯算式）+ 反射读注解，
 * **不起 Spring 上下文、不 sleep**。
 *
 * <p><b>为什么这三条要能失败</b>：
 * ① 加速算式写成 {@code realNow × speed}（而不是「起点 + 经过的真实时间 × 倍速」）——
 *    服务一重启时间就跳到 1970 年之后，存档里所有时间戳当场失去意义，而它不报任何错；
 * ② 有人把 {@code @Profile("dev")} 摘掉（"反正本地设了环境变量就行"），
 *    那么 prod 只要有人设了同名环境变量，**整个服的时间就开始加速** ——
 *    周税提前到期、赛季提前结算、行军提前到达，而且**症状与"玩家长时间没上线"完全分不开**；
 * ③ 倍速解析放宽（接受 0 / 负数 / 小数）⇒ 服务以某个荒谬的倍速启动而没人发现。
 */
class DevClockSpeedTest {

    private static final long BOOT_REAL = 1_900_000_000_000L;

    @Test
    @DisplayName("加速算式是「起点 + 经过的真实时间 × 倍速」，不是「真实时刻 × 倍速」")
    void theFormulaIsBootPlusElapsedTimesSpeed() {
        DevClockSpeed clock = new DevClockSpeed();
        // 用反射把两个起点与倍速摆到已知值：算式本身必须是纯函数，不能读环境变量
        set(clock, "speed", 2_400L);
        set(clock, "bootReal", BOOT_REAL);
        set(clock, "bootVirtual", BOOT_REAL);

        // 过了 10 秒真实时间 ⇒ 虚拟时间过了 10×2400 = 24000 秒（6 小时 40 分）
        assertThat(clock.virtualAt(BOOT_REAL + 10_000L) - BOOT_REAL).isEqualTo(24_000_000L);
        // **起点之前与起点当刻都返回起点**：真实时刻早于 bootReal 只能来自时钟回拨，
        // 那种情况下返回"起点 - 一个巨大的负偏移"会让本轮直接判定过期
        assertThat(clock.virtualAt(BOOT_REAL)).isEqualTo(BOOT_REAL);
        assertThat(clock.virtualAt(BOOT_REAL - 5_000L)).isEqualTo(BOOT_REAL);
        // 倍速为 1 ⇒ 与真实时间逐位一致（不设环境变量时必须与改动前完全一样）
        set(clock, "speed", 1L);
        assertThat(clock.virtualAt(BOOT_REAL + 10_000L)).isEqualTo(BOOT_REAL + 10_000L);
    }

    @Test
    @DisplayName("只有正整数倍速才认：没设 / 0 / 负数 / 小数 / 垃圾值 / 超上限一律退 1")
    void onlyPositiveIntegersAreAccepted() {
        assertThat(DevClockSpeed.readSpeed(null)).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("   ")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("0")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("-60")).isEqualTo(1L);
        // 小数退 1 而不是取整：倍速取整会让「2.5 倍」悄悄变成 2 倍，而没人知道
        assertThat(DevClockSpeed.readSpeed("2.5")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("fast")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed("999999999")).isEqualTo(1L);
        assertThat(DevClockSpeed.readSpeed(" 60 ")).isEqualTo(60L);
        assertThat(DevClockSpeed.readSpeed("1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("dev 实现必须挂在 @Profile(\"dev\") 上：prod 读不到它是结构事实，不是「恰好没设环境变量」")
    void devImplementationIsProfileGated() {
        Profile profile = DevClockSpeed.class.getAnnotation(Profile.class);
        assertThat(profile).as("DevClockSpeed 上没有 @Profile 注解 —— prod 就会注册它，整个服的时间会开始加速")
                .isNotNull();
        assertThat(profile.value()).containsExactly("dev");

        // 环境变量的名字钉住：改名要同步改探针脚本，而脚本不在编译期依赖这里
        assertThat(DevClockSpeed.SPEED_ENV).isEqualTo("IRONOATH_DEV_TIME_SPEED");
        // 不设时走真实时间：SYSTEM 那个默认实现必须原样返回系统钟
        long before = System.currentTimeMillis();
        long got = ClockSource.SYSTEM.nowMillis();
        assertThat(got).isBetween(before, System.currentTimeMillis());
        assertThat(ClockSource.SYSTEM.speed()).as("默认倍速恒为 1").isEqualTo(1L);
    }

    private static void set(Object target, String field, long value) {
        try {
            Field f = DevClockSpeed.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setLong(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("改不到字段 " + field + "（字段改名了要同步改这条用例）", e);
        }
    }
}