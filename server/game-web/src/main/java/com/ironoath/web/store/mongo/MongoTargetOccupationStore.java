package com.ironoath.web.store.mongo;

import com.ironoath.web.world.TargetOccupationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mongo7单文档CAS。只依赖_id唯一索引；不重置集合、不回填，也不改变现有玩家存档。 */
public final class MongoTargetOccupationStore implements TargetOccupationStore {
    private final MongoTemplate mongo;
    public MongoTargetOccupationStore(MongoTemplate mongo) { this.mongo=mongo; }

    @Override public Map<String,Snapshot> findByTargetIds(Collection<String> ids) {
        if(ids.isEmpty())return Map.of();
        return read(Query.query(Criteria.where("_id").in(ids)));
    }

    @Override public Map<String,Snapshot> findAll(int limit) {
        if(limit<1)throw new IllegalArgumentException("归属扫描上限必须为正");
        return read(new Query().limit(limit).with(org.springframework.data.domain.Sort.by("_id")));
    }

    private Map<String,Snapshot> read(Query query) {
        Map<String,Snapshot> found=new LinkedHashMap<>();
        for(var doc:mongo.find(query,TargetOccupationDocument.class,
                TargetOccupationDocument.COLLECTION)) {
            if(doc.state()==null || !doc.targetId().equals(doc.state().targetId())) {
                throw new IllegalStateException("战略目标归属文档损坏，拒绝当作中立档");
            }
            found.put(doc.targetId(),doc.state());
        }
        return Map.copyOf(found);
    }

    @Override public boolean compareAndSet(Snapshot next,long expectedVersion) {
        TargetOccupationStore.validateWrite(next,expectedVersion);
        try {
            if(expectedVersion==0L) {
                mongo.insert(new TargetOccupationDocument(next.targetId(),next),TargetOccupationDocument.COLLECTION);
                return true;
            }
            var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(next.targetId())
                            .and("state.version").is(expectedVersion)
                            .and("state.operationId").ne(next.operationId())),new Update().set("state",next),
                    TargetOccupationDocument.class,TargetOccupationDocument.COLLECTION);
            if(result.getModifiedCount()==1L)return true;
            return confirmed(next);
        } catch(DuplicateKeyException conflict) {
            return confirmed(next);
        } catch(RuntimeException unknownWrite) {
            try { if(confirmed(next))return true; }
            catch(RuntimeException readFailure) { unknownWrite.addSuppressed(readFailure); }
            throw unknownWrite;
        }
    }

    private boolean confirmed(Snapshot next) {
        return next.equals(findByTargetIds(java.util.List.of(next.targetId())).get(next.targetId()));
    }
}
