package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.web.store.memory.InMemoryActivityRuleHistoryStore;

/** 真配置热更，时钟为夹具；全服、个人锚、无打开记录及有界CAS失败均可独立复验。 */
class ActivityRuleHistoryTest {
    private static final long OPEN=1_800_000_000_000L, DAY=86_400_000L;
    private ConfigRegistry configs;
    private ObjectNode table;
    private TimeService time;
    private InMemoryActivityRuleHistoryStore store;
    private ActivityRuleHistory history;
    @BeforeEach void setup() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contract/config/activity.json"))) root=root.getParent();
        configs=ConfigRegistry.loadFromDirectory(root.resolve("contract/config"));
        table=(ObjectNode)JsonUtils.mapper().readTree(Files.readString(root.resolve("contract/config/activity.json")));
        // 本组测试明确模拟v2旧服再热更，不能拿未来零金币表冒充旧承诺。
        try (var input=ActivityRuleHistory.class.getResourceAsStream("/migrations/activity-rules-v2.json")) {
            table.set("rows",JsonUtils.mapper().readTree(input));
        }
        configs.reload("activity",ActivityCfg.class,table.toString());
        time=mock(TimeService.class); when(time.serverNow()).thenReturn(OPEN+DAY);
        store=new InMemoryActivityRuleHistoryStore(); history=new ActivityRuleHistory(store,configs,time);
    }
    private void change(String id,long goal,long duration,long at) {
        when(time.serverNow()).thenReturn(at);
        for (var node:table.withArray("rows")) if (node.get("id").asText().equals(id)) {
            ((ObjectNode)node).put("conditionValue",goal).put("durationDays",duration);
        }
        configs.reload("activity",ActivityCfg.class,table.toString());
    }
    @Test void wholeServerWindowKeepsOldRulesEvenForLateNewPlayer() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        var old=history.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN,OPEN+2*DAY);
        assertThat(old.def().goalValue()).isEqualTo(50);
        assertThat(old.startAt()).isEqualTo(OPEN);
        assertThat(old.endAt()).isEqualTo(OPEN+3*DAY);
        var next=history.resolve("activity_monster_hunt",OPEN+3*DAY,OPEN,OPEN+2*DAY);
        assertThat(next.def().goalValue()).isEqualTo(80);
        assertThat(next.startAt()).isEqualTo(OPEN+3*DAY);
    }
    @Test void developmentFallbackUsesEachPlayersOpenWindow() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        assertThat(history.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN,OPEN).def().goalValue()).isEqualTo(50);
        assertThat(history.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN+2*DAY,OPEN+2*DAY).def().goalValue()).isEqualTo(80);
    }
    @Test void multipleRevisionsBeforeDeadlineChangeFutureWindowOnly() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        change("activity_monster_hunt",90,2,OPEN+2*DAY);
        assertThat(history.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN,OPEN).def().goalValue()).isEqualTo(50);
        var next=history.resolve("activity_monster_hunt",OPEN+3*DAY,OPEN,OPEN);
        assertThat(next.def().goalValue()).isEqualTo(90);
        assertThat(next.endAt()).isEqualTo(OPEN+5*DAY);
    }
    @Test void unchangedRowsDoNotShiftTheirExistingCalendar() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        var help=history.resolve("activity_squad_help",OPEN+10*DAY,OPEN,OPEN);
        assertThat(help.startAt()).isEqualTo(OPEN+7*DAY);
        assertThat(help.endAt()).isEqualTo(OPEN+14*DAY);
    }
    @Test void restartKeepsHistoryAndDoesNotRecaptureIdenticalConfig() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        var restart=new ActivityRuleHistory(store,configs,time);
        assertThat(store.load()).hasSize(2);
        assertThat(restart.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN,OPEN).def().goalValue()).isEqualTo(50);
    }
    @Test void failedHistoryPersistenceRejectsConfigCommit() {
        var failing=new ActivityRuleHistoryStore() {
            public List<Revision> load() { return store.load(); }
            public boolean saveIfVersion(int version,List<Revision> next) { return false; }
        };
        new ActivityRuleHistory(failing,configs,time);
        assertThatThrownBy(() -> change("activity_monster_hunt",80,1,OPEN+DAY)).isInstanceOf(IllegalStateException.class);
        assertThat(configs.get(ActivityCfg.class,"activity_monster_hunt").conditionValue()).isEqualTo(50);
    }
    @Test void firstUpgradeWithAlreadyChangedConfigSeedsOldRulesForUnopenedPlayers() {
        change("activity_monster_hunt",80,1,OPEN+DAY);
        var coldStore=new InMemoryActivityRuleHistoryStore();
        var cold=new ActivityRuleHistory(coldStore,configs,time);
        assertThat(coldStore.load()).hasSize(2);
        assertThat(cold.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN,OPEN).def().goalValue()).isEqualTo(50);
        assertThat(cold.resolve("activity_monster_hunt",OPEN+2*DAY,OPEN+2*DAY,OPEN+2*DAY).def().goalValue()).isEqualTo(80);
    }
}
