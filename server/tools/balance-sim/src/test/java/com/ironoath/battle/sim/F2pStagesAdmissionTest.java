package com.ironoath.battle.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.battle.BattleRules;
import com.ironoath.config.ConfigRegistry;

/** Mechanical diagnostic correctness, never a fabricated lawful seven-day profile. */
class F2pStagesAdmissionTest {
    @Test void currentCavalryOnlyStageRejectsTheExplicitInfantryDiagnosticInput() throws Exception {
        String text=capture(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));
        assertThat(line(text,"stage_03_03")).contains("准入不满足").doesNotContain("%");
    }
    @Test void rangedOnlyStageAlsoRejectsExplicitInfantry(@TempDir Path temp) throws Exception {
        ConfigRegistry registry=fixture(temp,"stage_01_01",r->r.put("unitRestriction","RANGED_ONLY"));
        assertThat(line(capture(registry),"stage_01_01")).contains("准入不满足").doesNotContain("%");
    }
    @Test void noSiegeIsAnAttackerRestrictionAndDoesNotEraseTheActualEnemySiege(@TempDir Path temp) throws Exception {
        ConfigRegistry registry=fixture(temp,"stage_03_09",r->{
            long total=r.path("enemyInfantry").asLong()+r.path("enemyCavalry").asLong()+r.path("enemyArcher").asLong()+r.path("enemySiege").asLong();
            r.put("unitRestriction","NO_SIEGE");r.put("enemyInfantry",0);r.put("enemyCavalry",0);r.put("enemyArcher",0);r.put("enemySiege",total);
        });
        var output=new String[1];
        assertThatCode(()->output[0]=capture(registry)).as("legal attacker cannot erase the whole defending army").doesNotThrowAnyException();
        assertThat(line(output[0],"stage_03_09")).endsWith("0.0%").doesNotContain("100.0%");
    }
    private static ConfigRegistry fixture(Path temp,String id,java.util.function.Consumer<ObjectNode> edit) throws Exception {
        try(var files=Files.list(configDirectory())){
            for(Path f:files.filter(p->p.getFileName().toString().endsWith(".json")).toList())Files.copy(f,temp.resolve(f.getFileName()));
        }
        var mapper=new ObjectMapper();var file=temp.resolve("stage.json");var table=mapper.readTree(Files.readString(file));boolean found=false;
        for(var row:table.path("rows"))if(row.path("id").asText().equals(id)){edit.accept((ObjectNode)row);found=true;}
        assertThat(found).isTrue();Files.writeString(file,mapper.writeValueAsString(table),StandardCharsets.UTF_8);
        return ConfigRegistry.loadFromDirectory(temp);
    }
    private static Path configDirectory(){
        for(Path ancestor=Path.of("").toAbsolutePath();ancestor!=null;ancestor=ancestor.getParent()){
            Path candidate=ancestor.resolve("contract/config");if(Files.isDirectory(candidate))return candidate;
        }
        throw new IllegalStateException("project contract/config was not found in the worktree ancestors");
    }
    private static String line(String output,String id){return output.lines().filter(s->s.startsWith(id)).findFirst().orElseThrow();}
    private static String capture(ConfigRegistry configs) throws Exception {
        Method print=BalanceCli.class.getDeclaredMethod("printF2pStages",ConfigRegistry.class,BattleParamsResolver.class,BattleRules.class,Map.class,Map.class,int.class);print.setAccessible(true);
        var resolver=new BattleParamsResolver(configs);var bytes=new ByteArrayOutputStream();var original=System.out;
        try(var stream=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setOut(stream);Object completed=print.invoke(null,configs,resolver,resolver.rules(),resolver.unitStats(1),Map.of("runs","1","troops","1"),1);
            assertThat(completed).isEqualTo(true);return bytes.toString(StandardCharsets.UTF_8);
        }finally{System.setOut(original);}
    }
}
