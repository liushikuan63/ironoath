package com.ironoath.web.bot;

import java.util.List;

/** 互动专用持久槽；与一Bot一动作待办互不覆盖。 */
public interface BotConversationStore {
    record Pending(String botId, String peerId, String channel, String channelKey, String sourceId,
                   String content, long sequence, long dueAt, String marchId) { }

    /** 源时刻必须严格递增；同毫秒合并，旧事件不补排，busy/cooldown也观察高水位。 */
    boolean offer(Pending pending, long cooldownMillis);
    List<Pending> due(long now, int limit);
    List<Pending> dueFor(long now, java.util.Collection<String> botIds, int limit);
    boolean complete(String botId, long sequence, long now, long cooldownMillis);
    void clear();
}
