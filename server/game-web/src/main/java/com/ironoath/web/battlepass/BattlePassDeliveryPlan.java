package com.ironoath.web.battlepass;

import java.util.*;
import com.ironoath.core.reward.*;
import com.ironoath.web.dto.generated.BattlePassTrack;

/** Accepted qualification and original reward. DIRECT freezes overflow window; MAIL freezes retention duration, not a fictitious pre-delivery start. */
public record BattlePassDeliveryPlan(String key,String seasonId,BattlePassTrack track,Kind kind,
        List<Tier> tiers,long createdAt,long expireAt,String title,String reason) {
    public enum Kind { DIRECT, SEASON_MAIL, LATE_MAIL }
    public record Tier(int tier,long requiredPoints,RewardItem reward,String name,FrozenMailReward asset) {
        public Tier {
            if(tier<=0||requiredPoints<0||reward==null||name==null||name.isBlank()||asset!=null&&!asset.reward().equals(reward))
                throw new IllegalArgumentException("战令冻结档位无效");
        }
    }
    public BattlePassDeliveryPlan {
        if(key==null||key.isBlank()||seasonId==null||seasonId.isBlank()||track==null||kind==null||tiers==null||tiers.isEmpty()
                ||createdAt<=0||expireAt<=createdAt||title==null||title.isBlank()||reason==null||reason.isBlank())throw new IllegalArgumentException("战令投递计划无效");
        tiers=List.copyOf(tiers);var seen=new HashSet<Integer>();
        for(var tier:tiers)if(!seen.add(tier.tier())||kind==Kind.DIRECT&&tier.asset()==null)throw new IllegalArgumentException("战令计划档位重复或缺少资产快照");
        if(kind!=Kind.SEASON_MAIL&&tiers.size()!=1||!key.equals(keyOf(seasonId,track,kind,tiers)))throw new IllegalArgumentException("战令计划业务键不匹配");
    }
    public static String keyOf(String season,BattlePassTrack track,Kind kind,List<Tier> tiers) {
        if(kind==Kind.LATE_MAIL)return "activity-battle-pass:"+season+":"+tiers.get(0).tier()+":"+track.name();
        String prefix=kind==Kind.DIRECT?"battlepass-direct:":"battlepass-sweep:";
        String ids=tiers.stream().map(Tier::tier).sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return prefix+season+":"+track.name()+":"+ids;
    }
    public boolean mail(){return kind!=Kind.DIRECT;}
    public RewardContext context() {
        return mail()?new RewardContext("battlePassRecovery",seasonId+":"+track.name(),key,false)
                :new RewardContext("battlepass_claim","battlepass:"+seasonId+":"+track.name(),key,true,
                    Set.of("battlepass-plan-owned","battlepass-overflow-created:"+createdAt,"battlepass-overflow-expires:"+expireAt));
    }
}
