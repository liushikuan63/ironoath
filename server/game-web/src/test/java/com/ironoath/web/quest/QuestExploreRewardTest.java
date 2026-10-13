package com.ironoath.web.quest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.service.PlayerInitService;
/** 高档为明确的去重区域总线夹具；实际到达由WorldEndpointTest与隔离运行验证，不冒充50次实走。 */
@SpringBootTest @ActiveProfiles("test")
class QuestExploreRewardTest {
    @Autowired PlayerInitService init;@Autowired QuestAppService quests;@Autowired QuestEvents events;
    @Autowired TimeService time;@Autowired PlayerRepository players;
    private String id(){return UUID.randomUUID().toString();}
    private String player(){return init.init(new PlayerInitReq(id(),"explore-"+id(),"探索成就",time.serverNow(),"")).playerId();}
    private long gold(String uid){return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();}
    private com.ironoath.web.dto.generated.QuestView view(String uid,int tier){
        var found=quests.list(uid).quests().stream().filter(q->q.questId().equals("quest_achievement_explore_"+tier)).findFirst();
        assertThat(found).as("批准探索三档配置").isPresent();return found.orElseThrow();
    }
    private void region(String uid,int n){events.progressOnce(uid,GoalType.valueOf("EXPLORE_CHUNK"),null,1,time.serverNow(),"explore:chunk:fixture:"+n);}
    @Test void threeTiersPayExactly300OnlyAfterExplicitIndependentClaims() {
        String uid=player();long before=gold(uid);view(uid,5);
        for(int n=1;n<=50;n++){region(uid,n);region(uid,n);}
        assertThat(gold(uid)).isEqualTo(before);int[] goals={5,20,50},amounts={50,100,150};
        for(int i=0;i<3;i++){
            var q=view(uid,goals[i]);assertThat(q.current()).isEqualTo(goals[i]);assertThat(q.claimable()).isTrue();
            assertThat(q.rewards().get(0).count()).isEqualTo(amounts[i]);
            quests.claim(uid,new QuestClaimReq(id(),q.questId(),null));assertThat(view(uid,goals[i]).claimed()).isTrue();
            assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),q.questId(),null))).isInstanceOf(RuntimeException.class);
        }
        assertThat(gold(uid)).isEqualTo(before+300);
    }
    @Test void repeatedRegionFactsRemainUidScopedAndUnqualifiedWithoutMoney() {
        String a=player(),b=player();long ga=gold(a),gb=gold(b);view(a,5);view(b,5);
        for(int n=1;n<=4;n++){region(a,n);region(a,n);region(b,n);}
        assertThat(view(a,5).current()).isEqualTo(4);assertThat(view(b,5).current()).isEqualTo(4);
        assertThat(view(a,5).claimable()).isFalse();assertThat(gold(a)).isEqualTo(ga);assertThat(gold(b)).isEqualTo(gb);
    }
}
