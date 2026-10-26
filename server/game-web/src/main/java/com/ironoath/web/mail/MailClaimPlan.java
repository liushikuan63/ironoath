package com.ironoath.web.mail;

import java.util.List;

/** A durable accepted mail promise; the source's original expiry remains on MailRecord. */
public record MailClaimPlan(String businessKey, String mailId, List<MailStore.RewardLine> attachments,
                            long acceptedAt, long overflowExpireAt, java.util.List<com.ironoath.core.reward.FrozenMailReward> frozenAttachments) {
    public MailClaimPlan(String businessKey,String mailId,List<MailStore.RewardLine> attachments,long acceptedAt,long overflowExpireAt) {
        this(businessKey,mailId,attachments,acceptedAt,overflowExpireAt,null);
    }
    public MailClaimPlan {
        if (mailId == null || mailId.isBlank() || !java.util.Objects.equals(businessKey, keyOf(mailId))
                || acceptedAt <= 0 || overflowExpireAt <= acceptedAt || attachments == null || attachments.isEmpty())
            throw new IllegalArgumentException("邮件领取计划无效");
        attachments = List.copyOf(attachments);
        if(frozenAttachments!=null) {
            frozenAttachments=List.copyOf(frozenAttachments);
            if(frozenAttachments.size()!=attachments.size())throw new IllegalArgumentException("冻结附件数量不一致");
            for(int i=0;i<attachments.size();i++) {
                var line=attachments.get(i);var reward=frozenAttachments.get(i).reward();
                if(!line.type().equals(reward.type().name())||!line.id().equals(reward.id())||line.count()!=reward.count())throw new IllegalArgumentException("冻结附件不能改变原奖励");
            }
        }
    }
    public static String keyOf(String mailId) { return "mail_claim:" + mailId; }
}
