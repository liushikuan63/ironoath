package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.march.March;
import com.ironoath.core.world.*;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.world.*;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 两个真实守卫注册者共用注册表；不能因Bean先后次序丢掉任何一类持久锚点。 */
class WorldTargetReloadGuardsTest {
    @TempDir Path directory;
    @ParameterizedTest
    @CsvSource({"true,NPC,single","false,NPC,single","true,OWNER,single","false,OWNER,single","true,OPERATION,single","false,OPERATION,single",
                "true,NPC,directory","false,NPC,directory","true,OWNER,directory","false,OWNER,directory","true,OPERATION,directory","false,OPERATION,directory"})
    void allPersistentAnchorsVetoReloadInEitherRegistrationOrder(boolean defenderFirst,String artifact,String mode)throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isDirectory(root.resolve("contract/config")))root=root.getParent();
        assertThat(root).isNotNull();
        try(var paths=Files.list(root.resolve("contract/config"))){for(var path:paths.filter(Files::isRegularFile).toList())Files.copy(path,directory.resolve(path.getFileName()));}
        var cfg=ConfigRegistry.loadFromDirectory(directory);var owners=new InMemoryTargetOccupationStore();
        var neutral=new InMemoryNeutralTargetGarrisonStore();var operations=new InMemoryTargetOperationStore();
        var owner=new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,"owned-N","验收国",100,"capture",1,TargetOccupationStore.Garrison.stationed("owned-march"));
        if(artifact.equals("OWNER"))owners.compareAndSet(owner,0);
        if(artifact.equals("NPC"))neutral.compareAndSet(new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,Map.of("unit_infantry_t4",100L),Map.of("unit_infantry_t4",3L),NeutralTargetGarrisonStore.Phase.NEUTRAL,100,"npc-loss",1),0);
        if(artifact.equals("OPERATION")){
            var march=new March("owned-march","owned-leader",Coord.of(10,10),Coord.of(256,216),100,110,Map.of("unit_infantry_t4",3L),List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
            march.attachToRally("owned-rally");march.arrive(110);march.holdStrategicVictory(Map.of(march.playerId(),march.units()));
            var plan=TargetOperationStore.WithdrawalPlan.create(owner,march,0,1,120);
            operations.compareAndSet(new TargetOperationStore.Snapshot("gate_north",1,false,plan),0);
        }
        Runnable registerDefender=()->new StrategicTargetDefender(cfg,owners,new InMemoryMarchStore(32),mock(SocialStore.class),neutral);
        Runnable registerWorld=()->new WorldAppService(cfg,mock(WorldRepository.class),mock(PlayerRepository.class),null,null,null,null,null,owners,operations);
        if(defenderFirst){registerDefender.run();registerWorld.run();}else{registerWorld.run();registerDefender.run();}
        var before=cfg.all(WorldTargetCfg.class);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var json=mapper.readTree(Files.readString(directory.resolve("world_target.json")));
        for(var row:json.get("rows"))if(row.get("id").asText().equals("gate_north"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("x",255);
        Files.writeString(directory.resolve("world_target.json"),json.toString());
        assertThatThrownBy(()->{if(mode.equals("single"))cfg.reload("world_target",WorldTargetCfg.class,json.toString());else cfg.reloadAllFromDirectory(directory);})
                .as("%s persisted anchor must survive both registration orders in %s reload",artifact,mode).isInstanceOf(IllegalStateException.class).hasMessageContaining("移位");
        assertThat(cfg.all(WorldTargetCfg.class)).isEqualTo(before);
    }
}
