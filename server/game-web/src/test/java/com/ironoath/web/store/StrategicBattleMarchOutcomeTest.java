package com.ironoath.web.store;

import com.ironoath.battle.Winner;
import com.ironoath.core.march.*;
import com.ironoath.web.battle.*;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.mongo.MongoMarchStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 实际捕获内核与自有March仓储；DRAW使用私有注册表max1/gap1，不改产品配置或宣称默认平衡。 */
class StrategicBattleMarchOutcomeTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"march");}

    @ParameterizedTest @CsvSource({"MEMORY,NPC_WIN","MONGO,NPC_WIN","MEMORY,NPC_LOSS","MONGO,NPC_LOSS","MEMORY,PLAYER","MONGO,PLAYER","MEMORY,DRAW","MONGO,DRAW"})
    void frozenBattleProjectsExactHeldReturningOrDeletedCustody(String backend,String scenario)throws Exception {
        boolean player=scenario.equals("PLAYER")||scenario.equals("DRAW");boolean hospital=scenario.equals("NPC_WIN")||scenario.equals("DRAW");
        var battle=StrategicCombatSlotTest.battle(2382,player,hospital,scenario.equals("DRAW"));var plan=TargetOperationStore.CombatPlan.create(battle,1);
        if(scenario.equals("DRAW"))assertThat(battle.result().winner()).isEqualTo(Winner.DRAW);
        if(scenario.equals("NPC_WIN"))assertThat(battle.result().winner()).isEqualTo(Winner.ATTACKER);
        MarchRepository repo=backend.equals("MEMORY")?new InMemoryMarchStore(32):new MongoMarchStore(db.template(),32);
        for(var side:StrategicRallyCasualties.Side.values()) {
            var projected=StrategicBattleMarchOutcome.project(plan,side);
            var original=side==StrategicRallyCasualties.Side.ATTACKER?battle.attacker():battle.defender();
            if(original==null){assertThat(projected).isEmpty();continue;}
            var outcome=projected.orElseThrow();assertThat(outcome.before()).isEqualTo(original.march());assertThat(outcome.beforeVersion()).isEqualTo(original.marchVersion());
            assertThat(outcome.plan()).isSameAs(plan);assertThat(outcome.side()).isEqualTo(side);
            var before=March.fromSnapshot(original.march());assertThat(repo.insertIfAbsent(before)).isTrue();
            // 自有仓储夹具推进到捕获载荷指定的真实CAS版本；不涉及玩家存档/真实发兵授权。
            while(repo.versionOf(before.id())<original.marchVersion())repo.save(before,repo.versionOf(before.id()));
            assertThat(repo.versionOf(before.id())).isEqualTo(original.marchVersion());
            long survivors=original.survivorsByPlayer().values().stream().flatMap(x->x.values().stream()).mapToLong(Long::longValue).sum();
            if(survivors==0) {
                assertThat(outcome.disposition()).isEqualTo(StrategicBattleMarchOutcome.Disposition.ANNIHILATED);assertThat(outcome.after()).isNull();
                assertThat(repo.deleteIfVersion(before.id(),outcome.beforeVersion())).isTrue();assertThat(repo.findById(before.id())).isEmpty();
            } else {
                var after=March.fromSnapshot(outcome.after());assertThat(after.garrisonTroopsByPlayer()).isEqualTo(original.survivorsByPlayer());
                boolean hold=side==StrategicRallyCasualties.Side.ATTACKER?battle.result().winner()==Winner.ATTACKER:battle.result().winner()!=Winner.ATTACKER;
                assertThat(outcome.disposition()).isEqualTo(hold?StrategicBattleMarchOutcome.Disposition.HOLD:StrategicBattleMarchOutcome.Disposition.RETURN);
                assertThat(after.status()).isEqualTo(hold?March.Status.STATIONED:March.Status.RETURNING);
                if(!hold){assertThat(after.returnArriveAt()).isEqualTo(plan.battle().happenedAt()+before.arriveAt()-before.startAt());assertThat(after.snapshot().returnFrom()).isEqualTo(before.to());}
                assertThat(after.hasGarrisonOwnership()).isTrue();assertThat(after.load()).isEqualTo(before.load());assertThat(after.loadCap()).isEqualTo(before.loadCap());
                repo.save(after,outcome.beforeVersion());assertThat(repo.findById(before.id()).orElseThrow().snapshot()).isEqualTo(outcome.after());
                assertThatThrownBy(()->outcome.after().garrisonTroopsByPlayer().clear()).isInstanceOf(UnsupportedOperationException.class);
            }
            var mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder().disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
            var decoded=mapper.readValue(mapper.writeValueAsString(outcome),StrategicBattleMarchOutcome.class);assertThat(decoded).isEqualTo(outcome);
            assertThatThrownBy(()->new StrategicBattleMarchOutcome(1,plan,side,outcome.disposition(),original.march())).isInstanceOf(IllegalArgumentException.class);
            assertThat(original.march()).isEqualTo(before.snapshot());
        }
    }
}
