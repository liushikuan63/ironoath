package com.ironoath.web.store.mongo;

import com.ironoath.web.world.NeutralTargetGarrisonStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.DuplicateKeyException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mongo7逐目标单文档CAS，未知写回执仅凭独立完全一致读回确认。 */
public final class MongoNeutralTargetGarrisonStore implements NeutralTargetGarrisonStore {
    private final MongoTemplate mongo;
    public MongoNeutralTargetGarrisonStore(MongoTemplate mongo){this.mongo=mongo;}
    @Override public Map<String,Snapshot> findByTargetIds(Collection<String> ids){return ids.isEmpty()?Map.of():read(Query.query(Criteria.where("_id").in(ids)));}
    @Override public Map<String,Snapshot> findAll(int limit){
        if(limit<1)throw new IllegalArgumentException("中立守军扫描上限须为正");
        return read(new Query().limit(limit).with(org.springframework.data.domain.Sort.by("_id")));
    }
    private Map<String,Snapshot> read(Query query){
        Map<String,Snapshot> found=new LinkedHashMap<>();
        for(var doc:mongo.find(query,NeutralTargetGarrisonDocument.class,NeutralTargetGarrisonDocument.COLLECTION)){
            if(doc.state()==null || !doc.targetId().equals(doc.state().targetId()))throw new IllegalStateException("中立守军文档损坏，不能视为首次交战");
            found.put(doc.targetId(),doc.state());
        }return Map.copyOf(found);
    }
    @Override public boolean compareAndSet(Snapshot next,long expectedVersion){
        NeutralTargetGarrisonStore.validateWrite(next,expectedVersion);
        try{
            if(expectedVersion==0){mongo.insert(new NeutralTargetGarrisonDocument(next.targetId(),next),NeutralTargetGarrisonDocument.COLLECTION);return true;}
            var current=findByTargetIds(List.of(next.targetId())).get(next.targetId());
            if(next.equals(current))return true;
            if(current==null || current.version()!=expectedVersion || current.operationId().equals(next.operationId()) || !NeutralTargetGarrisonStore.allows(current,next))return false;
            var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(next.targetId()).and("state.version").is(expectedVersion).and("state.operationId").ne(next.operationId())),new Update().set("state",next),NeutralTargetGarrisonDocument.class,NeutralTargetGarrisonDocument.COLLECTION);
            return result.getModifiedCount()==1 || confirmed(next);
        }catch(DuplicateKeyException conflict){return confirmed(next);}
        catch(RuntimeException writeFailure){
            try{if(confirmed(next))return true;}catch(RuntimeException readFailure){if(readFailure!=writeFailure)writeFailure.addSuppressed(readFailure);}throw writeFailure;
        }
    }
    private boolean confirmed(Snapshot next){return next.equals(findByTargetIds(List.of(next.targetId())).get(next.targetId()));}
}
