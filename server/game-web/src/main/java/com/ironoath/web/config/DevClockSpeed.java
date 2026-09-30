package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 职责：<b>只在 dev profile</b> 生效的服务端时间加速（见 {@link ClockSource}）。
 * 依赖：Spring 的 {@code @Profile}；倍速从环境变量 {@code IRONOATH_DEV_TIME_SPEED} 读。
 *
 * <p><b>解决的是哪一类问题</b>：B13 §4 的国策一轮要 48 小时（24h 投票窗 + 24h 生效段），
 * 赛季与周税同理。探针与真机截图都等不到，于是那一整段只能靠拨钟的单元测试 ——
 * 而「文本断言全绿而画面是坏的」在本仓已经发生过不止一次。
 * 倍速让 48 小时在几分钟内**真实流逝**：结算、开窗、到期全是同一套代码在跑，
 * 没有后门端点、没有第二个时间源。
 *
 * <p><b>为什么是「倍速」而不是「偏移」</b>：偏移是跳时间（要一个能改的入口 ⇒ 端点或文件，
 * 两个都碰红线或让时间有两个来源）；倍速是**让时间走得快**，入口只有启动时那一个环境变量，
 * 之后不可改 —— 那正是「模式隔离」那条纪律要的形状：不设就与改动前逐位一致。
 *
 * <p><b>算式</b>：{@code virtual = bootVirtual + (realNow - bootReal) × speed}。
 * 刻意<b>不</b>用 {@code realNow × speed}：后者会让服务重启后时间跳到"现在 × 倍速"
 * （今天 2400 倍速就等于 1970 年之后），所有存档里的时间戳全部失去意义。
 *
 * <p><b>{@code @Profile("dev")} 是承重的那一层</b>：prod 与 test 上下文里这个 bean 不存在，
 * {@code ObjectProvider.getIfAvailable()} 返回 null ⇒ 走 {@link ClockSource#SYSTEM}
 * ⇒ 走系统钟。于是「prod 读不到它」是**结构事实**，不是「忘了设环境变量」。
 * {@code DevClockSpeedTest} 用反射钉住这条注解 —— 有人摘掉它，测试立刻红。
 *
 * <p><b>开档时打一行 INFO</b>：本地起服务的人一眼能看到「时间是加速档」，
 * 而这行日志在 prod 不可能出现。审计一条比注释一条可靠。
 */
@Component
@Profile("dev")
public class DevClockSpeed implements ClockSource {

    private static final Logger LOG = LoggerFactory.getLogger(DevClockSpeed.class);

    /** 倍速的环境变量名。**不设 = 1（真实时间）**。 */
    public static final String SPEED_ENV = "IRONOATH_DEV_TIME_SPEED";
    /** 倍速上限：再大只会让探针的「等窗口开」变成「看不清发生了什么」，而且会放大浮点溢出。 */
    public static final long MAX_SPEED = 100_000L;

    private final long speed;
    private final long bootReal;
    private final long bootVirtual;

    public DevClockSpeed() {
        this.speed = readSpeed(System.getenv(SPEED_ENV));
        this.bootReal = System.currentTimeMillis();
        // 起点对齐到分钟：加速档里的秒级时刻不该长得像真时间（截图上写着「还有 3 秒」会很怪）
        this.bootVirtual = (this.bootReal / 60_000L) * 60_000L;
        if (speed > 1L) {
            LOG.info("dev 时间加速档已开：倍速={}×（1 秒真实时间 = {} 虚拟秒）。"
                            + "结算、开窗、到期走的都是同一套代码；prod 读不到这个 bean。",
                    speed, speed);
        }
    }

    /** 现在几点（服务端时间戳）。 */
    @Override
    public long nowMillis() {
        return virtualAt(System.currentTimeMillis());
    }

    /**
     * 给定「真实时刻」算出对应的虚拟时刻。
     *
     * <p><b>纯函数</b>：不读环境、不读字段 —— 这样加速算式本身能被单测钉住，
     * 而不必起一个 Spring 容器再等它跑起来。
     */
    public long virtualAt(long realMillis) {
        if (speed <= 1L) {
            return realMillis;
        }
        long elapsed = realMillis - bootReal;
        if (elapsed <= 0L) {
            return bootVirtual;
        }
        return bootVirtual + elapsed * speed;
    }

    /** 当前倍速（1 = 真实时间）。给日志与探针自查用。 */
    @Override
    public long speed() {
        return speed;
    }

    /**
     * 环境变量 → 倍速。
     *
     * <p><b>只有正整数（≥1）才认</b>：0、负数、小数、垃圾值一律退 1（真实时间）。
     * 退 1 而不是抛错，是因为这一格出问题不该让 dev 服务起不来 ——
     * 而「accidentally 变成 2×」比「accidentally 变慢」危险得多，所以只接受显式的正整数。
     */
    public static long readSpeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return (parsed >= 1L && parsed <= MAX_SPEED) ? parsed : 1L;
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}