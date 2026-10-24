package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.nation.Nation;
import com.ironoath.common.BizException;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.StrategicTargetMarchAdvancer;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryWarStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleMarchOutcome;
import com.ironoath.web.battle.StrategicWoundSettlement;
import com.ironoath.web.battle.StrategicBattleReportPlan;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.store.memory.InMemoryBattleReportStore;
import com.ironoath.web.store.memory.InMemorySeasonBoardStore;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.StrategicCombatRecovery;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pending COMBAT retries must use frozen battle rallies before reading partially written target state. */
class StrategicCombatPendingLockRecoveryTest {
    @Test void pendingCombatRecoveryMustPersistEveryMembersHospitalReceiptBeforeAnyLaterEffect() {
        var battle=StrategicCombatSlotTest.battle(2399,true,true);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.DRAW);
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var rankedOut= battle.defender().rally().initiatorId();var rankFixture=new RankFixture(configs,Set.of(rankedOut));
        var operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var plan=TargetOperationStore.CombatPlan.create(battle,1).withReportPlan(StrategicBattleReportPlan.create(battle,
                operationId,
                battle.happenedAt()+configs.longParam("BATTLE_REPORT_TTL_SECONDS")*1000L,"attacker","defender"));
        plan=plan.withKillPlan(rankFixture.service.freezeStrategicKills(battle,operationId,1));
        var operations=new InMemoryTargetOperationStore();
        var owners=new InMemoryTargetOccupationStore();var originalOwner=battle.target().defender().occupation();
        assertThat(originalOwner).isNotNull();assertThat(owners.compareAndSet(originalOwner,0)).isTrue();
        var nationStore=mock(NationStore.class);var attackerNation=Nation.found(battle.attacker().rally().nationId(),"进攻国",
                battle.attacker().rally().initiatorId(),"owned-attacker-alliance",0,0,100,new NationRulesAssembler(configs).rules());
        when(nationStore.findById(attackerNation.id())).thenReturn(Optional.of(attackerNation));
        var armies=new InMemoryArmyStore();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var rules=new WarRulesAssembler(configs);var wars=new InMemoryWarStore(rules);
        var board=new WarScoreBoard(rules.rules(),battle.happenedAt()-10,"owned-N",battle.happenedAt()+1000);
        board.registerNation("owned-N");board.registerNation(battle.defender().rally().nationId());assertThat(wars.insertIfNoneActive(board)).isTrue();
        var members=new ArrayList<WarScoreBoard.BattleMember>();
        addMembers(members,battle.attacker(),battle.result().atkKillsByMember());addMembers(members,battle.defender(),battle.result().defKillsByMember());
        var warReservation=wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members).orElseThrow();
        plan=plan.withWarRecovery(new TargetOperationStore.WarRecoveryPlan(warReservation,null));
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

        var marches=new InMemoryMarchStore(32);var marchSnapshots=new LinkedHashMap<String,March.Snapshot>();var marchVersions=new LinkedHashMap<String,Long>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            marchSnapshots.put(march.id(),march.snapshot());marchVersions.put(march.id(),marches.versionOf(march.id()));
        }
        var defenders=mock(StrategicTargetDefender.class);
        when(defenders.read(any())).thenThrow(new AssertionError("combat recovery must use the frozen plan"));
        var social=mock(SocialAppService.class);when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var battleReports=new InMemoryBattleReportStore();
        var reportService=new BattleReportService(configs,battleReports,mock(com.ironoath.common.time.TimeService.class),
                social,rankFixture.service,wars,mock(NationMembership.class));
        var due=new SortedMarchDueQueue();
        var withdrawal=new StrategicGarrisonWithdrawal(configs,operations,owners,marches,
                due,defenders,social,new InJvmPlayerLock(),new StrategicCombatRecovery(operations,armies,marches,
                owners,new InMemoryNeutralTargetGarrisonStore(),nationStore,due,reportService,rankFixture.service,wars));

        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenders,social,withdrawal,operations);
        var attackMarch=March.fromSnapshot(battle.attacker().march());var advanced=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(advancer.advance(attackMarch,battle.happenedAt(),()->{advanced.incrementAndGet();return true;})).isTrue();
        assertThat(advanced.get()).isEqualTo(1);
        assertThat(wars.findByStartedAt(battle.happenedAt()-10).orElseThrow().battleEvent(battle.target().targetId()))
                .as("COMBAT recovery must materialize the frozen reservation as one exact WarEvent")
                .isNotNull();
        var attackerReports=battleReports.reportsOf(battle.attacker().rally().initiatorId());
        assertThat(attackerReports)
                .as("pending COMBAT recovery must persist the deterministic attacker initiator report")
                .hasSize(1);
        var attackerReport=attackerReports.get(0);
        assertThat(attackerReport.reportId()).isEqualTo(com.ironoath.web.battle.BattleReport.idForOperation(
                plan.operationId(),battle.attacker().rally().initiatorId()));
        assertThat(attackerReport.result().atkKillsByMember()).isEqualTo(battle.result().atkKillsByMember());
        var defenderReports=battleReports.reportsOf(battle.defender().rally().initiatorId());
        assertThat(defenderReports).hasSize(1);
        assertThat(defenderReports.get(0).result().defKillsByMember()).isEqualTo(battle.result().defKillsByMember());
        var expectedKills=new LinkedHashMap<String,Long>();
        expectedKills.putAll(battle.result().atkKillsByMember());expectedKills.putAll(battle.result().defKillsByMember());
        expectedKills.remove(rankedOut);
        var initialKillRows=rankFixture.boards.board("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL);
        var actualKillScores=initialKillRows.stream().collect(java.util.stream.Collectors.toMap(
                com.ironoath.core.season.SeasonSettlement.Entry::id,com.ironoath.core.season.SeasonSettlement.Entry::score));
        assertThat(actualKillScores).isEqualTo(expectedKills);
        for(var member:plan.killPlan().members()) {
            var receipt=rankFixture.boards.scopedIncrementOf("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL,
                    member.playerId(),plan.killPlan().sourceId());
            if(member.leaderboardEligible())assertThat(receipt).contains(new com.ironoath.web.season.SeasonBoardStore.ScopedIncrement(
                    plan.killPlan().sourceId(),plan.killPlan().sequence(),plan.operationId(),member.playerId(),member.displayName(),member.kills()));
            else assertThat(receipt).isEmpty();
        }
        var ownerAfter=owners.findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        if(battle.result().winner()==com.ironoath.battle.Winner.ATTACKER) {
            assertThat(ownerAfter.nationId()).isEqualTo(attackerNation.id());
            assertThat(ownerAfter.garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(battle.attacker().march().id()));
        } else assertThat(ownerAfter).isEqualTo(originalOwner);
        for(var side:List.of(StrategicRallyCasualties.Side.ATTACKER,StrategicRallyCasualties.Side.DEFENDER)) {
            var outcome=StrategicBattleMarchOutcome.project(plan,side).orElseThrow();
            if(outcome.disposition()==StrategicBattleMarchOutcome.Disposition.ANNIHILATED) {
                assertThat(marches.findById(outcome.before().id())).isEmpty();
                assertThat(due.dueAtOf(outcome.before().id()).isPresent()).isFalse();
            } else {
                assertThat(marches.findById(outcome.after().id()).orElseThrow().snapshot()).isEqualTo(outcome.after());
                if(outcome.disposition()==StrategicBattleMarchOutcome.Disposition.RETURN)
                    assertThat(due.dueAtOf(outcome.after().id()).orElseThrow()).isEqualTo(outcome.after().returnArriveAt());
                else assertThat(due.dueAtOf(outcome.after().id()).isPresent()).isFalse();
            }
        }

        var firstReceipts=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var army=armies.findByPlayerId(player).orElseThrow();var receipt=army.snapshot().strategicWounds().get(battle.target().targetId());
            assertThat(receipt)
                    .as("every actual member gets one persistent hospital receipt before recovery advances")
                    .isNotNull();firstReceipts.put(player,receipt);
        }
        for(String marchId:marchSnapshots.keySet()) assertThat(marches.versionOf(marchId)).isEqualTo(Math.addExact(marchVersions.get(marchId),1));
        var pendingAfterFirst=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(pendingAfterFirst.completed()).isTrue();assertThat(pendingAfterFirst.version()).isEqualTo(4);
        assertThat(pendingAfterFirst.combatPlan().battle()).isEqualTo(battle);
        assertThat(pendingAfterFirst.combatPlan().marchRecovery()).isNotNull();
        var warPlan=pendingAfterFirst.combatPlan().warRecovery();
        assertThat(warPlan).isNotNull();assertThat(warPlan.event()).isNotNull();
        var warBoard=wars.findByStartedAt(battle.happenedAt()-10).orElseThrow();
        assertThat(warBoard.battleEvent(battle.target().targetId())).isEqualTo(warPlan.event());
        assertThat(wars.pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isEmpty();
        var eventKills=warPlan.event().effects().stream().collect(java.util.stream.Collectors.toMap(
                e->e.impact().playerId(),e->e.impact().kills()));
        var allEngineKills=new LinkedHashMap<String,Long>();
        allEngineKills.putAll(battle.result().atkKillsByMember());allEngineKills.putAll(battle.result().defKillsByMember());
        assertThat(eventKills).isEqualTo(allEngineKills);

        withdrawal.recoverPending();
        assertThat(operations.findByTargetId(battle.target().targetId())).contains(pendingAfterFirst);
        assertThat(advanced.get()).isEqualTo(1);
        firstReceipts.forEach((player,receipt)->{
            var army=armies.findByPlayerId(player).orElseThrow();
            assertThat(army.snapshot().strategicWounds().get(battle.target().targetId())).isEqualTo(receipt);
            assertThat(army.totalWounded()).isEqualTo(receipt.admitted().values().stream().mapToLong(Long::longValue).sum());
        });
        assertThat(battleReports.reportsOf(battle.attacker().rally().initiatorId())).containsExactly(attackerReport);
        assertThat(battleReports.reportsOf(battle.defender().rally().initiatorId())).containsExactly(defenderReports.get(0));
        assertThat(rankFixture.boards.board("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL))
                .isEqualTo(initialKillRows);
        var pendingAfterReplay=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(pendingAfterReplay).isEqualTo(pendingAfterFirst);
        assertThat(wars.findByStartedAt(battle.happenedAt()-10).orElseThrow().battleEvent(battle.target().targetId()))
                .isEqualTo(warPlan.event());
        assertThat(wars.pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isEmpty();
    }

    @Test void npcAttackerVictoryPersistsNewOwnerAndRetiresFrozenNeutralGarrison() {
        var battle=StrategicCombatSlotTest.battle(2382,false,true,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        assertThat(battle.npcSurvivors()).isNotEmpty();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var owners=new InMemoryTargetOccupationStore();
        var rankFixture=new RankFixture(configs);
        var targetName=com.ironoath.web.service.WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().id().equals(battle.target().targetId())).findFirst().orElseThrow().definition().name();
        var operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var reportPlan=StrategicBattleReportPlan.create(battle,operationId,
                battle.happenedAt()+configs.longParam("BATTLE_REPORT_TTL_SECONDS")*1000L,"attacker",targetName);
        var plan=TargetOperationStore.CombatPlan.create(battle,1).withReportPlan(reportPlan)
                .withKillPlan(rankFixture.service.freezeStrategicKills(battle,operationId,1));
        var operations=new InMemoryTargetOperationStore();
        var neutral=new InMemoryNeutralTargetGarrisonStore();var armies=new InMemoryArmyStore();
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var rules=new WarRulesAssembler(configs);var wars=new InMemoryWarStore(rules);
        var board=new WarScoreBoard(rules.rules(),battle.happenedAt()-10,battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation("owned-opponent");
        assertThat(wars.insertIfNoneActive(board)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        var warReservation=wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members).orElseThrow();
        plan=plan.withWarRecovery(new TargetOperationStore.WarRecoveryPlan(warReservation,null));
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

        var marches=new InMemoryMarchStore(32);var attacker=March.fromSnapshot(battle.attacker().march());
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        while(marches.versionOf(attacker.id())<battle.attacker().marchVersion())marches.save(attacker,marches.versionOf(attacker.id()));
        long originalVersion=marches.versionOf(attacker.id());
        var defenderReader=mock(StrategicTargetDefender.class);
        when(defenderReader.read(any())).thenThrow(new AssertionError("target recovery must use the frozen NPC payload"));
        var nationStore=mock(NationStore.class);var nation=Nation.found(battle.attacker().rally().nationId(),"进攻国",
                battle.attacker().rally().initiatorId(),"owned-attacker-alliance",0,0,100,new NationRulesAssembler(configs).rules());
        when(nationStore.findById(nation.id())).thenReturn(Optional.of(nation));
        var social=mock(SocialAppService.class);
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var battleReports=new InMemoryBattleReportStore();
        var reportService=new BattleReportService(configs,battleReports,mock(com.ironoath.common.time.TimeService.class),
                social,rankFixture.service,wars,mock(NationMembership.class));
        var due=new SortedMarchDueQueue();var recovery=new StrategicCombatRecovery(operations,armies,marches,owners,neutral,nationStore,
                due,reportService,rankFixture.service,wars);
        due.schedule(attacker.id(),battle.attacker().march().arriveAt());
        var targetOperations=new StrategicGarrisonWithdrawal(configs,operations,owners,marches,due,defenderReader,social,
                new InJvmPlayerLock(),recovery);
        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenderReader,social,targetOperations,operations);
        var advanced=new java.util.concurrent.atomic.AtomicInteger();

        assertThat(advancer.advance(attacker,battle.happenedAt(),()->{advanced.incrementAndGet();return true;})).isTrue();
        assertThat(advanced.get()).isEqualTo(1);
        var owner=owners.findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        assertThat(owner).as("attacker wins and must establish an exact target ownership receipt before later steps").isNotNull();
        assertThat(owner.nationId()).isEqualTo(nation.id());
        assertThat(owner.garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(attacker.id()));
        var npc=neutral.findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        assertThat(npc.phase()).isEqualTo(com.ironoath.web.world.NeutralTargetGarrisonStore.Phase.RETIRED);
        assertThat(npc.troops()).isEmpty();
        var projected=StrategicBattleMarchOutcome.project(plan,StrategicRallyCasualties.Side.ATTACKER).orElseThrow();
        assertThat(projected.disposition()).isEqualTo(StrategicBattleMarchOutcome.Disposition.HOLD);
        assertThat(marches.versionOf(attacker.id())).isEqualTo(Math.addExact(originalVersion,1));
        assertThat(marches.findById(attacker.id()).orElseThrow().snapshot()).isEqualTo(projected.after());
        assertThat(due.dueBefore(battle.happenedAt()+10_000,16)).isEmpty();
        var persistedReport=battleReports.reportsOf(battle.attacker().rally().initiatorId()).get(0);
        assertThat(persistedReport.reportId()).isEqualTo(com.ironoath.web.battle.BattleReport.idForOperation(plan.operationId(),
                battle.attacker().rally().initiatorId()));
        assertThat(persistedReport.defenderName()).isEqualTo(targetName);
        var expectedKills=battle.result().atkKillsByMember();
        var actualKillScores=rankFixture.boards.board(plan.killPlan().seasonId(),com.ironoath.core.season.SeasonSettlement.Board.KILL)
                .stream().collect(java.util.stream.Collectors.toMap(com.ironoath.core.season.SeasonSettlement.Entry::id,
                        com.ironoath.core.season.SeasonSettlement.Entry::score));
        assertThat(actualKillScores).isEqualTo(expectedKills);
        var pending=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(pending.completed()).isTrue();assertThat(pending.version()).isEqualTo(4);
        assertThat(pending.combatPlan().battle()).isEqualTo(battle);
        assertThat(pending.combatPlan().marchRecovery()).isNotNull();
        var warEvent=pending.combatPlan().warRecovery().event();assertThat(warEvent).isNotNull();
        assertThat(wars.findByStartedAt(battle.happenedAt()-10).orElseThrow().battleEvent(battle.target().targetId()))
                .isEqualTo(warEvent);
        assertThat(wars.pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isEmpty();
    }

    @Test void npcDefenderVictoryReplaysTheWholeCombatWithoutInventingAnOwner() {
        var battle=StrategicCombatSlotTest.battle(2380,false,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.DEFENDER);
        assertThat(battle.npcSurvivors()).isNotEmpty();
        var recovered=recoverWholeOperation(battle);
        assertThat(recovered.slot().completed()).isTrue();
        assertThat(recovered.owners().findByTargetIds(List.of(battle.target().targetId()))).doesNotContainKey(battle.target().targetId());
        var neutral=recovered.neutral().findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        assertThat(neutral.phase()).isEqualTo(InMemoryNeutralTargetGarrisonStore.Phase.NEUTRAL);
        assertThat(neutral.troops()).isEqualTo(battle.npcSurvivors());
        assertThat(recovered.reports().reportsOf(battle.attacker().rally().initiatorId())).hasSize(1);
        var expectedKills=battle.result().atkKillsByMember();
        var actualKills=recovered.rankBoards().board("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL)
                .stream().collect(java.util.stream.Collectors.toMap(com.ironoath.core.season.SeasonSettlement.Entry::id,
                        com.ironoath.core.season.SeasonSettlement.Entry::score));
        assertThat(actualKills).isEqualTo(expectedKills);
        assertMarchOutcomeApplied(recovered,StrategicRallyCasualties.Side.ATTACKER);
        assertThat(recovered.wars().findByStartedAt(battle.happenedAt()-10).orElseThrow()
                .battleEvent(battle.target().targetId())).isEqualTo(recovered.slot().combatPlan().warRecovery().event());
        assertThat(recovered.wars().pendingBattleReservation(battle.happenedAt()-10,
                recovered.slot().operationId())).isEmpty();
    }

    @Test void playerAttackerVictoryCompletesOwnershipReportsMarchAndWarFromTheFrozenResult() {
        var battle=StrategicCombatSlotTest.battle(2384,true,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        var recovered=recoverWholeOperation(battle);
        assertThat(recovered.slot().completed()).isTrue();
        var owner=recovered.owners().findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        assertThat(owner.nationId()).isEqualTo(battle.attacker().rally().nationId());
        assertThat(owner.garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(battle.attacker().march().id()));
        assertThat(recovered.reports().reportsOf(battle.attacker().rally().initiatorId())).hasSize(1);
        assertThat(recovered.reports().reportsOf(battle.defender().rally().initiatorId())).hasSize(1);
        var expectedKills=new java.util.HashMap<String,Long>(battle.result().atkKillsByMember());
        expectedKills.putAll(battle.result().defKillsByMember());
        var actualKills=recovered.rankBoards().board("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL)
                .stream().collect(java.util.stream.Collectors.toMap(com.ironoath.core.season.SeasonSettlement.Entry::id,
                        com.ironoath.core.season.SeasonSettlement.Entry::score));
        assertThat(actualKills).isEqualTo(expectedKills);
        assertMarchOutcomeApplied(recovered,StrategicRallyCasualties.Side.ATTACKER);
        assertMarchOutcomeApplied(recovered,StrategicRallyCasualties.Side.DEFENDER);
        assertThat(recovered.wars().findByStartedAt(battle.happenedAt()-10).orElseThrow()
                .battleEvent(battle.target().targetId())).isEqualTo(recovered.slot().combatPlan().warRecovery().event());
        assertThat(recovered.wars().pendingBattleReservation(battle.happenedAt()-10,
                recovered.slot().operationId())).isEmpty();
    }

    private record WholeRecovery(TargetOperationStore.Snapshot slot,InMemoryTargetOccupationStore owners,
            InMemoryNeutralTargetGarrisonStore neutral,InMemoryMarchStore marches,InMemoryBattleReportStore reports,
            InMemoryWarStore wars,InMemorySeasonBoardStore rankBoards,SortedMarchDueQueue due) {}

    private static void assertMarchOutcomeApplied(WholeRecovery recovered,StrategicRallyCasualties.Side side) {
        var recovery=recovered.slot().combatPlan().marchRecovery();
        var intent=side==StrategicRallyCasualties.Side.ATTACKER?recovery.attacker():recovery.defender();
        assertThat(intent).isNotNull();var observed=recovered.marches().findById(intent.marchId());
        if(intent.disposition()==StrategicBattleMarchOutcome.Disposition.ANNIHILATED)assertThat(observed).isEmpty();
        else assertThat(observed).map(March::snapshot).contains(intent.after());
        Long returnAt=intent.after()==null?null:intent.after().returnArriveAt();
        if(returnAt!=null)assertThat(recovered.due().dueAtOf(intent.marchId())).hasValue(returnAt);
        else assertThat(recovered.due().dueAtOf(intent.marchId())).isEmpty();
    }

    private static WholeRecovery recoverWholeOperation(com.ironoath.web.battle.CapturedStrategicBattle battle) {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var rankFixture=new RankFixture(configs);
        String operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        String targetName=com.ironoath.web.service.WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().id().equals(battle.target().targetId())).findFirst().orElseThrow()
                .definition().name();
        var reportPlan=StrategicBattleReportPlan.create(battle,operationId,
                battle.happenedAt()+configs.longParam("BATTLE_REPORT_TTL_SECONDS")*1000L,"attacker",
                battle.defender()==null?targetName:"defender");
        var killPlan=rankFixture.service.freezeStrategicKills(battle,operationId,1);
        var plan=TargetOperationStore.CombatPlan.create(battle,1).withReportPlan(reportPlan).withKillPlan(killPlan);
        var operations=new InMemoryTargetOperationStore();
        var owners=new InMemoryTargetOccupationStore();
        if(battle.target().defender().occupation()!=null)
            assertThat(owners.compareAndSet(battle.target().defender().occupation(),0)).isTrue();
        var neutral=new InMemoryNeutralTargetGarrisonStore();var armies=new InMemoryArmyStore();
        var marches=new InMemoryMarchStore(32);var due=new SortedMarchDueQueue();
        var sides=new ArrayList<StrategicRallyCasualties>();sides.add(battle.attacker());
        if(battle.defender()!=null)sides.add(battle.defender());
        for(var side:sides) {
            for(var member:side.currentMembers().entrySet()) {
                var army=new ArmyState();member.getValue().forEach(army::add);
                assertThat(armies.insertIfAbsent(member.getKey(),army)).isTrue();
            }
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            due.schedule(march.id(),march.arriveAt());
        }
        var nationRules=new NationRulesAssembler(configs);var nationStore=mock(NationStore.class);
        var attackerNation=Nation.found(battle.attacker().rally().nationId(),"进攻国",
                battle.attacker().rally().initiatorId(),"owned-attacker-alliance",0,0,100,nationRules.rules());
        when(nationStore.findById(attackerNation.id())).thenReturn(Optional.of(attackerNation));
        var warRules=new WarRulesAssembler(configs);var wars=new InMemoryWarStore(warRules);
        var war=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,battle.attacker().rally().nationId(),
                battle.happenedAt()+1000);
        war.registerNation(battle.attacker().rally().nationId());
        if(battle.defender()!=null)war.registerNation(battle.defender().rally().nationId());else war.registerNation("owned-opponent");
        if(battle.target().kind()==com.ironoath.core.world.WorldGenerator.EntityType.GATE
                && battle.target().defender().occupation()!=null)
            war.captureGate(battle.target().defender().occupation().nationId(),battle.target().targetId());
        assertThat(wars.insertIfNoneActive(war)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        if(battle.defender()!=null)addMembers(members,battle.defender(),battle.result().defKillsByMember());
        var reservation=wars.reserveBattle(battle.target().targetId(),1,operationId,battle.happenedAt(),members).orElseThrow();
        plan=plan.withWarRecovery(new TargetOperationStore.WarRecoveryPlan(reservation,null));
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var reports=new InMemoryBattleReportStore();var social=mock(SocialAppService.class);
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var reportService=new BattleReportService(configs,reports,mock(com.ironoath.common.time.TimeService.class),
                social,rankFixture.service,wars,mock(NationMembership.class));
        var recovery=new StrategicCombatRecovery(operations,armies,marches,owners,neutral,nationStore,due,reportService,
                rankFixture.service,wars);
        var pending=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);recovery.recoverTargetState(pending,hospital);
        var withMarch=recovery.freezeMarchOutcomes(operations.findByTargetId(battle.target().targetId()).orElseThrow());
        recovery.recoverMarchOutcomes(withMarch);
        var withEvent=recovery.freezeWarEvent(operations.findByTargetId(battle.target().targetId()).orElseThrow(),hospital);
        recovery.recoverWarEvent(withEvent);
        var completedRef=new java.util.concurrent.atomic.AtomicReference<TargetOperationStore.Snapshot>();
        assertThatCode(()->completedRef.set(recovery.completeCombat(
                operations.findByTargetId(battle.target().targetId()).orElseThrow(),hospital))).doesNotThrowAnyException();
        var completed=completedRef.get();
        return new WholeRecovery(completed,owners,neutral,marches,reports,wars,rankFixture.boards,due);
    }

    private static void addMembers(List<WarScoreBoard.BattleMember> out,StrategicRallyCasualties side,Map<String,Long> kills) {
        side.currentMembers().keySet().forEach(player->out.add(new WarScoreBoard.BattleMember(player,side.rally().nationId(),kills.get(player))));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void pendingCombatUsesFrozenRalliesAndStopsAtUnsupportedRecoveryBarrier(boolean playerDefender) {
        var battle=StrategicCombatSlotTest.battle(2391,playerDefender);
        var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var slot=TargetOperationStore.Snapshot.combat(1,false,plan);
        var operations=new InMemoryTargetOperationStore();assertThat(operations.compareAndSet(slot,0)).isTrue();
        var attacker=March.fromSnapshot(battle.attacker().march());
        var marches=mock(MarchRepository.class);when(marches.findById(attacker.id())).thenReturn(Optional.of(attacker));
        var defenders=mock(StrategicTargetDefender.class);
        when(defenders.read(any())).thenThrow(new AssertionError("must recover frozen COMBAT before live defender read"));
        var social=mock(SocialAppService.class);var lockSets=new ArrayList<List<String>>();
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->{
            lockSets.add(List.copyOf((List<String>)call.getArgument(0)));
            return ((Supplier<?>)call.getArgument(1)).get();
        });
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        PlayerLock lock=new InJvmPlayerLock();
        var withdrawal=new StrategicGarrisonWithdrawal(configs,operations,new InMemoryTargetOccupationStore(),
                marches,mock(MarchDueQueue.class),defenders,social,lock);
        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenders,social,withdrawal,operations);

        assertThatThrownBy(()->advancer.advance(attacker,battle.happenedAt(),()->true))
                .isInstanceOf(BizException.class).hasMessageContaining("战略战斗操作尚未确认恢复");
        var expected=new ArrayList<String>();expected.add(battle.attacker().march().rallyId());
        if(playerDefender)expected.add(battle.defender().march().rallyId());
        assertThat(lockSets).containsExactly(expected.stream().sorted().toList());
        assertThat(operations.findByTargetId(slot.targetId())).contains(slot);
        verify(defenders,never()).read(any());
    }

    private static final class RankFixture {
        final InMemorySeasonBoardStore boards=new InMemorySeasonBoardStore();
        final Set<String> bots;
        final RankBoardService service;
        RankFixture(ConfigRegistry configs) {this(configs,Set.of());}
        RankFixture(ConfigRegistry configs,Set<String> bots) {
            this.bots=Set.copyOf(bots);
            var social=mock(SocialStore.class);var nations=mock(NationStore.class);
            var players=mock(PlayerRepository.class);var botRegistry=mock(BotRegistry.class);
            when(botRegistry.humanOnly(anyString(),anyString())).thenAnswer(call->this.bots.contains(call.getArgument(0))?null:call.getArgument(0));
            when(players.findBriefs(anyCollection())).thenAnswer(call->{
                var out=new LinkedHashMap<String,PlayerBrief>();
                for(String id:(java.util.Collection<String>)call.getArgument(0))
                    out.put(id,new PlayerBrief(id,"rank:"+id,1,0,0));
                return out;
            });
            var rules=mock(SeasonRulesAssembler.class);
            when(rules.timelineRules(org.mockito.ArgumentMatchers.anyLong())).thenReturn(new com.ironoath.core.season.SeasonTimeline.Rules(
                    List.of(new com.ironoath.core.season.SeasonTimeline.Stage(1,
                            com.ironoath.core.season.SeasonTimeline.Phase.EXPAND,0,30)),"season-test"));
            service=new RankBoardService(boards,social,nations,new NationMembership(social,nations),players,botRegistry,
                    configs,rules,mock(com.ironoath.common.time.TimeService.class));
        }
    }
}
