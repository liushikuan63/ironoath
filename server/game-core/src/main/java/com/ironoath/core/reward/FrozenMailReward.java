package com.ironoath.core.reward;
/** Immutable attachment classification accepted before any asset write. */
public record FrozenMailReward(RewardItem reward, boolean equipment, long stackMax) {
    public FrozenMailReward {
        if (reward == null || reward.type() != RewardType.RESOURCE && reward.type() != RewardType.STAMINA && reward.type() != RewardType.ITEM
                || equipment && reward.type() != RewardType.ITEM || reward.type() == RewardType.ITEM && !equipment && stackMax <= 0)
            throw new IllegalArgumentException("邮件附件冻结分类无效");
    }
}
