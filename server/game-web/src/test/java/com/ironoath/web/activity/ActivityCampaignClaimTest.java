package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;

@SpringBootTest @ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ActivityCampaignClaimTest {
    private static final String ID="activity_theme_major";
    private static final long NOW=OffsetDateTime.parse("2026-11-03T12:00:00+08:00").toInstant().toEpochMilli();
    @Autowired ActivityAppService activities;
    @Autowired ActivityProgressStore store;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired QuestEvents events;
    @SpyBean TimeService time;
    @SpyBean RewardService rewards;
    private String uid;
    @BeforeEach void setup() {
        doReturn(NOW).when(time).serverNow();
        uid=init.init(new PlayerInitReq(id(),"campaign-"+id(),"主题活动验收",NOW,"")).playerId();
        for(int i=0;i<40;i++) events.progressOnce(uid,GoalType.KILL_MONSTER,"monster_1",1,NOW,"solo-monster:fixture-"+i);
        assertThat(row().goal()).isEqualTo(40);
        assertThat(row().progress()).isEqualTo(40);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private com.ironoath.web.dto.generated.ActivityView row() { return activities.list(uid).activities().stream().filter(v -> ID.equals(v.id())).findFirst().orElseThrow(); }
    private long gold() { return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current(); }
    private void lose(boolean after) {
        var once=new AtomicBoolean(true);
        doAnswer(call -> {
            RewardContext ctx=call.getArgument(2);
            if (ID.equals(ctx.sourceRef()) && once.getAndSet(false)) {
                if (after) call.callRealMethod();
                throw new IllegalStateException("fixture: "+(after?"wallet committed reply lost":"before wallet commit"));
            }
            return call.callRealMethod();
        }).when(rewards).grant(anyString(),any(),any(RewardContext.class));
    }
    @Test void preWalletFailureKeepsQualificationAndCanRetryForOriginal960() {
        long before=gold(); lose(false); String request=id();
        assertThatThrownBy(() -> activities.claim(uid,new ActivityClaimReq(ID,request))).isInstanceOf(IllegalStateException.class);
        assertThat(row().claimed()).isFalse(); assertThat(gold()).isEqualTo(before);
        activities.claim(uid,new ActivityClaimReq(ID,request));
        assertThat(gold()).isEqualTo(before+960);
    }
    @Test void postWalletFailureRecoversClaimedProjectionAndNeverRegrantsWithAnotherRequest() {
        long before=gold(); lose(true);
        assertThatThrownBy(() -> activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        assertThat(players.findByPlayerId(uid).orElseThrow().goldClaims().values())
                .singleElement().satisfies(receipt -> { assertThat(receipt.source()).isEqualTo("free_gold"); assertThat(receipt.requested()).isEqualTo(960); });
        assertThat(row().claimed()).isTrue();
        assertThat(gold()).isEqualTo(before+960);
        assertThatThrownBy(() -> activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(gold()).isEqualTo(before+960);
    }
}
