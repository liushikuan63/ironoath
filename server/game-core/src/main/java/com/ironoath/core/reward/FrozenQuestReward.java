package com.ironoath.core.reward;

/** A claim-time reward and its frozen item conversion; no config lookup during replay. */
public record FrozenQuestReward(RewardItem reward, ItemConversion itemConversion) {
    public record ItemConversion(String itemId, long count, long stackMax) {
        public ItemConversion {
            if (itemId == null || itemId.isBlank() || count <= 0 || stackMax <= 0)
                throw new IllegalArgumentException("任务道具快照无效");
        }
        public RewardItem reward() { return new RewardItem(RewardType.ITEM, itemId, count); }
    }
    public FrozenQuestReward {
        if (reward == null || reward.type() == RewardType.PRIVILEGE)
            throw new IllegalArgumentException("普通任务不支持此奖励");
        if ((reward.type() == RewardType.ITEM || reward.type() == RewardType.HERO_FRAGMENT) && itemConversion == null)
            throw new IllegalArgumentException("任务道具必须冻结映射和堆叠限制");
        if (reward.type() == RewardType.HERO && reward.count() != 1)
            throw new IllegalArgumentException("任务武将一次只能一名");
    }
}
