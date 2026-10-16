package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.BizException;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.city.*;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.player.*;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.TrainReq;

/** Advice accepts a local capacity value; the mutating train path always validates a fresh value. */
@SpringBootTest @ActiveProfiles("test")
class ArmyTroopCapSnapshotTest {
    @Autowired ArmyAppService army;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired CityRepository cities;
    @SpyBean HeroAppService heroes;
    @SpyBean CityAppService city;
    @SpyBean HeroRepository rosters;
    @SpyBean ArmyRepository armies;
    @SpyBean TimeService time;
    long now; String uid;

    @BeforeEach void fixture() {
        now=System.currentTimeMillis(); doReturn(now).when(time).serverNow();
        String key=UUID.randomUUID().toString();
        uid=init.init(new PlayerInitReq(key,"army-cap-snapshot-"+key,"训练建议验收",now,"")).playerId();
        city.list(uid);
        var player=players.findByPlayerId(uid).orElseThrow(); player.setCityLevel(4);
        for(String id:List.of("WOOD","STONE","IRON","GRAIN")) {
            var state=player.resource(id);
            player.putResource(id,new PlayerResourceState(10000,10000,state.protectedAmount(),0,now));
        }
        players.save(player);
        var state=new CityState();
        state.restoreBuilding(new BuildingInstance("snapshot-main","main_city",4,3,3));
        state.restoreBuilding(new BuildingInstance("snapshot-barracks","barracks",1,2,1));
        cities.save(uid,state,cities.versionOf(uid));
        clearInvocations(heroes,city,rosters,armies);
    }
    @Test void zeroIsACompleteSuccessfulSnapshot() {
        doReturn(0L).when(heroes).troopCap(uid);
        var capacity=heroes.troopCapSnapshot(uid);
        assertThat(capacity.maximumFor(uid)).isZero();
        assertThat(army.firstTrainable(uid,now,capacity)).isEmpty();
        verify(heroes,times(1)).troopCap(uid);
        verify(city,times(1)).withSettledCity(eq(uid),any());
        assertThat(armies.findByPlayerId(uid)).isPresent();
    }
    @Test void eachNewSnapshotReadsAgainWhileAnExistingSnapshotIsImmutable() {
        doReturn(100L,0L).when(heroes).troopCap(uid);
        var first=heroes.troopCapSnapshot(uid); var second=heroes.troopCapSnapshot(uid);
        assertThat(first.maximumFor(uid)).isEqualTo(100L);
        assertThat(second.maximumFor(uid)).isZero();
        assertThat(army.firstTrainable(uid,now,first)).isPresent();
        assertThat(army.firstTrainable(uid,now,second)).isEmpty();
        verify(heroes,times(2)).troopCap(uid);
    }
    @Test void ordinaryAdviceStillReadsCurrentCapacityInsideEachSettlement() {
        doReturn(100L,0L).when(heroes).troopCap(uid);
        assertThat(army.firstTrainable(uid,now)).isPresent();
        assertThat(army.firstTrainable(uid,now)).isEmpty();
        verify(heroes,times(2)).troopCap(uid);
        verify(city,times(2)).withSettledCity(eq(uid),any());
    }
    @Test void foreignPlayerSnapshotIsRejectedBeforeAnyCityOrArmyRead() {
        doReturn(100L).when(heroes).troopCap(uid);
        var capacity=heroes.troopCapSnapshot(uid); clearInvocations(city,armies);
        assertThatThrownBy(()->army.firstTrainable(uid+"-foreign",now,capacity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("玩家");
        verifyNoInteractions(city,armies);
    }
    @Test void missingSnapshotIsRejectedBeforeAnyCityOrArmyRead() {
        assertThatThrownBy(()->army.firstTrainable(uid,now,null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(city,armies);
    }
    @Test void sourceFailureCannotProduceASnapshotAndTheNextReadRetries() {
        var failure=new IllegalStateException("own failed capacity source");
        doThrow(failure).doReturn(100L).when(heroes).troopCap(uid);
        assertThatThrownBy(()->heroes.troopCapSnapshot(uid)).isSameAs(failure);
        assertThat(heroes.troopCapSnapshot(uid).maximumFor(uid)).isEqualTo(100L);
        verify(heroes,times(2)).troopCap(uid); verifyNoInteractions(city,armies);
    }
    @Test void oldPositiveAdviceDoesNotAuthorizeTrainingAfterCurrentCapacityDrops() {
        doReturn(100L).when(heroes).troopCap(uid);
        var old=heroes.troopCapSnapshot(uid);
        assertThat(army.firstTrainable(uid,now,old)).isPresent();
        long before=players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current();
        doReturn(0L).when(heroes).troopCap(uid); clearInvocations(heroes);
        assertThatThrownBy(()->army.train(uid,new TrainReq(UUID.randomUUID().toString(),"unit_infantry_t1",1L)))
                .isInstanceOf(BizException.class);
        verify(heroes,times(1)).troopCap(uid);
        assertThat(armies.findByPlayerId(uid).orElseThrow().totalTraining()).isZero();
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(before);
    }
    @Test void oldZeroAdviceDoesNotPreventRealTrainingAfterCurrentCapacityRises() {
        doReturn(0L).when(heroes).troopCap(uid);
        var old=heroes.troopCapSnapshot(uid);
        assertThat(army.firstTrainable(uid,now,old)).isEmpty();
        doReturn(100L).when(heroes).troopCap(uid); clearInvocations(heroes);
        var response=army.train(uid,new TrainReq(UUID.randomUUID().toString(),"unit_infantry_t1",1L));
        assertThat(response.count()).isEqualTo(1L);
        assertThat(armies.findByPlayerId(uid).orElseThrow().totalTraining()).isEqualTo(1L);
        verify(heroes,times(3)).troopCap(uid);
    }
    @Test void realColdHeroCreationIsRetainedBySnapshotAcquisition() {
        assertThat(rosters.findByPlayerId(uid)).isEmpty(); clearInvocations(rosters);
        assertThat(heroes.troopCapSnapshot(uid).maximumFor(uid)).isZero();
        verify(rosters,times(1)).insertIfAbsent(eq(uid),any());
        verify(rosters,times(2)).findByPlayerId(uid);
        assertThat(rosters.findByPlayerId(uid)).isPresent();
    }
}
