package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.*;
import com.ironoath.web.store.memory.InMemoryWarStore;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real owned stores, original whole event; no report/COMBAT producer or player kill-distribution claim. */
class WarBattleEventStoreTest {
 enum Backend{MEMORY,MONGO}
 static final long T0=1_800_000_000_000L;
 static TestMongo db;static WarRulesAssembler rules;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();rules=new WarRulesAssembler(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoWarStore(db.template(),rules).clear();}
 WarStore store(Backend b){return b==Backend.MEMORY?new InMemoryWarStore(rules):new MongoWarStore(db.template(),rules);}
 WarScoreBoard original(){var b=new WarScoreBoard(rules.rules(),T0,"owned-NA",T0+1000);b.registerNation("owned-NA");b.registerNation("owned-NB");return b;}
 WarScoreBoard.BattleEvent event(WarScoreBoard b){return b.freezeBattleEvent("gate_north",1,"owned-battle-1",T0+1,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",11,2),new WarScoreBoard.BattleImpact("owned-B","owned-NB",3,7)));}
 @ParameterizedTest @EnumSource(Backend.class)
 void wholeEventReplaysOnOriginalWarEvenWhenANewerWarExistsAndStaleSaveCannotEraseIt(Backend backend){
  var s=store(backend);var b=original();var event=event(b);assertThat(s.insertIfAbsent(b)).isTrue();var stale=s.findByStartedAt(T0).orElseThrow();
  assertThat(s.recordBattleEvent(event)).isEqualTo(event);var first=s.findByStartedAt(T0).orElseThrow().toSnapshot();
  var newer=new WarScoreBoard(rules.rules(),T0+100,"owned-NC",Long.MAX_VALUE);newer.registerNation("owned-NC");assertThat(s.insertIfAbsent(newer)).isTrue();
  var rebuilt=backend==Backend.MONGO?store(backend):s;assertThat(rebuilt.recordBattleEvent(event)).isEqualTo(event);
  assertThat(rebuilt.findByStartedAt(T0).orElseThrow().toSnapshot()).isEqualTo(first);assertThat(rebuilt.findByStartedAt(T0+100).orElseThrow().totalKills()).isZero();
  assertThatThrownBy(()->s.save(stale)).isInstanceOf(IllegalStateException.class);var back=rebuilt.findByStartedAt(T0).orElseThrow();
  assertThat(back.killsBy("owned-A")).isEqualTo(11);assertThat(back.killsBy("owned-B")).isEqualTo(3);assertThat(back.fatigueOf("owned-A")).isEqualTo(Math.min(rules.rules().fatigueMax(),2*rules.rules().fatiguePerWounded()));
  assertThat(back.fatigueOf("owned-B")).isEqualTo(Math.min(rules.rules().fatigueMax(),7*rules.rules().fatiguePerWounded()));assertThat(back.totalKills()).isEqualTo(14);assertThat(back.battleEvent("gate_north")).isEqualTo(event);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void missingOriginalWarOrMissingReceiptInClosedWarCannotBecomeSilentCompletion(Backend backend){
  var s=store(backend);var b=original();var event=event(b);assertThatThrownBy(()->s.recordBattleEvent(event)).isInstanceOf(IllegalStateException.class);
  b.expirePreparation(T0+1000);assertThat(s.insertIfAbsent(b)).isTrue();assertThatThrownBy(()->s.recordBattleEvent(event)).isInstanceOf(IllegalStateException.class);assertThat(s.findByStartedAt(T0).orElseThrow().totalKills()).isZero();
 }
}
