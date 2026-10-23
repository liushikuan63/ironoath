package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.world.*;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.social.SocialStore;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 配置兵只用于未交战目标；保存的NPC余兵/显式空/退出状态各有独立语义。 */
class NeutralTargetDefenderTest {
    final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    final InMemoryNeutralTargetGarrisonStore neutral=new InMemoryNeutralTargetGarrisonStore();
    final InMemoryTargetOccupationStore owners=new InMemoryTargetOccupationStore();
    final InMemoryMarchStore marches=new InMemoryMarchStore(32);
    final SocialStore social=mock(SocialStore.class);
    final WorldTargets.Target target=WorldTargets.from(configs).at(com.ironoath.core.world.Coord.of(256,216));
    NeutralTargetGarrisonStore.Snapshot state(long count,long version,String op){return new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,Map.of("unit_infantry_t4",100L),count==0?Map.of():Map.of("unit_infantry_t4",count),NeutralTargetGarrisonStore.Phase.NEUTRAL,100+version,op,version);}
    StrategicTargetDefender service(NeutralTargetGarrisonStore store){return new StrategicTargetDefender(configs,owners,marches,social,store);}
    @Test void noNationUsesPersistedThreeSurvivorsThenExplicitEmpty(){
        var s=service(neutral);assertThat(s.read(target).initialNpc()).isTrue();
        neutral.compareAndSet(state(3,1,"first-loss"),0);
        var remaining=s.read(target);assertThat(remaining.troops()).containsExactlyEntriesOf(Map.of("unit_infantry_t4",3L));
        assertThat(remaining.neutral()).isEqualTo(state(3,1,"first-loss"));assertThat(remaining.initialNpc()).isFalse();assertThat(remaining.occupation()).isNull();
        neutral.compareAndSet(state(0,2,"second-loss"),1);assertThat(s.read(target).troops()).isEmpty();verifyNoInteractions(social);
    }
    @Test void retiredWithoutOwnerDoesNotRefillAndOwnedEmptyRemainsEmpty(){
        var retired=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,Map.of("unit_infantry_t4",100L),Map.of(),NeutralTargetGarrisonStore.Phase.RETIRED,100,"capture",1);
        neutral.compareAndSet(retired,0);var s=service(neutral);
        assertThatThrownBy(()->s.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("退出中立");
        owners.compareAndSet(new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,"owned-N","验收国",100,"capture",1,TargetOccupationStore.Garrison.empty()),0);
        assertThat(s.read(target).troops()).isEmpty();assertThat(s.read(target).neutral()).isEqualTo(retired);
    }
    @Test void activeNpcAndOwnerCannotFormMixedInput(){
        neutral.compareAndSet(state(3,1,"loss"),0);var s=service(neutral);
        owners.compareAndSet(new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,"owned-N","验收国",100,"capture",1,TargetOccupationStore.Garrison.empty()),0);
        assertThatThrownBy(()->s.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("中立守军");
    }
    @Test void concurrentNeutralChangeIsRejected(){
        var before=state(3,1,"first");neutral.compareAndSet(before,0);var changing=spy(neutral);
        when(changing.findByTargetIds(List.of("gate_north"))).thenReturn(Map.of("gate_north",before),Map.of("gate_north",state(2,2,"second")));
        assertThatThrownBy(()->service(changing).read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("已变化");
    }
    @Test void movedOrOrphanedNeutralBlocksStartup(){
        var moved=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,255,216,Map.of("unit_infantry_t4",100L),Map.of(),NeutralTargetGarrisonStore.Phase.NEUTRAL,100,"loss",1);
        neutral.compareAndSet(moved,0);assertThatThrownBy(()->service(neutral)).isInstanceOf(IllegalStateException.class).hasMessageContaining("移位");
    }
    @Test void savedNeutralAnchorBlocksConfigMoveWithoutMutatingOldCatalog()throws Exception{
        neutral.compareAndSet(state(3,1,"first"),0);service(neutral);
        var before=configs.all(WorldTargetCfg.class);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !java.nio.file.Files.isRegularFile(root.resolve("contract/config/world_target.json")))root=root.getParent();
        assertThat(root).as("find actual checkout without assuming module cwd").isNotNull();
        var json=mapper.readTree(java.nio.file.Files.readString(root.resolve("contract/config/world_target.json")));
        for(var row:json.get("rows"))if(row.get("id").asText().equals("gate_north"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("x",255);
        assertThatThrownBy(()->configs.reload("world_target",WorldTargetCfg.class,json.toString())).isInstanceOf(IllegalStateException.class).hasMessageContaining("移位");
        assertThat(configs.all(WorldTargetCfg.class)).isEqualTo(before);
    }
    @Test void mongoRebuiltStoreFeedsActualNpcSurvivors(){
        try(var db=TestMongo.tryOpen()){
            assertThat(db).isNotNull();var repo=new com.ironoath.web.store.mongo.MongoNeutralTargetGarrisonStore(db.template());
            assertThat(repo.compareAndSet(state(3,1,"owned-loss"),0)).isTrue();
            var input=service(new com.ironoath.web.store.mongo.MongoNeutralTargetGarrisonStore(db.template())).read(target);
            assertThat(input.occupation()).isNull();assertThat(input.troops()).containsExactlyEntriesOf(Map.of("unit_infantry_t4",3L));
            assertThat(input.neutral().operationId()).isEqualTo("owned-loss");assertThat(input.initialNpc()).isFalse();
        }
    }
}
