package com.ironoath.config;

import com.ironoath.config.cfg.UnitCfg;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 同表守卫是多个持久承诺，注册者后到不能移除先到的保护。 */
class ConfigRegistryGuardCompositionTest {
    private Path directory(){
        for(Path root=Path.of("").toAbsolutePath();root!=null;root=root.getParent())if(Files.isDirectory(root.resolve("contract/config")))return root.resolve("contract/config");
        throw new IllegalStateException("config root missing");
    }
    private String candidate()throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var root=mapper.readTree(Files.readString(directory().resolve("unit.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.get("rows").get(0)).put("name","守卫候选兵");return root.toString();
    }
    @Test void allRegisteredGuardsObserveSameCandidateInOrderBeforePublication()throws Exception{
        var live=ConfigRegistry.loadFromDirectory(directory());var seen=new ArrayList<String>();String before=live.all(UnitCfg.class).get(0).name();
        live.addReloadGuard("unit","first",c->{assertThat(live.all(UnitCfg.class).get(0).name()).isEqualTo(before);seen.add("first:"+c.all(UnitCfg.class).get(0).name());});
        live.addReloadGuard("unit","second",c->{assertThat(live.all(UnitCfg.class).get(0).name()).isEqualTo(before);seen.add("second:"+c.all(UnitCfg.class).get(0).name());});
        live.reload("unit",UnitCfg.class,candidate());
        assertThat(seen).containsExactly("first:守卫候选兵","second:守卫候选兵");assertThat(live.all(UnitCfg.class).get(0).name()).isEqualTo("守卫候选兵");
    }
    @Test void laterVetoPreservesOldPublishedTableAndOriginalFailure()throws Exception{
        var live=ConfigRegistry.loadFromDirectory(directory());var before=live.all(UnitCfg.class);var seen=new ArrayList<String>();var failure=new IllegalStateException("owned second guard veto");
        live.addReloadGuard("unit","first",c->seen.add("first"));live.addReloadGuard("unit","second",c->{seen.add("second");throw failure;});live.addReloadGuard("unit","third",c->seen.add("third"));
        String json=candidate();assertThatThrownBy(()->live.reload("unit",UnitCfg.class,json)).isSameAs(failure);
        assertThat(seen).containsExactly("first","second");assertThat(live.all(UnitCfg.class)).isEqualTo(before);
    }
    @Test void legacyAndSameIdentityReplacementRetainOtherNamedGuards()throws Exception{
        var live=ConfigRegistry.loadFromDirectory(directory());var seen=new ArrayList<String>();
        live.addReloadGuard("unit","named",c->{throw new IllegalStateException("superseded named instance");});
        live.addReloadGuard("unit","named",c->seen.add("named"));
        live.addReloadGuard("unit",c->{throw new IllegalStateException("superseded legacy instance");});
        live.addReloadGuard("unit",c->seen.add("latest-legacy"));
        live.reload("unit",UnitCfg.class,candidate());assertThat(seen).containsExactly("named","latest-legacy");
    }
}
