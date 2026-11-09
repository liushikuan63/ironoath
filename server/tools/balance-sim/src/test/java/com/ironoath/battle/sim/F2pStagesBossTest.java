package com.ironoath.battle.sim;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.battle.BattleRules;
import com.ironoath.config.ConfigRegistry;

/** BOSS wiring is tested with private synthetic config; not a seven-day-player acceptance. */
class F2pStagesBossTest {
    @Test void configuredCounterBossHasPositiveActualRoundTriggerEvidence(@TempDir Path temp) throws Exception {
        ConfigRegistry configs=fixture(temp,"stage_01_01");
        String output=capture(configs);String observed=output.lines().filter(s->s.startsWith("机制 stage_01_01：")).findFirst().orElse("");
        assertThat(observed).matches("机制 stage_01_01：COUNTER_STRIKE，实际触发 [1-9][0-9]* 次");
        assertThat(output).contains("B09-4 未验","不构成全三星结论").doesNotContain("bossMechanic 未装配");
    }
    @Test void forbiddenInfantryStageDoesNotExecuteItsBoss(@TempDir Path temp) throws Exception {
        String output=capture(fixture(temp,"stage_03_03"));
        assertThat(output.lines().filter(s->s.startsWith("stage_03_03")).findFirst().orElseThrow()).contains("准入不满足");
        assertThat(output).doesNotContain("机制 stage_03_03：");
    }
    @Test void resolverUsesOriginalParametersForAllMechanismKinds(){
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var resolver=new BattleParamsResolver(configs);
        for(var type:com.ironoath.config.cfg.StageCfg.BossMechanic.values()){
            var expected=switch(type){case NONE->com.ironoath.battle.BossMechanic.none();case REINFORCEMENT->com.ironoath.battle.BossMechanic.reinforcement(configs.longParam("BOSS_REINFORCE_INTERVAL_ROUNDS"),configs.fixedParam("BOSS_REINFORCE_RATIO"));case SHIELD_PHASE->com.ironoath.battle.BossMechanic.shieldPhase(configs.fixedParam("BOSS_SHIELD_HP_THRESHOLD"),configs.fixedParam("BOSS_SHIELD_DEF_BONUS"));case COUNTER_STRIKE->com.ironoath.battle.BossMechanic.counterStrike(configs.fixedParam("BOSS_COUNTER_REFLECT_RATIO"));};
            assertThat(resolver.bossMechanic(type)).isEqualTo(expected);
        }
        assertThatThrownBy(()->resolver.bossMechanic(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void resolverUsesRealReloadWithoutMutatingPreviouslyBuiltInput(@TempDir Path temp) throws Exception {
        var configs=fixture(temp,"stage_01_01");var resolver=new BattleParamsResolver(configs);var first=resolver.bossMechanic(com.ironoath.config.cfg.StageCfg.BossMechanic.COUNTER_STRIKE);
        var mapper=new ObjectMapper();var root=(ObjectNode)mapper.readTree(Files.readString(temp.resolve("global.json")));root.put("version",root.path("version").asLong()+1);
        for(var row:root.path("rows"))if("BOSS_COUNTER_REFLECT_RATIO".equals(row.path("id").asText()))((ObjectNode)row).put("value","0.35");
        configs.reload("global",com.ironoath.config.model.GlobalCfg.class,mapper.writeValueAsString(root));
        assertThat(resolver.bossMechanic(com.ironoath.config.cfg.StageCfg.BossMechanic.COUNTER_STRIKE).ratioFixed()).isEqualTo(3500);assertThat(first.ratioFixed()).isEqualTo(10000);
    }
    private static ConfigRegistry fixture(Path temp,String id) throws Exception {
        try(var stream=Files.list(configDirectory())){
            for(Path file:stream.filter(p->p.getFileName().toString().endsWith(".json")).toList())Files.copy(file,temp.resolve(file.getFileName()));
        }
        var mapper=new ObjectMapper();var file=temp.resolve("stage.json");var root=mapper.readTree(Files.readString(file));
        for(var row:root.path("rows"))if(id.equals(row.path("id").asText()))((ObjectNode)row).put("bossMechanic","COUNTER_STRIKE");
        Files.writeString(file,mapper.writeValueAsString(root),StandardCharsets.UTF_8);
        // Private fixture uses full reflection so the actual casualty counter cannot round down to zero.
        var global=temp.resolve("global.json");var globals=mapper.readTree(Files.readString(global));
        for(var row:globals.path("rows"))if("BOSS_COUNTER_REFLECT_RATIO".equals(row.path("id").asText()))((ObjectNode)row).put("value","1.0");
        Files.writeString(global,mapper.writeValueAsString(globals),StandardCharsets.UTF_8);return ConfigRegistry.loadFromDirectory(temp);
    }
    static Path configDirectory(){
        for(Path ancestor=Path.of("").toAbsolutePath();ancestor!=null;ancestor=ancestor.getParent()){
            Path candidate=ancestor.resolve("contract/config");if(Files.isDirectory(candidate))return candidate;
        }
        throw new IllegalStateException("project config directory missing");
    }
    private static String capture(ConfigRegistry configs) throws Exception {
        Method print=BalanceCli.class.getDeclaredMethod("printF2pStages",ConfigRegistry.class,BattleParamsResolver.class,BattleRules.class,Map.class,Map.class,int.class);print.setAccessible(true);
        var resolver=new BattleParamsResolver(configs);var bytes=new ByteArrayOutputStream();var original=System.out;
        try(var stream=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setOut(stream);Object completed=print.invoke(null,configs,resolver,resolver.rules(),resolver.unitStats(1),Map.of("runs","1","troops","1000"),1);
            assertThat(completed).isEqualTo(true);return bytes.toString(StandardCharsets.UTF_8);
        }finally{System.setOut(original);}
    }
}
