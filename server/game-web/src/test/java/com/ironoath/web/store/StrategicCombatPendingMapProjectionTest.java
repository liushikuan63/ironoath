package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.dto.generated.ViewportReq;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.service.PublicEnemyBroadcaster;
import com.ironoath.web.bot.BotSpawnService;
import com.ironoath.web.ws.PushGateway;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Map reads must project the frozen pre-COMBAT owner while a multi-store recovery is still pending. */
class StrategicCombatPendingMapProjectionTest {
    @Test void mapDoesNotExposePartiallyWrittenCombatOwnershipBeforeOperationCompletion() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var battle=StrategicCombatSlotTest.battle(2402,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var original=battle.target().defender().occupation();assertThat(original).isNotNull();
        var operations=new InMemoryTargetOperationStore();
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var occupations=new InMemoryTargetOccupationStore();assertThat(occupations.compareAndSet(original,0)).isTrue();

        // Simulate recovery's ownership CAS completing before the later March/report/War receipts.
        var partial=new TargetOccupationStore.Snapshot(original.targetId(),original.kind(),original.x(),original.y(),
                battle.attacker().rally().nationId(),"攻击国显示名",battle.happenedAt(),plan.operationId(),
                Math.addExact(original.version(),1),TargetOccupationStore.Garrison.stationed(battle.attacker().march().id()));
        assertThat(occupations.compareAndSet(partial,original.version())).isTrue();

        var world=mock(WorldRepository.class);var home=Coord.of(255,216);var fog=new FogOfWar();fog.explore(Coord.of(original.x(),original.y()),32);
        when(world.cityOf("owned-viewer")).thenReturn(Optional.of(home));when(world.fogOf("owned-viewer")).thenReturn(fog);
        when(world.allCities()).thenReturn(Map.of());when(world.consumedInChunk(anyString(),anyInt())).thenReturn(List.of());
        var time=mock(TimeService.class);when(time.serverNow()).thenReturn(battle.happenedAt());
        var push=mock(PushGateway.class);when(push.onlineCount()).thenReturn(0);
        var app=new WorldAppService(configs,world,mock(PlayerRepository.class),time,mock(PowerService.class),
                mock(PublicEnemyBroadcaster.class),mock(BotSpawnService.class),push,occupations,operations);
        var before=app.visibleEntitiesOf("owned-viewer").stream().filter(e->e.id().equals(original.targetId())).findFirst().orElseThrow();
        assertThat(before.ownerName()).isEqualTo(original.nationName());
        var request=new ViewportReq(256,216,1,List.of());var first=app.viewport("owned-viewer",request);
        var firstChunk=first.chunks().stream().filter(c->c.key().equals("8:6")).findFirst().orElseThrow();
        assertThat(firstChunk.entities().stream().filter(e->e.id().equals(original.targetId())).findFirst().orElseThrow().ownerName())
                .isEqualTo(original.nationName());

        var done=TargetOperationStore.Snapshot.combat(2,true,plan);
        assertThat(operations.compareAndSet(done,1)).isTrue();
        var after=app.visibleEntitiesOf("owned-viewer").stream().filter(e->e.id().equals(original.targetId())).findFirst().orElseThrow();
        assertThat(after.ownerName()).isEqualTo("攻击国显示名");
        var versions=first.chunks().stream().map(c->new com.ironoath.web.dto.generated.ChunkVersion(c.key(),c.version())).toList();
        var refreshed=app.viewport("owned-viewer",new ViewportReq(256,216,1,versions));
        var next=refreshed.chunks().stream().filter(c->c.key().equals("8:6")).findFirst().orElseThrow();
        assertThat(next.version()).isGreaterThan(firstChunk.version());
        assertThat(next.entities().stream().filter(e->e.id().equals(original.targetId())).findFirst().orElseThrow().ownerName())
                .isEqualTo("攻击国显示名");
    }
}
