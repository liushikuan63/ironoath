package com.ironoath.core.pay;

import com.ironoath.common.time.DayKey;

/** 独立订单周期；同SKU续费从上期到期续接，其他SKU和旧月卡不共用游标。 */
public record MonthlyCardPurchase(String orderId, String productId, MonthlyCardTerms terms,
        long startsAt, long expiresAt, int quantity, long settledDays) {
    public MonthlyCardPurchase {
        if (orderId == null || orderId.isBlank() || productId == null || productId.isBlank() || terms == null
                || startsAt <= 0 || expiresAt <= startsAt || quantity < 1 || settledDays < 0
                || settledDays > Math.multiplyExact(terms.durationDays(), quantity))
            throw new IllegalArgumentException("月卡购买周期或结清游标无效");
    }
    public boolean active(long now) { return now >= startsAt && now < expiresAt; }
    public long totalDays() { return Math.multiplyExact(terms.durationDays(), quantity); }
    public long settleThroughDays(long now) {
        return Math.max(0, Math.min(totalDays(), DayKey.businessDaysBetween(startsAt, now) + 1));
    }
    /** 沿既有活跃期/自然日/剩余天数补领口径，并以一期30份额度限制额外跨日。 */
    public long claimableDays(long now) {
        if (!active(now)) return 0;
        long owed = Math.max(0, settleThroughDays(now) - settledDays);
        long remaining = DayKey.businessDaysBetween(now, expiresAt) + 1;
        return Math.min(owed, remaining);
    }
    public MonthlyCardPurchase withSettledDays(long days) {
        if (days < settledDays) throw new IllegalArgumentException("月卡结清游标不可倒退");
        return new MonthlyCardPurchase(orderId, productId, terms, startsAt, expiresAt, quantity, days);
    }
}
