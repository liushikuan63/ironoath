package com.ironoath.web.bot;

/** 只保存行为水位与节流，不向玩家存档或协议公开托管身份。 */
public interface BotBehaviorStore {
    record State(Long reactedUpTo, String greetedAlliance, Long lastSeekAllianceAt) {
        public static final State EMPTY = new State(null, null, null);
    }
    State find(String botId);
    /** 原子单调推进；同一或更旧受击只有一次回应归属。 */
    boolean advanceReaction(String botId, long hitAt);
    void recordGreeting(String botId, String allianceId);
    boolean trySeekAlliance(String botId, long now, long intervalMillis);
    void clear();
}
