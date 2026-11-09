package com.ironoath.core.reward;

/** Ordinary reward receipt, atomically saved with its resource balance. Gold's old lane is independent. */
public record ResourceClaimReceipt(String businessKey, RewardItem reward, long credited,
                                   long acceptedAt, String overflowMailId) {
    public ResourceClaimReceipt {
        if (businessKey == null || businessKey.isBlank() || reward == null
                || (reward.type() != RewardType.RESOURCE && reward.type() != RewardType.STAMINA)
                || credited < 0 || credited > reward.count() || acceptedAt <= 0
                || overflowMailId != null && overflowMailId.isBlank())
            throw new IllegalArgumentException("资源收据无效");
    }
    public long overflow() { return reward.count() - credited; }
    public boolean pendingOverflow() { return overflow() > 0 && overflowMailId == null; }
    public ResourceClaimReceipt withOverflowMail(String id) {
        if (id == null || id.isBlank() || overflowMailId != null && !overflowMailId.equals(id))
            throw new IllegalArgumentException("资源收据邮件不可替换");
        return new ResourceClaimReceipt(businessKey, reward, credited, acceptedAt, id);
    }
}
