package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityState;
import com.ironoath.core.city.ResourceSettlement;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.nation.NationOutputReadScope;
import com.ironoath.web.tech.TechEffects;

/** Only consumed settlement segments should read live organization bonuses. */
class ResourceSettlementRateReadCountTest {
    private static final long START = 1_700_000_000_000L;
    private final ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private final NationTechBonuses national = mock(NationTechBonuses.class);
    private final NationPolicyBonuses policy = mock(NationPolicyBonuses.class);
    private final ResourceRateService service = new ResourceRateService(configs, mock(TechEffects.class), national, policy);

    private PlayerSave player(String id) {
        return PlayerSave.createNew(id, "device-"+id, "结算读数", 1, START, 1,
                Map.of(ResourceIds.GRAIN, new PlayerResourceState(0, 1_000_000, 0, 0, START)),
                PlayerPower.zero(), null);
    }

    private CityState finishingCity(long secondFinish) {
        List<BuildingCfg> producers = configs.all(BuildingCfg.class).stream()
                .filter(c -> c.outputResource() != null && c.outputBasePerHour() != null && c.outputBasePerHour() > 0)
                .limit(2).toList();
        assertThat(producers).hasSize(2);
        return CityState.fromSnapshot(new CityState.Snapshot(List.of(
                new CityState.BuildingSnapshot("first", producers.get(0).id(), 1, 0, 0, BuildingStatus.UPGRADING,
                        START+3_600_000, START, 3600, 3600, 0, START, 0, 0),
                new CityState.BuildingSnapshot("second", producers.get(1).id(), 1, 1, 0, BuildingStatus.UPGRADING,
                        secondFinish, START, (secondFinish-START)/1000, (secondFinish-START)/1000, 0, START, 0, 0)), 0));
    }

    private ResourceRateService.Rates expectedRates(CityState city, String playerId, long percent) {
        var referencePolicy = mock(NationPolicyBonuses.class);
        when(referencePolicy.outputPercent(eq(playerId), any(NationOutputReadScope.class))).thenReturn(percent);
        return new ResourceRateService(configs, mock(TechEffects.class), mock(NationTechBonuses.class), referencePolicy)
                .compute(city, player(playerId).tech(), playerId);
    }

    @Test void noMilestoneReadsOneBonusAndUsesThatReadInTheReturnedRates() {
        when(policy.outputPercent(eq("plain"), any(NationOutputReadScope.class))).thenReturn(1000L, 9000L);
        PlayerSave player = player("plain");
        var result = service.settle(player, new CityState(), START+3_600_000);
        verify(policy).outputPercent(eq("plain"), any(NationOutputReadScope.class));
        for (var resource : configs.allResources()) verify(national).outputPercent(eq(resource.id()), eq("plain"), any(NationOutputReadScope.class));
        long grainBase = configs.allResources().stream().filter(r -> ResourceIds.GRAIN.equals(r.id())).findFirst().orElseThrow().basePerHour();
        assertThat(result.rates().perHour(ResourceIds.GRAIN)).isEqualTo(grainBase*11000/10000);
        assertThat(result.states().get(ResourceIds.GRAIN).current()).isEqualTo(result.rates().perHour(ResourceIds.GRAIN));
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(result.states().get(ResourceIds.GRAIN).current());
        assertThat(result.harvested()).isEmpty();
    }

    @Test void twoFinishTimesReadExactlyThreeConsumedSegmentsAndHarvestBoth() {
        when(policy.outputPercent(eq("segments"), any(NationOutputReadScope.class))).thenReturn(1000L, 2000L, 3000L, 9000L);
        CityState city = finishingCity(START+7_200_000);
        CityState referenceCity = CityState.fromSnapshot(city.snapshot());
        var before = expectedRates(referenceCity, "segments", 1000);
        var first = ResourceSettlement.settle(0, before.cap(ResourceIds.GRAIN), before.perHour(ResourceIds.GRAIN), START, START+3_600_000);
        referenceCity.collectFinished(START+3_600_000);
        var middle = expectedRates(referenceCity, "segments", 2000);
        var second = ResourceSettlement.settle(first.current(), middle.cap(ResourceIds.GRAIN), middle.perHour(ResourceIds.GRAIN), first.lastSettle(), START+7_200_000);
        referenceCity.collectFinished(START+7_200_000);
        var after = expectedRates(referenceCity, "segments", 3000);
        var tail = ResourceSettlement.settle(second.current(), after.cap(ResourceIds.GRAIN), after.perHour(ResourceIds.GRAIN), second.lastSettle(), START+10_800_000);
        var result = service.settle(player("segments"), city, START+10_800_000);
        verify(policy, times(3)).outputPercent(eq("segments"), any(NationOutputReadScope.class));
        assertThat(result.harvested()).containsExactly("first", "second");
        assertThat(city.building("first").level()).isEqualTo(2);
        assertThat(city.building("second").level()).isEqualTo(2);
        assertThat(result.states().get(ResourceIds.GRAIN).lastSettle()).isEqualTo(START+10_800_000);
        assertThat(result.states().get(ResourceIds.GRAIN).current()).isEqualTo(tail.current());
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(tail.current());
        assertThat(result.rates()).isEqualTo(after);
    }

    @Test void equalFinishTimesShareOneSegmentButStillRecomputeAfterHarvest() {
        CityState city = finishingCity(START+3_600_000);
        var result = service.settle(player("same-time"), city, START+7_200_000);
        verify(policy, times(2)).outputPercent(eq("same-time"), any(NationOutputReadScope.class));
        assertThat(result.harvested()).containsExactly("first", "second");
        assertThat(city.building("first").level()).isEqualTo(2);
        assertThat(city.building("second").level()).isEqualTo(2);
    }

    @Test void nextSettlementAndAnotherPlayerReadAuthorityAgainIncludingZero() {
        when(policy.outputPercent(eq("repeat"), any(NationOutputReadScope.class))).thenReturn(0L, 2000L);
        PlayerSave player = player("repeat");
        var first = service.settle(player, new CityState(), START+1000);
        var second = service.settle(player, new CityState(), START+2000);
        service.settle(player("other"), new CityState(), START+1000);
        verify(policy, times(2)).outputPercent(eq("repeat"), any(NationOutputReadScope.class));
        verify(policy).outputPercent(eq("other"), any(NationOutputReadScope.class));
        assertThat(second.rates().perHour(ResourceIds.GRAIN)).isGreaterThan(first.rates().perHour(ResourceIds.GRAIN));
    }

    @Test void failingFirstConsumedRateDoesNotMutatePlayerOrCityAndNextCallRetries() {
        PlayerSave player = player("failure"); CityState city = finishingCity(START+7_200_000);
        var resources = player.resources(); var snapshot = city.snapshot();
        var failure = new IllegalStateException("owned rate read unavailable");
        when(policy.outputPercent(eq("failure"), any(NationOutputReadScope.class))).thenThrow(failure).thenReturn(0L);
        assertThatThrownBy(() -> service.settle(player, city, START+10_800_000)).isSameAs(failure);
        assertThat(player.resources()).isEqualTo(resources); assertThat(city.snapshot()).isEqualTo(snapshot);
        assertThat(service.settle(player, city, START+10_800_000).harvested()).containsExactly("first", "second");
        verify(policy, times(4)).outputPercent(eq("failure"), any(NationOutputReadScope.class));
    }

    @Test void tailAtTheExactLastFinishTimeStillRefreshesRatesAfterHarvest() {
        when(policy.outputPercent(eq("exact-finish"), any(NationOutputReadScope.class))).thenReturn(1000L, 2000L, 9000L);
        CityState city = finishingCity(START+3_600_000);
        var before = expectedRates(CityState.fromSnapshot(city.snapshot()), "exact-finish", 1000);
        var result = service.settle(player("exact-finish"), city, START+3_600_000);
        verify(policy, times(2)).outputPercent(eq("exact-finish"), any(NationOutputReadScope.class));
        assertThat(result.harvested()).containsExactly("first", "second");
        assertThat(result.rates()).isEqualTo(expectedRates(city, "exact-finish", 2000));
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(before.perHour(ResourceIds.GRAIN));
        assertThat(result.states().get(ResourceIds.GRAIN).perHour()).isEqualTo(result.rates().perHour(ResourceIds.GRAIN));
    }

    @Test void nullCityAndPlayerKeepTheirExistingArgumentErrorsBeforeAnyRead() {
        PlayerSave player = player("invalid"); var resources = player.resources();
        assertThatThrownBy(() -> service.settle(player, null, START)).isInstanceOf(IllegalArgumentException.class).hasMessage("city 不得为 null");
        assertThatThrownBy(() -> service.settle(null, null, START)).isInstanceOf(IllegalArgumentException.class).hasMessage("player 不得为 null");
        assertThat(player.resources()).isEqualTo(resources);
        verifyNoInteractions(national, policy);
    }
}
