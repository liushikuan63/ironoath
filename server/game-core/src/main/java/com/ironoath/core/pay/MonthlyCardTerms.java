package com.ironoath.core.pay;

import java.util.List;
import com.ironoath.core.reward.RewardItem;

/** 新月卡购买时固定的条款；发货、补单和日包读取同一快照，不重读后来配置。 */
public record MonthlyCardTerms(String name, long durationDays, long initialGold,
        List<RewardItem> dailyRewards, boolean adFree, long extraQueues, boolean earlyCollectAll) {
    public MonthlyCardTerms {
        if (name == null || name.isBlank() || durationDays <= 0 || initialGold < 0 || extraQueues < 0)
            throw new IllegalArgumentException("月卡条款必须有名字、正天数和非负奖励权益");
        if (dailyRewards == null || dailyRewards.isEmpty()) throw new IllegalArgumentException("月卡日包不得为空");
        dailyRewards = List.copyOf(dailyRewards);
    }
}
