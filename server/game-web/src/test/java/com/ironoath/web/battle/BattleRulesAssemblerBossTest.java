package com.ironoath.web.battle;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.battle.BossMechanic;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.config.cfg.*;

class BattleRulesAssemblerBossTest {
    @ParameterizedTest @EnumSource(StageCfg.BossMechanic.class)
    void realProductionAdapterUsesExactlyTheOriginalParameters(StageCfg.BossMechanic type){
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var runtime=new BattleRulesAssembler(configs);
        var expected=switch(type){case NONE->BossMechanic.none();case REINFORCEMENT->BossMechanic.reinforcement(configs.longParam("BOSS_REINFORCE_INTERVAL_ROUNDS"),configs.fixedParam("BOSS_REINFORCE_RATIO"));case SHIELD_PHASE->BossMechanic.shieldPhase(configs.fixedParam("BOSS_SHIELD_HP_THRESHOLD"),configs.fixedParam("BOSS_SHIELD_DEF_BONUS"));case COUNTER_STRIKE->BossMechanic.counterStrike(configs.fixedParam("BOSS_COUNTER_REFLECT_RATIO"));};
        assertThat(runtime.bossMechanic(type)).isEqualTo(expected);
    }
    @Test void oldNoneAndNullDeclarationContractsArePreserved(){
        var runtime=new BattleRulesAssembler(null);assertThat(runtime.bossMechanic(StageCfg.BossMechanic.NONE)).isEqualTo(BossMechanic.none());
        assertThatThrownBy(()->runtime.bossMechanic(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void productionAdapterSeesActualReloadButOldBattleMechanicStaysFrozen() throws Exception {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var runtime=new BattleRulesAssembler(configs);var first=runtime.bossMechanic(StageCfg.BossMechanic.COUNTER_STRIKE);long original=first.ratioFixed();
        String changed=original==3500?"0.45":"0.35";var mapper=new ObjectMapper();var root=(ObjectNode)mapper.readTree(Files.readString(configDirectory().resolve("global.json")));root.put("version",root.path("version").asLong()+1);
        for(var row:root.path("rows"))if("BOSS_COUNTER_REFLECT_RATIO".equals(row.path("id").asText()))((ObjectNode)row).put("value",changed);
        configs.reload("global",GlobalCfg.class,mapper.writeValueAsString(root));
        assertThat(runtime.bossMechanic(StageCfg.BossMechanic.COUNTER_STRIKE).ratioFixed()).isEqualTo(FixedPoint.parse(changed));assertThat(first.ratioFixed()).isEqualTo(original);
    }
    private static Path configDirectory(){
        for(Path ancestor=Path.of("").toAbsolutePath();ancestor!=null;ancestor=ancestor.getParent()){
            Path candidate=ancestor.resolve("contract/config");if(Files.isDirectory(candidate))return candidate;
        }
        throw new IllegalStateException("project config directory missing");
    }
}
