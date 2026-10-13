package com.ironoath.web.activity;

import com.ironoath.core.reward.RewardType;

/** Accepted activity delivery; freeze the exact entitlement before touching any reward aggregate. */
public record ActivityClaimPlan(String businessKey, ActivityInstance instance, String seasonId, long acceptedAt,
                                java.util.List<com.ironoath.config.cfg.BattlePassCfg> battlePassTiers) {
    public ActivityClaimPlan(String businessKey,ActivityInstance instance,String seasonId,long acceptedAt){
        this(businessKey,instance,seasonId,acceptedAt,java.util.List.of());
    }
    public ActivityClaimPlan {
        if(instance==null || !keyOf(instance).equals(businessKey) || instance.openedAt()==null
                || acceptedAt<instance.startAt() || instance.endAt()!=null&&acceptedAt>=instance.endAt()
                || seasonId==null || seasonId.isBlank())throw new IllegalArgumentException("活动待发计划身份或接受时间无效");
        for(var reward:instance.rewards())if(reward.type()!=RewardType.ITEM
                && !(reward.type()==RewardType.RESOURCE&&"GOLD".equals(reward.id())))
            throw new IllegalArgumentException("活动待发计划只接受金币与普通道具");
        battlePassTiers=battlePassTiers==null?java.util.List.of():java.util.List.copyOf(battlePassTiers);
        var keys=new java.util.HashSet<Long>();
        for(var tier:battlePassTiers)if(tier.tier()<=0||tier.requiredPoints()<=0||!keys.add(tier.tier()))
            throw new IllegalArgumentException("活动原季战令档位快照无效");
    }
    public static String keyOf(ActivityInstance instance){return "activity_claim:"+instance.def().activityId()+":"+instance.startAt();}
}
