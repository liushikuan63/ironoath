package com.ironoath.web.store.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ironoath.web.bot.BotBehaviorStore;

@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="memory",matchIfMissing=true)
public class InMemoryBotBehaviorStore implements BotBehaviorStore {
    private final ConcurrentHashMap<String,State> states = new ConcurrentHashMap<>();
    @Override public State find(String id) { return states.getOrDefault(id,State.EMPTY); }
    @Override public boolean advanceReaction(String id,long hitAt) {
        var accepted = new AtomicBoolean();
        states.compute(id,(key,old)->{
            State before = old == null ? State.EMPTY : old;
            if(before.reactedUpTo() != null && before.reactedUpTo() >= hitAt) return before;
            accepted.set(true);
            return new State(hitAt,before.greetedAlliance(),before.lastSeekAllianceAt());
        });
        return accepted.get();
    }
    @Override public void recordGreeting(String id,String allianceId) {
        states.compute(id,(key,old)->{
            State before = old == null ? State.EMPTY : old;
            return new State(before.reactedUpTo(),allianceId,before.lastSeekAllianceAt());
        });
    }
    @Override public boolean trySeekAlliance(String id,long now,long interval) {
        var accepted = new AtomicBoolean();
        states.compute(id,(key,old)->{
            State before = old == null ? State.EMPTY : old;
            if(before.lastSeekAllianceAt() != null && now-before.lastSeekAllianceAt() < interval) return before;
            accepted.set(true);
            return new State(before.reactedUpTo(),before.greetedAlliance(),now);
        });
        return accepted.get();
    }
    @Override public void clear() { states.clear(); }
}
