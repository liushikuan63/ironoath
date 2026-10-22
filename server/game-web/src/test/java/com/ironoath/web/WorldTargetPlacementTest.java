package com.ironoath.web;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.service.WorldTargets;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 固定目标预留不能吞掉旧城，也不能让新号、迁城重新占进去；只用隔离仓储替身。 */
class WorldTargetPlacementTest {
    private final ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private final WorldRepository store = mock(WorldRepository.class);
    private final com.ironoath.web.world.TargetOccupationStore occupations =
            new com.ironoath.web.store.memory.InMemoryTargetOccupationStore();
    private final WorldAppService service = new WorldAppService(configs, store,
            mock(PlayerRepository.class), null, null, null, null, null,occupations,new com.ironoath.web.store.memory.InMemoryTargetOperationStore());
    private static final List<Coord> RESERVED = List.of(Coord.of(256,256),
            Coord.of(256,216), Coord.of(296,256), Coord.of(256,296), Coord.of(216,256),
            Coord.of(320,192), Coord.of(352,224), Coord.of(192,192), Coord.of(160,224),
            Coord.of(192,320), Coord.of(160,288), Coord.of(320,320), Coord.of(352,288));

    @Test void relocationRejectsEveryTargetBeforeStorageWrite() {
        when(store.moveCity(anyString(), any())).thenReturn(true);
        for (Coord target : RESERVED) {
            assertThat(service.relocateCity("owned-fixture", target)).as("reserved %s", target).isFalse();
        }
        verify(store, never()).moveCity(anyString(), any());
    }

    @Test void existingCityIsPreservedEvenOnReservedTile() {
        when(store.cityOf("legacy-owned-fixture")).thenReturn(Optional.of(RESERVED.get(0)));
        assertThat(service.homeOf("legacy-owned-fixture")).isEqualTo(RESERVED.get(0));
        verify(store, never()).moveCity(anyString(), any());
        verify(store, never()).placeCity(anyString(), any());
    }

    @Test void newHomeSkipsReservedSpawnCandidate() {
        String id = null;
        for (int n=0; n<1_000_000; n++) {
            String candidate = "target-spawn-fixture-" + n;
            long index = Math.floorMod((long) candidate.hashCode(), 1_000_000L);
            if (RESERVED.contains(WorldGenerator.spawnCoord(service.rules(), index))) {
                id = candidate;
                break;
            }
        }
        assertThat(id).as("fixture must hit a reserved first candidate").isNotNull();
        when(store.placeCity(anyString(), any())).thenReturn(true);
        assertThat(RESERVED).doesNotContain(service.homeOf(id));
        verify(store).placeCity(eq(id), argThat(c -> !RESERVED.contains(c)));
    }

    @Test void directoryMatchesGeometryAndReportsConflictsWithoutMutatingCities() {
        var catalog = WorldTargets.from(configs);
        assertThat(catalog.targets()).hasSize(13);
        assertThat(catalog.targets().stream().map(WorldTargets.Target::coord).toList())
                .containsExactlyInAnyOrderElementsOf(RESERVED);
        var cities = new java.util.HashMap<String, Coord>();
        cities.put("owned-city", RESERVED.get(0));
        cities.put("outside", Coord.of(1,1));
        var before = java.util.Map.copyOf(cities);
        assertThat(catalog.conflicts(cities)).containsExactly(
                new WorldTargets.Conflict("capital_center", RESERVED.get(0), "owned-city"));
        assertThat(cities).isEqualTo(before);
        for (var target : catalog.targets()) {
            long expected = switch (target.definition().kind()) {
                case CAPITAL -> 183_670L;
                case GATE -> 60_185L;
                case FORTRESS -> 19_721L;
            };
            assertThat(target.troops()).isEqualTo(expected);
            assertThat(configs.rawTable("world_target").rows().stream()
                    .filter(r -> r.get("id").asText().equals(target.definition().id()))
                    .findFirst().orElseThrow().get("todo").asText()).contains("TODO(需确认)");
        }
        var fortresses = catalog.targets().stream()
                .filter(t -> t.definition().kind().name().equals("FORTRESS")).toList();
        for (var a : fortresses) for (var b : fortresses) {
            if (a == b) continue;
            long dx = a.coord().x() - b.coord().x(), dy = a.coord().y() - b.coord().y();
            long radii = a.definition().effectRadius() + b.definition().effectRadius();
            assertThat(dx*dx + dy*dy).isGreaterThan(radii*radii);
        }
    }

    @Test void randomRelocationSkipsReservedCandidate() {
        long selected = -1;
        for (long seed=0; seed<1_000_000L; seed++) {
            long index = Math.floorMod(com.ironoath.common.rng.Rng.of(seed).nextLong(), 1_000_000L);
            if (RESERVED.contains(WorldGenerator.spawnCoord(service.rules(), index))) {
                selected=seed; break;
            }
        }
        assertThat(selected).isNotNegative();
        assertThat(RESERVED).doesNotContain(service.randomFreeCoord(selected));
    }

    @Test void botRingAndFallbackNeverPlaceIntoTargets() throws Exception {
        var anchor = Coord.of(256,206);
        long selected = -1;
        for (long seed=0; seed<100_000L; seed++) {
            var first = WorldGenerator.ringAround(anchor,
                    (int)configs.longParam("BOT_SPAWN_DISTANCE_MIN"),
                    (int)configs.longParam("BOT_SPAWN_DISTANCE_MAX"), com.ironoath.common.rng.Rng.of(seed));
            if (first.isPresent() && RESERVED.contains(first.get())) { selected=seed; break; }
        }
        assertThat(selected).as("bot ring fixture hits gate").isNotNegative();
        when(store.placeCity(anyString(), any())).thenReturn(true);
        var bot = new com.ironoath.web.bot.BotSpawnService(configs, null, null, null, null, store);
        var method = bot.getClass().getDeclaredMethod("placeNear", String.class, Coord.class,
                WorldGenerator.Rules.class, com.ironoath.common.rng.Rng.class);
        method.setAccessible(true);
        method.invoke(bot, "owned-bot", anchor, service.rules(), com.ironoath.common.rng.Rng.of(selected));
        verify(store, never()).placeCity(anyString(), argThat(RESERVED::contains));
    }
    @Test void invalidTargetCoordinatesFailClosed() {
        var real = configs.all(com.ironoath.config.cfg.WorldTargetCfg.class).get(0);
        var candidate = mock(ConfigRegistry.class);
        when(candidate.longParam("WORLD_SIZE")).thenReturn(512L);
        when(candidate.all(com.ironoath.config.cfg.MapmonsterCfg.class))
                .thenReturn(configs.all(com.ironoath.config.cfg.MapmonsterCfg.class));
        var outside = new com.ironoath.config.cfg.WorldTargetCfg(real.id(), real.name(), real.kind(),
                512L, real.y(), real.defenderId(), real.effectRadius());
        when(candidate.all(com.ironoath.config.cfg.WorldTargetCfg.class)).thenReturn(List.of(outside));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WorldTargets.from(candidate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("越界");
        when(candidate.all(com.ironoath.config.cfg.WorldTargetCfg.class)).thenReturn(List.of(real,real));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WorldTargets.from(candidate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }

    @Test void fixedTargetsOverrideConsumedTerrainAndNeverBecomeMonsters() {
        when(store.isConsumed(any())).thenReturn(true);
        var targets = WorldTargets.from(configs).targets();
        for (var target : targets) {
            var cell = service.cellAt(target.coord());
            assertThat(cell.entityType().name()).isEqualTo(target.definition().kind().name());
            assertThat(cell.entityId()).isEqualTo(target.definition().id());
            assertThat(cell.level()).isEqualTo((int)target.defender().level());
        }
    }

    @Test void visibleMapCarriesServerNamesAndPreservesLegacyCities() {
        var catalog = WorldTargets.from(configs);
        var fog = new com.ironoath.core.world.FogOfWar();
        when(store.fogOf("viewer")).thenReturn(fog);
        for (var target : catalog.targets()) {
            Coord nearby = Coord.of(target.coord().x()+1,target.coord().y());
            when(store.cityOf("viewer")).thenReturn(Optional.of(nearby));
            when(store.allCities()).thenReturn(java.util.Map.of());
            fog.explore(target.coord(),32);
            when(store.consumedInChunk(anyString(),eq(32))).thenReturn(List.of(target.coord()));
            var entity = service.visibleEntitiesOf("viewer").stream()
                    .filter(e -> e.x()==target.coord().x() && e.y()==target.coord().y()).findFirst().orElseThrow();
            assertThat(entity.type().name()).isEqualTo(target.definition().kind().name());
            assertThat(entity.displayName()).isEqualTo(target.definition().name());
            assertThat(entity.ownerName()).isNull();
            when(store.allCities()).thenReturn(java.util.Map.of("legacy-owner",target.coord()));
            var legacy = service.visibleEntitiesOf("viewer").stream()
                    .filter(e -> e.x()==target.coord().x() && e.y()==target.coord().y()).findFirst().orElseThrow();
            assertThat(legacy.type().name()).isEqualTo("CITY");
            assertThat(legacy.id()).isEqualTo("legacy-owner");
        }
    }

    @Test void unexploredTargetChunkDoesNotLeakFixedEntities() {
        when(store.cityOf("viewer")).thenReturn(Optional.of(Coord.of(255,255)));
        when(store.fogOf("viewer")).thenReturn(new com.ironoath.core.world.FogOfWar());
        assertThat(service.visibleEntitiesOf("viewer")).isEmpty();
    }

    @Test void ownershipAndChunkVersionComeFromOneReadSnapshot() {
        var time=mock(com.ironoath.common.time.TimeService.class);
        var app=new WorldAppService(configs,store,mock(PlayerRepository.class),time,
                mock(com.ironoath.web.service.PowerService.class),
                mock(com.ironoath.web.service.PublicEnemyBroadcaster.class),
                mock(com.ironoath.web.bot.BotSpawnService.class),mock(com.ironoath.web.ws.PushGateway.class),occupations,new com.ironoath.web.store.memory.InMemoryTargetOperationStore());
        when(store.cityOf("viewer")).thenReturn(Optional.of(Coord.of(255,216)));
        var fog=new com.ironoath.core.world.FogOfWar();fog.explore(Coord.of(256,216),32);
        when(store.fogOf("viewer")).thenReturn(fog);
        var neutral=app.viewport("viewer",new com.ironoath.web.dto.generated.ViewportReq(256,216,1,List.of()));
        var before=neutral.chunks().stream().filter(c->c.key().equals("8:6")).findFirst().orElseThrow();
        var owner=new com.ironoath.web.world.TargetOccupationStore.Snapshot("gate_north",
                WorldGenerator.EntityType.GATE,256,216,"N-owned","验收国",100,"owned-battle-1",1);
        assertThat(occupations.compareAndSet(owner,0)).isTrue();
        var versions=neutral.chunks().stream().map(c->new com.ironoath.web.dto.generated.ChunkVersion(c.key(),c.version())).toList();
        var changed=app.viewport("viewer",new com.ironoath.web.dto.generated.ViewportReq(256,216,1,versions));
        assertThat(changed.chunks()).hasSize(1);
        assertThat(changed.chunks().get(0).version()).isEqualTo(before.version()+1);
        assertThat(changed.chunks().get(0).entities()).anySatisfy(e->{
            assertThat(e.id()).isEqualTo("gate_north");assertThat(e.ownerName()).isEqualTo("验收国");
            assertThat(e.displayName()).isEqualTo("北境关卡");
        });
    }

    @Test void incompatibleOwnedAnchorFailsBeforeMapReadOrConfigurationSwitch() {
        var wrong=new com.ironoath.web.world.TargetOccupationStore.Snapshot("gate_north",
                WorldGenerator.EntityType.GATE,257,216,"N-owned","验收国",100,"owned-wrong-anchor",1);
        assertThat(occupations.compareAndSet(wrong,0)).isTrue();
        when(store.cityOf("viewer")).thenReturn(Optional.of(Coord.of(255,216)));
        when(store.fogOf("viewer")).thenReturn(new com.ironoath.core.world.FogOfWar());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.visibleEntitiesOf("viewer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("迁移");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new WorldAppService(configs,store,
                mock(PlayerRepository.class),null,null,null,null,null,occupations,new com.ironoath.web.store.memory.InMemoryTargetOperationStore()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("迁移");
    }

    @Test void configurationMoveOfOwnedTargetIsRejectedAndOldTableIsRetained() throws Exception {
        var owner=new com.ironoath.web.world.TargetOccupationStore.Snapshot("gate_north",
                WorldGenerator.EntityType.GATE,256,216,"N-owned","验收国",100,"owned-valid-anchor",1);
        assertThat(occupations.compareAndSet(owner,0)).isTrue();
        Path root=Path.of("").toAbsolutePath();
        for(int i=0;i<6 && root!=null && !java.nio.file.Files.exists(root.resolve("contract/config/world_target.json"));i++) {
            root=root.getParent();
        }
        assertThat(root).isNotNull();
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(java.nio.file.Files.readString(root.resolve("contract/config/world_target.json")));
        json.put("version",json.get("version").asInt()+1);
        for(var row:json.withArray("rows")) {
            if(row.get("id").asText().equals("gate_north"))
                ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("x",257);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(()->configs.reload("world_target",
                com.ironoath.config.cfg.WorldTargetCfg.class,json.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("迁移");
        assertThat(WorldTargets.from(configs).at(Coord.of(256,216)).definition().id()).isEqualTo("gate_north");
        assertThat(WorldTargets.from(configs).at(Coord.of(257,216))).isNull();
    }

    @Test void operationWithoutOwnerStillGuardsStartupAndConfigurationMove() throws Exception {
        var operations=mock(com.ironoath.web.world.TargetOperationStore.class);
        var m=new com.ironoath.core.march.March("owned-plan-anchor","owned-player",Coord.of(255,216),Coord.of(256,216),100,110,
                java.util.Map.of("unit_infantry_t4",2L),List.of(),100,1,
                com.ironoath.core.march.March.TargetType.STRATEGIC_TARGET,"gate_north",com.ironoath.core.march.March.Action.ATTACK);
        m.attachToRally("owned-plan-rally");m.arrive(110);m.holdStrategicVictory(java.util.Map.of(m.playerId(),m.units()));
        var owner=new com.ironoath.web.world.TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,
                256,216,"owned-nation","验收国",100,"owned-capture",1,com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(m.id()));
        var plan=com.ironoath.web.world.TargetOperationStore.WithdrawalPlan.create(owner,m,0,1,120);
        when(operations.findAllTargetIds(anyInt())).thenReturn(List.of("gate_north"));
        when(operations.findByTargetId("gate_north")).thenReturn(Optional.of(new com.ironoath.web.world.TargetOperationStore.Snapshot("gate_north",1,false,plan)));
        new WorldAppService(configs,store,mock(PlayerRepository.class),null,null,null,null,null,occupations,operations);
        Path root=Path.of("").toAbsolutePath();
        for(int i=0;i<6 && root!=null && !java.nio.file.Files.exists(root.resolve("contract/config/world_target.json"));i++)root=root.getParent();
        assertThat(root).isNotNull();
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(java.nio.file.Files.readString(root.resolve("contract/config/world_target.json")));
        json.put("version",json.get("version").asInt()+1);
        for(var row:json.withArray("rows"))if(row.get("id").asText().equals("gate_north"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("x",257);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->configs.reload("world_target",
                com.ironoath.config.cfg.WorldTargetCfg.class,json.toString())).hasMessageContaining("迁移");
        assertThat(WorldTargets.from(configs).at(Coord.of(256,216)).definition().id()).isEqualTo("gate_north");
        when(operations.findByTargetId("gate_north")).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new WorldAppService(configs,store,mock(PlayerRepository.class),
                null,null,null,null,null,occupations,operations)).hasMessageContaining("缺档");
    }
}
