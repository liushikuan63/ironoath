package com.ironoath.web.battlepass;

import java.util.*;
import com.ironoath.config.*;
import com.ironoath.config.cfg.*;
import com.ironoath.core.reward.*;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.reward.*;

/** Called only under the owner lock. No asset/mail work belongs inside a progress CAS callback. */
final class BattlePassClaimRecovery {
    private final BattlePassStore store; private final ConfigRegistry configs;
    private final RewardService rewards; private final RewardNames names; private final StoreMailbox mailbox;
    BattlePassClaimRecovery(BattlePassStore store,ConfigRegistry configs,RewardService rewards,RewardNames names,StoreMailbox mailbox) {
        this.store=store;this.configs=configs;this.rewards=rewards;this.names=names;this.mailbox=mailbox;
    }
    BattlePassDeliveryPlan.Tier freeze(BattlePassCfg row,BattlePassTrack track,boolean direct) {
        boolean paid=track==BattlePassTrack.PAID;
        var type=RewardType.valueOf(paid?row.paidRewardType().name():row.freeRewardType().name());
        var reward=new RewardItem(type,paid?row.paidRewardId():row.freeRewardId(),paid?row.paidRewardCount():row.freeRewardCount());
        String name;
        try{name=names.nameOf(reward);}catch(ConfigException missing){if(direct)throw missing;name=type==RewardType.ITEM?"未知道具":"资源奖励";}
        FrozenMailReward asset=null;
        if(direct) {
            if(type==RewardType.ITEM){var item=configs.get(ItemCfg.class,reward.id());asset=new FrozenMailReward(reward,item.type()==ItemCfg.Type.EQUIP,item.stackMax());}
            else {configs.getResource(reward.id());asset=new FrozenMailReward(reward,false,0);}
        }
        return new BattlePassDeliveryPlan.Tier(Math.toIntExact(row.tier()),row.requiredPoints(),reward,name,asset);
    }
    long expireAt(long now){return Math.addExact(now,Math.multiplyExact(configs.longParam("MAIL_RETENTION_DAYS"),86400000L));}
    static BattlePassDeliveryPlan plan(String season,BattlePassTrack track,BattlePassDeliveryPlan.Kind kind,
            List<BattlePassDeliveryPlan.Tier> tiers,long now,long expires,String sourceName) {
        var ordered=tiers.stream().sorted(java.util.Comparator.comparingInt(BattlePassDeliveryPlan.Tier::tier)).toList();
        String trackName=track==BattlePassTrack.PAID?"付费":"免费";
        String reason=kind==BattlePassDeliveryPlan.Kind.LATE_MAIL?sourceName+"奖励恢复后补齐了原赛季积分，这档"+trackName+"奖励已补发："
                :kind==BattlePassDeliveryPlan.Kind.SEASON_MAIL?"赛季结束时有 "+ordered.size()+" 档"+trackName+"奖励还没领，已按档补发：":"战令档位奖励";
        return new BattlePassDeliveryPlan(BattlePassDeliveryPlan.keyOf(season,track,kind,ordered),season,track,kind,ordered,now,expires,
                kind==BattlePassDeliveryPlan.Kind.DIRECT?"战令奖励":"赛季战令奖励补发",reason);
    }
    /** Legacy send-before-mark proof wins even when current points or reward rows changed. */
    void reconcileLegacy(String playerId,String season,List<BattlePassCfg> tiers) {
        var progress=store.load(season,playerId);
        for(var row:tiers)for(var track:BattlePassTrack.values()) {
            int tier=Math.toIntExact(row.tier());if(progress.claimed(tier,track))continue;
            String key="activity-battle-pass:"+season+":"+tier+":"+track.name();
            var ctx=new RewardContext("battlePassRecovery",season+":"+track.name(),key,false);
            var proof=mailbox.findGrantOnce(playerId,ctx);if(proof.isEmpty())continue;
            var mail=proof.get();if(!"BATTLE_PASS".equals(mail.kind())||!(ctx.source()+":"+ctx.sourceRef()).equals(mail.sourceRef()))
                throw new IllegalStateException("旧战令邮件身份冲突，拒绝重复接受资格");
            progress=store.update(season,playerId,p->p.withTierMailWindow(key,mail.createdAt(),mail.expireAt()).withClaimed(tier,track));
        }
    }
    int recoverAll(String playerId,String currentSeason) {
        var seasons=new LinkedHashSet<>(store.pendingSeasonsOf(playerId));seasons.add(currentSeason);
        int sent=0;for(String season:seasons)sent+=recoverSeason(playerId,season);return sent;
    }
    int recoverSeason(String playerId,String season) {
        int sent=0;
        for(var plan:store.load(season,playerId).pendingPlans().values()) {
            if(!plan.seasonId().equals(season))throw new IllegalStateException("战令计划跨季，拒绝重新解释");
            if(plan.mail()) {
                var lines=plan.tiers().stream().map(t->new MailStore.RewardLine(t.reward().type().name(),t.reward().id(),t.reward().count(),t.name())).toList();
                var mail=mailbox.sendFrozenGrantOnce(playerId,plan.title(),lines,plan.reason(),plan.context(),plan.createdAt(),plan.expireAt());
                if(!"BATTLE_PASS".equals(mail.kind())||!(plan.context().source()+":"+plan.context().sourceRef()).equals(mail.sourceRef())
                        ||!mail.rewards().equals(lines)||Math.subtractExact(mail.expireAt(),mail.createdAt())!=Math.subtractExact(plan.expireAt(),plan.createdAt()))
                    throw new IllegalStateException("战令投递证据与原计划不一致，保留待处理");
                store.update(season,playerId,p->p.completeMailDelivery(plan.key(),mail.createdAt(),mail.expireAt()));
                sent++;
            } else {
                var result=rewards.grantBattlePass(playerId,plan.tiers().stream().map(BattlePassDeliveryPlan.Tier::asset).toList(),plan.context());
                if(result==null||result.hasCompensation())throw new IllegalStateException("战令计划未确认入账，保留原承诺");
            }
            if(!plan.mail())store.update(season,playerId,p->p.completeDelivery(plan.key()));
        }
        return sent;
    }
}
