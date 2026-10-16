package com.ironoath.web.bot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.BizException;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.army.*;
import com.ironoath.core.city.*;
import com.ironoath.core.player.*;
import com.ironoath.web.service.*;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.TrainReq;

/** Actual own players/Army data; injected writes explicitly test the advisory consistency boundary. */
@SpringBootTest @ActiveProfiles("test")
class BotObservationArmyReadSnapshotTest {
    @Autowired BotWorldAdapter adapter;
    @Autowired BotRegistry bots;
    @Autowired BotRuntimeService runtime;
    @Autowired BotRulesAssembler rules;
    @Autowired PlayerInitService init;
    @Autowired ArmyAppService army;
    @Autowired CityAppService city;
    @Autowired PlayerRepository players;
    @SpyBean ArmyRepository armies;
    @SpyBean CityRepository cities;
    @SpyBean HeroAppService heroes;
    @SpyBean QuestAppService quests;
    @SpyBean TimeService time;
    long now;String uid;
    @BeforeEach void fixture() {
        now=System.currentTimeMillis();doReturn(now).when(time).serverNow();
        runtime.reset();bots.clear();adapter.resetCounters();uid=player(true);
        doReturn(100L).when(heroes).troopCap(uid);clearInvocations(armies,cities,heroes,quests);
    }
    String player(boolean createArmy) {
        String key=UUID.randomUUID().toString();String id=init.init(new PlayerInitReq(key,"army-observe-"+key,"军队观察验收",now,"")).playerId();
        city.list(id);heroes.troopCap(id);
        var p=players.findByPlayerId(id).orElseThrow();p.setCityLevel(4);
        for(String resource:List.of("WOOD","STONE","IRON","GRAIN")) {
            var old=p.resource(resource);p.putResource(resource,new PlayerResourceState(10000,10000,old.protectedAmount(),0,now));
        }
        players.save(p);var state=new CityState();state.restoreBuilding(new BuildingInstance("observe-main","main_city",4,3,3));
        state.restoreBuilding(new BuildingInstance("observe-barracks","barracks",1,2,1));cities.save(id,state,cities.versionOf(id));
        if(createArmy)army.firstTrainable(id,now);
        bots.register(rules.archetypes().get(0).spawnProfile(id,1,Rng.of(431)));return id;
    }
    void fill(String id) {
        var state=armies.findByPlayerId(id).orElseThrow();state.add("unit_infantry_t1",100L);armies.save(id,state,armies.versionOf(id));
    }
    @Test void existingArmyIsReadOnlyOnceForOneObservation() {
        var view=adapter.observe(uid,now);assertThat(view.populationFull()).isFalse();assertThat(view.canAffordTraining()).isTrue();
        verify(armies,times(1)).findByPlayerId(uid);
        verify(cities,times(2)).save(eq(uid),any(),anyLong());
    }
    @Test void nextObservationReadsChangedArmyAgain() {
        assertThat(adapter.observe(uid,now).populationFull()).isFalse();fill(uid);clearInvocations(armies);
        var next=adapter.observe(uid,now);assertThat(next.populationFull()).isTrue();assertThat(next.canAffordTraining()).isFalse();
        verify(armies,times(1)).findByPlayerId(uid);
    }
    @Test void firstReadFailureIsNotRetainedByTheNextObservation() {
        var failure=new IllegalStateException("own Army source unavailable");doThrow(failure).doCallRealMethod().when(armies).findByPlayerId(uid);
        assertThatThrownBy(()->adapter.observe(uid,now)).isSameAs(failure);verifyNoInteractions(heroes);
        assertThat(adapter.observe(uid,now)).isNotNull();verify(armies,times(2)).findByPlayerId(uid);
    }
    @Test void missingArmyStillUsesTheOriginalColdCreationAndTwoCitySaves() {
        String cold=player(false);doReturn(100L).when(heroes).troopCap(cold);
        assertThat(armies.findByPlayerId(cold)).isEmpty();clearInvocations(armies,cities);
        assertThat(adapter.observe(cold,now).canAffordTraining()).isTrue();
        verify(armies,times(3)).findByPlayerId(cold);verify(armies,times(1)).insertIfAbsent(eq(cold),any());
        verify(cities,times(2)).save(eq(cold),any(),anyLong());assertThat(armies.findByPlayerId(cold)).isPresent();
    }
    @Test void initiallyMissingArmySeesALateCreationAtTheOriginalReloadPosition() {
        String cold=player(false);doReturn(100L).when(heroes).troopCap(cold);
        doAnswer(call->{var late=new ArmyState();late.add("unit_infantry_t1",100L);armies.insertIfAbsent(cold,late);return call.callRealMethod();}).when(quests).claimableCount(cold);
        var view=adapter.observe(cold,now);assertThat(view.populationFull()).isFalse();assertThat(view.canAffordTraining()).isFalse();
        assertThat(armies.findByPlayerId(cold).orElseThrow().totalTroops()).isEqualTo(100L);
    }
    @Test void onePresentSnapshotKeepsAdviceCoherentButRealTrainingReadsTheUpdatedArmy() {
        // Deliberate fixture mutation between reads; production Quest refresh does not add troops.
        doAnswer(call->{fill(uid);return call.callRealMethod();}).when(quests).claimableCount(uid);
        var view=adapter.observe(uid,now);assertThat(view.populationFull()).isFalse();assertThat(view.canAffordTraining()).isTrue();
        assertThat(army.firstTrainable(uid,now)).isEmpty();
        long before=players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current();
        assertThatThrownBy(()->army.train(uid,new TrainReq(UUID.randomUUID().toString(),"unit_infantry_t1",1L))).isInstanceOf(BizException.class);
        assertThat(armies.findByPlayerId(uid).orElseThrow().totalTraining()).isZero();
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(before);
    }
}
