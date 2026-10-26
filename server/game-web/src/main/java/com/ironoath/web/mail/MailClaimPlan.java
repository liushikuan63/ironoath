package com.ironoath.web.mail;

import java.util.List;

/** A durable accepted mail promise; the source's original expiry remains on MailRecord. */
public record MailClaimPlan(String businessKey, String mailId, List<MailStore.RewardLine> attachments,
                            long acceptedAt, long overflowExpireAt) {
    public MailClaimPlan {
        if (mailId == null || mailId.isBlank() || !java.util.Objects.equals(businessKey, keyOf(mailId))
                || acceptedAt <= 0 || overflowExpireAt <= acceptedAt || attachments == null || attachments.isEmpty())
            throw new IllegalArgumentException("邮件领取计划无效");
        attachments = List.copyOf(attachments);
    }
    public static String keyOf(String mailId) { return "mail_claim:" + mailId; }
}
