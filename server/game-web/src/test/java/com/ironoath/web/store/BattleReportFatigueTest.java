package com.ironoath.web.store;

import com.ironoath.battle.*;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.battle.*;
import com.ironoath.web.nation.*;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real report service/WarStore; isolated TestMongo. Asymmetric result is a declared service fixture, not kernel balance evidence. */
class BattleReportFatigueTest {
    private static final long T0=1_800_000_000_000L;
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @ParameterizedTest
    @CsvSource({"MEMORY,2,7","MONGO,2,7","MEMORY,0,7","MONGO,0,7","MEMORY,7,0","MONGO,7,0","MEMORY,2,2","MONGO,2,2"})
    void reportCreditsOpponentDeadButFatiguesOnlyOwnWounded(String backend,long atkWounded,long defWounded) {
        var cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var rules=new WarRulesAssembler(cfg);
        WarStore wars=backend.equals("MONGO")?new MongoWarStore(db.template(),rules):new InMemoryWarStore(rules);wars.clear();
        BattleReportStore reports=backend.equals("MONGO")?new MongoBattleReportStore(db.template()):new InMemoryBattleReportStore();reports.clear();
        var board=new WarScoreBoard(rules.rules(),T0,"owned-NA",Long.MAX_VALUE);board.registerNation("owned-NA");board.registerNation("owned-ND");assertThat(wars.insertIfAbsent(board)).isTrue();
        var social=mock(SocialAppService.class);when(social.nicknameOf("owned-A")).thenReturn("攻方");when(social.nicknameOf("owned-D")).thenReturn("守方");
        var membership=mock(NationMembership.class);when(membership.nationIdOf("owned-A")).thenReturn("owned-NA");when(membership.nationIdOf("owned-D")).thenReturn("owned-ND");
        var ranks=mock(RankBoardService.class);var service=new BattleReportService(cfg,reports,mock(TimeService.class),social,ranks,wars,membership);
        var result=new BattleResult(Winner.ATTACKER,List.of(),0,Map.of(),Map.of(),3,atkWounded,0,11,defWounded,0,Map.of(),0,2383,List.of());
        var attack=service.record("owned-A","owned-A","owned-D","守方","攻方",BattleType.SIEGE,List.of(),List.of(),result,T0+1);
        var defense=service.record("owned-D","owned-A","owned-D","守方","攻方",BattleType.SIEGE,List.of(),List.of(),result,T0+1);
        WarStore rebuilt=backend.equals("MONGO")?new MongoWarStore(db.template(),rules):wars;var back=rebuilt.findLatest().orElseThrow();
        assertThat(back.fatigueOf("owned-A")).as("攻方只负担本人伤兵").isEqualTo(Math.min(rules.rules().fatigueMax(),atkWounded*rules.rules().fatiguePerWounded()));
        assertThat(back.fatigueOf("owned-D")).as("守方只负担本人伤兵").isEqualTo(Math.min(rules.rules().fatigueMax(),defWounded*rules.rules().fatiguePerWounded()));
        assertThat(back.fatigueOf("owned-NA")).isZero();assertThat(back.fatigueOf("owned-ND")).isZero();
        assertThat(back.killsBy("owned-A")).isEqualTo(11);assertThat(back.killsBy("owned-D")).isEqualTo(3);assertThat(back.totalKills()).isEqualTo(14);
        verify(ranks).reportKills("owned-A","攻方",11);verify(ranks).reportKills("owned-D","守方",3);verifyNoMoreInteractions(ranks);
        assertThat(reports.findById(attack.reportId()).orElseThrow().result()).isEqualTo(result);assertThat(reports.findById(defense.reportId()).orElseThrow().result()).isEqualTo(result);
    }
}
