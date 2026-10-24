package com.ironoath.web.nation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.world.TargetOccupationStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategicFortressOutputReadScopeTest {
    private final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));

    @Test void onlyTheEightConfiguredFortressesContributeAndOwnershipChangesAtItsTimestamp() {
        var targets=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .toList();
        assertThat(targets).hasSize(8);
        var store=new InMemoryTargetOccupationStore();String firstId=targets.get(0).definition().id();
        for(var target:targets) {
            var kind=WorldGenerator.EntityType.valueOf(target.definition().kind().name());
            if(target.definition().id().equals(firstId)) {
                var old=new TargetOccupationStore.Snapshot(firstId,kind,target.coord().x(),target.coord().y(),
                        "nation-a","A",100,"capture-a",1,TargetOccupationStore.Garrison.empty());
                var current=new TargetOccupationStore.Snapshot(firstId,kind,target.coord().x(),target.coord().y(),
                        "nation-b","B",200,"capture-b",2,TargetOccupationStore.Garrison.empty(),
                        old.ownershipHistoryClosedAt(200));
                assertThat(store.compareAndSet(old,0)).isTrue();assertThat(store.compareAndSet(current,1)).isTrue();
            } else {
                var owner=new TargetOccupationStore.Snapshot(target.definition().id(),kind,target.coord().x(),
                        target.coord().y(),"nation-a","A",50,"capture-"+target.definition().id(),1,
                        TargetOccupationStore.Garrison.empty());
                assertThat(store.compareAndSet(owner,0)).isTrue();
            }
        }

        var read=StrategicFortressOutputReadScope.capture(configs,store);

        assertThat(read.outputPercent("nation-a",75,false)).isEqualTo(1_400L);
        assertThat(read.outputPercent("nation-a",150,false)).isEqualTo(1_600L);
        assertThat(read.outputPercent("nation-a",200,true)).isEqualTo(1_600L);
        assertThat(read.outputPercent("nation-a",200,false)).isEqualTo(1_400L);
        assertThat(read.outputPercent("nation-b",200,false)).isEqualTo(200L);
        assertThat(read.outputPercent(null,200,false)).isZero();
        assertThat(read.changeTimes(0,250)).containsExactly(50L,100L,200L);
    }
}
