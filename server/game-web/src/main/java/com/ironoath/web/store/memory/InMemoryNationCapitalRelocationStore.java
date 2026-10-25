package com.ironoath.web.store.memory;
import com.ironoath.web.nation.NationCapitalRelocationStore;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
public final class InMemoryNationCapitalRelocationStore implements NationCapitalRelocationStore {
 private final ConcurrentHashMap<String,Snapshot> states=new ConcurrentHashMap<>();
 @Override public Optional<Snapshot> findByNationId(String nationId){return nationId==null?Optional.empty():Optional.ofNullable(states.get(nationId));}
 @Override public List<Snapshot> findPending(int limit){
  if(limit<1)throw new IllegalArgumentException("limit must be positive");
  return states.values().stream().filter(s->s.stage()!=Stage.COMPLETED).sorted(Comparator.comparing(Snapshot::nationId)).limit(limit).toList();
 }
 @Override public boolean compareAndSet(Snapshot next,long expectedVersion){
  if(next==null||expectedVersion<0)throw new IllegalArgumentException("invalid CAS");
  var accepted=new java.util.concurrent.atomic.AtomicBoolean();
  states.compute(next.nationId(),(id,current)->{
   if(next.equals(current)){accepted.set(true);return current;}
   if(NationCapitalRelocationStore.allowed(current,next,expectedVersion)){accepted.set(true);return next;}return current;
  });
  return accepted.get();
 }
}
