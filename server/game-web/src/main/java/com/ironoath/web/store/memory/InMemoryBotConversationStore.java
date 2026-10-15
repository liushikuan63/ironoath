package com.ironoath.web.store.memory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ironoath.web.bot.BotConversationStore;

/** 内存测试/开发等价实现，生产Mongo实现保持相同水位和CAS语义。 */
@Component
@ConditionalOnProperty(name="ironoath.storage", havingValue="memory", matchIfMissing=true)
public class InMemoryBotConversationStore implements BotConversationStore {
    private static class Slot { long observedAt; long nextChatAt; Pending pending; }
    private final Map<String, Slot> slots = new HashMap<>();

    @Override public synchronized boolean offer(Pending pending, long cooldownMillis) {
        Slot slot = slots.computeIfAbsent(pending.botId(), k -> new Slot());
        boolean allowed = pending.sequence() > slot.observedAt && slot.pending == null
                && pending.sequence() >= slot.nextChatAt;
        slot.observedAt = Math.max(slot.observedAt, pending.sequence());
        if (!allowed) return false;
        slot.pending = pending;
        slot.nextChatAt = pending.sequence() + cooldownMillis;
        return true;
    }
    @Override public synchronized List<Pending> due(long now, int limit) {
        return slots.values().stream().map(s -> s.pending).filter(p -> p != null && p.dueAt() <= now)
                .sorted(Comparator.comparingLong(Pending::dueAt).thenComparing(Pending::botId))
                .limit(limit).toList();
    }
    @Override public synchronized List<Pending> dueFor(long now, java.util.Collection<String> botIds, int limit) {
        var known=java.util.Set.copyOf(botIds);
        return slots.values().stream().map(s -> s.pending)
                .filter(p -> p!=null && known.contains(p.botId()) && p.dueAt()<=now)
                .sorted(Comparator.comparingLong(Pending::dueAt).thenComparing(Pending::botId))
                .limit(limit).toList();
    }
    @Override public synchronized boolean complete(String botId, long sequence, long now, long cooldownMillis) {
        Slot slot = slots.get(botId);
        if (slot == null || slot.pending == null || slot.pending.sequence() != sequence) return false;
        slot.pending = null;
        slot.nextChatAt = Math.max(slot.nextChatAt, now + cooldownMillis);
        return true;
    }
    @Override public List<Pending> dueForKnownBots(long now,
            java.util.function.Supplier<? extends java.util.Collection<String>> knownBots, int limit) {
        java.util.Objects.requireNonNull(knownBots,"knownBots");
        synchronized(this) {
            if(slots.values().stream().noneMatch(s->s.pending!=null && s.pending.dueAt()<=now))return List.of();
        }
        // 不持内存仓储monitor读取外部身份权威；最终选择仍重新检查当前待办。
        return dueFor(now,java.util.Objects.requireNonNull(knownBots.get(),"knownBots result"),limit);
    }
    @Override public synchronized void clear() { slots.clear(); }
}
