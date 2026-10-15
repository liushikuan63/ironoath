package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.*;
import com.ironoath.core.world.*;
import com.ironoath.web.bot.*;
import com.ironoath.web.service.PlayerInitService;

class BotHumanAverageReadScopeTest {
    private final PlayerRepository players=mock(PlayerRepository.class);
    private final WorldRepository world=mock(WorldRepository.class);
    private final BotRegistry registry=mock(BotRegistry.class);
    private final BotSpawnService service=new BotSpawnService(mock(ConfigRegistry.class),mock(BotRulesAssembler.class),
            registry,players,mock(PlayerInitService.class),world);

    private void worldWith(Set<String> botIds,String... cityIds) {
        Map<String,Coord> cities=new LinkedHashMap<>();
        for(int i=0;i<cityIds.length;i++)cities.put(cityIds[i],Coord.of(i+10,10));
        when(world.allCities()).thenReturn(cities);
        when(world.cityPlayerIds()).thenReturn(Set.copyOf(cities.keySet()));
        when(registry.humansIn(anyCollection())).thenAnswer(call->{
            Collection<String> ids=call.getArgument(0);
            return ids.stream().filter(id->!botIds.contains(id)).toList();
        });
    }
    private PlayerSave power(long value) {
        PlayerSave save=mock(PlayerSave.class);when(save.power()).thenReturn(new PlayerPower(value,value,value));return save;
    }
    @Test
    void averageReadsOnlyHumanDocumentsAndIgnoresMissingHumanSave() {
        worldWith(Set.of("bot-a","bot-b"),"human-a","bot-a","missing-human","human-b","bot-b");
        when(players.findByPlayerIds(anyCollection())).thenAnswer(call->{
            Collection<String> requested=call.getArgument(0);
            assertThat(requested).as("真人均值不能反复复制所有Bot玩家档").containsExactlyInAnyOrder("human-a","human-b","missing-human");
            return Map.of("human-a",power(200),"human-b",power(400));
        });
        assertThat(service.humanAverageMatchPower()).isEqualTo(300);
    }
    @Test
    void allBotCitiesDoNotReadPlayerDocuments() {
        worldWith(Set.of("bot-a","bot-b"),"bot-a","bot-b");
        Map<String,PlayerSave> saves=Map.of("bot-a",power(50000),"bot-b",power(90000));
        when(players.findByPlayerIds(anyCollection())).thenReturn(saves);
        assertThat(service.humanAverageMatchPower()).isZero();
        verifyNoInteractions(players);
    }
    @Test
    void changedClassificationAndPlayerPowerAreReadAgainWithoutCaching() {
        Set<String> bots=new HashSet<>();worldWith(bots,"human-a","human-b");
        when(players.findByPlayerIds(anyCollection())).thenAnswer(call->{
            Collection<String> requested=call.getArgument(0);Map<String,PlayerSave> saves=new HashMap<>();
            for(String id:requested)saves.put(id,power(id.equals("human-a")?200:600));return saves;
        });
        assertThat(service.humanAverageMatchPower()).isEqualTo(400);
        bots.add("human-b");assertThat(service.humanAverageMatchPower()).isEqualTo(200);
        Map<String,PlayerSave> updated=Map.of("human-a",power(800));
        when(players.findByPlayerIds(anyCollection())).thenReturn(updated);
        assertThat(service.humanAverageMatchPower()).isEqualTo(800);
    }

    @Test void averageNeedsOnlyPlacedPlayerIdsWithoutLoadingCoordinates() {
        worldWith(Set.of("bot-a"),"human-a","bot-a");
        PlayerSave human=power(200);
        when(players.findByPlayerIds(anyCollection())).thenReturn(Map.of("human-a",human));
        assertThat(service.humanAverageMatchPower()).isEqualTo(200);
        verify(world).cityPlayerIds();
        verify(world,never()).allCities();
    }

    @Test void cityIdentityReadFailurePropagatesInsteadOfProducingAnEmptyMean() {
        worldWith(Set.of(),"human-a");
        when(world.cityPlayerIds()).thenThrow(new IllegalStateException("city identities unavailable"));
        org.assertj.core.api.Assertions.assertThatThrownBy(service::humanAverageMatchPower)
                .hasMessage("city identities unavailable");
        verifyNoInteractions(players);
    }
}
