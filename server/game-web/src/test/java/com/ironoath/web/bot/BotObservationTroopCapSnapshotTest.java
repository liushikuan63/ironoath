package com.ironoath.web.bot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.*;

/** Actual initialized players and production observation; repository reads are counted, not mocked away. */
@SpringBootTest @ActiveProfiles("test")
class BotObservationTroopCapSnapshotTest {
    @Autowired BotWorldAdapter adapter;
    @Autowired BotRegistry bots;
    @Autowired BotRuntimeService runtime;
    @Autowired BotRulesAssembler rules;
    @Autowired PlayerInitService init;
    @SpyBean CityAppService city;
    @SpyBean ArmyRepository armies;
    @SpyBean CityRepository cities;
    @SpyBean HeroAppService heroes;
    @SpyBean HeroRepository rosters;
    @SpyBean InventoryRepository inventories;
    @SpyBean TimeService time;
    long now; String uid;

    @BeforeEach void fixture() {
        now=System.currentTimeMillis(); doReturn(now).when(time).serverNow();
        runtime.reset(); bots.clear(); adapter.resetCounters(); uid=player();
        clearInvocations(heroes,rosters,inventories);
    }
    String player() {
        String key=UUID.randomUUID().toString();
        String id=init.init(new PlayerInitReq(key,"troop-snapshot-"+key,"观察上限验收",now,"")).playerId();
        city.list(id); heroes.troopCap(id);
        bots.register(rules.archetypes().get(0).spawnProfile(id,1,Rng.of(431)));
        return id;
    }
    @Test void oneObservationReadsTheActualRosterAndEquipmentOnlyOnceForBothAdvisoryFacts() {
        assertThat(adapter.observe(uid,now)).isNotNull();
        verify(heroes,times(1)).troopCap(uid);
        verify(rosters,times(1)).findByPlayerId(uid);
        verify(inventories,times(1)).findByPlayerId(uid);
    }
    @Test void nextObservationReadsAgainAndZeroIsAValidFirstValue() {
        doReturn(0L,100L).when(heroes).troopCap(uid);
        assertThat(adapter.observe(uid,now).populationFull()).isTrue();
        assertThat(adapter.observe(uid,now).populationFull()).isFalse();
        verify(heroes,times(2)).troopCap(uid);
    }
    @Test void anotherPlayerCannotReuseTheFirstPlayersObservation() {
        String other=player(); clearInvocations(heroes,rosters,inventories);
        doReturn(0L).when(heroes).troopCap(uid); doReturn(100L).when(heroes).troopCap(other);
        assertThat(adapter.observe(uid,now).populationFull()).isTrue();
        assertThat(adapter.observe(other,now).populationFull()).isFalse();
        verify(heroes,times(1)).troopCap(uid); verify(heroes,times(1)).troopCap(other);
    }
    @Test void firstCapacityReadFailurePropagatesAndNextObservationRetries() {
        var failure=new IllegalStateException("own troop-cap source unavailable");
        doThrow(failure).doCallRealMethod().when(heroes).troopCap(uid);
        assertThatThrownBy(()->adapter.observe(uid,now)).isSameAs(failure);
        assertThat(adapter.observe(uid,now)).isNotNull();
        verify(heroes,times(2)).troopCap(uid);
    }
    @Test void bothSettlementsAndColdArmyInitializationRemainReal() {
        assertThat(armies.findByPlayerId(uid)).isEmpty();
        clearInvocations(city,armies,cities);
        assertThat(adapter.observe(uid,now)).isNotNull();
        verify(city,times(2)).withSettledCity(eq(uid),any());
        verify(cities,times(2)).save(eq(uid),any(),anyLong());
        verify(armies,times(1)).insertIfAbsent(eq(uid),any());
        verify(armies,times(3)).findByPlayerId(uid);
        assertThat(armies.findByPlayerId(uid)).isPresent();
    }
}
