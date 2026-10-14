package com.ironoath.web.bot;

/** 真实已发生攻击的独立滚动账本；规则热更不重建或清空事实。 */
public interface BotAttackLedgerStore {
    long WINDOW_MILLIS = 24L * 3_600_000L;
    record Attack(String sourceId,String victimId,long at) {
        public Attack {
            if(sourceId == null || sourceId.isBlank() || victimId == null || victimId.isBlank() || at <= 0)
                throw new IllegalArgumentException("攻击来源/受害者/发生时刻必须有效");
        }
    }
    enum Admission { ACQUIRED, QUOTA_FULL, ALREADY_PRESENT }
    /** 未确定的占位也消耗可用额度，但不伪装成已发生攻击。 */
    Admission reserve(Attack attack,int limit);
    void release(Attack attack);
    long occupied(String victimId,long now);
    boolean record(Attack attack);
    long count(String victimId,long now);
    void evict(long now);
    void clear();
}
