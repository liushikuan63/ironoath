package com.ironoath.web.store;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.social.Rally;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.CapturedBattlePreparation;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.nation.NationOperationLock;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.MongoTargetOperationStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.ironoath.web.world.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual simulator payload, actual nation/rally/target lock scopes, random TestMongo only. */
class StrategicCombatPublisherTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}

    private static final class Fixture {
        final ConfigRegistry cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        final CapturedStrategicBattle battle;
        final InMemoryMarchStore marches=new InMemoryMarchStore(32);
        final InMemoryTargetOccupationStore owners=new InMemoryTargetOccupationStore();
        final InMemoryNeutralTargetGarrisonStore neutral=new InMemoryNeutralTargetGarrisonStore();
        final SocialStore store=mock(SocialStore.class);
        final SocialAppService social=mock(SocialAppService.class,CALLS_REAL_METHODS);
        final StrategicGarrisonWithdrawal targetLocks;
        final StrategicTargetDefender defenders;
        final WarStore wars;
        final TargetOperationStore operations;
        final RankBoardService ranks;
        final StrategicCombatPublisher publisher;
        final com.ironoath.web.service.WarCapitalTargetResolver capitalTargets;
        Fixture(boolean mongo,boolean player) {
            this(mongo,player,StrategicCombatSlotTest.battle(2394,player,true,false),null,null);
        }
        Fixture(boolean mongo,boolean player,CapturedStrategicBattle battle,WarStore preloadedWars,
                com.ironoath.web.service.WarCapitalTargetResolver capitalTargets) {
            this.battle=battle;this.capitalTargets=capitalTargets;
            operations=mongo?new MongoTargetOperationStore(db.template()):new InMemoryTargetOperationStore();
            if(mongo)db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"world_target_operation");
            var lock=new InJvmPlayerLock();
            var warRules=new WarRulesAssembler(cfg);
            if(preloadedWars!=null)wars=preloadedWars;
            else {
                wars=mongo?new MongoWarStore(db.template(),warRules):new InMemoryWarStore(warRules);
                wars.clear();
                var board=new WarScoreBoard(warRules.rules(),100,"owned-N",300L);
                board.registerNation("owned-N");board.registerNation(player?"owned-defender-N":"owned-opponent");
                assertThat(wars.insertIfNoneActive(board)).isTrue();
            }
            ReflectionTestUtils.setField(social,"store",store);
            ReflectionTestUtils.setField(social,"armies",mock(ArmyRepository.class));
            doAnswer(call->"nick:"+call.getArgument(0)).when(social).nicknameOf(anyString());
            ReflectionTestUtils.setField(social,"playerLock",lock);
            ReflectionTestUtils.setField(social,"nationLock",new NationOperationLock(lock));
            ReflectionTestUtils.setField(social,"heldRallyContexts",new ThreadLocal<>());
            install(battle.attacker());if(battle.defender()!=null)install(battle.defender());
            if(battle.target().defender().occupation()!=null)
                assertThat(owners.compareAndSet(battle.target().defender().occupation(),0)).isTrue();
            defenders=capitalTargets==null
                    ?new StrategicTargetDefender(cfg,owners,marches,store,neutral)
                    :new StrategicTargetDefender(cfg,owners,marches,store,neutral,capitalTargets);
            targetLocks=capitalTargets==null
                    ?new StrategicGarrisonWithdrawal(cfg,operations,owners,marches,mock(MarchDueQueue.class),defenders,social,lock)
                    :new StrategicGarrisonWithdrawal(cfg,operations,owners,marches,mock(MarchDueQueue.class),defenders,social,lock,null,capitalTargets);
            var players=mock(PlayerRepository.class);var bots=mock(BotRegistry.class);
            when(bots.humanOnly(anyString(),anyString())).thenAnswer(call->call.getArgument(0));
            when(players.findBriefs(anyCollection())).thenAnswer(call->{
                var out=new LinkedHashMap<String,PlayerBrief>();
                for(String id:(Collection<String>)call.getArgument(0))out.put(id,new PlayerBrief(id,"rank:"+id,1,0,0));
                return out;
            });
            var rankRules=mock(SeasonRulesAssembler.class);
            when(rankRules.timelineRules(org.mockito.ArgumentMatchers.anyLong())).thenReturn(new com.ironoath.core.season.SeasonTimeline.Rules(
                    List.of(new com.ironoath.core.season.SeasonTimeline.Stage(1,
                            com.ironoath.core.season.SeasonTimeline.Phase.EXPAND,0,30)),"season-test"));
            var seasonBoards=new InMemorySeasonBoardStore();
            ranks=new RankBoardService(seasonBoards,store,mock(NationStore.class),new NationMembership(store,mock(NationStore.class)),
                    players,bots,cfg,rankRules,mock(com.ironoath.common.time.TimeService.class));
            publisher=capitalTargets==null
                    ?new StrategicCombatPublisher(cfg,operations,marches,defenders,store,social,targetLocks,wars,ranks)
                    :new StrategicCombatPublisher(cfg,operations,marches,defenders,store,social,targetLocks,wars,ranks,capitalTargets);
        }
        StrategicCombatPublisher publisherWith(TargetOperationStore port) {
            return capitalTargets==null
                    ?new StrategicCombatPublisher(cfg,port,marches,defenders,store,social,targetLocks,wars,ranks)
                    :new StrategicCombatPublisher(cfg,port,marches,defenders,store,social,targetLocks,wars,ranks,capitalTargets);
        }
        void install(StrategicRallyCasualties side) {
            var identity=side.rally();
            var rally=Rally.restore(identity.rallyId(),identity.scope(),identity.nationId(),identity.initiatorId(),
                    2,1,100,110,identity.participants(),identity.status(),identity.departure(),
                    identity.targetX(),identity.targetY(),identity.targetType(),identity.version());
            when(store.rallyOf(rally.rallyId())).thenReturn(Optional.of(rally));
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
        }
        <T> T locked(Supplier<T> action) {
            var ids=new ArrayList<String>();ids.add(battle.attacker().rally().rallyId());
            if(battle.defender()!=null)ids.add(battle.defender().rally().rallyId());
            return social.runRalliesDepartureLocked(ids,action);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publishAndExactReplayPreserveOnePendingOriginalPayload(boolean mongo) {
        var f=new Fixture(mongo,false);
        var slot=f.locked(()->f.publisher.publish(f.battle));
        assertThat(slot.kind()).isEqualTo(TargetOperationStore.Kind.COMBAT);
        assertThat(slot.completed()).isFalse();assertThat(slot.sequence()).isEqualTo(1);
        assertThat(slot.combatPlan().battle()).isEqualTo(f.battle);
        assertThat(slot.combatPlan().reportPlan()).isNotNull();
        assertThat(slot.combatPlan().killPlan().seasonId()).isEqualTo("season-test");
        assertThat(f.operations.findByTargetId(slot.targetId())).contains(slot);
        assertThat(f.wars.pendingBattleReservation(100,slot.operationId())).isPresent()
                .get().extracting(com.ironoath.core.nation.WarScoreBoard.BattleReservation::sourceId).isEqualTo(slot.targetId());
        assertThat(f.locked(()->f.publisher.publish(f.battle))).isEqualTo(slot);
        assertThat(f.marches.versionOf(f.battle.attacker().march().id())).isEqualTo(f.battle.attacker().marchVersion());
        assertThat(f.owners.findByTargetIds(List.of(slot.targetId()))).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void dynamicWarCapitalCoordinatesAreFrozenIntoPublishedCombat(boolean mongo) {
        var cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var nationRules=new com.ironoath.web.nation.NationRulesAssembler(cfg);
        var warRules=new WarRulesAssembler(cfg);
        var nations=new com.ironoath.web.store.memory.InMemoryNationStore(nationRules);
        var targetNation=com.ironoath.core.nation.Nation.found("owned-capital-target","Capital Target","owned-capital-king",
                "owned-capital-alliance",300,300,100,nationRules.rules());
        assertThat(nations.insertIfAbsent(targetNation)).isTrue();
        WarStore wars=mongo?new MongoWarStore(db.template(),warRules):new InMemoryWarStore(warRules);
        wars.clear();
        var board=new WarScoreBoard(warRules.rules(),100,"owned-N",targetNation.id(),10_000L);
        board.registerNation("owned-N");board.registerNation(targetNation.id());
        assertThat(wars.insertIfNoneActive(board)).isTrue();
        var directory=new com.ironoath.web.service.WarCapitalTargetResolver(nations,wars,
                mock(com.ironoath.core.world.WorldRepository.class));
        var battle=StrategicCombatSlotTest.dynamicCapitalBattle(2394,com.ironoath.core.world.Coord.of(300,300),directory);
        assertThat(battle.target().targetId()).isEqualTo("capital_center");
        assertThat(battle.target().x()).isEqualTo(300);
        assertThat(battle.target().y()).isEqualTo(300);

        var fixture=new Fixture(mongo,false,battle,wars,directory);
        var published=new java.util.concurrent.atomic.AtomicReference<TargetOperationStore.Snapshot>();
        Throwable failure=catchThrowable(()->published.set(fixture.locked(()->fixture.publisher.publish(battle))));

        assertThat(failure).as("新COMBAT应按当前War目标国都城发布，并冻结其坐标").isNull();
        assertThat(published.get().kind()).isEqualTo(TargetOperationStore.Kind.COMBAT);
        TargetOperationStore stored=mongo?new MongoTargetOperationStore(db.template()):fixture.operations;
        var readBack=stored.findByTargetId("capital_center").orElseThrow();
        assertThat(readBack.combatPlan().battle().target().x()).isEqualTo(300);
        assertThat(readBack.combatPlan().battle().target().y()).isEqualTo(300);
    }

    @Test
    void realArrivalProducerPublishesAndRecoversDynamicCapitalVictory() {
        var cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var nationRules=new com.ironoath.web.nation.NationRulesAssembler(cfg);
        var warRules=new WarRulesAssembler(cfg);
        var nations=new com.ironoath.web.store.memory.InMemoryNationStore(nationRules);
        var targetNation=com.ironoath.core.nation.Nation.found("owned-capital-producer-target","Capital Producer Target",
                "owned-capital-producer-king","owned-capital-producer-alliance",300,300,100,nationRules.rules());
        assertThat(nations.insertIfAbsent(targetNation)).isTrue();
        var wars=new InMemoryWarStore(warRules);
        var board=new WarScoreBoard(warRules.rules(),100,"owned-N",targetNation.id(),10_000L);
        board.registerNation("owned-N");board.registerNation(targetNation.id());
        for(String gate:List.of("gate_north","gate_east","gate_south","gate_west"))board.captureGate("owned-N",gate);
        board.beginSiege(150);assertThat(wars.insertIfNoneActive(board)).isTrue();
        var directory=new com.ironoath.web.service.WarCapitalTargetResolver(nations,wars,
                mock(com.ironoath.core.world.WorldRepository.class));
        // Test-only force count ensures a deterministic attacker win; it is not a gameplay troop-value proposal.
        var battle=StrategicCombatSlotTest.dynamicCapitalBattle(2395,com.ironoath.core.world.Coord.of(300,300),directory,1_000_000L);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        var fixture=new Fixture(false,false,battle,wars,directory);

        var players=mock(com.ironoath.core.player.PlayerRepository.class);
        var cities=mock(com.ironoath.core.city.CityRepository.class);
        var heroRepo=mock(com.ironoath.core.hero.HeroRepository.class);
        var inventory=mock(com.ironoath.core.bag.InventoryRepository.class);
        for(String id:battle.attacker().currentMembers().keySet()) {
            var save=mock(com.ironoath.core.player.PlayerSave.class);
            when(save.tech()).thenReturn(com.ironoath.core.player.PlayerTech.empty());
            when(save.power()).thenReturn(new com.ironoath.core.player.PlayerPower(1_000_000,1_000_000,1_000_000));
            when(save.version()).thenReturn(1L);when(players.findByPlayerId(id)).thenReturn(Optional.of(save));
            var city=mock(com.ironoath.core.city.CityState.class);when(city.buildings()).thenReturn(List.of());
            when(cities.findVersionedByPlayerId(id)).thenReturn(Optional.of(
                    new com.ironoath.core.city.CityRepository.VersionedCity(city,1)));
            when(heroRepo.findByPlayerId(id)).thenReturn(Optional.empty());
            when(inventory.findByPlayerId(id)).thenReturn(Optional.empty());
        }
        var factory=new com.ironoath.web.battle.BattleArmyFactory(fixture.cfg);
        var heroMapper=new com.ironoath.web.battle.HeroBattleMapper(fixture.cfg,
                new com.ironoath.web.service.HeroStatsService(fixture.cfg),
                new com.ironoath.web.hero.EquipLedgers(inventory,fixture.cfg),heroRepo,fixture.store);
        var tech=new com.ironoath.web.battle.BattleTechBonuses(
                new com.ironoath.web.battle.AllianceTechBonuses(fixture.store,fixture.cfg),
                new com.ironoath.web.tech.TechEffects(fixture.cfg),players);
        var policies=new com.ironoath.web.nation.NationPolicyBonuses(fixture.cfg,fixture.store,
                mock(NationStore.class),mock(com.ironoath.web.nation.NationLeaders.class),factory);
        var preparation=new CapturedBattlePreparation(fixture.cfg,heroMapper,tech,policies,directory);
        var circle=mock(com.ironoath.web.service.StrategicTargetCircleGuard.class);
        var seeds=mock(com.ironoath.web.reward.ServerSeedSource.class);when(seeds.nextSeed()).thenReturn(battle.result().seed());
        var producer=new com.ironoath.web.service.StrategicCombatArrivalProducer(fixture.cfg,preparation,fixture.defenders,
                fixture.publisher,fixture.marches,players,cities,heroRepo,inventory,fixture.store,circle,seeds);
        var march=March.fromSnapshot(battle.attacker().march());

        Throwable producerFailure=catchThrowable(()->fixture.locked(()->{
            producer.publish(march,battle.attacker().marchVersion(),battle.happenedAt());return null;
        }));
        assertThat(producerFailure).as("实际到达生产者应沿War目标Nation当前都城读取、核验并发布COMBAT").isNull();

        var published=fixture.operations.findByTargetId("capital_center").orElseThrow();
        assertThat(published.kind()).isEqualTo(TargetOperationStore.Kind.COMBAT);
        assertThat(published.completed()).isFalse();
        assertThat(published.combatPlan().battle().target().x()).isEqualTo(300);
        assertThat(published.combatPlan().battle().target().y()).isEqualTo(300);
        assertThat(fixture.wars.pendingBattleReservation(100,published.operationId())).isPresent();

        var armies=new InMemoryArmyStore();
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new com.ironoath.core.army.ArmyState();
            battle.attacker().currentMembers().get(player).forEach(army::add);
            assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var attackingNation=new com.ironoath.web.store.memory.InMemoryNationStore(nationRules);
        var ownedAttacker=com.ironoath.core.nation.Nation.found("owned-N","攻方","owned-leader","owned-alliance",
                10,10,100,new com.ironoath.web.nation.NationRulesAssembler(cfg).rules());
        assertThat(attackingNation.insertIfAbsent(ownedAttacker)).isTrue();
        var due=new SortedMarchDueQueue();
        due.schedule(battle.attacker().march().id(),battle.attacker().march().arriveAt());
        var reportStore=new InMemoryBattleReportStore();
        var reportService=new com.ironoath.web.battle.BattleReportService(cfg,reportStore,
                mock(com.ironoath.common.time.TimeService.class),fixture.social,fixture.ranks,wars,
                mock(NationMembership.class));
        var recovery=new StrategicCombatRecovery(fixture.operations,armies,fixture.marches,fixture.owners,
                fixture.neutral,attackingNation,due,reportService,fixture.ranks,wars);
        var pending=fixture.operations.findByTargetId("capital_center").orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);
        recovery.recoverTargetState(pending,hospital);
        var withMarch=recovery.freezeMarchOutcomes(fixture.operations.findByTargetId("capital_center").orElseThrow());
        recovery.recoverMarchOutcomes(withMarch);
        var withEvent=recovery.freezeWarEvent(fixture.operations.findByTargetId("capital_center").orElseThrow(),hospital);
        recovery.recoverWarEvent(withEvent);
        var completed=recovery.completeCombat(fixture.operations.findByTargetId("capital_center").orElseThrow(),hospital);

        assertThat(completed.completed()).isTrue();
        assertThat(wars.findByStartedAt(100).orElseThrow().capitalHolder()).isEqualTo("owned-N");
        assertThat(fixture.owners.findByTargetIds(List.of("capital_center")).get("capital_center").nationId())
                .isEqualTo("owned-N");
        assertThat(fixture.operations.findByTargetId("capital_center")).contains(completed);
    }

    @Test void publicationMustFreezeStableBattleReportOwnersBeforeAnyRecoveryEffect() {
        var f=new Fixture(false,true);
        var slot=f.locked(()->f.publisher.publish(f.battle));
        var reports=slot.combatPlan().reportPlan();
        assertThat(reports)
                .as("pending COMBAT must persist the two initiator report identities before restartable effects")
                .isNotNull();
        assertThat(reports.battleType()).isEqualTo(com.ironoath.battle.BattleType.SIEGE);
        assertThat(reports.createdAt()).isEqualTo(f.battle.happenedAt());
        assertThat(reports.expiresAt()).isEqualTo(Math.addExact(f.battle.happenedAt(),
                Math.multiplyExact(f.cfg.longParam("BATTLE_REPORT_TTL_SECONDS"),1000L)));
        assertThat(reports.recipients()).hasSize(2);
        assertThat(reports.recipients()).extracting(com.ironoath.web.battle.StrategicBattleReportPlan.Recipient::ownerId)
                .containsExactlyInAnyOrder(f.battle.attacker().rally().initiatorId(),f.battle.defender().rally().initiatorId());
        assertThat(reports.recipients()).allSatisfy(recipient->assertThat(recipient.reportId())
                .isEqualTo(com.ironoath.web.battle.BattleReport.idForOperation(slot.operationId(),recipient.ownerId())));
        assertThat(reports.recipients()).allSatisfy(recipient->{
            assertThat(recipient.attackerName()).isEqualTo("nick:"+f.battle.attacker().rally().initiatorId());
            assertThat(recipient.defenderName()).isEqualTo("nick:"+f.battle.defender().rally().initiatorId());
        });
        var kills=slot.combatPlan().killPlan();
        assertThat(kills).as("COMBAT publication must persist original-season, per-member KILL identities and engine deltas")
                .isNotNull();
        assertThat(kills.sourceId()).isEqualTo(slot.targetId());assertThat(kills.sequence()).isEqualTo(slot.sequence());
        var expectedKillOwners=new java.util.TreeSet<>(f.battle.attacker().currentMembers().keySet());
        expectedKillOwners.addAll(f.battle.defender().currentMembers().keySet());
        var engineKills=new java.util.HashMap<String,Long>(f.battle.result().atkKillsByMember());
        engineKills.putAll(f.battle.result().defKillsByMember());
        assertThat(kills.members()).extracting(com.ironoath.web.battle.StrategicBattleKillPlan.Member::playerId)
                .containsExactlyElementsOf(expectedKillOwners);
        assertThat(kills.members()).allSatisfy(member->{
            assertThat(member.kills()).isEqualTo(engineKills.get(member.playerId()));
            assertThat(member.displayName()).isEqualTo("rank:"+member.playerId());
        });
    }

    @Test void npcPublicationFreezesConfiguredTargetNameInAttackerReport() {
        var f=new Fixture(false,false);
        var slot=f.locked(()->f.publisher.publish(f.battle));
        var target=com.ironoath.web.service.WorldTargets.from(f.cfg).targets().stream()
                .filter(candidate->candidate.definition().id().equals(f.battle.target().targetId())).findFirst().orElseThrow();
        var reports=slot.combatPlan().reportPlan();
        assertThat(reports.recipients()).hasSize(1);
        var report=reports.recipients().get(0);
        assertThat(report.ownerId()).isEqualTo(f.battle.attacker().rally().initiatorId());
        assertThat(report.defenderId()).isEqualTo(f.battle.target().targetId());
        assertThat(report.defenderName()).isEqualTo(target.definition().name());
        assertThat(report.attackerName()).isEqualTo("nick:"+report.attackerId());
    }

    @Test void actualArrivalProducerCapturesSIEGESimulationAndPublishesTheFrozenInput() {
        var f=new Fixture(false,true);
        var players=mock(com.ironoath.core.player.PlayerRepository.class);
        var cities=mock(com.ironoath.core.city.CityRepository.class);
        var heroRepo=mock(com.ironoath.core.hero.HeroRepository.class);
        var inventory=mock(com.ironoath.core.bag.InventoryRepository.class);
        var sources=new TreeSet<String>();
        for(var side:List.of(f.battle.attacker(),f.battle.defender()))sources.addAll(side.rally().participants().keySet());
        for(String id:sources) {
            var save=mock(com.ironoath.core.player.PlayerSave.class);
            when(save.tech()).thenReturn(com.ironoath.core.player.PlayerTech.empty());
            when(save.power()).thenReturn(new com.ironoath.core.player.PlayerPower(1_000_000,1_000_000,1_000_000));
            when(save.version()).thenReturn(1L);when(players.findByPlayerId(id)).thenReturn(Optional.of(save));
            var city=mock(com.ironoath.core.city.CityState.class);when(city.buildings()).thenReturn(List.of());
            when(cities.findVersionedByPlayerId(id)).thenReturn(Optional.of(new com.ironoath.core.city.CityRepository.VersionedCity(city,1)));
            when(heroRepo.versionOf(id)).thenReturn(1L);when(heroRepo.findByPlayerId(id)).thenReturn(Optional.empty());
            when(inventory.versionOf(id)).thenReturn(1L);when(inventory.findByPlayerId(id)).thenReturn(Optional.empty());
        }
        var factory=new com.ironoath.web.battle.BattleArmyFactory(f.cfg);
        var heroMapper=new com.ironoath.web.battle.HeroBattleMapper(f.cfg,new com.ironoath.web.service.HeroStatsService(f.cfg),
                new com.ironoath.web.hero.EquipLedgers(inventory,f.cfg),heroRepo,f.store);
        var tech=new com.ironoath.web.battle.BattleTechBonuses(
                new com.ironoath.web.battle.AllianceTechBonuses(f.store,f.cfg),new com.ironoath.web.tech.TechEffects(f.cfg),players);
        var policies=new com.ironoath.web.nation.NationPolicyBonuses(f.cfg,f.store,mock(NationStore.class),
                mock(com.ironoath.web.nation.NationLeaders.class),factory);
        var preparation=new CapturedBattlePreparation(f.cfg,heroMapper,tech,policies);
        var circle=mock(com.ironoath.web.service.StrategicTargetCircleGuard.class);
        var seeds=mock(com.ironoath.web.reward.ServerSeedSource.class);when(seeds.nextSeed()).thenReturn(f.battle.result().seed());
        var producer=new com.ironoath.web.service.StrategicCombatArrivalProducer(f.cfg,preparation,f.defenders,f.publisher,
                f.marches,players,cities,heroRepo,inventory,f.store,circle,seeds);
        var march=com.ironoath.core.march.March.fromSnapshot(f.battle.attacker().march());

        f.locked(()->{producer.publish(march,f.battle.attacker().marchVersion(),f.battle.happenedAt());return null;});

        var published=f.operations.findByTargetId(f.battle.target().targetId()).orElseThrow();
        assertThat(published.completed()).isFalse();
        assertThat(published.combatPlan().battle().formatVersion()).isEqualTo(2);
        assertThat(published.combatPlan().battle().input().battleType()).isEqualTo(com.ironoath.battle.BattleType.SIEGE);
        assertThat(published.combatPlan().battle().input().seed()).isEqualTo(f.battle.result().seed());
        assertThat(published.combatPlan().reportPlan()).isNotNull();assertThat(published.combatPlan().killPlan()).isNotNull();
        assertThat(published.combatPlan().warRecovery().reservation()).isNotNull();
        verify(circle).guardArrival(eq(1_000_000L),any(StrategicTargetDefender.Defender.class),eq(f.battle.attacker().rally().participants().size()));
        assertThat(f.marches.versionOf(march.id())).isEqualTo(f.battle.attacker().marchVersion());
    }

    @Test void bothOriginalPlayerRalliesMustAlreadyBeLocked() {
        var f=new Fixture(false,true);
        assertThatThrownBy(()->f.publisher.publish(f.battle)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.social.runRallyDepartureLocked(f.battle.attacker().rally().rallyId(),
                ()->f.publisher.publish(f.battle))).isInstanceOf(IllegalStateException.class);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
        assertThat(f.locked(()->f.publisher.publish(f.battle)).combatPlan().battle()).isEqualTo(f.battle);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void changedSourceMarchVersionCannotPublishOldFrozenCombat(boolean playerDefender) {
        var f=new Fixture(false,playerDefender);
        var side=playerDefender?f.battle.defender():f.battle.attacker();
        var original=March.fromSnapshot(side.march());f.marches.save(original,side.marchVersion());
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void closedWarCannotAcceptUnreservedPreDeadlineCombatPublication() {
        var f=new Fixture(false,false);
        assertThat(f.wars.settleIfExpired(300L).orElseThrow().abandonedNow()).isTrue();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已闭战窗口");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
        assertThat(f.marches.findById(f.battle.attacker().march().id())).isPresent();
    }

    @Test void changedTargetCannotBePublishedEvenWithOriginalMarchAndRally() {
        var f=new Fixture(false,true);var before=f.battle.target().defender().occupation();
        var changed=new TargetOccupationStore.Snapshot(before.targetId(),before.kind(),before.x(),before.y(),
                before.nationId(),before.nationName(),before.capturedAt(),"owned-new-owner-operation",2,before.garrison());
        assertThat(f.owners.compareAndSet(changed,1)).isTrue();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("守军或归属");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void sameOperationIdCannotReplaceStoredKernelResult() {
        var f=new Fixture(false,false);var first=f.locked(()->f.publisher.publish(f.battle));
        var changed=StrategicCombatSlotTest.battle(2395,false,true,false);
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(changed)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("原载荷");
        assertThat(f.operations.findByTargetId(first.targetId())).contains(first);
    }

    @Test void pendingWithdrawalCannotBeOverwrittenByNewCombat() {
        var f=new Fixture(false,false);
        var pending=new TargetOperationStore.Snapshot("gate_north",1,false,TargetOperationStoreTest.plan("owned-pending",1,120));
        f.operations.compareAndSet(pending,0);
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未完成");
        assertThat(f.operations.findByTargetId("gate_north")).contains(pending);
    }

    @Test void holdingSameRallyIdForAnotherNationDoesNotAuthorizeOriginalCombat() {
        var f=new Fixture(false,false);var r=f.battle.attacker().rally();
        var changed=Rally.restore(r.rallyId(),r.scope(),"owned-another-nation",r.initiatorId(),2,1,100,110,
                r.participants(),r.status(),r.departure(),r.targetX(),r.targetY(),r.targetType(),r.version());
        when(f.store.rallyOf(r.rallyId())).thenReturn(Optional.of(changed));
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("国家");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void sameNationAndRallyIdCannotHideChangedOriginalRallyVersion() {
        var f=new Fixture(false,false);
        f.store.rallyOf(f.battle.attacker().rally().rallyId()).orElseThrow().arrive();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("集结状态或成员");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void pendingExactReplayReadsFrozenPayloadDespitePartialLiveTargetChanges() {
        var f=new Fixture(false,false);var first=f.locked(()->f.publisher.publish(f.battle));
        f.marches.deleteIfVersion(f.battle.attacker().march().id(),f.battle.attacker().marchVersion());
        f.owners.compareAndSet(TargetOperationStoreTest.owner(TargetOperationStoreTest.stationed("owned-unreadable-live"),1),0);
        assertThatThrownBy(()->f.defenders.read(com.ironoath.web.service.WorldTargets.from(f.cfg).targets().stream()
                .filter(t->t.definition().id().equals("gate_north")).findFirst().orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.locked(()->f.publisher.publish(f.battle))).isEqualTo(first);
        assertThat(f.operations.findByTargetId("gate_north")).contains(first);
    }

    @Test void lostWriteReplyIsAcceptedOnlyAfterExactIndependentReadback() {
        var f=new Fixture(false,false);var port=spy(f.operations);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("owned reply lost");})
                .when(port).compareAndSet(any(),anyLong());
        var actual=f.locked(()->f.publisherWith(port).publish(f.battle));
        assertThat(f.operations.findByTargetId("gate_north")).contains(actual);
        assertThat(actual.completed()).isFalse();
    }

    @Test void unreadableUnknownWriteKeepsOriginalFailureAndPendingStateUnconfirmed() {
        var f=new Fixture(false,false);var port=mock(TargetOperationStore.class);
        var write=new IllegalStateException("owned write unknown");var read=new IllegalStateException("owned read unavailable");
        when(port.findByTargetId("gate_north")).thenReturn(Optional.empty()).thenThrow(read);
        when(port.compareAndSet(any(),anyLong())).thenThrow(write);
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isSameAs(write).hasSuppressedException(read);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void successfulReplyWithoutStoredPlanCannotPassPublicationBarrier() {
        var f=new Fixture(false,false);var port=mock(TargetOperationStore.class);
        when(port.compareAndSet(any(),anyLong())).thenReturn(true);
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("独立读回");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void targetCasConflictDoesNotGrabNewVersionAndOverwriteAnotherOperation() {
        var f=new Fixture(false,false);var port=spy(f.operations);
        var competing=new TargetOperationStore.Snapshot("gate_north",1,false,TargetOperationStoreTest.plan("owned-racing",1,120));
        doAnswer(call->{f.operations.compareAndSet(competing,0);return call.callRealMethod();})
                .when(port).compareAndSet(any(),anyLong());
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CAS冲突");
        assertThat(f.operations.findByTargetId("gate_north")).contains(competing);
        verify(port,times(1)).compareAndSet(any(),eq(0L));
    }
}
