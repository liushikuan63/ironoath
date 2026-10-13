package com.ironoath.web.activity;

import java.util.List;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityWindow;
import com.ironoath.core.reward.RewardItem;

/** 一个已开放窗口的承诺：规则、显示名、奖励和积分与进度一起保留。 */
public record ActivityInstance(ActivityProgress.Def def, String name, List<RewardItem> rewards,
                               long battlePassPoints, long startAt, Long endAt, Long openedAt) {
    public ActivityInstance(ActivityProgress.Def def,String name,List<RewardItem> rewards,long battlePassPoints,
                            long startAt,Long endAt) {
        this(def,name,rewards,battlePassPoints,startAt,endAt,null);
    }
    public ActivityInstance {
        if (def == null || name == null || name.isBlank() || battlePassPoints < 0) {
            throw new IllegalArgumentException("活动实例规则、名称或积分无效");
        }
        rewards = List.copyOf(rewards);
        new ActivityWindow.Span(startAt, endAt);
        // 旧快照没有此字段，旧普通活动沿已保存承诺；新主题未到开始时间的记录只是预览。
        if (openedAt==null && !def.requiresFact()) openedAt=startAt;
        if (openedAt!=null && (openedAt<startAt || endAt!=null && openedAt>=endAt))
            throw new IllegalArgumentException("活动开放确认必须落在对应窗口内");
    }
    public ActivityWindow.Span span() { return new ActivityWindow.Span(startAt, endAt); }
}
