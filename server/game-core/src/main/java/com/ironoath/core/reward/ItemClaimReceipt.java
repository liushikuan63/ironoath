package com.ironoath.core.reward;

import java.util.HashSet;
import java.util.List;

/** 材料日包的整批收据，与全部已入包道具同一次背包版本更新。 */
public record ItemClaimReceipt(String businessKey, String source, String sourceRef,
        List<RewardItem> requested, List<RewardItem> credited, long acceptedAt, String overflowMailId) {
    public ItemClaimReceipt {
        if (businessKey == null || businessKey.isBlank() || !"paid_items".equals(source)
                || sourceRef == null || sourceRef.isBlank() || acceptedAt <= 0)
            throw new IllegalArgumentException("道具收据必须有稳定业务键、付费来源和有效时间");
        requested = List.copyOf(requested); credited = List.copyOf(credited);
        if (requested.isEmpty()) throw new IllegalArgumentException("材料日包不能为空");
        var ids = new HashSet<String>();
        for (var item : requested) {
            if (item.type() != RewardType.ITEM || !ids.add(item.id()))
                throw new IllegalArgumentException("材料日包必须是合并同id后的普通道具");
        }
        var creditedIds = new HashSet<String>();
        for (var item : credited) {
            long limit = requested.stream().filter(r -> r.id().equals(item.id())).mapToLong(RewardItem::count).sum();
            if (item.type() != RewardType.ITEM || !creditedIds.add(item.id()) || item.count() > limit)
                throw new IllegalArgumentException("道具收据实际入包不得超过对应请求");
        }
        if (overflowMailId != null && overflowMailId.isBlank()) throw new IllegalArgumentException("溢出邮件号不得为空");
    }

    public List<RewardItem> overflow() {
        return requested.stream().map(item -> {
            long got = credited.stream().filter(r -> r.id().equals(item.id())).mapToLong(RewardItem::count).sum();
            return item.count() == got ? null : item.withCount(item.count() - got);
        }).filter(java.util.Objects::nonNull).toList();
    }
    public boolean pendingOverflow() { return overflowMailId == null && !overflow().isEmpty(); }
    public ItemClaimReceipt withOverflowMail(String mailId) {
        if (mailId == null || mailId.isBlank()) throw new IllegalArgumentException("溢出邮件号不得为空");
        return new ItemClaimReceipt(businessKey, source, sourceRef, requested, credited, acceptedAt, mailId);
    }
}
