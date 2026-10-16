package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.army.*;
import com.ironoath.core.city.*;
import com.ironoath.core.player.*;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.TrainReq;

@SpringBootTest @ActiveProfiles("test")
class ArmyObservationSnapshotTest {
    @Autowired ArmyAppService army;
    @Autowired PlayerInitService init;
    @SpyBean PlayerRepository players;
    @SpyBean CityRepository cities;
    @SpyBean HeroAppService heroes;
    @SpyBean CityAppService city;
    @SpyBean ArmyRepository armies;
    @SpyBean TimeService time;
    long now;String uid;
    @BeforeEach void fixture() {
        now=System.currentTimeMillis();doReturn(now).when(time).serverNow();
        String key=UUID.randomUUID().toString();uid=init.init(new PlayerInitReq(key,"army-read-"+key,"军队快照验收",now,"")).playerId();
        city.list(uid);var player=players.findByPlayerId(uid).orElseThrow();player.setCityLevel(4);
        for(String id:List.of("WOOD","STONE","IRON","GRAIN")) {
            var state=player.resource(id);player.putResource(id,new PlayerResourceState(10000,10000,state.protectedAmount(),0,now));
        }
        players.save(player);var state=new CityState();state.restoreBuilding(new BuildingInstance("read-main","main_city",4,3,3));
        state.restoreBuilding(new BuildingInstance("read-barracks","barracks",1,2,1));cities.save(uid,state,cities.versionOf(uid));
        doReturn(100L).when(heroes).troopCap(uid);clearInvocations(heroes,city,armies,players,cities);
    }
    HeroAppService.TroopCapSnapshot capacity(){return heroes.troopCapSnapshot(uid);}
    void create(){assertThat(armies.insertIfAbsent(uid,new ArmyState())).isTrue();}
    void add(long count){var state=armies.findByPlayerId(uid).orElseThrow();state.add("unit_infantry_t1",count);armies.save(uid,state,armies.versionOf(uid));}

    @Test void presentZeroTroopsAreAReusableSnapshotAndStillRunBothSettlements() {
        create();var observed=army.observationSnapshot(uid);var capacity=capacity();clearInvocations(armies,city,cities);
        assertThat(observed.populationInUseFor(uid)).isZero();
        assertThat(army.firstTrainable(uid,now,capacity,observed)).isPresent();
        assertThat(army.firstTrainable(uid,now,capacity,observed)).isPresent();
        verifyNoInteractions(armies);verify(city,times(2)).withSettledCity(eq(uid),any());verify(cities,times(2)).save(eq(uid),any(),anyLong());
    }
    @Test void initialAbsenceKeepsColdCreationAndEveryLaterCallReloads() {
        var observed=army.observationSnapshot(uid);var capacity=capacity();verify(armies,never()).insertIfAbsent(anyString(),any());clearInvocations(armies);
        assertThat(army.firstTrainable(uid,now,capacity,observed)).isPresent();
        verify(armies,times(2)).findByPlayerId(uid);verify(armies,times(1)).insertIfAbsent(eq(uid),any());
        add(100L);clearInvocations(armies);
        assertThat(army.firstTrainable(uid,now,capacity,observed)).isEmpty();
        verify(armies,times(1)).findByPlayerId(uid);verify(armies,never()).insertIfAbsent(anyString(),any());
    }
    @Test void nextSnapshotSeesThePersistedTroopChange() {
        create();var first=army.observationSnapshot(uid);add(100L);var second=army.observationSnapshot(uid);
        assertThat(first.populationInUseFor(uid)).isZero();assertThat(second.populationInUseFor(uid)).isEqualTo(100L);
        assertThat(army.firstTrainable(uid,now,capacity(),first)).isPresent();assertThat(army.firstTrainable(uid,now,capacity(),second)).isEmpty();
    }
    @Test void foreignArmySnapshotIsRejectedWithAValidForeignCapacityBeforeAnyCityOrArmyIO() {
        create();var observed=army.observationSnapshot(uid);
        String other=init.init(new PlayerInitReq(UUID.randomUUID().toString(),"other-"+UUID.randomUUID(),"另一军队快照",now,"")).playerId();
        doReturn(100L).when(heroes).troopCap(other);var otherCapacity=heroes.troopCapSnapshot(other);clearInvocations(city,armies,cities,players);
        assertThatThrownBy(()->army.firstTrainable(other,now,otherCapacity,observed)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("军队观察");
        verifyNoInteractions(city,armies,cities,players);
    }
    @Test void populationRejectsForeignPlayerAndMissingSnapshotRejectsBeforeIO() {
        create();var observed=army.observationSnapshot(uid);var capacity=capacity();clearInvocations(city,armies,cities,players);
        assertThatThrownBy(()->observed.populationInUseFor(uid+"-other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->army.firstTrainable(uid,now,capacity,null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(city,armies,cities,players);
    }
    @Test void invalidFactoryPlayerIsRejectedBeforeStoreAccess() {
        assertThatThrownBy(()->army.observationSnapshot(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->army.observationSnapshot(" ")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(armies);
    }
    @Test void ordinaryTwoAndThreeArgumentAdviceRemainFreshAfterASnapshotWasTaken() {
        create();var observed=army.observationSnapshot(uid);var capacity=capacity();add(100L);clearInvocations(armies);
        assertThat(army.firstTrainable(uid,now)).isEmpty();assertThat(army.firstTrainable(uid,now,capacity)).isEmpty();
        verify(armies,times(2)).findByPlayerId(uid);assertThat(observed.populationInUseFor(uid)).isZero();
    }
    @Test void snapshotCountsBothRealTrainingAndStoredTroops() {
        army.train(uid,new TrainReq(UUID.randomUUID().toString(),"unit_infantry_t1",10L));add(5L);
        var observed=army.observationSnapshot(uid);assertThat(observed.populationInUseFor(uid)).isEqualTo(15L);
        add(100L);assertThat(observed.populationInUseFor(uid)).isEqualTo(15L);assertThat(army.observationSnapshot(uid).populationInUseFor(uid)).isEqualTo(115L);
    }
    @Test void sourceMutationAfterCaptureCannotChangeTheSnapshot() {
        var shared=new ArmyState();shared.add("unit_infantry_t1",5L);doReturn(Optional.of(shared)).when(armies).findByPlayerId(uid);
        var observed=army.observationSnapshot(uid);shared.add("unit_infantry_t1",95L);
        assertThat(observed.populationInUseFor(uid)).isEqualTo(5L);assertThat(army.firstTrainable(uid,now,capacity(),observed)).isPresent();
    }
    @Test void coldReloadFailurePropagatesAndDoesNotPersistTheCityOrPlayer() {
        var observed=army.observationSnapshot(uid);var capacity=capacity();var failure=new IllegalStateException("own cold reload unavailable");
        doThrow(failure).when(armies).findByPlayerId(uid);clearInvocations(players,cities);
        assertThatThrownBy(()->army.firstTrainable(uid,now,capacity,observed)).isSameAs(failure);
        verify(players,never()).save(any());verify(cities,never()).save(anyString(),any(),anyLong());
        verify(armies,never()).insertIfAbsent(anyString(),any());
    }
}
