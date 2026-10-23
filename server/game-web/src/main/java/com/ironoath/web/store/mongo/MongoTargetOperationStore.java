package com.ironoath.web.store.mongo;

import com.ironoath.web.world.TargetOperationStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 单文档版本CAS，不建默认槽、不清集合；未知写入只由完整独立回执确认。 */
public final class MongoTargetOperationStore implements TargetOperationStore {
    private final MongoTemplate mongo;
    public MongoTargetOperationStore(MongoTemplate mongo){this.mongo=mongo;}
    private Optional<Snapshot> read(TargetOperationDocument doc){
        if(doc==null)return Optional.empty();
        if(doc.state()==null || !doc.targetId().equals(doc.state().targetId()))
            throw new IllegalStateException("目标操作文档损坏，不能当作没有待恢复计划");
        return Optional.of(doc.state());
    }
    @Override public Optional<Snapshot> findByTargetId(String id){
        return read(mongo.findById(id,TargetOperationDocument.class,TargetOperationDocument.COLLECTION));
    }
    @Override public Optional<Snapshot> findByOperationId(String op){
        if(op==null || op.isBlank())return Optional.empty();
        return read(mongo.findOne(Query.query(new Criteria().orOperator(Criteria.where("state.plan.operationId").is(op),
                        Criteria.where("state.combatPlan.operationId").is(op))),
                TargetOperationDocument.class,TargetOperationDocument.COLLECTION));
    }
    @Override public List<String> pendingTargetIds(Collection<String> ids){
        if(ids.isEmpty())return List.of();
        var q=Query.query(Criteria.where("_id").in(ids).and("state.completed").ne(true));
        q.fields().include("_id");q.with(Sort.by("_id"));
        return mongo.find(q,Document.class,TargetOperationDocument.COLLECTION).stream().map(d->d.getString("_id")).toList();
    }
    @Override public List<String> findAllTargetIds(int limit){
        if(limit<1)throw new IllegalArgumentException("目标操作扫描上限须为正");
        var q=new Query().limit(limit).with(Sort.by("_id"));q.fields().include("_id");
        return mongo.find(q,Document.class,TargetOperationDocument.COLLECTION).stream().map(d->d.getString("_id")).toList();
    }
    @Override public boolean compareAndSet(Snapshot next,long expected){
        TargetOperationStore.validateWrite(next,expected);
        try {
            if(expected==0){
                if(!TargetOperationStore.allowed(null,next,expected))return false;
                mongo.insert(new TargetOperationDocument(next.targetId(),next),TargetOperationDocument.COLLECTION);return true;
            }
            var current=findByTargetId(next.targetId()).orElse(null);
            if(next.equals(current))return true;
            if(!TargetOperationStore.allowed(current,next,expected))return false;
            var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(next.targetId()).and("state.version").is(expected)),
                    new Update().set("state",next),TargetOperationDocument.class,TargetOperationDocument.COLLECTION);
            return result.getModifiedCount()==1 || confirmed(next);
        } catch(DuplicateKeyException conflict){return confirmed(next);}
        catch(RuntimeException unknown){
            try{if(confirmed(next))return true;}catch(RuntimeException readFailure){if(readFailure!=unknown)unknown.addSuppressed(readFailure);}
            throw unknown;
        }
    }
    private boolean confirmed(Snapshot next){return findByTargetId(next.targetId()).filter(next::equals).isPresent();}
}
