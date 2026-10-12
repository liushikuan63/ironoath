package com.ironoath.web.activity;

import java.util.List;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityWindow;
import com.ironoath.core.reward.RewardItem;

/** 一个已开放窗口的承诺：规则、显示名、奖励和积分与进度一起保留。 */
public record ActivityInstance(ActivityProgress.Def def, String name, List<RewardItem> rewards,
                               long battlePassPoints, long startAt, Long endAt) {
    public ActivityInstance {
        if (def == null || name == null || name.isBlank() || battlePassPoints < 0) {
            throw new IllegalArgumentException("活动实例规则、名称或积分无效");
        }
        rewards = List.copyOf(rewards);
        new ActivityWindow.Span(startAt, endAt);
    }
    public ActivityWindow.Span span() { return new ActivityWindow.Span(startAt, endAt); }
}
