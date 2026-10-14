package com.ironoath.web.store.memory;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ironoath.web.bot.BotAttackLedgerStore;

@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="memory",matchIfMissing=true)
public class InMemoryBotAttackLedgerStore implements BotAttackLedgerStore {
    private record Entry(Attack attack,boolean pending) { }
    private final Map<String,Entry> entries = new HashMap<>();
    private Entry existing(Attack attack) {
        Entry old=entries.get(attack.sourceId());
        if(old!=null && !old.attack().victimId().equals(attack.victimId()))
            throw new IllegalStateException("攻击来源已有不同受害者，拒绝改写");
        return old;
    }
    @Override public synchronized Admission reserve(Attack attack,int limit) {
        if(existing(attack)!=null) return Admission.ALREADY_PRESENT;
        if(occupied(attack.victimId(),attack.at())>=limit) return Admission.QUOTA_FULL;
        entries.put(attack.sourceId(),new Entry(attack,true));return Admission.ACQUIRED;
    }
    @Override public synchronized void release(Attack attack) {
        Entry old=existing(attack);
        if(old!=null && old.pending()) entries.remove(attack.sourceId());
    }
    @Override public synchronized boolean record(Attack attack) {
        Entry old=existing(attack);
        if(old!=null && !old.pending()) return false;
        entries.put(attack.sourceId(),new Entry(old==null ? attack : old.attack(),false));return true;
    }
    @Override public synchronized long count(String victim,long now) {
        return entries.values().stream().filter(e->!e.pending() && e.attack().victimId().equals(victim) && e.attack().at()>now-WINDOW_MILLIS).count();
    }
    @Override public synchronized long occupied(String victim,long now) {
        return entries.values().stream().filter(e->e.attack().victimId().equals(victim) && (e.pending() || e.attack().at()>now-WINDOW_MILLIS)).count();
    }
    @Override public synchronized void evict(long now) { entries.entrySet().removeIf(e->!e.getValue().pending() && e.getValue().attack().at()<=now-WINDOW_MILLIS); }
    @Override public synchronized void clear() { entries.clear(); }
}
