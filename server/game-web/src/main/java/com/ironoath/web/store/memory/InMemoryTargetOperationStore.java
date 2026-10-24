package com.ironoath.web.store.memory;

import com.ironoath.web.world.TargetOperationStore;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryTargetOperationStore implements TargetOperationStore {
    private final ConcurrentHashMap<String,Snapshot> states=new ConcurrentHashMap<>();
    @Override public Optional<Snapshot> findByTargetId(String id){return Optional.ofNullable(states.get(id));}
    @Override public Map<String,Snapshot> findByTargetIds(Collection<String> ids){
        if(ids==null || ids.isEmpty())return Map.of();var out=new LinkedHashMap<String,Snapshot>();
        ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(id->{var state=states.get(id);if(state!=null)out.put(id,state);});
        return Map.copyOf(out);
    }
    @Override public Optional<Snapshot> findByOperationId(String op){
        if(op==null || op.isBlank())return Optional.empty();
        return states.values().stream().filter(s->s.operationId().equals(op)).findFirst();
    }
    @Override public List<String> pendingTargetIds(Collection<String> ids){
        return states.values().stream().filter(s->ids.contains(s.targetId()) && !s.completed())
                .map(Snapshot::targetId).sorted().toList();
    }
    @Override public List<String> findAllTargetIds(int limit){
        if(limit<1)throw new IllegalArgumentException("目标操作扫描上限须为正");
        return states.keySet().stream().sorted().limit(limit).toList();
    }
    @Override public boolean compareAndSet(Snapshot next,long expected){
        TargetOperationStore.validateWrite(next,expected);var accepted=new AtomicBoolean();
        states.compute(next.targetId(),(id,current)->{
            if(next.equals(current)){accepted.set(true);return current;}
            if(TargetOperationStore.allowed(current,next,expected)){accepted.set(true);return next;}
            return current;
        });return accepted.get();
    }
}
