package com.ironoath.web.store.memory;

import com.ironoath.web.world.TargetOccupationStore;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单进程原子归属仓储；不可变快照不泄露可变引用，重启不保留，仅供内存模式。 */
public final class InMemoryTargetOccupationStore implements TargetOccupationStore {
    private final ConcurrentHashMap<String,Snapshot> states=new ConcurrentHashMap<>();

    @Override public Map<String,Snapshot> findByTargetIds(Collection<String> ids) {
        Map<String,Snapshot> found=new LinkedHashMap<>();
        for(String id:ids) { var state=states.get(id); if(state!=null)found.put(id,state); }
        return Map.copyOf(found);
    }

    @Override public boolean compareAndSet(Snapshot next,long expectedVersion) {
        TargetOccupationStore.validateWrite(next,expectedVersion);
        var applied=new AtomicBoolean();
        states.compute(next.targetId(),(id,current)->{
            if(next.equals(current)) { applied.set(true); return current; }
            if(current!=null && current.operationId().equals(next.operationId()))return current;
            long version=current==null?0L:current.version();
            if(version!=expectedVersion)return current;
            applied.set(true);return next;
        });
        return applied.get();
    }

    @Override public Map<String,Snapshot> findAll(int limit) {
        if(limit<1)throw new IllegalArgumentException("归属扫描上限必须为正");
        Map<String,Snapshot> found=new LinkedHashMap<>();
        states.values().stream().sorted(java.util.Comparator.comparing(Snapshot::targetId))
                .limit(limit).forEach(s->found.put(s.targetId(),s));
        return Map.copyOf(found);
    }
}
