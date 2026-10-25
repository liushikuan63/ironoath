package com.ironoath.web.world;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleReportPlan;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.WarCapitalTargetResolver;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.rank.RankBoardService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 原战略战果的持久发布边界：持原国家/全部集结锁后才取目标锁，核对原源版本再CAS单一COMBAT槽。
 * 原输入须由调用者在相同锁域内捕获；此类不代替争夺/赛季/圈层准入，也不装配地形或成员贡献。
 * 发布先在活跃War快照CAS冻结原成员参战资格/系数与闭战预留，再CAS目标COMBAT槽；不执行伤兵、归属、行军、战报或War积分副作用。
 * 最终恢复器须在所有精确回执确认后释放预留；生产到达调用及完整恢复未接前，玩家入口继续关闭。
 */
@Service
public final class StrategicCombatPublisher {
    private final ConfigRegistry configs;
    private final TargetOperationStore operations;
    private final MarchRepository marches;
    private final StrategicTargetDefender defenders;
    private final SocialStore rallies;
    private final SocialAppService social;
    private final StrategicGarrisonWithdrawal targetLocks;
    private final WarStore wars;
    private final RankBoardService ranks;
    private final WarCapitalTargetResolver capitalTargets;

    public StrategicCombatPublisher(ConfigRegistry configs,TargetOperationStore operations,MarchRepository marches,
            StrategicTargetDefender defenders,SocialStore rallies,SocialAppService social,
            StrategicGarrisonWithdrawal targetLocks,WarStore wars,RankBoardService ranks) {
        this(configs,operations,marches,defenders,rallies,social,targetLocks,wars,ranks,null);
    }

    @Autowired
    public StrategicCombatPublisher(ConfigRegistry configs,TargetOperationStore operations,MarchRepository marches,
            StrategicTargetDefender defenders,SocialStore rallies,SocialAppService social,
            StrategicGarrisonWithdrawal targetLocks,WarStore wars,RankBoardService ranks,
            WarCapitalTargetResolver capitalTargets) {
        this.configs=Objects.requireNonNull(configs);this.operations=Objects.requireNonNull(operations);
        this.marches=Objects.requireNonNull(marches);this.defenders=Objects.requireNonNull(defenders);
        this.rallies=Objects.requireNonNull(rallies);this.social=Objects.requireNonNull(social);
        this.targetLocks=Objects.requireNonNull(targetLocks);
        this.wars=Objects.requireNonNull(wars);
        this.ranks=Objects.requireNonNull(ranks);
        this.capitalTargets=capitalTargets;
    }

    public TargetOperationStore.Snapshot publish(CapturedStrategicBattle battle) {
        Objects.requireNonNull(battle,"原战略战果不得为空");
        var nations=new TreeMap<String,String>();addRally(nations,battle.attacker());
        if(battle.defender()!=null)addRally(nations,battle.defender());
        // 在取target锁之前拒绝缺失/错国上下文；发布过程绝不扩张外层锁集合。
        social.requireNationalRalliesLocked(nations);
        return targetLocks.runCombatPublicationLocked(battle.target().targetId(),()->publishLocked(battle));
    }

    private TargetOperationStore.Snapshot publishLocked(CapturedStrategicBattle battle) {
        String targetId=battle.target().targetId();
        String operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var current=operations.findByTargetId(targetId).orElse(null);
        if(current!=null && current.operationId().equals(operationId)) {
            if(current.kind()!=TargetOperationStore.Kind.COMBAT || !current.combatPlan().battle().equals(battle))
                throw new IllegalStateException("同一COMBAT操作不能替换原载荷或重新模拟战果");
            if(!current.completed())verifyWarReplay(current);
            return current;
        }
        if(current!=null && !current.completed())throw new IllegalStateException("目标原操作未完成，不能发布下一COMBAT");

        var directory=capitalTargets==null?WorldTargets.from(configs):capitalTargets.resolve(configs).targets();
        var target=directory.targets().stream()
                .filter(candidate->candidate.definition().id().equals(targetId)).findFirst()
                .orElseThrow(()->new IllegalStateException("原战略目标已不在配置目录"));
        var facts=battle.target();
        if(target.coord().x()!=facts.x() || target.coord().y()!=facts.y()
                || !target.definition().kind().name().equals(facts.kind().name())
                || target.battleTerrain()!=battle.input().terrain())
            throw new IllegalStateException("原战略目标锚点与当前目录不一致");
        requireOriginalSide(battle.attacker());
        if(battle.defender()!=null)requireOriginalSide(battle.defender());
        if(!facts.defender().equals(defenders.read(target)))
            throw new IllegalStateException("原目标守军或归属已变化，不能发布旧战果");
        // 目标读取会查其他存档；再次验证版本，不能给旧快照配一个新CAS版本。
        requireOriginalSide(battle.attacker());
        if(battle.defender()!=null)requireOriginalSide(battle.defender());

        long version=current==null?0:current.version();
        long sequence=current==null?1:Math.addExact(current.sequence(),1);
        var reportPlan=freezeReportPlan(battle,operationId,target.definition().name());
        var killPlan=ranks.freezeStrategicKills(battle,operationId,sequence);
        var reservation=reserveWarBattle(battle,sequence,operationId);
        var warPlan=new TargetOperationStore.WarRecoveryPlan(reservation.orElse(null),null);
        var combat=TargetOperationStore.CombatPlan.create(battle,sequence).withReportPlan(reportPlan)
                .withKillPlan(killPlan).withWarRecovery(warPlan);
        var next=TargetOperationStore.Snapshot.combat(Math.addExact(version,1),false,combat);
        try {
            boolean accepted=operations.compareAndSet(next,version);
            if(!confirmed(next))throw new IllegalStateException(accepted
                    ?"COMBAT发布回执未被独立读回确认":"COMBAT槽CAS冲突，不能用新版本重发旧战果");
        } catch(RuntimeException unknown) {
            try {if(confirmed(next))return next;}
            catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);}
            throw unknown;
        }
        return next;
    }

    private java.util.Optional<com.ironoath.core.nation.WarScoreBoard.BattleReservation> reserveWarBattle(
            CapturedStrategicBattle battle,long sequence,String operationId) {
        if(battle.formatVersion()!=2)throw new IllegalStateException("旧COMBAT格式没有逐成员引擎来源，不能发布War事件预留");
        var members=new ArrayList<com.ironoath.core.nation.WarScoreBoard.BattleMember>();
        addMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        if(battle.defender()!=null)addMembers(members,battle.defender(),battle.result().defKillsByMember());
        return wars.reserveBattle(battle.target().targetId(),sequence,operationId,battle.happenedAt(),members);
    }

    private void verifyWarReplay(TargetOperationStore.Snapshot slot) {
        var plan=slot.combatPlan().warRecovery();
        if(plan==null)throw new IllegalStateException("原COMBAT缺War预留计划，不能发布/恢复国战贡献");
        var reservation=plan.reservation();if(reservation==null)return;
        var board=wars.findByStartedAt(reservation.warStartedAt())
                .orElseThrow(()->new IllegalStateException("原COMBAT绑定的War存档缺失"));
        var persistedEvent=board.battleEvent(reservation.sourceId());
        var pending=wars.pendingBattleReservation(reservation.warStartedAt(),reservation.operationId());
        if(plan.event()==null) {
            if(!pending.filter(reservation::equals).isPresent() || persistedEvent!=null)
                throw new IllegalStateException("原COMBAT War预留缺失或已有未冻结的WarEvent");
        } else {
            if(persistedEvent!=null && !persistedEvent.equals(plan.event()))
                throw new IllegalStateException("原COMBAT WarEvent与恢复槽冻结载荷冲突");
            if(pending.isPresent() && !pending.get().equals(reservation))
                throw new IllegalStateException("原COMBAT War预留与恢复槽冻结载荷冲突");
            if(pending.isEmpty() && !plan.event().equals(persistedEvent))
                throw new IllegalStateException("原COMBAT War屏障已释放但缺少精确事件回执");
        }
    }

    private StrategicBattleReportPlan freezeReportPlan(CapturedStrategicBattle battle,String operationId,String targetName) {
        long createdAt=battle.happenedAt();
        long ttlMillis=Math.multiplyExact(configs.longParam("BATTLE_REPORT_TTL_SECONDS"),1000L);
        long expiresAt=Math.addExact(createdAt,ttlMillis);
        String attackerOwner=battle.attacker().rally().initiatorId();
        String attackerName=social.nicknameOf(attackerOwner);
        String defenderOwner=battle.defender()==null?null:battle.defender().rally().initiatorId();
        String defenderName=defenderOwner==null?targetName:social.nicknameOf(defenderOwner);
        return StrategicBattleReportPlan.create(battle,operationId,expiresAt,attackerName,defenderName);
    }

    private static void addMembers(List<com.ironoath.core.nation.WarScoreBoard.BattleMember> out,
            StrategicRallyCasualties side,Map<String,Long> kills) {
        var nationId=side.rally().nationId();
        for(String playerId:side.currentMembers().keySet()) {
            Long count=kills.get(playerId);
            if(count==null || count<0)throw new IllegalStateException("原COMBAT成员引擎击杀来源缺失");
            out.add(new com.ironoath.core.nation.WarScoreBoard.BattleMember(playerId,nationId,count));
        }
        if(!kills.keySet().equals(side.currentMembers().keySet()))
            throw new IllegalStateException("原COMBAT成员击杀集合与冻结兵权不一致");
    }

    private void requireOriginalSide(StrategicRallyCasualties side) {
        String id=side.march().id();
        long before=marches.versionOf(id);
        var march=marches.findById(id).orElseThrow(()->new IllegalStateException("原COMBAT行军缺失"));
        if(before!=side.marchVersion() || marches.versionOf(id)!=before || !side.march().equals(march.snapshot()))
            throw new IllegalStateException("原COMBAT行军版本或兵权已变化");
        var rally=rallies.rallyOf(side.rally().rallyId()).orElseThrow(()->new IllegalStateException("原COMBAT集结缺失"));
        if(!side.rally().equals(StrategicRallyCasualties.RallyIdentity.freeze(rally)))
            throw new IllegalStateException("原COMBAT集结状态或成员承诺已变化");
    }
    private boolean confirmed(TargetOperationStore.Snapshot expected) {
        return operations.findByTargetId(expected.targetId()).filter(expected::equals).isPresent();
    }
    private static void addRally(Map<String,String> nations,StrategicRallyCasualties side) {
        var identity=side.rally();var old=nations.putIfAbsent(identity.rallyId(),identity.nationId());
        if(old!=null && !old.equals(identity.nationId()))throw new IllegalArgumentException("同一原集结不能绑定两个国家");
    }
}
