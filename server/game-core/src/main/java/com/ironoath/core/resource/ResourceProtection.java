package com.ironoath.core.resource;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：资源保护量与掠夺扣减的纯计算（B04 §1 资源模型、验收 6）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>为什么单独抽一个类，而不是塞进 ResourceSettlement</b>：结算管「时间 → 产量」，
 * 保护量管「被攻击时能抢走多少」，两者没有任何共享状态。混在一起会让惰性结算的
 * 单测被迫准备一堆掠夺场景，也会让掠夺逻辑的修改波及结算路径。
 *
 * <p><b>保护量口径</b>：{@code protectedAmount = cap × 保护比例}（B04 §1），比例来自
 * global.RESOURCE_PROTECT_RATIO。按容量而不是按当前存量取比例，是为了让保护量可预期 ——
 * 玩家升仓库就能提高「打不掉的底」，这是一个真实的升级动机；按存量取比例则会让
 * 「故意把资源花光再上线」变成最优防守策略，那是在鼓励玩家不囤资源，与城建养成线相悖。
 *
 * <p><b>与 C00 公理二的关系</b>：公理二只保留三条「阻止冲突发生」的保护，
 * 资源保护量不在其中，因为它不阻止任何一次攻击 —— 玩家照样被打、照样掉八成、
 * 照样有复仇与求援动机。它避免的是「一次被掠空直接退游」，不是「冲突」。
 * 详见 global.json 里 RESOURCE_PROTECT_RATIO 的 why 字段。
 */
public final class ResourceProtection {

    private ResourceProtection() {
    }

    /**
     * 保护量 = 容量 × 保护比例，向下取整。
     *
     * <p>向下取整是刻意的：向上取整会在比例为 1.0 之外制造出「保护量 > 容量」的边界，
     * 也会让 1 点容量的资源凭空多出保护。少保护 1 点对玩家不可感知，多保护 1 点则是漏洞。
     *
     * @param cap         资源容量上限
     * @param ratioFixed  保护比例（定点 ×10000），必须在 [0, 1] 内
     */
    public static long protectedAmount(long cap, long ratioFixed) {
        if (cap < 0L) {
            throw new IllegalArgumentException("cap 不得为负：" + cap);
        }
        if (ratioFixed < 0L || ratioFixed > FixedPoint.SCALE) {
            throw new IllegalArgumentException("保护比例必须落在 [0, 1] 内，定点值=" + ratioFixed
                    + "。超过 1 意味着资源抢不走，等于取消了掠夺玩法（C00 公理二）。");
        }
        if (cap == 0L || ratioFixed == 0L) {
            return 0L;
        }
        return FixedPoint.round(FixedPoint.mul(FixedPoint.of(cap), ratioFixed));
    }

    /**
     * 可被掠走的量 = max(0, 存量 − 保护量)。
     *
     * <p>这是 B04 验收 6 的判定式：保护量内的资源不可被掠夺。
     * 返回「可掠夺上限」而不是直接扣减，是因为实际掠夺量还受攻方负重（B07 行军）约束，
     * 本方法只负责回答「守方这边最多能被拿走多少」。
     */
    public static long plunderable(long current, long protectedAmount) {
        if (current < 0L) {
            throw new IllegalArgumentException("current 不得为负：" + current);
        }
        if (protectedAmount < 0L) {
            throw new IllegalArgumentException("protectedAmount 不得为负：" + protectedAmount);
        }
        return Math.max(0L, current - protectedAmount);
    }

    /**
     * 执行一次掠夺扣减，返回扣减后的存量。
     *
     * <p>{@code requested} 超过可掠夺量时按可掠夺量截断，而不是抛异常：
     * 攻方负重与守方存量是两边独立算出来的，请求量偏大是正常情况（攻方不知道守方确切存量），
     * 抛异常会让一次正常的战斗结算变成 500。截断到下限即可，剩余部分留在守方仓库里。
     *
     * <p>存量永不为负（B04 禁止项：不要让资源出现负数）。
     */
    public static long afterPlunder(long current, long protectedAmount, long requested) {
        if (requested < 0L) {
            throw new IllegalArgumentException("掠夺请求量不得为负：" + requested);
        }
        long take = Math.min(requested, plunderable(current, protectedAmount));
        return current - take;
    }
}
