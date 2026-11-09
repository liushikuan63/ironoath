package com.ironoath.web.quest;

import java.util.List;
import com.ironoath.core.reward.FrozenQuestReward;
import com.ironoath.config.cfg.BattlePassCfg;

/** Accepted ordinary claim; independent from later daily/week rollover and config changes. */
public record QuestClaimPlan(String businessKey, String questId, String periodKey,
        List<FrozenQuestReward> rewards, String seasonId, long points, List<BattlePassCfg> battlePassTiers,
        long acceptedAt, long mailExpireAt) {
    public QuestClaimPlan {
        if (questId == null || questId.isBlank() || periodKey == null || periodKey.isBlank()
                || !java.util.Objects.equals(businessKey, "quest_claim:" + questId + ":" + periodKey)
                || seasonId == null || seasonId.isBlank() || points < 0 || acceptedAt <= 0 || mailExpireAt <= acceptedAt)
            throw new IllegalArgumentException("普通任务冻结计划无效");
        rewards = List.copyOf(rewards);
        battlePassTiers = List.copyOf(battlePassTiers);
        if (points > 0 && battlePassTiers.isEmpty()) throw new IllegalArgumentException("任务缺少原季战令档位");
    }
}
