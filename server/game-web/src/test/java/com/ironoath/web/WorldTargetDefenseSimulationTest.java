package com.ironoath.web;

import com.ironoath.battle.*;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.battle.BattleRulesAssembler;
import com.ironoath.web.service.WorldTargets;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 候选防守的无武将/无科技平原基线采样；这是内核模拟，不是集结生产链或最终平衡验收。 */
class WorldTargetDefenseSimulationTest {
    @Test void sampleCandidateGarrisonsWithIndependentAttackerAndDefenderStats() {
        var configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var factory = new BattleArmyFactory(configs);
        var rules = new BattleRulesAssembler(configs).rules();
        var catalog = WorldTargets.from(configs);
        for (String id : List.of("fortress_ne_1", "gate_north", "capital_center")) {
            var target = catalog.targets().stream().filter(t -> t.definition().id().equals(id)).findFirst().orElseThrow();
            var d = target.defender();
            var defense = factory.fold(Map.of("unit_infantry_t"+d.unitTier(),d.infantryCount(),
                    "unit_cavalry_t"+d.unitTier(),d.cavalryCount(),"unit_archer_t"+d.unitTier(),d.archerCount(),
                    "unit_siege_t"+d.unitTier(),d.siegeCount()));
            var defender = factory.toSide("candidate-defender", defense, List.of(),0L,TechBonus.none(),0L);
            for (int tier : List.of(3,4,5)) for (long total : List.of(10_000L,20_000L,50_000L,100_000L,200_000L,500_000L)) {
                var offense = factory.fold(Map.of("unit_infantry_t"+tier,total*2/5,
                        "unit_cavalry_t"+tier,total/5,"unit_archer_t"+tier,total/5,"unit_siege_t"+tier,total/5));
                var attacker = factory.toSide("sample-attacker",offense,List.of(),0L,TechBonus.none(),total);
                int wins=0; long losses=0;
                for (long seed=1; seed<=20; seed++) {
                    var input = new BattleInput(attacker,defender,TerrainType.PLAIN,seed,BattleType.PVE,
                            BattleModifier.none(),BattleModifier.none(),offense.stats(),defense.stats(),rules,null);
                    var result = BattleSimulator.simulate(input);
                    if (result.winner()==Winner.ATTACKER) wins++;
                    long survivor = result.atkSurvivors().values().stream().mapToLong(Long::longValue).sum();
                    assertThat(survivor+result.atkDead()+result.atkWounded()).isEqualTo(total);
                    losses += total-survivor;
                    if (seed==1) assertThat(BattleSimulator.simulate(input)).isEqualTo(result);
                }
                System.out.printf("TARGET_SIM,%s,T%d,%d,wins=%d/20,meanLoss=%d,defenderTroops=%d,power=%d%n",
                        id,tier,total,wins,losses/20,target.troops(),d.power());
            }
        }
    }
}
