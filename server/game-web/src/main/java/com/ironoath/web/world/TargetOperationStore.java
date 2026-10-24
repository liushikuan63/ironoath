package com.ironoath.web.world;

import com.ironoath.core.march.March;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单目标当前操作槽。代次不重置；所有持久步骤确认后才能完成并被下一计划替换。 */
public interface TargetOperationStore {
    enum Kind { WITHDRAWAL,COMBAT }
    record Anchor(String targetId,com.ironoath.core.world.WorldGenerator.EntityType kind,int x,int y) {}
    record MarchIntent(String marchId,long beforeVersion,March.Snapshot before,
                       com.ironoath.web.battle.StrategicBattleMarchOutcome.Disposition disposition,
                       March.Snapshot after) {
        public MarchIntent {
            if(marchId==null || marchId.isBlank() || beforeVersion<0 || before==null || disposition==null)
                throw new IllegalArgumentException("COMBAT行军恢复意图缺少来源/版本/去向");
            before=freeze(before);
            if(!marchId.equals(before.id()))throw new IllegalArgumentException("COMBAT行军恢复前态ID不匹配");
            if(disposition==com.ironoath.web.battle.StrategicBattleMarchOutcome.Disposition.ANNIHILATED) {
                if(after!=null)throw new IllegalArgumentException("全灭行军意图不能保存0兵after态");
            } else {
                if(after==null)throw new IllegalArgumentException("仍存活行军恢复意图缺少after态");
                after=freeze(after);
                if(!marchId.equals(after.id()))throw new IllegalArgumentException("COMBAT行军恢复后态ID不匹配");
            }
        }
        public static MarchIntent from(com.ironoath.web.battle.StrategicBattleMarchOutcome outcome) {
            return new MarchIntent(outcome.before().id(),outcome.beforeVersion(),outcome.before(),outcome.disposition(),outcome.after());
        }
    }
    record MarchRecoveryPlan(MarchIntent attacker,MarchIntent defender) {
        public MarchRecoveryPlan {
            if(attacker==null || attacker.disposition()==null)throw new IllegalArgumentException("攻方COMBAT行军恢复意图缺失");
            if(defender!=null && defender.marchId().equals(attacker.marchId()))
                throw new IllegalArgumentException("COMBAT攻守不能复用同一March恢复意图");
        }
    }
    record WarRecoveryPlan(com.ironoath.core.nation.WarScoreBoard.BattleReservation reservation,
                           com.ironoath.core.nation.WarScoreBoard.BattleEvent event) {
        public WarRecoveryPlan {
            if(reservation==null && event!=null)throw new IllegalArgumentException("无原战事预留不能产生WarEvent");
            if(reservation!=null && event!=null && (!event.sourceId().equals(reservation.sourceId())
                    || event.sequence()!=reservation.sequence() || !event.operationId().equals(reservation.operationId())
                    || event.warStartedAt()!=reservation.warStartedAt() || event.happenedAt()!=reservation.happenedAt()
                    || !event.rates().equals(reservation.rates())))
                throw new IllegalArgumentException("COMBAT WarEvent与原战事预留身份/系数不一致");
        }
        public WarRecoveryPlan withEvent(com.ironoath.core.nation.WarScoreBoard.BattleEvent next) {
            if(event!=null && !event.equals(next))throw new IllegalStateException("同一COMBAT不能替换冻结WarEvent");
            return new WarRecoveryPlan(reservation,next);
        }
    }
    record CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle,
                      MarchRecoveryPlan marchRecovery,com.ironoath.web.battle.StrategicBattleReportPlan reportPlan,
                      com.ironoath.web.battle.StrategicBattleKillPlan killPlan,WarRecoveryPlan warRecovery) {
        public CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle) {
            this(operationId,sequence,battle,null,null,null,null);
        }
        public CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle,
                MarchRecoveryPlan marchRecovery) {
            this(operationId,sequence,battle,marchRecovery,null,null,null);
        }
        public CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle,
                MarchRecoveryPlan marchRecovery,com.ironoath.web.battle.StrategicBattleReportPlan reportPlan) {
            this(operationId,sequence,battle,marchRecovery,reportPlan,null,null);
        }
        public CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle,
                MarchRecoveryPlan marchRecovery,com.ironoath.web.battle.StrategicBattleReportPlan reportPlan,
                com.ironoath.web.battle.StrategicBattleKillPlan killPlan) {
            this(operationId,sequence,battle,marchRecovery,reportPlan,killPlan,null);
        }
        public CombatPlan {
            if(sequence<1 || battle==null || !Objects.equals(operationId,"battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion()))
                throw new IllegalArgumentException("原战斗操作ID/代次与兵权版本不一致");
            if(marchRecovery!=null) {
                var attacker=marchRecovery.attacker();
                if(!attacker.marchId().equals(battle.attacker().march().id())
                        || attacker.beforeVersion()!=battle.attacker().marchVersion()
                        || !attacker.before().equals(battle.attacker().march()))
                    throw new IllegalArgumentException("COMBAT攻方行军恢复意图与冻结战果不一致");
                var frozenDefender=battle.defender();var intentDefender=marchRecovery.defender();
                if((frozenDefender==null)!=(intentDefender==null)
                        || frozenDefender!=null && (!intentDefender.marchId().equals(frozenDefender.march().id())
                        || intentDefender.beforeVersion()!=frozenDefender.marchVersion()
                        || !intentDefender.before().equals(frozenDefender.march())))
                    throw new IllegalArgumentException("COMBAT守方行军恢复意图与冻结战果不一致");
            }
            if(reportPlan!=null) {
                String attackerOwner=battle.attacker().rally().initiatorId();
                String defenderOwner=battle.defender()==null?null:battle.defender().rally().initiatorId();
                String defenderId=defenderOwner==null?battle.target().targetId():defenderOwner;
                var expectedOwners=new java.util.HashSet<String>();expectedOwners.add(attackerOwner);
                if(defenderOwner!=null)expectedOwners.add(defenderOwner);
                var owners=reportPlan.recipients().stream()
                        .map(com.ironoath.web.battle.StrategicBattleReportPlan.Recipient::ownerId)
                        .collect(java.util.stream.Collectors.toSet());
                if(!reportPlan.operationId().equals(operationId) || reportPlan.createdAt()!=battle.happenedAt()
                        || reportPlan.battleType()!=battle.input().battleType() || !owners.equals(expectedOwners))
                    throw new IllegalArgumentException("COMBAT战报身份计划与原操作/攻守发起人/事件不一致");
                var attackHeroes=reportHeroes(battle.attacker());
                var defendHeroes=battle.defender()==null?List.<String>of():reportHeroes(battle.defender());
                for(var recipient:reportPlan.recipients()) {
                    if(!recipient.attackerId().equals(attackerOwner) || !recipient.defenderId().equals(defenderId)
                            || !recipient.attackerHeroIds().equals(attackHeroes)
                            || !recipient.defenderHeroIds().equals(defendHeroes))
                        throw new IllegalArgumentException("COMBAT战报内容身份/英雄列表与原集结不一致");
                }
            }
            if(killPlan!=null) {
                if(!killPlan.operationId().equals(operationId) || killPlan.sequence()!=sequence
                        || !killPlan.sourceId().equals(battle.target().targetId()))
                    throw new IllegalArgumentException("COMBAT季榜KILL计划与原目标操作/代次不一致");
                var sourceKills=new java.util.LinkedHashMap<String,Long>();
                sourceKills.putAll(battle.result().atkKillsByMember());sourceKills.putAll(battle.result().defKillsByMember());
                if(killPlan.active()) {
                    var originalPlayers=new java.util.HashSet<String>(battle.attacker().currentMembers().keySet());
                    if(battle.defender()!=null)originalPlayers.addAll(battle.defender().currentMembers().keySet());
                    var plannedPlayers=killPlan.members().stream()
                            .map(com.ironoath.web.battle.StrategicBattleKillPlan.Member::playerId)
                            .collect(java.util.stream.Collectors.toSet());
                    if(!plannedPlayers.equals(originalPlayers))
                        throw new IllegalArgumentException("活跃赛季COMBAT KILL计划未覆盖全部原成员身份");
                }
                for(var member:killPlan.members()) {
                    var expected=sourceKills.get(member.playerId());
                    if(expected==null || expected.longValue()!=member.kills())
                        throw new IllegalArgumentException("COMBAT季榜KILL贡献与引擎成员击杀来源不一致");
                }
            }
            if(warRecovery!=null && warRecovery.reservation()!=null) {
                var reservation=warRecovery.reservation();
                if(!reservation.sourceId().equals(battle.target().targetId()) || reservation.sequence()!=sequence
                        || !reservation.operationId().equals(operationId) || reservation.happenedAt()!=battle.happenedAt())
                    throw new IllegalArgumentException("COMBAT War reservation与原目标操作/代次/时点不一致");
            }
            if(warRecovery!=null && warRecovery.event()!=null) {
                var event=warRecovery.event();
                if(!event.sourceId().equals(battle.target().targetId()) || event.sequence()!=sequence
                        || !event.operationId().equals(operationId) || event.happenedAt()!=battle.happenedAt())
                    throw new IllegalArgumentException("COMBAT WarEvent与原目标操作/代次/时点不一致");
            }
        }
        public static CombatPlan create(com.ironoath.web.battle.CapturedStrategicBattle battle,long sequence) {
            return new CombatPlan("battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion(),sequence,battle);
        }
        public CombatPlan withMarchRecovery(MarchRecoveryPlan recovery) {
            return new CombatPlan(operationId,sequence,battle,recovery,reportPlan,killPlan,warRecovery);
        }
        public CombatPlan withReportPlan(com.ironoath.web.battle.StrategicBattleReportPlan reports) {
            return new CombatPlan(operationId,sequence,battle,marchRecovery,reports,killPlan,warRecovery);
        }
        public CombatPlan withKillPlan(com.ironoath.web.battle.StrategicBattleKillPlan kills) {
            return new CombatPlan(operationId,sequence,battle,marchRecovery,reportPlan,kills,warRecovery);
        }
        public CombatPlan withWarRecovery(WarRecoveryPlan plan) {
            return new CombatPlan(operationId,sequence,battle,marchRecovery,reportPlan,killPlan,plan);
        }
        public CombatPlan withWarEvent(com.ironoath.core.nation.WarScoreBoard.BattleEvent event) {
            if(warRecovery==null)throw new IllegalStateException("COMBAT原War reservation计划缺失");
            return withWarRecovery(warRecovery.withEvent(event));
        }
    }
    record WithdrawalPlan(String operationId,long sequence,long happenedAt,
                          TargetOccupationStore.Snapshot before,TargetOccupationStore.Snapshot after,
                          long marchVersion,March.Snapshot stationed,March.Snapshot returning) {
        public WithdrawalPlan {
            if(before==null || after==null || sequence<1 || happenedAt<before.capturedAt() || marchVersion<0
                    || stationed==null || returning==null)throw new IllegalArgumentException("撤防计划缺失或代次无效");
            stationed=freeze(stationed);returning=freeze(returning);
            var original=March.fromSnapshot(stationed);
            if(!original.hasGarrisonOwnership() || original.status()!=March.Status.STATIONED
                    || original.targetType()!=March.TargetType.STRATEGIC_TARGET || !original.isRallyMarch()
                    || !before.targetId().equals(original.targetId())
                    || before.x()!=original.to().x() || before.y()!=original.to().y()
                    || !TargetOccupationStore.Garrison.stationed(original.id()).equals(before.garrison())
                    || !Objects.equals(operationId,"withdraw:"+original.id()))
                throw new IllegalArgumentException("撤防计划原驻军/目标/操作绑定不一致");
            var expectedOwner=new TargetOccupationStore.Snapshot(before.targetId(),before.kind(),before.x(),before.y(),
                    before.nationId(),before.nationName(),before.capturedAt(),operationId,
                    Math.addExact(before.version(),1),TargetOccupationStore.Garrison.empty(),before.ownershipHistory());
            if(!expectedOwner.equals(after))throw new IllegalArgumentException("撤防不能改变国家/占领锚点或补回守军");
            original.recall(happenedAt);
            if(!original.snapshot().equals(returning) || returning.returnArriveAt()==null
                    || returning.returnArriveAt()<happenedAt)
                throw new IllegalArgumentException("撤防返程必须是原固定时刻与兵权的精确召回");
        }
        public String targetId(){return before.targetId();}
        public static WithdrawalPlan create(TargetOccupationStore.Snapshot owner,March march,long marchVersion,
                                            long sequence,long now) {
            var before=freeze(march.snapshot());var returned=March.fromSnapshot(before);returned.recall(now);
            String op="withdraw:"+march.id();
            var after=new TargetOccupationStore.Snapshot(owner.targetId(),owner.kind(),owner.x(),owner.y(),
                    owner.nationId(),owner.nationName(),owner.capturedAt(),op,Math.addExact(owner.version(),1),
                    TargetOccupationStore.Garrison.empty(),owner.ownershipHistory());
            return new WithdrawalPlan(op,sequence,now,owner,after,marchVersion,before,returned.snapshot());
        }
    }
    record Snapshot(String targetId,long version,boolean completed,WithdrawalPlan plan,Kind kind,CombatPlan combatPlan) {
        /** 旧BSON缺kind/combatPlan时仅在原撤防计划完整存在的情况下归一化，读取不回填。 */
        public Snapshot(String targetId,long version,boolean completed,WithdrawalPlan plan) {
            this(targetId,version,completed,plan,Kind.WITHDRAWAL,null);
        }
        public Snapshot {
            if(kind==null && plan!=null && combatPlan==null)kind=Kind.WITHDRAWAL;
            if(targetId==null || targetId.isBlank() || version<1 || kind==null
                    || (kind==Kind.WITHDRAWAL && (plan==null || combatPlan!=null || !targetId.equals(plan.targetId())))
                    || (kind==Kind.COMBAT && (plan!=null || combatPlan==null || !targetId.equals(combatPlan.battle().target().targetId()))))
                throw new IllegalArgumentException("目标操作槽与计划绑定无效");
        }
        public static Snapshot combat(long version,boolean completed,CombatPlan plan) {
            return new Snapshot(plan.battle().target().targetId(),version,completed,null,Kind.COMBAT,plan);
        }
        public String operationId(){return kind==Kind.WITHDRAWAL?plan.operationId():combatPlan.operationId();}
        public long sequence(){return kind==Kind.WITHDRAWAL?plan.sequence():combatPlan.sequence();}
        public long happenedAt(){return kind==Kind.WITHDRAWAL?plan.happenedAt():combatPlan.battle().happenedAt();}
        public Anchor anchor(){
            if(kind==Kind.WITHDRAWAL){var b=plan.before();return new Anchor(targetId,b.kind(),b.x(),b.y());}
            var b=combatPlan.battle().target();return new Anchor(targetId,b.kind(),b.x(),b.y());
        }
    }
    private static March.Snapshot freeze(March.Snapshot source){return March.fromSnapshot(source).snapshot();}
    private static List<String> reportHeroes(com.ironoath.web.battle.StrategicRallyCasualties side) {
        var ids=new java.util.ArrayList<String>();side.rally().participants().values().forEach(p->ids.addAll(p.heroes()));
        return List.copyOf(ids);
    }

    Optional<Snapshot> findByTargetId(String targetId);
    /** A bounded catalog batch, so viewport recovery projection does not fan out one read per strategic target. */
    Map<String,Snapshot> findByTargetIds(Collection<String> targetIds);
    /** 仅查询仍保留的当前槽；不是全历史RPC响应缓存。 */
    Optional<Snapshot> findByOperationId(String operationId);
    /** 只读候选ID，不解码计划；坏目标可逐项隔离，不挡住其他固定目标。 */
    List<String> pendingTargetIds(Collection<String> catalogIds);
    List<String> findAllTargetIds(int limit);
    boolean compareAndSet(Snapshot next,long expectedVersion);

    static void validateWrite(Snapshot next,long expectedVersion){
        if(next==null || expectedVersion<0 || next.version()!=Math.addExact(expectedVersion,1))
            throw new IllegalArgumentException("目标操作版本必须等于原期望版本加一");
    }
    static boolean allowed(Snapshot current,Snapshot next,long expectedVersion){
        if(current==null)return expectedVersion==0 && !next.completed() && next.sequence()==1;
        if(current.version()!=expectedVersion)return false;
        if(current.operationId().equals(next.operationId())) {
            if(current.kind()!=next.kind())return false;
            if(current.kind()==Kind.WITHDRAWAL)
                return !current.completed() && next.completed() && Objects.equals(current.plan(),next.plan());
            var before=current.combatPlan();var after=next.combatPlan();
            if(before==null || after==null || !Objects.equals(before.operationId(),after.operationId())
                    || before.sequence()!=after.sequence() || !Objects.equals(before.battle(),after.battle())
                    || !Objects.equals(before.reportPlan(),after.reportPlan())
                    || !Objects.equals(before.killPlan(),after.killPlan()))return false;
            if(current.completed())return false;
            if(next.completed())return Objects.equals(before,after);
            boolean warSourceSame=before.warRecovery()==null?after.warRecovery()==null
                    :after.warRecovery()!=null && Objects.equals(before.warRecovery().reservation(),after.warRecovery().reservation());
            if(!warSourceSame)return false;
            boolean freezeMarch=before.marchRecovery()==null && after.marchRecovery()!=null
                    && Objects.equals(before.warRecovery(),after.warRecovery());
            boolean freezeWarEvent=Objects.equals(before.marchRecovery(),after.marchRecovery())
                    && before.warRecovery()!=null && before.warRecovery().event()==null
                    && after.warRecovery().event()!=null;
            return freezeMarch || freezeWarEvent;
        }
        return current.completed() && !next.completed()
                && next.sequence()==Math.addExact(current.sequence(),1);
    }
}
