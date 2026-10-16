package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.nation.NationOutputReadScope;
import com.ironoath.web.tech.TechEffects;

/** One policy snapshot belongs to one rate calculation, including a legitimate zero. */
class ResourceRatePolicyReadScopeTest {
    private final ConfigRegistry configs = spy(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));
    private final TechEffects personal = mock(TechEffects.class);
    private final NationTechBonuses national = mock(NationTechBonuses.class);
    private final NationPolicyBonuses policy = mock(NationPolicyBonuses.class);
    private final ResourceRateService service = new ResourceRateService(configs, personal, national, policy);
    private final PlayerTech tech = PlayerTech.empty();

    private void assertPolicy(ResourceRateService.Rates rates, long percent) {
        assertThat(configs.allResources().size()).isGreaterThan(1);
        for (ResourceCfg res : configs.allResources()) {
            var base = res.basePerHour() == 0 ? List.<ResourceOutputCalculator.Line>of()
                    : List.of(ResourceOutputCalculator.Line.flat("领地基础产出", res.basePerHour()));
            assertThat(rates.breakdowns().get(res.id()))
                    .isEqualTo(ResourceOutputCalculator.compute(base, percent, 0, 0));
        }
    }

    @Test
    void oneNonzeroReadFeedsEveryResourceWithoutSkippingResourceSpecificBonuses() {
        when(policy.outputPercent(eq("player-a"), any(NationOutputReadScope.class))).thenReturn(1000L);
        when(personal.outputPercent(anyString(), same(tech))).thenReturn(200L);
        when(national.outputPercent(anyString(), eq("player-a"), any(NationOutputReadScope.class))).thenReturn(300L);
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 1500L);
        verify(policy).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
        for (ResourceCfg res : configs.allResources()) {
            verify(personal).outputPercent(res.id(), tech);
            verify(national).outputPercent(eq(res.id()), eq("player-a"), any(NationOutputReadScope.class));
        }
        var order = inOrder(personal, national, policy);
        String first = configs.allResources().get(0).id();
        order.verify(personal).outputPercent(first, tech);
        order.verify(national).outputPercent(eq(first), eq("player-a"), any(NationOutputReadScope.class));
        order.verify(policy).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
    }

    @Test
    void zeroIsAResolvedPolicyValue() {
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 0);
        verify(policy).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
    }

    @Test
    void nextCalculationReadsChangedPolicyAgain() {
        when(policy.outputPercent(eq("player-a"), any(NationOutputReadScope.class))).thenReturn(1000L, 2000L);
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 1000);
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 2000);
        verify(policy, times(2)).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
    }

    @Test
    void failurePropagatesAndNextCalculationRetriesAuthoritativeRead() {
        var failure = new IllegalStateException("policy configuration unavailable");
        when(policy.outputPercent(eq("player-a"), any(NationOutputReadScope.class))).thenThrow(failure).thenReturn(700L);
        assertThatThrownBy(() -> service.compute(new CityState(), tech, "player-a"))
                .isSameAs(failure);
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 700);
        verify(policy, times(2)).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
    }

    @Test
    void anotherPlayerDoesNotReuseThePreviousPlayersPolicy() {
        when(policy.outputPercent(eq("player-a"), any(NationOutputReadScope.class))).thenReturn(500L);
        when(policy.outputPercent(eq("player-b"), any(NationOutputReadScope.class))).thenReturn(1700L);
        assertPolicy(service.compute(new CityState(), tech, "player-a"), 500);
        assertPolicy(service.compute(new CityState(), tech, "player-b"), 1700);
        verify(policy).outputPercent(eq("player-a"), any(NationOutputReadScope.class));
        verify(policy).outputPercent(eq("player-b"), any(NationOutputReadScope.class));
    }

    @Test
    void noResourcesDoesNotIntroduceAPolicyRead() {
        doReturn(List.of()).when(configs).allResources();
        assertThat(service.compute(new CityState(), tech, "player-a").breakdowns()).isEmpty();
        verifyNoInteractions(personal, national, policy);
    }
}
