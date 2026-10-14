package com.ironoath.web.bot;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;

/**
 * B11滚动24h攻击频控。当前规则来自装配器，已经发生的事实保存在独立账本。
 * 出征只检查，真正打起来后按稳定行军来源记账；重启与规则热更都不清事实。
 */
@Component
public class BotAttackLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(BotAttackLimiter.class);
    private final BotRegistry bots;
    private final BotRulesAssembler assembler;
    private final BotAttackLedgerStore ledger;
    private final java.util.concurrent.ConcurrentHashMap<String,BotAttackLedgerStore.Attack> pendingFacts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,BotAttackLedgerStore.Attack> pendingReleases = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();

    public BotAttackLimiter(BotRegistry bots,BotRulesAssembler assembler,BotAttackLedgerStore ledger) {
        this.bots = bots;
        this.assembler = assembler;
        this.ledger = ledger;
    }

    public void requireQuota(String attackerId,String victimId,long now) {
        if (!bots.botOnHuman(attackerId,victimId)) return;
        long count = ledger.occupied(victimId,now);
        int limit = assembler.tuningRules().attackLimitPer24h();
        if (count < limit) return;
        blocked.incrementAndGet();
        LOG.info("攻击被拒：频控生效（B11）attacker={} victim={} 24h累计={} 上限={}",attackerId,victimId,count,limit);
        throw new BizException(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED,
                "该玩家24小时内被攻击"+count+"次（上限"+limit+"次），等额度恢复后再来");
    }

    /** 仅真正即将开战时申请；身份判断在任何伤亡副作用之前完成。 */
    public BotAttackLedgerStore.Attack beginAttack(String attackerId,String victimId,long now,String sourceId) {
        // 生产调用者已经取得该行军的FIGHTING CAS归属；此处尚未进入任何战损。
        var attack=new BotAttackLedgerStore.Attack(sourceId,victimId,now);
        BotAttackLedgerStore.Admission admission;
        try {
            if(!bots.botOnHuman(attackerId,victimId)) return null;
            admission=ledger.reserve(attack,assembler.tuningRules().attackLimitPer24h());
        } catch(RuntimeException preparationFailure) {
            // reserve提交但回执丢失也只释放本来源的pending，不删除已确认事实。
            cancelAttack(attack);
            throw new BizException(ErrorCode.SYSTEM_ERROR,"战斗准备暂时失败，未进入战斗，队伍安全返程",preparationFailure);
        }
        if(admission==BotAttackLedgerStore.Admission.ACQUIRED) return attack;
        if(admission==BotAttackLedgerStore.Admission.ALREADY_PRESENT)
            throw new IllegalStateException("该攻击来源已有已完成事实或未确定占位，需核对原结算，禁止重放："+sourceId);
        blocked.incrementAndGet();
        throw new BizException(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED,"该玩家当前24小时攻击额度已用尽（包含待确认战斗），队伍返程");
    }
    public void cancelAttack(BotAttackLedgerStore.Attack attack) {
        if(attack==null) return;
        pendingReleases.putIfAbsent(attack.sourceId(),attack);
        retryRelease(attack);
    }
    private void retryRelease(BotAttackLedgerStore.Attack attack) {
        try {
            ledger.release(attack);pendingReleases.remove(attack.sourceId(),attack);
        } catch(RuntimeException failure) {
            LOG.warn("明确未开战占位待释放 source={} victim={}，不冒充实际攻击",attack.sourceId(),attack.victimId(),failure);
        }
    }
    public void retryPendingReleases() { for(var release:pendingReleases.values()) retryRelease(release); }
    /** 战斗已完成：附属账失败不能阻止保存真实剩余兵力和返程。占位仍持久地扣减额度。 */
    public void finishAttack(BotAttackLedgerStore.Attack attack) {
        if(attack==null) return;
        pendingFacts.putIfAbsent(attack.sourceId(),attack);
        retryFact(attack);
    }
    private void retryFact(BotAttackLedgerStore.Attack attack) {
        try {
            if(ledger.record(attack)) recorded.incrementAndGet();
            pendingFacts.remove(attack.sourceId(),attack);
        } catch(RuntimeException failure) {
            LOG.warn("已完成Bot战斗待补账 source={} victim={}；保留占位，不重打、不改变实际返兵",attack.sourceId(),attack.victimId(),failure);
        }
    }

    /** 一次真实结算用稳定来源；重复回执保原发生时刻，不多计也不延长窗口。 */
    public void recordAttack(String attackerId,String victimId,long now,String sourceId) {
        if (!bots.botOnHuman(attackerId,victimId)) return;
        if (ledger.record(new BotAttackLedgerStore.Attack(sourceId,victimId,now))) recorded.incrementAndGet();
        LOG.info("频控记账：Bot攻击真人 attacker={} victim={} source={}",attackerId,victimId,sourceId);
    }

    /** 兼容诊断和测试的单次输入；生产结算必须传稳定行军来源。 */
    public void recordAttack(String attackerId,String victimId,long now) {
        recordAttack(attackerId,victimId,now,"manual:"+UUID.randomUUID());
    }

    public void evict(long now) { retryPendingReleases();for(var fact:pendingFacts.values()) retryFact(fact);ledger.evict(now); }
    public long blockedCount() { return blocked.get(); }
    public long recordedCount() { return recorded.get(); }
    /** 测试用显式清账；规则热更不调用此入口。 */
    public void reset() { ledger.clear();pendingFacts.clear();pendingReleases.clear();blocked.set(0L);recorded.set(0L); }
}
