package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.ActivityView;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;

/** 真实服务/钱包/热更；时间与总线进度为明确夹具，不等同原生玩家验收。 */
@SpringBootTest @ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ActivityInstanceProtectionTest {
    private static final String HUNT = "activity_monster_hunt";
    @Autowired ActivityAppService activities;
    @Autowired ActivityProgressStore store;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired QuestEvents events;
    @Autowired ConfigRegistry configs;
    @Autowired com.ironoath.web.battlepass.BattlePassService pass;
    @SpyBean TimeService time;
    private ObjectNode table;
    private long now;

    @BeforeEach void setup() throws Exception {
        now = time.serverNow();
        doReturn(now).when(time).serverNow();
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contract/config/activity.json"))) root = root.getParent();
        table = (ObjectNode) JsonUtils.mapper().readTree(Files.readString(root.resolve("contract/config/activity.json")));
    }
    private String player() {
        return init.init(new PlayerInitReq(id(), "instance-"+id(), "活动保护验收", now, "")).playerId();
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private ActivityView row(String uid) { return activities.list(uid).activities().stream().filter(v -> v.id().equals(HUNT)).findFirst().orElseThrow(); }
    private long gold(String uid) { return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current(); }
    private void event(String uid, long count) { events.progress(uid, GoalType.KILL_MONSTER, "m1", count, now); }
    private void edit() {
        for (var item : table.withArray("rows")) if (item.get("id").asText().equals(HUNT)) {
            ObjectNode r = (ObjectNode)item;
            r.put("name", "未来剿匪令").put("conditionValue", 80).put("durationDays", 1)
                    .put("rewardGold", 960).put("battlePassPoints", 1).put("rewardItemCount", 1);
        }
        reload();
    }
    private void reload() {
        now += 1000;
        doReturn(now).when(time).serverNow();
        configs.reload("activity", ActivityCfg.class, table.toString());
    }
    @Test void unfinishedOpenedInstanceKeepsConditionDeadlineNameAndReward() {
        String uid=player(); event(uid, 7); var before=row(uid); edit(); var after=row(uid);
        assertThat(after.goal()).isEqualTo(50);
        assertThat(after.progress()).isEqualTo(7);
        assertThat(after.windowEndAt()).isEqualTo(before.windowEndAt());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.rewards()).isEqualTo(before.rewards());
    }
    @Test void zeroProgressInstanceAlsoKeepsOldRules() {
        String uid=player(); var before=row(uid); edit(); var after=row(uid);
        assertThat(after.goal()).isEqualTo(50);
        assertThat(after.progress()).isZero();
        assertThat(after.windowEndAt()).isEqualTo(before.windowEndAt());
        assertThat(after.rewards()).isEqualTo(before.rewards());
    }
    @Test void unopenedPlayerWithoutProgressDocumentKeepsAlreadyOpenWindow() {
        String uid=player(); store.clear(); edit(); var after=row(uid);
        assertThat(after.goal()).isEqualTo(50);
        assertThat(after.rewards().stream().filter(r -> "GOLD".equals(r.id())).findFirst().orElseThrow().count()).isEqualTo(500);
    }
    @Test void claimUsesOriginalMaterialsGoldAndBattlePassPointsAfterReload() {
        String uid=player(); event(uid, 49); row(uid); edit(); event(uid, 1);
        assertThat(row(uid).state()).isEqualTo(com.ironoath.web.dto.generated.ActivityState.CLAIMABLE);
        long before=gold(uid), points=pass.status(uid).points();
        var result=activities.claim(uid, new ActivityClaimReq(HUNT, id()));
        assertThat(result.claimed()).isTrue();
        assertThat(gold(uid)).isEqualTo(before+500);
        assertThat(pass.status(uid).points()).isEqualTo(points+50);
        assertThat(result.rewards().stream().filter(r -> "ITEM".equals(r.type())).findFirst().orElseThrow().count()).isEqualTo(2);
    }
    @Test void removingRuleKeepsUnfinishedInstanceAndItsClaimPath() {
        String uid=player(); event(uid, 49); var before=row(uid);
        ArrayNode next=JsonUtils.mapper().createArrayNode();
        table.withArray("rows").forEach(r -> { if (!r.get("id").asText().equals(HUNT)) next.add(r); });
        table.set("rows", next); reload();
        assertThat(activities.list(uid).activities()).extracting(ActivityView::id).contains(HUNT);
        assertThat(row(uid).goal()).isEqualTo(before.goal());
        event(uid, 1); long beforeGold=gold(uid);
        activities.claim(uid, new ActivityClaimReq(HUNT, id()));
        assertThat(gold(uid)).isEqualTo(beforeGold+500);
    }
    @Test void emptyInstanceAdoptsNewRulesOnlyAtOriginalDeadline() {
        String uid=player(); var before=row(uid); edit();
        now=before.windowEndAt()-1; doReturn(now).when(time).serverNow();
        assertThat(row(uid).goal()).isEqualTo(50);
        now++; doReturn(now).when(time).serverNow();
        var next=row(uid);
        assertThat(next.goal()).isEqualTo(80);
        assertThat(next.progress()).isZero();
        assertThat(next.windowEndAt()).isEqualTo(now+86_400_000L);
        assertThat(next.name()).isEqualTo("未来剿匪令");
    }
    @Test void pendingExpiredInstanceCannotClaimAndLateEventCannotAdvanceNextWindow() {
        String uid=player(); event(uid,49); var before=row(uid); edit();
        long oldTime=now; now=before.windowEndAt(); doReturn(now).when(time).serverNow();
        assertThat(row(uid).state()).isEqualTo(com.ironoath.web.dto.generated.ActivityState.EXPIRED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> activities.claim(uid,new ActivityClaimReq(HUNT,id())))
                .isInstanceOf(com.ironoath.common.BizException.class);
        events.progress(uid,GoalType.KILL_MONSTER,"m1",1,oldTime);
        assertThat(row(uid).progress()).isZero();
        event(uid,1);
        assertThat(row(uid).progress()).isEqualTo(1);
        assertThat(row(uid).goal()).isEqualTo(80);
    }
    @Test void claimedInstanceDoesNotReopenAfterConfigChange() {
        String uid=player(); event(uid,50); activities.claim(uid,new ActivityClaimReq(HUNT,id()));
        long after=gold(uid); edit();
        assertThat(row(uid).claimed()).isTrue();
        assertThat(row(uid).goal()).isEqualTo(50);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> activities.claim(uid,new ActivityClaimReq(HUNT,id())))
                .isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(gold(uid)).isEqualTo(after);
    }
}
