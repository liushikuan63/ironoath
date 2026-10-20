package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.tech.TechEffects;

/** Pure service tests: no Spring context, database, backend or real player data. */
class HospitalCapacityLookupScopeTest {
    private ConfigRegistry configs;
    private PlayerRepository players;
    private ArmyAppService army;

    @BeforeEach
    void setUp() {
        configs = mock(ConfigRegistry.class, RETURNS_DEEP_STUBS);
        players = mock(PlayerRepository.class);
        when(configs.curve("BUILDING_OUTPUT").exponentFixed()).thenReturn(10_000L);
        army = new ArmyAppService(configs, null, null, null, null, null, null,
                null, null, players, mock(TechEffects.class), null);
    }

    private CityState hospital(boolean upgrading) {
        CityState city = mock(CityState.class);
        BuildingInstance building = mock(BuildingInstance.class);
        BuildingCfg cfg = mock(BuildingCfg.class);
        when(city.buildings()).thenReturn(List.of(building));
        when(building.configId()).thenReturn("hospital");
        when(building.level()).thenReturn(1);
        when(building.isUpgrading()).thenReturn(upgrading);
        when(configs.get(BuildingCfg.class, "hospital")).thenReturn(cfg);
        when(cfg.woundedCapBase()).thenReturn(100L);
        return city;
    }

    @Test
    void noHospitalReturnsZeroEvenWhenPlayerStoreUnavailable() {
        CityState city = mock(CityState.class);
        when(city.buildings()).thenReturn(List.of());
        when(players.findByPlayerId("owner")).thenThrow(new IllegalStateException("store unavailable"));
        assertThat(army.hospitalCapacity("owner", city)).isZero();
        verifyNoInteractions(players);
    }

    @Test
    void upgradingHospitalReturnsZeroWithoutPlayerLookup() {
        when(players.findByPlayerId("owner")).thenThrow(new IllegalStateException("store unavailable"));
        assertThat(army.hospitalCapacity("owner", hospital(true))).isZero();
        verifyNoInteractions(players);
    }

    @Test
    void positiveCapacityStillLooksUpPlayerExactlyOnce() {
        when(players.findByPlayerId("owner")).thenReturn(Optional.empty());
        assertThat(army.hospitalCapacity("owner", hospital(false))).isEqualTo(100L);
        verify(players, times(1)).findByPlayerId("owner");
    }

    @Test
    void knownNullPlayerMeansNoTechRatherThanFallbackLookup() {
        when(players.findByPlayerId("owner")).thenThrow(new IllegalStateException("store unavailable"));
        assertThat(army.hospitalCapacity("owner", hospital(false), null)).isEqualTo(100L);
        verifyNoInteractions(players);
    }

    @Test
    void positiveCapacityPreservesPlayerStoreFailure() {
        IllegalStateException failure = new IllegalStateException("store unavailable");
        when(players.findByPlayerId("owner")).thenThrow(failure);
        assertThatThrownBy(() -> army.hospitalCapacity("owner", hospital(false))).isSameAs(failure);
    }

    @Test
    void nullPlayerIdKeepsBaseCapacityWithoutLookup() {
        assertThat(army.hospitalCapacity(null, hospital(false))).isEqualTo(100L);
        verifyNoInteractions(players);
    }
}
