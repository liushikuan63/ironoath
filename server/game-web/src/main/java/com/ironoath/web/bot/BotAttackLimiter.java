package com.ironoath.web.bot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.bot.BotTuning;

/**
 * 职责：B11 §五 的攻击频控 —— 「同一真人 24h 内被托管账号攻击 ≤ N 次」的判定与记账。
 * 依赖：{@link BotRulesAssembler}（规则来源）、{@link BotRegistry}（谁被托管，身份问题只在那里回答）。
 *
 * <p><b>为什么单独一个组件，而不是各调用点 new 一个 {@code BotTuning}</b>：
 * 频控的<b>状态</b>就住在 {@code BotTuning.attackLog} 里。每次现 new 一个实例等于每次带一本空账本 ——
 * 表现是「限额永远查不出来」（每次都返回 0 次），而代码看起来完全正常。
 * 所以本类持有唯一实例，并且与调度器同一条纪律：<b>规则一变就重建</b>（重建会清空 24h 记录，
 * 打一条 INFO 说明，避免"改了表之后限额短暂失效"变成无人知晓的事）。
 *
 * <p><b>为什么方法是 synchronized</b>：{@code BotTuning.attackLog} 是普通 HashMap，
 * 而它的两个写入口（守卫判定、结算记账）来自不同请求线程（攻击方点的那个请求与到期扫描）。
 * 并发写 HashMap 会静默丢数据 —— 表现是"某几次攻击没被计入"，而那正是本类要防的东西。
 *
 * <p><b>「判在这里、记在结算」的分工</b>：判在 {@code AttackGuardService}（出征那一刻，
 * 玩家还能改主意），记在 {@code MarchAppService.resolveAttack}（仗真的打起来了）——
 * 于是「出门又召回」与「半路目标没了」不会白吃真人的被攻击额度。
 */
@Component
public class BotAttackLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(BotAttackLimiter.class);

    private final BotRegistry bots;
    private final BotRulesAssembler assembler;

    /** 当前规则 + 它对应的账本。规则一变就重建（见类注释）。 */
    private final AtomicReference<Held> held = new AtomicReference<>();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();

    public BotAttackLimiter(BotRegistry bots, BotRulesAssembler assembler) {
        this.bots = bots;
        this.assembler = assembler;
    }

    private record Held(BotTuning.Rules rules, BotTuning tuning) {
    }

    /**
     * 频控闸门：这次「Bot 打真人」还有额度吗。不是这一类组合就一律放行（见 {@code botOnHuman}）。
     *
     * @throws BizException 额度用尽时抛出（{@code BOT_ATTACK_QUOTA_EXCEEDED}）
     */
    public synchronized void requireQuota(String attackerId, String victimId, long now) {
        if (!bots.botOnHuman(attackerId, victimId)) {
            return;
        }
        BotTuning tuning = tuning();
        if (tuning.mayAttack(victimId, now)) {
            return;
        }
        blocked.incrementAndGet();
        LOG.info("攻击被拒：频控生效（B11 §五）attacker={} victim={} 24h 内已被攻击={} 次 上限={}",
                attackerId, victimId, tuning.attacksIn24h(victimId, now),
                tuning.rules().attackLimitPer24h());
        throw new BizException(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED,
                "该玩家 24 小时内被攻击 " + tuning.attacksIn24h(victimId, now) + " 次（上限 "
                        + tuning.rules().attackLimitPer24h() + " 次），等额度恢复后再来");
    }

    /**
     * 记账：一次「Bot 打真人」的攻击真的发生了（仗打起来了，胜负都算）。
     *
     * <p>不是这一类组合就什么都不做 —— 与 {@link #requireQuota} 共用同一份 {@code botOnHuman} 判定，
     * 所以「谁受限」只有一个答案。
     */
    public synchronized void recordAttack(String attackerId, String victimId, long now) {
        if (!bots.botOnHuman(attackerId, victimId)) {
            return;
        }
        int count = tuning().recordAttack(victimId, now);
        recorded.incrementAndGet();
        LOG.info("频控记账：Bot 攻击真人 attacker={} victim={} 24h 内累计={} 次",
                attackerId, victimId, count);
    }

    /**
     * 清理超过 24h 的记录。由 Bot tick 每轮顺带跑（`BotRuntimeService.tick`）——
     * 那是本仓库唯一的"系统自己会动的时刻"（服务端禁常驻定时器）。
     *
     * <p>不清理的话这张表只增不减：它按受害者索引、每人几条，量级不大，
     * 但"只增不减"在这个项目里已经被记为内存泄漏而不是缓存（同 {@code BotTuning.evict} 的注释）。
     */
    public synchronized void evict(long now) {
        tuning().evict(now);
    }

    /** 被频控挡下的攻击次数（诊断：持续增长说明某几个真人被 Bot 盯着打）。 */
    public long blockedCount() {
        return blocked.get();
    }

    /** 已记账的 Bot→真人 攻击次数。 */
    public long recordedCount() {
        return recorded.get();
    }

    /** 测试用：清零计数与账本（同一上下文里跑多条用例时避免互相污染）。 */
    public synchronized void reset() {
        held.set(null);
        blocked.set(0L);
        recorded.set(0L);
    }

    /** 拿到与当前配置一致的账本；规则变了就重建（重建意味着 24h 记录清零，所以留一条 INFO）。 */
    private BotTuning tuning() {
        BotTuning.Rules rules = assembler.tuningRules();
        Held current = held.get();
        if (current != null && current.rules().equals(rules)) {
            return current.tuning();
        }
        BotTuning next = new BotTuning(rules);
        held.set(new Held(rules, next));
        if (current != null) {
            LOG.info("攻击频控参数已热更：上限={} 次/24h（账本随规则重建，已记的攻击次数清零）",
                    rules.attackLimitPer24h());
        }
        return next;
    }
}
