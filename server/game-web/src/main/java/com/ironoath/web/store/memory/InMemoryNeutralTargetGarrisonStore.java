package com.ironoath.web.store.memory;

import com.ironoath.web.world.NeutralTargetGarrisonStore;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只用于内存模式，逐目标原子CAS；重启持久化由Mongo实现承担。 */
public final class InMemoryNeutralTargetGarrisonStore implements NeutralTargetGarrisonStore {
    private final ConcurrentHashMap<String,Snapshot> states=new ConcurrentHashMap<>();
    @Override public Map<String,Snapshot> findByTargetIds(Collection<String> ids) {
        Map<String,Snapshot> found=new LinkedHashMap<>();for(String id:ids){var s=states.get(id);if(s!=null)found.put(id,s);}return Map.copyOf(found);
    }
    @Override public Map<String,Snapshot> findAll(int limit) {
        if(limit<1)throw new IllegalArgumentException("中立守军扫描上限须为正");
        Map<String,Snapshot> found=new LinkedHashMap<>();states.values().stream().sorted(java.util.Comparator.comparing(Snapshot::targetId)).limit(limit).forEach(s->found.put(s.targetId(),s));return Map.copyOf(found);
    }
    @Override public boolean compareAndSet(Snapshot next,long expectedVersion) {
        NeutralTargetGarrisonStore.validateWrite(next,expectedVersion);var applied=new AtomicBoolean();
        states.compute(next.targetId(),(id,current)->{
            if(next.equals(current)){applied.set(true);return current;}
            if((current==null?0:current.version())!=expectedVersion || (current!=null && current.operationId().equals(next.operationId())) || !NeutralTargetGarrisonStore.allows(current,next))return current;
            applied.set(true);return next;
        });return applied.get();
    }
}
