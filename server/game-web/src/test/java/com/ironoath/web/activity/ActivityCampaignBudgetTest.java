package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;

/** 用户2026-10-13裁决：未来960加三份480承接金币，六项材料积分保持。 */
class ActivityCampaignBudgetTest {
    static ConfigRegistry configs() {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contract/config/activity.json"))) root = root.getParent();
        return ConfigRegistry.loadFromDirectory(root.resolve("contract/config"));
    }
    @Test void futureOrdinaryCampaignsHaveExactlyFourWindowsAnd2400Gold() {
        var campaigns = configs().all(ActivityCfg.class).stream().filter(r -> r.id().startsWith("activity_theme_")).toList();
        assertThat(campaigns).hasSize(4);
        assertThat(campaigns.stream().mapToLong(ActivityCfg::rewardGold).sum()).isEqualTo(2400);
        assertThat(campaigns.stream().map(ActivityCfg::rewardGold)).containsExactly(960L,480L,480L,480L);
        assertThat(campaigns.stream().map(ActivityCfg::conditionValue)).containsExactly(40L,20L,20L,20L);
        assertThat(campaigns.stream().map(ActivityCfg::durationDays)).containsExactly(14L,7L,7L,7L);
        assertThat(campaigns).allMatch(r -> r.activityType() == ActivityCfg.ActivityType.KILL_MONSTER
                && r.conditionType() == ActivityCfg.ConditionType.KILL_MONSTER_TOTAL && r.battlePassPoints() == 0);
    }
    @Test void sixFutureLegacyLoopsDoNotStackGoldButKeepOriginalMaterialAndPoints() {
        var normal = configs().all(ActivityCfg.class).stream()
                .filter(r -> !Boolean.TRUE.equals(r.retired()) && !r.id().startsWith("activity_theme_")).toList();
        assertThat(normal).hasSize(6);
        assertThat(normal).allMatch(r -> r.rewardGold() == 0);
        assertThat(normal).allMatch(r -> r.rewardItemId() != null && r.rewardItemCount() > 0 && r.battlePassPoints() > 0);
    }
}
