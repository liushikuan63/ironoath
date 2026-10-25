package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.nation.NationCapitalRelocationStore;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.store.mongo.MongoNationCapitalRelocationStore;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/** Durable, idempotent capital relocation across the Nation, occupation, garrison, and live target stores. */
@Service
public final class NationCapitalRelocationService implements ApplicationRunner {
    private static final String CAPITAL_TARGET_ID = "capital_center";
    private static final long LOCK_TIMEOUT_MS = 3000L;
    private static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final NationStore nations;
    private final NationRulesAssembler nationRules;
    private final NationCapitalRelocationStore relocations;
    private final TargetOccupationStore occupations;
    private final TargetOperationStore operations;
    private final MarchRepository marches;
    private final WarStore wars;
    private final ConfigRegistry configs;
    private final WorldRepository world;
    private final PlayerRepository players;
    private final PlayerLock playerLocks;
    private final TimeService time;

    public NationCapitalRelocationService(NationStore nations, NationRulesAssembler nationRules,
            NationCapitalRelocationStore relocations, TargetOccupationStore occupations,
            TargetOperationStore operations, MarchRepository marches, WarStore wars,
            ConfigRegistry configs, WorldRepository world, PlayerRepository players,
            PlayerLock playerLocks, TimeService time) {
        this.nations=Objects.requireNonNull(nations);
        this.nationRules=Objects.requireNonNull(nationRules);
        this.relocations=Objects.requireNonNull(relocations);
        this.occupations=Objects.requireNonNull(occupations);
        this.operations=Objects.requireNonNull(operations);
        this.marches=Objects.requireNonNull(marches);
        this.wars=Objects.requireNonNull(wars);
        this.configs=Objects.requireNonNull(configs);
        this.world=Objects.requireNonNull(world);
        this.players=Objects.requireNonNull(players);
        this.playerLocks=Objects.requireNonNull(playerLocks);
        this.time=Objects.requireNonNull(time);
    }

    /** Entry point used by the nation API. requestId is the durable idempotency key. */
    public Nation relocate(String actorId, String nationId, String requestId, int x, int y) {
        if(actorId==null||actorId.isBlank()||nationId==null||nationId.isBlank()
                ||requestId==null||requestId.isBlank())
            throw locked("迁都请求缺少玩家、国家或请求标识");
        return runNationLocked(nationId,()->playerLocks.runLocked("@strategic-target:"+CAPITAL_TARGET_ID,
                LOCK_TIMEOUT_MS,()->relocateLocked(actorId,nationId,requestId,x,y,time.serverNow())));
    }

    /** Startup recovery runs before the application starts serving map or battle requests. */
    @Override public void run(ApplicationArguments args) { recoverPending(); }

    public <T> T withCapitalTargetLock(Supplier<T> action) {
        return playerLocks.runLocked("@strategic-target:"+CAPITAL_TARGET_ID,LOCK_TIMEOUT_MS,action);
    }

    /** Cheap fail-closed check used while holding the strategic target lock; it acquires no Nation locks. */
    public boolean hasPendingRelocations() {
        return !relocations.findPending(1).isEmpty();
    }

    public void recoverPending() {
        for(var pending:relocations.findPending(100))
            runNationLocked(pending.nationId(),()->playerLocks.runLocked(
                    "@strategic-target:"+CAPITAL_TARGET_ID,LOCK_TIMEOUT_MS,()->{resume(pending.nationId());return null;}));
    }

    private Nation relocateLocked(String actorId,String nationId,String requestId,int x,int y,long now) {
        var nation=activeNation(nationId);
        requireMayRelocate(actorId,nation,now);
        var prior=relocations.findByNationId(nationId).orElse(null);
        var replay=prior==null?null:prior.requestHistory().stream().filter(r->r.requestId().equals(requestId)).findFirst().orElse(null);
        if(replay!=null) {
            if(replay.x()!=x||replay.y()!=y)throw locked("同一迁都请求标识不能改写目标坐标");
            if(prior.stage()!=NationCapitalRelocationStore.Stage.COMPLETED)resume(nationId);
            return activeNation(nationId);
        }
        if(prior!=null&&prior.stage()!=NationCapitalRelocationStore.Stage.COMPLETED)
            throw locked("该国家有迁都操作待恢复");
        int oldX=Math.toIntExact(nation.capitalX()),oldY=Math.toIntExact(nation.capitalY());
        if(oldX==x&&oldY==y) {
            var history=new ArrayList<NationCapitalRelocationStore.RequestReceipt>(prior==null?List.of():prior.requestHistory());
            history.add(new NationCapitalRelocationStore.RequestReceipt(requestId,x,y));
            long expectedVersion=prior==null?0:prior.version();
            NationCapitalRelocationStore.Snapshot receipt;
            if(prior==null) {
                var snapshot=nation.snapshot();
                receipt=new NationCapitalRelocationStore.Snapshot(nationId,"capital-noop:"+nationId+":"+requestId,
                        requestId,1,NationCapitalRelocationStore.Stage.COMPLETED,nation.version(),oldX,oldY,x,y,
                        null,-1,null,snapshot,snapshot,Map.of(),Map.of(),Map.of(),now,history);
            } else {
                receipt=prior.withRequestHistory(history);
            }
            if(!relocations.compareAndSet(receipt,expectedVersion)
                    &&!relocations.findByNationId(nationId).filter(receipt::equals).isPresent())
                throw locked("No-op relocation receipt could not be saved; retry request");
            return activeNation(nationId);
        }
        Coord destination=Coord.of(x,y);
        int worldSize=Math.toIntExact(configs.longParam("WORLD_SIZE"));
        if(!destination.withinWorld(worldSize))throw locked("新都坐标超出世界范围");
        if(world.cityAt(destination).isPresent())throw locked("新都坐标已有玩家城");
        try { WorldTargets.from(configs).withCapitalCoord(destination); }
        catch(IllegalArgumentException|IllegalStateException conflict) { throw locked("新都坐标与现有战略目标冲突"); }

        boolean currentWarTarget=wars.findLatest().map(w->nationId.equals(w.targetNationId())).orElse(false);
        if(currentWarTarget) {
            if(!operations.pendingTargetIds(List.of(CAPITAL_TARGET_ID)).isEmpty())
                throw locked("王城COMBAT或其他目标操作尚未完成");
            boolean inbound=marches.findByTargetId(CAPITAL_TARGET_ID).stream()
                    .anyMatch(m->m.status()==March.Status.MARCHING||m.status()==March.Status.FIGHTING);
            if(inbound)throw locked("仍有王城攻击行军未结算或返程完成");
        }

        var beforeNation=nation.snapshot();
        Nation after=Nation.fromSnapshot(beforeNation,nationRules.rules());
        after.moveCapital(x,y);after.incrementVersion();
        var occupation=currentWarTarget?occupations.findByTargetIds(List.of(CAPITAL_TARGET_ID)).get(CAPITAL_TARGET_ID):null;
        if(occupation!=null&&(occupation.kind()!=com.ironoath.core.world.WorldGenerator.EntityType.CAPITAL
                ||occupation.x()!=oldX||occupation.y()!=oldY))
            throw locked("王城占领记录与当前都城坐标不一致，拒绝迁移");

        var beforeGarrisons=new LinkedHashMap<String,March.Snapshot>();
        var afterGarrisons=new LinkedHashMap<String,March.Snapshot>();
        var garrisonVersions=new LinkedHashMap<String,Long>();
        TargetOccupationStore.Snapshot afterOccupation=null;
        if(occupation!=null) {
            if(occupation.garrison()!=null&&occupation.garrison().kind()==TargetOccupationStore.Garrison.Kind.STATIONED) {
                String id=occupation.garrison().marchId();
                March garrison=marches.findById(id).orElseThrow(()->locked("王城驻军行军记录缺失"));
                var before=garrison.snapshot();
                if(garrison.status()!=March.Status.STATIONED||garrison.targetType()!=March.TargetType.STRATEGIC_TARGET||!garrison.isRallyMarch()
                        ||!CAPITAL_TARGET_ID.equals(garrison.targetId())||!garrison.to().equals(Coord.of(oldX,oldY)))
                    throw locked("王城驻军状态与占领记录不一致，拒绝迁移");
                garrison.relocateStationedGarrison(destination);
                beforeGarrisons.put(id,before);afterGarrisons.put(id,garrison.snapshot());
                garrisonVersions.put(id,marches.versionOf(id));
            }
            afterOccupation=new TargetOccupationStore.Snapshot(occupation.targetId(),occupation.kind(),x,y,
                    occupation.nationId(),occupation.nationName(),occupation.capturedAt(),
                    "capital-move:"+nationId+":"+requestId,Math.addExact(occupation.version(),1),
                    occupation.garrison(),occupation.ownershipHistory());
        }

        long expectedVersion=prior==null?0:prior.version();
        var requestHistory=new ArrayList<NationCapitalRelocationStore.RequestReceipt>(prior==null?List.of():prior.requestHistory());
        requestHistory.add(new NationCapitalRelocationStore.RequestReceipt(requestId,x,y));
        var plan=new NationCapitalRelocationStore.Snapshot(nationId,"capital-move:"+nationId+":"+requestId,
                requestId,Math.addExact(expectedVersion,1),NationCapitalRelocationStore.Stage.PREPARED,
                nation.version(),oldX,oldY,x,y,occupation,occupation==null?-1:occupation.version(),afterOccupation,
                beforeNation,after.snapshot(),beforeGarrisons,afterGarrisons,garrisonVersions,-1,requestHistory);
        if(!relocations.compareAndSet(plan,expectedVersion)
                &&!relocations.findByNationId(nationId).filter(plan::equals).isPresent())
            throw locked("迁都计划写入竞争失败，请重试");
        resume(nationId);
        return activeNation(nationId);
    }

    private void resume(String nationId) {
        for(;;) {
            var current=relocations.findByNationId(nationId).orElseThrow(()->locked("迁都恢复计划缺失"));
            if(current.stage()==NationCapitalRelocationStore.Stage.COMPLETED)return;
            switch(current.stage()) {
                case PREPARED -> applyGarrisons(current);
                case GARRISON_MOVED -> applyOccupation(current);
                case OCCUPATION_MOVED -> applyNation(current);
                case NATION_MOVED -> applyAnchor(current);
                case ANCHOR_MOVED -> { }
                case COMPLETED -> { return; }
            }
            var next=current.advance(switch(current.stage()) {
                case PREPARED -> NationCapitalRelocationStore.Stage.GARRISON_MOVED;
                case GARRISON_MOVED -> NationCapitalRelocationStore.Stage.OCCUPATION_MOVED;
                case OCCUPATION_MOVED -> NationCapitalRelocationStore.Stage.NATION_MOVED;
                case NATION_MOVED -> NationCapitalRelocationStore.Stage.ANCHOR_MOVED;
                case ANCHOR_MOVED -> NationCapitalRelocationStore.Stage.COMPLETED;
                case COMPLETED -> throw new IllegalStateException("completed relocation cannot advance");
            });
            if(!relocations.compareAndSet(next,current.version())
                    &&!relocations.findByNationId(nationId).filter(next::equals).isPresent())
                throw locked("迁都恢复阶段CAS失败，请重试");
        }
    }

    private void applyGarrisons(NationCapitalRelocationStore.Snapshot plan) {
        for(var entry:plan.beforeGarrisons().entrySet()) {
            String id=entry.getKey();var before=entry.getValue();var after=plan.afterGarrisons().get(id);
            long expected=plan.beforeGarrisonVersions().getOrDefault(id,-1L);
            if(after==null||expected<0)throw locked("迁都驻军CAS计划不完整");
            var live=marches.findById(id).orElseThrow(()->locked("迁都驻军行军记录缺失"));
            if(live.snapshot().equals(after)&&marches.versionOf(id)==expected+1)continue;
            if(!live.snapshot().equals(before)||marches.versionOf(id)!=expected)
                throw locked("迁都驻军已被其他操作修改");
            try { marches.save(March.fromSnapshot(after),expected); }
            catch(RuntimeException unknown) {
                var confirmed=marches.findById(id).orElse(null);
                if(confirmed==null||!confirmed.snapshot().equals(after)||marches.versionOf(id)!=expected+1)throw unknown;
            }
        }
    }

    private void applyOccupation(NationCapitalRelocationStore.Snapshot plan) {
        var before=plan.beforeOccupation();var after=plan.afterOccupation();
        if(before==null&&after==null)return;
        var live=occupations.findByTargetIds(List.of(CAPITAL_TARGET_ID)).get(CAPITAL_TARGET_ID);
        if(after.equals(live))return;
        if(!before.equals(live))throw locked("王城占领记录已被其他操作修改");
        if(!occupations.compareAndSet(after,before.version())
                &&!after.equals(occupations.findByTargetIds(List.of(CAPITAL_TARGET_ID)).get(CAPITAL_TARGET_ID)))
            throw locked("王城占领迁移CAS失败，请重试");
    }

    private void applyNation(NationCapitalRelocationStore.Snapshot plan) {
        var live=activeNation(plan.nationId()).snapshot();
        if(live.equals(plan.afterNation()))return;
        if(!live.equals(plan.beforeNation()))throw locked("国家在迁都过程中已被其他操作修改");
        Nation next=Nation.fromSnapshot(plan.beforeNation(),nationRules.rules());
        next.moveCapital(plan.newX(),plan.newY());
        try { nations.save(next,plan.nationVersion()); }
        catch(RuntimeException unknown) {
            var confirmed=nations.findById(plan.nationId()).map(Nation::snapshot).orElse(null);
            if(!plan.afterNation().equals(confirmed))throw unknown;
        }
    }

    private void applyAnchor(NationCapitalRelocationStore.Snapshot plan) {
        var slot=operations.findByTargetId(CAPITAL_TARGET_ID).orElse(null);
        if(slot==null)return;
        if(!slot.completed())throw locked("王城COMBAT未完成，不能迁移活动锚点");
        var anchor=slot.anchor();
        if(anchor.kind()!=com.ironoath.core.world.WorldGenerator.EntityType.CAPITAL)
            throw locked("王城操作快照类型不匹配");
        if(anchor.x()==plan.newX()&&anchor.y()==plan.newY())return;
        if(anchor.x()!=plan.oldX()||anchor.y()!=plan.oldY())
            throw locked("王城活动锚点已改变，拒绝覆盖其他操作");
        var moved=slot.relocateCapitalAnchor(Coord.of(plan.newX(),plan.newY()));
        if(!operations.compareAndSet(moved,slot.version())
                &&!operations.findByTargetId(CAPITAL_TARGET_ID).filter(moved::equals).isPresent())
            throw locked("王城活动锚点迁移CAS失败，请重试");
    }

    private Nation activeNation(String nationId) {
        return nations.findById(nationId).filter(n->!n.isDisbanded())
                .orElseThrow(()->locked("国家不存在或已解散"));
    }

    private void requireMayRelocate(String actorId,Nation nation,long now) {
        if(nation.kingId().equals(actorId))return;
        var king=players.findByPlayerId(nation.kingId()).orElse(null);
        if(king==null||now<king.lastLoginAt()||now-king.lastLoginAt()<WEEK_MILLIS)
            throw locked("只有国王可迁都；国王离线满一周后，其他官员方可决定");
        if(nation.officeOf(actorId)==null)
            throw locked("国王离线满一周后，只有国家官员可决定迁都");
    }

    private <T> T runNationLocked(String nationId,Supplier<T> action) {
        return new com.ironoath.web.nation.NationOperationLock(playerLocks).runLocked(nationId,action);
    }

    private static BizException locked(String message) {
        return new BizException(ErrorCode.NATION_LOCKED,message);
    }
}
