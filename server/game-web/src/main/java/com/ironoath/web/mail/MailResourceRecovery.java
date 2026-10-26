package com.ironoath.web.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;

/** Owner-locked accepted mail promises; old pure resource plans keep their original identity. */
final class MailResourceRecovery {
    record Delivered(MailClaimPlan plan, GrantResult result) {}
    private final MailStore store;
    private final ConfigRegistry configs;
    private final RewardService rewards;
    MailResourceRecovery(MailStore store, ConfigRegistry configs, RewardService rewards) {
        this.store = store; this.configs = configs; this.rewards = rewards;
    }
    boolean supports(MailStore.MailRecord mail) {
        String source = mail.sourceRef().toLowerCase(Locale.ROOT);
        if (source.startsWith("pay:") || source.startsWith("paid") || source.startsWith("payment:")) return false;
        if(pureResources(mail))return true;
        if(mail.rewards().isEmpty()||mail.rewards().stream().anyMatch(l->!Set.of("RESOURCE","STAMINA","ITEM").contains(l.type())))return false;
        boolean candidate=mail.rewards().stream().anyMatch(l->("RESOURCE".equals(l.type())||"STAMINA".equals(l.type()))&&!"GOLD".equals(l.id())||equipment(l));
        // Only new candidates need a receipt lookup. Accepted GOLD/ITEM pins the old route after reload.
        return candidate&&!rewards.hasMailAttachmentClaim(mail.playerId(),mail.mailId());
    }
    private boolean pureResources(MailStore.MailRecord mail) {
        return !mail.rewards().isEmpty()&&mail.rewards().stream().allMatch(l->("RESOURCE".equals(l.type())||"STAMINA".equals(l.type()))&&!"GOLD".equals(l.id()));
    }
    private boolean equipment(MailStore.RewardLine line) {
        if(!"ITEM".equals(line.type()))return false;
        try{return configs.get(com.ironoath.config.cfg.ItemCfg.class,line.id()).type()==com.ironoath.config.cfg.ItemCfg.Type.EQUIP;}
        catch(com.ironoath.config.ConfigException missing){return false;}
    }
    MailClaimPlan freeze(MailStore.MailRecord mail, long eligibleAt) {
        if (!supports(mail)) throw new IllegalArgumentException("此邮件不属于普通资源或装备恢复范围");
        // Validate the complete immutable attachment list before accepting or touching any asset.
        var frozen=new ArrayList<com.ironoath.core.reward.FrozenMailReward>();
        for(var line:mail.rewards()) {
            var reward=new RewardItem(RewardType.valueOf(line.type()),line.id(),line.count());
            if("ITEM".equals(line.type())) {
                var cfg=configs.get(com.ironoath.config.cfg.ItemCfg.class,line.id());
                frozen.add(new com.ironoath.core.reward.FrozenMailReward(reward,cfg.type()==com.ironoath.config.cfg.ItemCfg.Type.EQUIP,cfg.stackMax()));
            } else {
                if(configs.getResource(line.id())==null)throw new IllegalArgumentException("资源附件配置缺失");
                frozen.add(new com.ironoath.core.reward.FrozenMailReward(reward,false,0));
            }
        }
        long expires = Math.addExact(eligibleAt, Math.multiplyExact(configs.longParam("MAIL_RETENTION_DAYS"), 86_400_000L));
        return new MailClaimPlan(MailClaimPlan.keyOf(mail.mailId()), mail.mailId(), mail.rewards(), eligibleAt, expires,pureResources(mail)?null:frozen);
    }
    List<Delivered> recover(String playerId) {
        var out = new ArrayList<Delivered>();
        for (var mail : store.pendingOf(playerId)) {
            var plan = mail.acceptedPlan();
            out.add(new Delivered(plan, deliverAndFinish(playerId, plan)));
        }
        return List.copyOf(out);
    }
    GrantResult deliverAndFinish(String playerId, MailClaimPlan plan) {
        var facts = Set.of("mail-overflow-created:" + plan.acceptedAt(), "mail-overflow-expires:" + plan.overflowExpireAt());
        var items = plan.attachments().stream().map(line -> new RewardItem(RewardType.valueOf(line.type()), line.id(), line.count())).toList();
        com.ironoath.core.reward.GrantResult result;
        if(plan.frozenAttachments()==null)result=rewards.grantMailResources(playerId,items,
                new RewardContext("mail_resource","mail:"+plan.mailId(),"mail_resource:"+plan.mailId(),true,facts));
        else {
            var ownedFacts=new java.util.HashSet<>(facts);ownedFacts.add("mail-plan-owned");
            result=rewards.grantMailAttachments(playerId,plan.frozenAttachments(),
                    new RewardContext("mail_attachment","mail:"+plan.mailId(),"mail_attachment:"+plan.mailId(),true,ownedFacts));
        }
        if (!store.completeAccepted(playerId, plan.mailId(), plan.businessKey()))
            throw new IllegalStateException("邮件领取计划结案失败，收据已保留");
        return result;
    }
}
