package com.ironoath.core.reward;

import java.util.Set;

/** 新金币奖励的持久收据；与金币余额同一聚合根、同一次版本更新。 */
public record GoldClaimReceipt(String businessKey, String source, String sourceRef,
        long requested, long credited, long acceptedAt, Set<String> facts, String overflowMailId) {
    public GoldClaimReceipt {
        if (businessKey == null || businessKey.isBlank() || source == null || source.isBlank()
                || sourceRef == null || sourceRef.isBlank() || requested <= 0 || credited < 0
                || credited > requested || acceptedAt <= 0) {
            throw new IllegalArgumentException("金币收据必须有稳定业务键、来源、正额度和合法入账记录");
        }
        facts = facts == null ? Set.of() : Set.copyOf(facts);
        if (facts.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("领取事实不得为空");
    }

    public long overflow() { return requested - credited; }
    public boolean pendingOverflow() { return overflow() > 0 && overflowMailId == null; }
    public GoldClaimReceipt withOverflowMail(String mailId) {
        if (mailId == null || mailId.isBlank()) throw new IllegalArgumentException("mailId不得为空");
        return new GoldClaimReceipt(businessKey, source, sourceRef, requested, credited, acceptedAt, facts, mailId);
    }
}
