package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleHospitalSettlement;
import com.ironoath.web.battle.StrategicBattleReportPlan;
import com.ironoath.web.battle.StrategicBattleKillPlan;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.store.mongo.*;
import com.ironoath.web.world.StrategicCombatRecovery;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/** The recovery owner replays actual Mongo Army/March/War/COMBAT receipts across fresh store instances. */
class StrategicCombatRecoveryMongoTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.template().getDb().drop();MongoIndexes.ensure(db.template());}

    @Test void strategicInitiatorReportsSurviveFreshMongoStoreReplay() {
        var battle=StrategicCombatSlotTest.battle(2404,true,true);
        var operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var reportPlan=StrategicBattleReportPlan.create(battle,operationId,battle.happenedAt()+86_400_000L,
                "frozen attacker","frozen defender");
        var combat=TargetOperationStore.CombatPlan.create(battle,1).withReportPlan(reportPlan);
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,combat),0)).isTrue();
        var persistedPlan=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId())
                .orElseThrow().combatPlan().reportPlan();
        assertThat(persistedPlan).isEqualTo(reportPlan);

        var first=new com.ironoath.web.battle.BattleReportService(ConfigRegistry.loadFromDirectory(Path.of("contract/config")),
                new MongoBattleReportStore(db.template()),mock(com.ironoath.common.time.TimeService.class),
                mock(com.ironoath.web.service.SocialAppService.class),mock(com.ironoath.web.rank.RankBoardService.class),
                mock(com.ironoath.web.nation.WarStore.class),mock(com.ironoath.web.nation.NationMembership.class))
                .recordStrategic(persistedPlan,battle.result());
        var replay=new com.ironoath.web.battle.BattleReportService(ConfigRegistry.loadFromDirectory(Path.of("contract/config")),
                new MongoBattleReportStore(db.template()),mock(com.ironoath.common.time.TimeService.class),
                mock(com.ironoath.web.service.SocialAppService.class),mock(com.ironoath.web.rank.RankBoardService.class),
                mock(com.ironoath.web.nation.WarStore.class),mock(com.ironoath.web.nation.NationMembership.class))
                .recordStrategic(persistedPlan,battle.result());
        assertThat(replay).isEqualTo(first).hasSize(2);
        var readStore=new MongoBattleReportStore(db.template());
        for(var report:first)assertThat(readStore.findById(report.reportId())).contains(report);
        assertThat(readStore.reportsOf(battle.attacker().rally().initiatorId())).containsExactly(first.get(0));
        assertThat(readStore.reportsOf(battle.defender().rally().initiatorId())).containsExactly(first.get(1));
    }

    @Test void strategicEngineKillsUseOriginalSeasonScopedMongoReceipts() {
        var battle=StrategicCombatSlotTest.battle(2406,true,true);
        String operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var engineKills=new java.util.TreeMap<String,Long>();
        engineKills.putAll(battle.result().atkKillsByMember());engineKills.putAll(battle.result().defKillsByMember());
        var members=engineKills.entrySet().stream().map(entry->new StrategicBattleKillPlan.Member(
                entry.getKey(),"frozen:"+entry.getKey(),entry.getValue(),true)).toList();
        var plan=new StrategicBattleKillPlan(1,battle.target().targetId(),1,operationId,"season-test",members);
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        java.util.function.Supplier<RankBoardService> service=()->new RankBoardService(new MongoSeasonBoardStore(db.template()),
                mock(com.ironoath.web.social.SocialStore.class),mock(com.ironoath.web.nation.NationStore.class),
                mock(com.ironoath.web.nation.NationMembership.class),mock(com.ironoath.core.player.PlayerRepository.class),
                mock(com.ironoath.web.bot.BotRegistry.class),configs,mock(com.ironoath.web.season.SeasonRulesAssembler.class),
                mock(com.ironoath.common.time.TimeService.class));
        var first=service.get().recordStrategicKills(plan);
        var replay=service.get().recordStrategicKills(plan);
        assertThat(replay).isEqualTo(first).hasSize(members.size());
        var board=new MongoSeasonBoardStore(db.template()).board("season-test",com.ironoath.core.season.SeasonSettlement.Board.KILL);
        var actual=board.stream().collect(java.util.stream.Collectors.toMap(
                com.ironoath.core.season.SeasonSettlement.Entry::id,com.ironoath.core.season.SeasonSettlement.Entry::score));
        var expected=engineKills.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
        assertThat(actual).isEqualTo(expected);
        for(var member:members)assertThat(new MongoSeasonBoardStore(db.template()).scopedIncrementOf("season-test",
                com.ironoath.core.season.SeasonSettlement.Board.KILL,member.playerId(),battle.target().targetId()))
                .contains(new com.ironoath.web.season.SeasonBoardStore.ScopedIncrement(battle.target().targetId(),1,
                        operationId,member.playerId(),member.displayName(),member.kills()));
    }

    @Test void warEventReleaseAndFinalCombatCompletionReplayFromFreshMongoStores() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var battle=StrategicCombatSlotTest.battle(2382,false,true,false);
        var operationId="battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion();
        var warRules=new WarRulesAssembler(configs);var wars=new MongoWarStore(db.template(),warRules);
        var board=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation("owned-opponent");
        assertThat(wars.insertIfNoneActive(board)).isTrue();var warMembers=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(warMembers,battle.attacker(),battle.result().atkKillsByMember());
        var reservation=wars.reserveBattle(battle.target().targetId(),1,operationId,battle.happenedAt(),warMembers).orElseThrow();

        var targetName=com.ironoath.web.service.WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().id().equals(battle.target().targetId())).findFirst().orElseThrow().definition().name();
        var reportPlan=StrategicBattleReportPlan.create(battle,operationId,
                battle.happenedAt()+configs.longParam("BATTLE_REPORT_TTL_SECONDS")*1000L,"attacker",targetName);
        var ranks=rankService(configs,new MongoSeasonBoardStore(db.template()));
        var killPlan=ranks.freezeStrategicKills(battle,operationId,1);
        var combat=TargetOperationStore.CombatPlan.create(battle,1).withReportPlan(reportPlan).withKillPlan(killPlan)
                .withWarRecovery(new TargetOperationStore.WarRecoveryPlan(reservation,null));
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,combat),0)).isTrue();

        var armies=new MongoArmyStore(db.template());
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var attacker=March.fromSnapshot(battle.attacker().march());
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        while(marches.versionOf(attacker.id())<battle.attacker().marchVersion())marches.save(attacker,marches.versionOf(attacker.id()));
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var nation=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(nation)).isTrue();
        var occupations=new MongoTargetOccupationStore(db.template());var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var due=new MongoMarchDueQueue(db.template());due.schedule(attacker.id(),battle.attacker().march().arriveAt());
        var reportStore=new MongoBattleReportStore(db.template());
        var reportService=new com.ironoath.web.battle.BattleReportService(configs,reportStore,
                mock(com.ironoath.common.time.TimeService.class),mock(com.ironoath.web.service.SocialAppService.class),
                ranks,wars,mock(com.ironoath.web.nation.NationMembership.class));
        var recovery=new StrategicCombatRecovery(operations,armies,marches,occupations,neutral,nations,due,reportService,ranks,wars);

        var pending=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);recovery.recoverTargetState(pending,hospital);
        var marchPlan=recovery.freezeMarchOutcomes(pending);recovery.recoverMarchOutcomes(marchPlan);
        var afterMarch=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        recovery.recoverReports(afterMarch,hospital);recovery.recoverSeasonKills(afterMarch);
        var withWarEvent=recovery.freezeWarEvent(afterMarch,hospital);recovery.recoverWarEvent(withWarEvent);
        var afterWar=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(afterWar.completed()).isFalse();assertThat(afterWar.combatPlan().warRecovery().event()).isNotNull();
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(reservation.warStartedAt(),operationId)).isEmpty();
        assertThat(new MongoWarStore(db.template(),warRules).findByStartedAt(reservation.warStartedAt()).orElseThrow()
                .battleEvent(battle.target().targetId())).isEqualTo(afterWar.combatPlan().warRecovery().event());

        var freshOperations=new MongoTargetOperationStore(db.template());
        var freshRanks=rankService(configs,new MongoSeasonBoardStore(db.template()));
        var freshRecovery=new StrategicCombatRecovery(freshOperations,new MongoArmyStore(db.template()),
                new MongoMarchStore(db.template(),32),new MongoTargetOccupationStore(db.template()),
                new MongoNeutralTargetGarrisonStore(db.template()),new MongoNationStore(db.template(),nationRules),
                new MongoMarchDueQueue(db.template()),new com.ironoath.web.battle.BattleReportService(configs,
                new MongoBattleReportStore(db.template()),mock(com.ironoath.common.time.TimeService.class),
                mock(com.ironoath.web.service.SocialAppService.class),freshRanks,new MongoWarStore(db.template(),warRules),
                mock(com.ironoath.web.nation.NationMembership.class)),freshRanks,new MongoWarStore(db.template(),warRules));
        var resumed=freshOperations.findByTargetId(battle.target().targetId()).orElseThrow();
        var resumedHospital=freshRecovery.recoverHospitalReceipts(resumed);
        var completed=freshRecovery.completeCombat(resumed,resumedHospital);
        assertThat(completed.completed()).isTrue();assertThat(completed.combatPlan().warRecovery().event())
                .isEqualTo(afterWar.combatPlan().warRecovery().event());
        assertThat(new MongoBattleReportStore(db.template()).reportsOf(battle.attacker().rally().initiatorId())).hasSize(1);
        assertThat(new MongoSeasonBoardStore(db.template()).board(killPlan.seasonId(),com.ironoath.core.season.SeasonSettlement.Board.KILL))
                .extracting(com.ironoath.core.season.SeasonSettlement.Entry::id)
                .containsExactlyElementsOf(killPlan.members().stream().filter(StrategicBattleKillPlan.Member::leaderboardEligible)
                        .map(StrategicBattleKillPlan.Member::playerId).sorted().toList());
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(reservation.warStartedAt(),operationId)).isEmpty();
    }

    private static RankBoardService rankService(ConfigRegistry configs,com.ironoath.web.season.SeasonBoardStore boards) {
        var social=mock(SocialStore.class);var nations=mock(com.ironoath.web.nation.NationStore.class);
        var players=mock(PlayerRepository.class);var bots=mock(BotRegistry.class);
        when(bots.humanOnly(anyString(),anyString())).thenAnswer(call->call.getArgument(0));
        when(players.findBriefs(anyCollection())).thenAnswer(call->{
            var out=new java.util.LinkedHashMap<String,PlayerBrief>();
            for(String id:(java.util.Collection<String>)call.getArgument(0))out.put(id,new PlayerBrief(id,"rank:"+id,1,0,0));
            return out;
        });
        var rules=mock(com.ironoath.web.season.SeasonRulesAssembler.class);
        when(rules.timelineRules(anyLong())).thenReturn(new com.ironoath.core.season.SeasonTimeline.Rules(
                List.of(new com.ironoath.core.season.SeasonTimeline.Stage(1,
                        com.ironoath.core.season.SeasonTimeline.Phase.EXPAND,0,30)),"season-test"));
        return new RankBoardService(boards,social,nations,new com.ironoath.web.nation.NationMembership(social,nations),
                players,bots,configs,rules,mock(com.ironoath.common.time.TimeService.class));
    }

    @Test void admittedHospitalReceiptsSurviveRecoveryRestartWithoutAdvancingThePendingCombat() {
        var battle=StrategicCombatSlotTest.battle(2400,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var rules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),rules);var board=new WarScoreBoard(rules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation(battle.defender().rally().nationId());
        assertThat(wars.insertIfNoneActive(board)).isTrue();
        var warMembers=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(warMembers,battle.attacker(),battle.result().atkKillsByMember());
        addWarMembers(warMembers,battle.defender(),battle.result().defKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),warMembers)).isPresent();

        var armies=new MongoArmyStore(db.template());
        var marches=new MongoMarchStore(db.template(),32);var originalMarches=new java.util.LinkedHashMap<String,March.Snapshot>();
        var originalVersions=new java.util.LinkedHashMap<String,Long>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            for(String player:side.currentMembers().keySet()) {
                var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
            }
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            originalMarches.put(march.id(),march.snapshot());originalVersions.put(march.id(),marches.versionOf(march.id()));
        }

        var recovery=new StrategicCombatRecovery(operations,armies,marches);
        var hospital=recovery.recoverHospitalReceipts(operations.findByTargetId(battle.target().targetId()).orElseThrow());
        assertThat(hospital.plan()).isEqualTo(plan);
        var receipts=readReceipts(battle,new MongoArmyStore(db.template()));
        assertThat(receipts).hasSize(battle.attacker().currentMembers().size()+battle.defender().currentMembers().size());
        assertThat(recoveryFromFreshStores(plan)).isEqualTo(hospital);
        assertThat(readReceipts(battle,new MongoArmyStore(db.template()))).isEqualTo(receipts);

        var afterOperations=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(afterOperations).isEqualTo(TargetOperationStore.Snapshot.combat(1,false,plan));
        var afterWar=new MongoWarStore(db.template(),rules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId());
        assertThat(afterWar).isPresent();
        var afterMarches=new MongoMarchStore(db.template(),32);
        originalMarches.forEach((id,snapshot)->{
            assertThat(afterMarches.findById(id).orElseThrow().snapshot()).isEqualTo(snapshot);
            assertThat(afterMarches.versionOf(id)).isEqualTo(originalVersions.get(id));
        });
        assertThat(new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId())))
                .doesNotContainKey(battle.target().targetId());
        assertThat(new MongoMarchDueQueue(db.template()).dueBefore(battle.happenedAt()+10_000,16)).isEmpty();
    }

    @Test void npcVictoryPersistsCaptureAndNeutralRetirementInMongoBeforeMarchCompletion() {
        var battle=StrategicCombatSlotTest.battle(2382,false,true,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        assertThat(battle.npcSurvivors()).isNotEmpty();
        var plan=TargetOperationStore.CombatPlan.create(battle,1);var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var warRules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),warRules);var board=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation("owned-opponent");
        assertThat(wars.insertIfNoneActive(board)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members)).isPresent();

        var armies=new MongoArmyStore(db.template());
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var attacker=March.fromSnapshot(battle.attacker().march());
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        while(marches.versionOf(attacker.id())<battle.attacker().marchVersion())marches.save(attacker,marches.versionOf(attacker.id()));
        long marchVersion=marches.versionOf(attacker.id());var marchBefore=marches.findById(attacker.id()).orElseThrow().snapshot();
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var nation=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(nation)).isTrue();
        var occupations=new MongoTargetOccupationStore(db.template());var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var recovery=new StrategicCombatRecovery(operations,armies,marches,occupations,neutral,nations);
        var current=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(current);recovery.recoverTargetState(current,hospital);

        var recoveredOwner=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId()))
                .get(battle.target().targetId());
        assertThat(recoveredOwner.nationId()).isEqualTo(nation.id());
        assertThat(recoveredOwner.garrison()).isEqualTo(com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(attacker.id()));
        var recoveredNpc=new MongoNeutralTargetGarrisonStore(db.template()).findByTargetIds(List.of(battle.target().targetId()))
                .get(battle.target().targetId());
        assertThat(recoveredNpc.phase()).isEqualTo(com.ironoath.web.world.NeutralTargetGarrisonStore.Phase.RETIRED);
        assertThat(recoveredNpc.troops()).isEmpty();
        assertThat(new MongoMarchStore(db.template(),32).versionOf(attacker.id())).isEqualTo(marchVersion);
        assertThat(new MongoMarchStore(db.template(),32).findById(attacker.id()).orElseThrow().snapshot()).isEqualTo(marchBefore);
        assertThat(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()))
                .contains(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId()))
                .isPresent();
    }

    @Test void fortressBattleRecoveryFeedsFreshMongoOccupationIntoResourceSettlement() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var fortress=com.ironoath.web.service.WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .sorted(java.util.Comparator.comparing(target->target.definition().id())).findFirst().orElseThrow();
        var battle=StrategicCombatSlotTest.fortressBattle(2384L,fortress.definition().id());
        assertThat(battle.target().kind()).isEqualTo(com.ironoath.core.world.WorldGenerator.EntityType.FORTRESS);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        var plan=TargetOperationStore.CombatPlan.create(battle,1);var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

        var armies=new MongoArmyStore(db.template());
        for(var member:battle.attacker().currentMembers().entrySet()) {
            var army=new ArmyState();member.getValue().forEach(army::add);
            assertThat(armies.insertIfAbsent(member.getKey(),army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var attacker=March.fromSnapshot(battle.attacker().march());
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        while(marches.versionOf(attacker.id())<battle.attacker().marchVersion())
            marches.save(attacker,marches.versionOf(attacker.id()));
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        long membershipStartedAt=Math.max(0,battle.happenedAt()-10);
        var nation=Nation.found(battle.attacker().rally().nationId(),"要塞Mongo收益国",
                battle.attacker().rally().initiatorId(),"owned-fortress-alliance",0,0,membershipStartedAt,nationRules.rules());
        assertThat(nations.insertIfAbsent(nation)).isTrue();
        var recovery=new StrategicCombatRecovery(operations,armies,marches,
                new MongoTargetOccupationStore(db.template()),new MongoNeutralTargetGarrisonStore(db.template()),nations);
        var pending=new MongoTargetOperationStore(db.template()).findByTargetId(fortress.definition().id()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);recovery.recoverTargetState(pending,hospital);

        var freshOccupation=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(fortress.definition().id()))
                .get(fortress.definition().id());
        assertThat(freshOccupation.nationId()).isEqualTo(nation.id());
        assertThat(freshOccupation.garrison()).isEqualTo(
                com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(attacker.id()));
        assertThat(freshOccupation.capturedAt()).isEqualTo(battle.happenedAt());

        var social=mock(SocialStore.class);var alliance=mock(com.ironoath.core.social.Alliance.class);
        String allianceId="owned-fortress-alliance";when(alliance.id()).thenReturn(allianceId);
        when(social.allianceOf(battle.attacker().rally().initiatorId())).thenReturn(java.util.Optional.of(alliance));
        when(social.allianceMembershipEpochs(battle.attacker().rally().initiatorId(),allianceId,
                battle.happenedAt()+3_600_000L)).thenReturn(List.of(new SocialStore.AllianceMembershipEpoch(
                        allianceId,membershipStartedAt,Long.MAX_VALUE)));
        var leaders=mock(com.ironoath.web.nation.NationLeaders.class);
        when(leaders.bind(org.mockito.ArgumentMatchers.any())).thenAnswer(call->call.getArgument(0));
        var nationTech=new com.ironoath.web.nation.NationTechBonuses(configs,social,
                new MongoNationStore(db.template(),nationRules),leaders);
        var policy=new com.ironoath.web.nation.NationPolicyBonuses(configs,social,
                new MongoNationStore(db.template(),nationRules),leaders,new com.ironoath.web.battle.BattleArmyFactory(configs));
        var freshRates=new com.ironoath.web.service.ResourceRateService(configs,new com.ironoath.web.tech.TechEffects(configs),
                nationTech,policy,new MongoTargetOccupationStore(db.template()));
        long at=battle.happenedAt(),settledAt=at+3_600_000L;
        var resources=Map.of(com.ironoath.core.resource.ResourceIds.GRAIN,
                new com.ironoath.core.player.PlayerResourceState(0,1_000_000L,0,0,at));
        var player=com.ironoath.core.player.PlayerSave.createNew(battle.attacker().rally().initiatorId(),
                "fresh-fortress-"+java.util.UUID.randomUUID(),"Mongo要塞成员",16,at,1,resources,
                com.ironoath.core.player.PlayerPower.zero(),null);
        long base=configs.getResource(com.ironoath.core.resource.ResourceIds.GRAIN).basePerHour();
        long beforeCapture=freshRates.compute(new com.ironoath.core.city.CityState(),player.tech(),player.playerId(),at-1)
                .perHour(com.ironoath.core.resource.ResourceIds.GRAIN);
        var settled=freshRates.settle(player,new com.ironoath.core.city.CityState(),settledAt);
        assertThat(settled.rates().perHour(com.ironoath.core.resource.ResourceIds.GRAIN))
                .isEqualTo(beforeCapture+base*2/100);
        assertThat(settled.credited().get(com.ironoath.core.resource.ResourceIds.GRAIN))
                .isEqualTo(beforeCapture+base*2/100);
    }

    @Test void playerDefenseKeepsFrozenOwnerInOneMongoRecoveryBoundary() {
        var battle=StrategicCombatSlotTest.battle(2399,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var warRules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),warRules);var board=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation(battle.defender().rally().nationId());
        assertThat(wars.insertIfNoneActive(board)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        addWarMembers(members,battle.defender(),battle.result().defKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members)).isPresent();

        var armies=new MongoArmyStore(db.template());
        for(var side:List.of(battle.attacker(),battle.defender()))for(var member:side.currentMembers().entrySet()) {
            var army=new ArmyState();member.getValue().forEach(army::add);assertThat(armies.insertIfAbsent(member.getKey(),army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var originalVersions=new java.util.LinkedHashMap<String,Long>();
        var originalMarches=new java.util.LinkedHashMap<String,March.Snapshot>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            originalVersions.put(march.id(),marches.versionOf(march.id()));originalMarches.put(march.id(),march.snapshot());
        }
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var attacker=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(attacker)).isTrue();
        var originalOwner=battle.target().defender().occupation();var occupations=new MongoTargetOccupationStore(db.template());
        assertThat(originalOwner).isNotNull();assertThat(occupations.compareAndSet(originalOwner,0)).isTrue();
        var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var recovery=new StrategicCombatRecovery(operations,armies,marches,occupations,neutral,nations);
        var pending=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);recovery.recoverTargetState(pending,hospital);

        var ownerRead=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        if(battle.result().winner()==com.ironoath.battle.Winner.ATTACKER) {
            assertThat(ownerRead.nationId()).isEqualTo(attacker.id());
            assertThat(ownerRead.garrison()).isEqualTo(com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(battle.attacker().march().id()));
        } else assertThat(ownerRead).isEqualTo(originalOwner);
        var currentSlot=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(currentSlot).isEqualTo(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isPresent();
        var marchRead=new MongoMarchStore(db.template(),32);
        originalMarches.forEach((id,snapshot)->{
            assertThat(marchRead.findById(id).orElseThrow().snapshot()).isEqualTo(snapshot);
            assertThat(marchRead.versionOf(id)).isEqualTo(originalVersions.get(id));
        });
    }

    @Test void npcDefenseVictoryPersistsOnlyEngineSurvivorsWithoutCreatingAnOwner() {
        var battle=StrategicCombatSlotTest.battle(2380,false,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.DEFENDER);
        assertThat(battle.npcSurvivors()).isNotEmpty();
        var plan=TargetOperationStore.CombatPlan.create(battle,1);var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var armies=new MongoArmyStore(db.template());
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var march=March.fromSnapshot(battle.attacker().march());var marches=new MongoMarchStore(db.template(),32);
        assertThat(marches.insertIfAbsent(march)).isTrue();
        while(marches.versionOf(march.id())<battle.attacker().marchVersion())marches.save(march,marches.versionOf(march.id()));
        var occupations=new MongoTargetOccupationStore(db.template());var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var recovery=new StrategicCombatRecovery(new MongoTargetOperationStore(db.template()),new MongoArmyStore(db.template()),
                new MongoMarchStore(db.template(),32),new MongoTargetOccupationStore(db.template()),
                new MongoNeutralTargetGarrisonStore(db.template()),mock(com.ironoath.web.nation.NationStore.class));
        var pending=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);
        assertThat(hospital.result().winner()).isEqualTo(com.ironoath.battle.Winner.DEFENDER);
        recovery.recoverTargetState(pending,hospital);

        var neutralRead=new MongoNeutralTargetGarrisonStore(db.template()).findByTargetIds(List.of(battle.target().targetId()));
        assertThat(neutralRead).containsKey(battle.target().targetId());
        var persistedNeutral=neutralRead.get(battle.target().targetId());
        assertThat(persistedNeutral.phase()).isEqualTo(com.ironoath.web.world.NeutralTargetGarrisonStore.Phase.NEUTRAL);
        assertThat(persistedNeutral.initialTroops()).isEqualTo(battle.target().defender().troops());
        assertThat(persistedNeutral.troops()).isEqualTo(battle.npcSurvivors());
        assertThat(new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId())))
                .doesNotContainKey(battle.target().targetId());
        assertThat(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()))
                .contains(TargetOperationStore.Snapshot.combat(1,false,plan));
    }

    @Test void playerAttackerVictoryPersistsOwnershipAgainstTheFrozenPlayerOwner() {
        var battle=StrategicCombatSlotTest.battle(2384,true,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        var plan=TargetOperationStore.CombatPlan.create(battle,1);var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var armies=new MongoArmyStore(db.template());
        for(var side:List.of(battle.attacker(),battle.defender()))for(var member:side.currentMembers().entrySet()) {
            var army=new ArmyState();member.getValue().forEach(army::add);
            assertThat(armies.insertIfAbsent(member.getKey(),army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);
        for(var side:List.of(battle.attacker(),battle.defender())) {
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
        }
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var attacker=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(attacker)).isTrue();
        var originalOwner=battle.target().defender().occupation();var occupations=new MongoTargetOccupationStore(db.template());
        assertThat(originalOwner).isNotNull();assertThat(occupations.compareAndSet(originalOwner,0)).isTrue();
        var recovery=new StrategicCombatRecovery(new MongoTargetOperationStore(db.template()),new MongoArmyStore(db.template()),
                new MongoMarchStore(db.template(),32),new MongoTargetOccupationStore(db.template()),
                new MongoNeutralTargetGarrisonStore(db.template()),new MongoNationStore(db.template(),nationRules));
        var pending=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);
        assertThat(hospital.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        recovery.recoverTargetState(pending,hospital);

        var ownerRead=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId()));
        assertThat(ownerRead).containsKey(battle.target().targetId());
        var captured=ownerRead.get(battle.target().targetId());
        assertThat(captured.nationId()).isEqualTo(attacker.id());
        assertThat(captured.garrison()).isEqualTo(com.ironoath.web.world.TargetOccupationStore.Garrison
                .stationed(battle.attacker().march().id()));
        assertThat(captured.version()).isEqualTo(originalOwner.version()+1);
        assertThat(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()))
                .contains(TargetOperationStore.Snapshot.combat(1,false,plan));
    }

    private static StrategicBattleHospitalSettlement recoveryFromFreshStores(TargetOperationStore.CombatPlan plan) {
        var battle=plan.battle();
        return new StrategicCombatRecovery(new MongoTargetOperationStore(db.template()),new MongoArmyStore(db.template()),
                new MongoMarchStore(db.template(),32))
                .recoverHospitalReceipts(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow());
    }

    private static Map<String,ArmyState.StrategicWoundReceipt> readReceipts(CapturedStrategicBattle battle,MongoArmyStore armies) {
        var receipts=new java.util.LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var receipt=armies.findByPlayerId(player).orElseThrow().snapshot().strategicWounds().get(battle.target().targetId());
            assertThat(receipt).isNotNull();receipts.put(player,receipt);
        }
        return Map.copyOf(receipts);
    }

    private static void addWarMembers(List<WarScoreBoard.BattleMember> out,StrategicRallyCasualties side,Map<String,Long> kills) {
        side.currentMembers().keySet().forEach(player->out.add(new WarScoreBoard.BattleMember(player,side.rally().nationId(),kills.get(player))));
    }
}
