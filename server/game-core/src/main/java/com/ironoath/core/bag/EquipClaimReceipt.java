package com.ironoath.core.bag;

import java.util.*;
/** Accepted equipment identity is retained even after those instances are consumed or forged. */
public record EquipClaimReceipt(String businessKey, String sourceRef, List<Line> lines, long acceptedAt, String overflowMailId) {
    public record Line(String equipId, long requested, List<String> creditedUids) {
        public Line {
            if (equipId == null || equipId.isBlank() || requested <= 0 || creditedUids == null || creditedUids.size() > requested)
                throw new IllegalArgumentException("装备收据数量无效");
            creditedUids = List.copyOf(creditedUids);
            if (creditedUids.stream().anyMatch(uid -> !Inventory.isInstanceUid(uid)) || new HashSet<>(creditedUids).size() != creditedUids.size())
                throw new IllegalArgumentException("装备收据实例无效");
        }
    }
    public EquipClaimReceipt {
        if (businessKey == null || businessKey.isBlank() || sourceRef == null || sourceRef.isBlank() || lines == null || lines.isEmpty()
                || acceptedAt <= 0 || overflowMailId != null && overflowMailId.isBlank()) throw new IllegalArgumentException("装备收据无效");
        lines = List.copyOf(lines); var ids = new HashSet<String>(); var uids = new HashSet<String>();
        for (var line : lines) {
            if (!ids.add(line.equipId())) throw new IllegalArgumentException("装备收据行重复");
            for (var uid : line.creditedUids()) if (!uids.add(uid)) throw new IllegalArgumentException("装备收据实例重复");
        }
    }
    public Map<String,Long> requested() { var m = new LinkedHashMap<String,Long>(); lines.forEach(l -> m.put(l.equipId(),l.requested())); return Collections.unmodifiableMap(m); }
    public Map<String,Long> credited() { var m = new LinkedHashMap<String,Long>(); lines.forEach(l -> { if (!l.creditedUids().isEmpty()) m.put(l.equipId(),(long)l.creditedUids().size()); }); return Collections.unmodifiableMap(m); }
    public Map<String,Long> overflow() { var m = new LinkedHashMap<String,Long>(); lines.forEach(l -> { long n=l.requested()-l.creditedUids().size(); if(n>0)m.put(l.equipId(),n); }); return Collections.unmodifiableMap(m); }
    public boolean pendingOverflow() { return !overflow().isEmpty() && overflowMailId == null; }
    public EquipClaimReceipt withOverflowMail(String id) {
        if (id == null || id.isBlank() || overflowMailId != null && !overflowMailId.equals(id)) throw new IllegalStateException("装备溢出邮件不可替换");
        return new EquipClaimReceipt(businessKey,sourceRef,lines,acceptedAt,id);
    }
}
