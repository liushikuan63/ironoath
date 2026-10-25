package com.ironoath.web.store.mongo;
import com.ironoath.web.nation.NationCapitalRelocationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.List;
import java.util.Optional;
public final class MongoNationCapitalRelocationStore implements NationCapitalRelocationStore {
 static final String COLLECTION="nation_capital_relocation";
 private final MongoTemplate mongo;
 public MongoNationCapitalRelocationStore(MongoTemplate mongo){this.mongo=java.util.Objects.requireNonNull(mongo);}
 public record Document(@Id String nationId,Snapshot state){}
 private Optional<Snapshot> read(Document doc){
  if(doc==null)return Optional.empty();
  if(doc.state()==null||!doc.nationId().equals(doc.state().nationId()))throw new IllegalStateException("corrupt capital relocation document");
  return Optional.of(doc.state());
 }
 @Override public Optional<Snapshot> findByNationId(String nationId){
  return nationId==null||nationId.isBlank()?Optional.empty():read(mongo.findById(nationId,Document.class,COLLECTION));
 }
 @Override public List<Snapshot> findPending(int limit){
  if(limit<1)throw new IllegalArgumentException("limit must be positive");
  var query=Query.query(Criteria.where("state.stage").ne(Stage.COMPLETED.name())).limit(limit).with(Sort.by(Sort.Direction.ASC,"_id"));
  return mongo.find(query,Document.class,COLLECTION).stream().map(this::read).flatMap(Optional::stream).toList();
 }
 @Override public boolean compareAndSet(Snapshot next,long expectedVersion){
  if(next==null||expectedVersion<0)throw new IllegalArgumentException("invalid CAS");
  try{
   if(expectedVersion==0){
    if(!NationCapitalRelocationStore.allowed(null,next,0))return false;
    mongo.insert(new Document(next.nationId(),next),COLLECTION);return true;
   }
   var current=findByNationId(next.nationId()).orElse(null);
   if(next.equals(current))return true;
   if(!NationCapitalRelocationStore.allowed(current,next,expectedVersion))return false;
   var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(next.nationId()).and("state.version").is(expectedVersion)),
       new Update().set("state",next),Document.class,COLLECTION);
   return result.getModifiedCount()==1||confirmed(next);
  }catch(DuplicateKeyException conflict){return confirmed(next);}
  catch(RuntimeException unknown){
   try{if(confirmed(next))return true;}catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);}
   throw unknown;
  }
 }
 private boolean confirmed(Snapshot next){return findByNationId(next.nationId()).filter(next::equals).isPresent();}
}
