package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;

@SpringBootTest @ActiveProfiles("test")
@Import(ActivityCampaignPreviewTest.PreviewClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ActivityCampaignPreviewTest {
    private static final String ID="activity_theme_major";
    @Autowired ActivityAppService activities;
    @Autowired PlayerInitService init;
    @Autowired ConfigRegistry configs;
    @Autowired QuestEvents events;
    @Autowired @Qualifier("previewClock") AtomicLong previewClock;
    @TestConfiguration(proxyBeanMethods=false)
    static class PreviewClockConfiguration {
        @Bean AtomicLong previewClock() { return new AtomicLong(at("2026-10-13T13:00:00")); }
        @Bean @Primary TimeService previewTimeService(@Qualifier("previewClock") AtomicLong clock) {
            return new TimeService(clock::get);
        }
    }
    private String uid;
    private ObjectNode table;
    private static long at(String value) { return OffsetDateTime.parse(value+"+08:00").toInstant().toEpochMilli(); }
    @BeforeEach void setup() throws Exception {
        long preview=at("2026-10-13T13:00:00"); previewClock.set(preview);
        String key=UUID.randomUUID().toString();
        uid=init.init(new PlayerInitReq(key,"preview-"+key,"未来活动验收",preview,"")).playerId();
        assertThat(activities.list(uid).activities()).anySatisfy(row -> {
            assertThat(row.id()).isEqualTo(ID); assertThat(row.goal()).isEqualTo(40);
        });
        Path root=Path.of("").toAbsolutePath();
        while(!Files.exists(root.resolve("contract/config/activity.json"))) root=root.getParent();
        table=(ObjectNode)JsonUtils.mapper().readTree(Files.readString(root.resolve("contract/config/activity.json")));
    }
    private void reload() {
        previewClock.set(at("2026-10-14T12:00:00"));
        configs.reload("activity",ActivityCfg.class,table.toString());
    }
    @Test void futurePreviewDisplaysBothServerAuthoritativeWindowEndpoints() {
        var current=activities.list(uid).activities().stream().filter(row -> ID.equals(row.id())).findFirst().orElseThrow();
        assertThat(current.details()).contains("2026-11-01 04:00", "2026-11-15 04:00", "北京时间");
        assertThat(current.windowStartAt()).isEqualTo(at("2026-11-01T04:00:00"));
        assertThat(current.windowEndAt()).isEqualTo(at("2026-11-15T04:00:00"));
    }
    @Test void offlinePreviewUsesRulesChangedBeforeOpeningInsteadOfFreezingOldPreview() {
        for(var row:table.withArray("rows")) if(ID.equals(row.path("id").asText()))
            ((ObjectNode)row).put("conditionValue",60).put("rewardGold",1440);
        reload();
        // 中间不读取、不触发事件，直接跨到原开始时间之后。
        previewClock.set(at("2026-11-02T12:00:00"));
        var current=activities.list(uid).activities().stream().filter(row -> ID.equals(row.id())).findFirst().orElseThrow();
        assertThat(current.goal()).isEqualTo(60);
        assertThat(current.rewards()).singleElement().extracting(com.ironoath.web.dto.generated.ActivityReward::count).isEqualTo(1440L);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void deletedFuturePreviewCannotOpenWhetherOrNotPlayerRevisitedBeforeStart(boolean revisits) {
        var remaining=JsonUtils.mapper().createArrayNode();
        table.withArray("rows").forEach(row -> { if(!ID.equals(row.path("id").asText())) remaining.add(row); });
        table.set("rows",remaining); reload();
        if(revisits) assertThat(activities.list(uid).activities()).noneSatisfy(row -> assertThat(row.id()).isEqualTo(ID));
        long opened=at("2026-11-02T12:00:00"); previewClock.set(opened);
        events.progressOnce(uid,GoalType.KILL_MONSTER,"monster",1,opened,"solo-monster:after-deletion");
        assertThat(activities.list(uid).activities()).noneSatisfy(row -> assertThat(row.id()).isEqualTo(ID));
    }
}
