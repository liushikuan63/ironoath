package com.ironoath.core.reward;

/** The obtain/duplicate decision is stored with the hero; duplicate items belong to Inventory. */
public record HeroClaimReceipt(String businessKey, FrozenQuestReward reward, boolean obtained, long acceptedAt) {
    public HeroClaimReceipt {
        if (businessKey == null || businessKey.isBlank() || reward == null
                || reward.reward().type() != RewardType.HERO || acceptedAt <= 0)
            throw new IllegalArgumentException("武将奖励收据无效");
    }
}
