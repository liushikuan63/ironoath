package com.ironoath.web.world;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.WorldTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 整支战略集结撤防：先持久原计划，再返程、保国清兵、登记队列、最后确认完成。
 * nation→rally→target内只恢复当前目标；全局候选发现只能在外层请求驱动中执行。
 */
@Service
public final class StrategicGarrisonWithdrawal {
    private static final Logger LOG=LoggerFactory.getLogger(StrategicGarrisonWithdrawal.class);
    private final ConfigRegistry configs;
    private final TargetOperationStore operations;
    private final TargetOccupationStore occupations;
    private final MarchRepository marches;
    private final MarchDueQueue due;
    private final StrategicTargetDefender defenders;
    private final SocialAppService social;
    private final PlayerLock locks;
    private final ThreadLocal<String> targetScope=new ThreadLocal<>();
    private static final class ContextChanged extends RuntimeException {}

    public StrategicGarrisonWithdrawal(ConfigRegistry configs,TargetOperationStore operations,
            TargetOccupationStore occupations,MarchRepository marches,MarchDueQueue due,
            StrategicTargetDefender defenders,SocialAppService social,PlayerLock locks) {
        this.configs=configs;this.operations=operations;this.occupations=occupations;this.marches=marches;
        this.due=due;this.defenders=defenders;this.social=social;this.locks=locks;
    }

    /** 当前槽仍留存时按冻结主人鉴权重放；完成后只回执，不重新创建March或队列。 */
    public Optional<March> replay(String playerId,String marchId) {
        var slot=operations.findByOperationId("withdraw:"+marchId).orElse(null);
        if(slot!=null) {
            requirePlayer(playerId,withdrawal(slot).stationed().playerId());
            if(!slot.completed())slot=recoverTarget(slot.targetId(),slot.operationId());
            if(slot!=null && slot.completed() && slot.operationId().equals("withdraw:"+marchId))
                return Optional.of(March.fromSnapshot(withdrawal(slot).returning()));
        }
        // 槽可能已被后续操作替换。只报告还在生命周期内的权威返程，不重新分配代次。
        var current=marches.findById(marchId).orElse(null);
        if(current!=null && current.hasGarrisonOwnership() && current.status()==March.Status.RETURNING) {
            requirePlayer(playerId,current.playerId());
            // 第一次查槽为空可能早于另一召回创建计划，RETURNING/EMPTY尚不证明队列和完成档已落盘。
            // 沿原集结拿锁后重查，锁内只恢复同一上下文，不能转拿另一国家/集结。
            return social.runRallyDepartureLocked(current.rallyId(),()->withTarget(current.targetId(),()->{
                var latest=operations.findByTargetId(current.targetId()).orElse(null);
                if(latest!=null && !latest.completed()) {
                    if(!sameContext(withdrawal(latest),current))throw state("目标由另一集结恢复中，请稍后重试");
                    applyLocked(latest);
                    latest=operations.findByTargetId(current.targetId()).orElseThrow();
                    if(!latest.completed())throw state("撤防尚未确认完成，不能提前返回成功");
                }
                if(latest!=null && latest.operationId().equals("withdraw:"+marchId)) {
                    requirePlayer(playerId,withdrawal(latest).stationed().playerId());
                    return Optional.of(March.fromSnapshot(withdrawal(latest).returning()));
                }
                var live=marches.findById(marchId).orElse(null);
                if(live==null)return Optional.empty();
                requirePlayer(playerId,live.playerId());requireDetachedReturning(live);
                if(live.status()!=March.Status.RETURNING)throw state("驻军状态已变化，请刷新后重试");
                return Optional.of(live);
            }));
        }
        return Optional.empty();
    }

    public March recall(String playerId,String marchId,long now) {
        for(int attempt=0;attempt<2;attempt++) {
            var replay=replay(playerId,marchId);if(replay.isPresent())return replay.get();
            var observed=requireOwn(playerId,marchId);
            if(observed.hasGarrisonOwnership() && observed.status()==March.Status.RETURNING) {
                var completed=replay(playerId,marchId);if(completed.isPresent())return completed.get();
                throw state("驻军已进入返程，请刷新后重试");
            }
            if(!observed.hasGarrisonOwnership() || observed.status()!=March.Status.STATIONED)
                throw state("只有实际驻军可以执行目标撤防");
            try {
                return social.runRallyDepartureLocked(observed.rallyId(),()->withTarget(observed.targetId(),()->{
                    var slot=operations.findByTargetId(observed.targetId()).orElse(null);
                    if(slot!=null && !slot.completed()) {
                        if(!sameContext(withdrawal(slot),observed))throw new ContextChanged();
                        applyLocked(slot);
                        slot=operations.findByTargetId(observed.targetId()).orElseThrow();
                    }
                    if(slot!=null && slot.operationId().equals("withdraw:"+marchId) && slot.completed())
                        return March.fromSnapshot(withdrawal(slot).returning());
                    var latest=requireOwn(playerId,marchId);
                    if(latest.status()==March.Status.RETURNING){requireDetachedReturning(latest);return latest;}
                    var target=requireTarget(latest.targetId());
                    var defender=defenders.read(target);
                    if(defender.march()==null || !defender.march().id().equals(marchId))
                        throw state("该队伍不是当前目标的实际驻军");
                    requirePlayer(playerId,defender.march().playerId());
                    long version=slot==null?0:slot.version();
                    long sequence=slot==null?1:Math.addExact(slot.sequence(),1);
                    var plan=TargetOperationStore.WithdrawalPlan.create(defender.occupation(),
                            March.fromSnapshot(defender.march()),defender.marchVersion(),sequence,now);
                    var active=new TargetOperationStore.Snapshot(plan.targetId(),Math.addExact(version,1),false,plan);
                    if(!operations.compareAndSet(active,version))throw new ContextChanged();
                    applyLocked(active);
                    return March.fromSnapshot(plan.returning());
                }));
            } catch(ContextChanged changed){ /* 释放全部旧锁后重新定位，不能锁内转拿另国。 */ }
        }
        throw state("目标操作归属发生变化，请刷新后重试");
    }

    /** 发现候选不解码计划，逐目标隔离；禁止在target锁内调用全局恢复。 */
    public void recoverPending() {
        if(targetScope.get()!=null)throw new IllegalStateException("目标锁内禁止全局恢复");
        List<String> candidates;
        try {
            var ids=WorldTargets.from(configs).targets().stream().map(t->t.definition().id()).toList();
            candidates=operations.pendingTargetIds(ids);
        } catch(RuntimeException unavailable){LOG.error("目标操作候选读取失败，战略推进仍须经过单目标完成屏障",unavailable);return;}
        for(String target:candidates) {
            try{recoverTarget(target,null);}
            catch(RuntimeException failure){LOG.error("目标撤防计划待恢复，保留原兵权/代次 targetId={}",target,failure);}
        }
    }

    /** 调用方已持有该March的国家/集结锁；恢复未确认完成时不得进入退款/删除。 */
    public <T> T runMarchLocked(March observed,Supplier<T> advance) {
        return withTarget(observed.targetId(),()->{
            var slot=operations.findByTargetId(observed.targetId()).orElse(null);
            if(slot!=null && !slot.completed()) {
                if(!sameContext(withdrawal(slot),observed))throw state("目标由另一集结恢复中，请稍后重试");
                applyLocked(slot);
            }
            var confirmed=operations.findByTargetId(observed.targetId()).orElse(null);
            if(confirmed!=null && !confirmed.completed())throw state("目标操作尚未确认完成，不能推进归队");
            var current=marches.findById(observed.id()).orElse(null);
            if(current!=null && current.hasGarrisonOwnership() && current.status()==March.Status.RETURNING)
                requireDetachedReturning(current);
            return advance.get();
        });
    }

    private TargetOperationStore.Snapshot recoverTarget(String target,String requiredOperation) {
        for(int attempt=0;attempt<2;attempt++) {
            var observed=operations.findByTargetId(target).orElse(null);
            if(observed==null || (requiredOperation!=null && !requiredOperation.equals(observed.operationId())))return null;
            if(observed.completed())return observed;
            try {
                return social.runRallyDepartureLocked(withdrawal(observed).stationed().rallyId(),()->withTarget(target,()->{
                    var latest=operations.findByTargetId(target).orElseThrow(ContextChanged::new);
                    if(!sameContext(withdrawal(observed),withdrawal(latest)))throw new ContextChanged();
                    if(requiredOperation!=null && !requiredOperation.equals(latest.operationId()))return null;
                    applyLocked(latest);
                    var done=operations.findByTargetId(target).orElseThrow();
                    if(!done.completed() || !withdrawal(done).equals(withdrawal(latest)))throw new ContextChanged();
                    return done;
                }));
            } catch(ContextChanged changed){ /* 仅在外层重新分派。 */ }
        }
        throw state("目标恢复归属持续变化，请刷新后重试");
    }

    private void applyLocked(TargetOperationStore.Snapshot slot) {
        if(slot.completed())return;
        var plan=withdrawal(slot);var target=requireTarget(slot.targetId());
        if(target.coord().x()!=plan.before().x() || target.coord().y()!=plan.before().y()
                || !target.definition().kind().name().equals(plan.before().kind().name()))
            throw state("目标计划锚点与当前配置不符，不能移动原部队");
        defenders.requireOriginalRally(March.fromSnapshot(plan.stationed()),plan.before(),target);
        var owner=occupations.findByTargetIds(List.of(slot.targetId())).get(slot.targetId());
        boolean before=plan.before().equals(owner),after=plan.after().equals(owner);
        if(!before && !after)throw state("撤防原归属版本已变化，保留计划等待核对");
        long version=marches.versionOf(plan.stationed().id());
        var current=marches.findById(plan.stationed().id()).orElseThrow(()->state("撤防原行军缺失，不能推断已归队"));
        boolean returned=version==Math.addExact(plan.marchVersion(),1) && current.snapshot().equals(plan.returning());
        boolean original=version==plan.marchVersion() && current.snapshot().equals(plan.stationed());
        if(!returned && !original)throw state("撤防兵权或行军版本已变化，不能覆盖其他写入");
        if(after && !returned)throw state("目标已清兵但原行军未确认返程，不能补造步骤");
        if(original) {
            try{marches.save(March.fromSnapshot(plan.returning()),plan.marchVersion());}
            catch(RuntimeException unknown){
                try{if(!returnConfirmed(plan))throw unknown;}
                catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);throw unknown;}
            }
        }
        if(before) {
            try {
                if(!occupations.compareAndSet(plan.after(),plan.before().version()) && !ownerConfirmed(plan))
                    throw state("撤防归属CAS冲突，不能抓新版本重试旧计划");
            } catch(RuntimeException unknown){
                try{if(!ownerConfirmed(plan))throw unknown;}
                catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);throw unknown;}
            }
        }
        due.reschedule(plan.returning().id(),plan.returning().returnArriveAt());
        var done=new TargetOperationStore.Snapshot(slot.targetId(),Math.addExact(slot.version(),1),true,plan);
        try {
            if(!operations.compareAndSet(done,slot.version()) && !doneConfirmed(done))
                throw state("撤防完成状态未确认，不能推进退款或删除");
        } catch(RuntimeException unknown){
            try{if(!doneConfirmed(done))throw unknown;}
            catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);throw unknown;}
        }
    }

    private boolean returnConfirmed(TargetOperationStore.WithdrawalPlan plan) {
        long version=marches.versionOf(plan.returning().id());
        return version==Math.addExact(plan.marchVersion(),1)
                && marches.findById(plan.returning().id()).map(m->m.snapshot().equals(plan.returning())).orElse(false);
    }
    private boolean ownerConfirmed(TargetOperationStore.WithdrawalPlan plan){
        return plan.after().equals(occupations.findByTargetIds(List.of(plan.targetId())).get(plan.targetId()));
    }
    private boolean doneConfirmed(TargetOperationStore.Snapshot done){return operations.findByTargetId(done.targetId()).filter(done::equals).isPresent();}
    private void requireDetachedReturning(March march){
        if(march.returnArriveAt()==null)throw state("实际驻军返程时刻缺失，不能推断已完成召回");
        var owner=occupations.findByTargetIds(List.of(march.targetId())).get(march.targetId());
        if(owner!=null && TargetOccupationStore.Garrison.stationed(march.id()).equals(owner.garrison()))
            throw state("返程驻军仍被目标关联且缺少完成证明，保留队伍等待恢复");
        if(owner!=null && owner.operationId().equals("withdraw:"+march.id())
                && operations.findByTargetId(march.targetId()).isEmpty())
            throw state("撤防归属存在但操作槽缺失，不能推断队列和完成步骤已提交");
    }
    private WorldTargets.Target requireTarget(String id){
        return WorldTargets.from(configs).targets().stream().filter(t->t.definition().id().equals(id)).findFirst()
                .orElseThrow(()->state("战略目标目录缺失，须先处理原驻军迁移"));
    }
    private <T> T withTarget(String id,Supplier<T> action){
        var prior=targetScope.get();
        if(prior!=null && !prior.equals(id))throw new IllegalStateException("目标锁内禁止转入另一目标");
        return locks.runLocked("@strategic-target:"+id,3000,()->{
            targetScope.set(id);try{return action.get();}finally{if(prior==null)targetScope.remove();else targetScope.set(prior);}
        });
    }
    private static TargetOperationStore.WithdrawalPlan withdrawal(TargetOperationStore.Snapshot slot){
        if(slot.kind()!=TargetOperationStore.Kind.WITHDRAWAL)
            throw state("战略战斗操作尚未确认恢复，不能按撤防或归队推进");
        return slot.plan();
    }
    private static boolean sameContext(TargetOperationStore.WithdrawalPlan plan,March march){return plan.stationed().rallyId().equals(march.rallyId());}
    private static boolean sameContext(TargetOperationStore.WithdrawalPlan first,TargetOperationStore.WithdrawalPlan second){
        return first.stationed().rallyId().equals(second.stationed().rallyId())
                && first.before().nationId().equals(second.before().nationId());
    }
    private March requireOwn(String player,String id){var m=marches.findById(id).orElseThrow(()->missing());requirePlayer(player,m.playerId());return m;}
    private static void requirePlayer(String player,String actual){if(!actual.equals(player))throw missing();}
    private static BizException missing(){return new BizException(ErrorCode.MARCH_NOT_FOUND,"行军不存在");}
    private static BizException state(String message){return new BizException(ErrorCode.MARCH_STATE_INVALID,message);}
}
