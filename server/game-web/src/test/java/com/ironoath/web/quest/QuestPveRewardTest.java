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
/** 资格高档为明确总线事实夹具；生产胜败和扫荡由端点真实内核回归负责，不冒充150场真人流程。 */
@SpringBootTest @ActiveProfiles("test")
class QuestPveRewardTest {
    @Autowired PlayerInitService init;@Autowired QuestAppService quests;@Autowired QuestEvents events;
    @Autowired TimeService time;@Autowired PlayerRepository players;
    private String id(){return UUID.randomUUID().toString();}
    private String player(){return init.init(new PlayerInitReq(id(),"pve-"+id(),"胜利成就",time.serverNow(),"")).playerId();}
    private long gold(String uid){return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();}
    private com.ironoath.web.dto.generated.QuestView view(String uid,int tier){
        var result=quests.list(uid).quests().stream().filter(q->q.questId().equals("quest_achievement_pve_"+tier)).findFirst();
        assertThat(result).as("批准的三档PvE成就配置").isPresent();return result.orElseThrow();
    }
    private void win(String uid,int n){events.progressOnce(uid,GoalType.valueOf("PVE_WIN"),null,1,time.serverNow(),"fixture-victory:"+n);}
    @Test void threeApprovedTiersArePermanentIndependentAndPayExactly300OnlyOnClaim(){
        String uid=player();long before=gold(uid);view(uid,10);
        for(int n=1;n<=150;n++){win(uid,n);win(uid,n);}
        assertThat(gold(uid)).isEqualTo(before);
        int[] goals={10,50,150},amounts={50,100,150};
        for(int i=0;i<3;i++){
            var preview=view(uid,goals[i]);assertThat(preview.current()).isEqualTo(goals[i]);assertThat(preview.claimable()).isTrue();
            assertThat(preview.rewards().get(0).count()).isEqualTo(amounts[i]);
            quests.claim(uid,new QuestClaimReq(id(),preview.questId(),null));
            assertThat(view(uid,goals[i]).claimed()).isTrue();
            assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),preview.questId(),null))).isInstanceOf(RuntimeException.class);
        }
        assertThat(gold(uid)).isEqualTo(before+300);
    }
    @Test void unqualifiedPveDoesNotAutomaticallyCreditAndRepeatedFactsAreScopedToEachUid(){
        String a=player(),b=player();long ga=gold(a),gb=gold(b);view(a,10);view(b,10);
        for(int n=1;n<=9;n++){win(a,n);win(a,n);win(b,n);}
        assertThat(view(a,10).current()).isEqualTo(9);assertThat(view(b,10).current()).isEqualTo(9);
        assertThat(view(a,10).claimable()).isFalse();assertThat(gold(a)).isEqualTo(ga);assertThat(gold(b)).isEqualTo(gb);
    }
}
