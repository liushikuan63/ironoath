package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import com.ironoath.web.bot.BotAttackLedgerStore;

/** 单受害者文档CAS：额度检查与占位属于同一次原子更新，不需要副本集事务。 */
@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="mongo")
public class MongoBotAttackLedgerStore implements BotAttackLedgerStore {
    public static final String COLLECTION = "bot_attack_ledger";
    public static final String BUDGET_COLLECTION = "bot_attack_budget";
    public record SourceDocument(@Id String id,String victimId,long at) { }
    public record Entry(String sourceId,long at,boolean pending) { }
    public record BudgetDocument(@Id String id,List<Entry> entries,long revision) { }
    private record Change<T>(List<Entry> entries,T result) { }
    private final MongoTemplate mongo;
    public MongoBotAttackLedgerStore(MongoTemplate mongo) { this.mongo = mongo; }
    private void ensureSource(Attack attack) {
        try { mongo.insert(new SourceDocument(attack.sourceId(),attack.victimId(),attack.at()),COLLECTION); }
        catch(DuplicateKeyException duplicate) {
            var old=mongo.findAndModify(Query.query(Criteria.where("_id").is(attack.sourceId()).and("victimId").is(attack.victimId())),
                    new Update().max("at",attack.at()),SourceDocument.class,COLLECTION);
            if(old==null) throw new IllegalStateException("攻击来源已有不同受害者或刚被清理，拒绝改写",duplicate);
        }
    }
    private <T> T mutate(String victim,Function<List<Entry>,Change<T>> operation) {
        try {
            mongo.upsert(Query.query(Criteria.where("_id").is(victim)),
                    new Update().setOnInsert("entries",List.of()).setOnInsert("revision",0L),BUDGET_COLLECTION);
        } catch(DuplicateKeyException concurrentCreation) { /* 并发创建已有同一受害者文档，继续CAS。 */ }
        for(int attempt=0;attempt<64;attempt++) {
            var old=mongo.findById(victim,BudgetDocument.class,BUDGET_COLLECTION);
            if(old==null) throw new IllegalStateException("额度文档被并发删除");
            var change=operation.apply(List.copyOf(old.entries()));
            if(change.entries()==null) return change.result();
            var saved=mongo.findAndModify(Query.query(Criteria.where("_id").is(victim).and("revision").is(old.revision())),
                    new Update().set("entries",change.entries()).inc("revision",1L),
                    FindAndModifyOptions.options().returnNew(true),BudgetDocument.class,BUDGET_COLLECTION);
            if(saved!=null) return change.result();
        }
        throw new IllegalStateException("额度文档持续竞争，未取得归属，拒绝开战");
    }
    @Override public Admission reserve(Attack attack,int limit) {
        ensureSource(attack);
        return mutate(attack.victimId(),entries->{
            if(entries.stream().anyMatch(e->e.sourceId().equals(attack.sourceId()))) return new Change<>(null,Admission.ALREADY_PRESENT);
            long occupied=entries.stream().filter(e->e.pending() || e.at()>attack.at()-WINDOW_MILLIS).count();
            if(occupied>=limit) return new Change<>(null,Admission.QUOTA_FULL);
            var updated=new ArrayList<>(entries);updated.add(new Entry(attack.sourceId(),attack.at(),true));
            return new Change<>(updated,Admission.ACQUIRED);
        });
    }
    @Override public void release(Attack attack) {
        mutate(attack.victimId(),entries->new Change<>(entries.stream().filter(e->!e.pending() || !e.sourceId().equals(attack.sourceId())).toList(),null));
    }
    @Override public boolean record(Attack attack) {
        ensureSource(attack);
        return mutate(attack.victimId(),entries->{
            Entry old=entries.stream().filter(e->e.sourceId().equals(attack.sourceId())).findFirst().orElse(null);
            if(old!=null && !old.pending()) return new Change<>(null,false);
            var updated=new ArrayList<>(entries.stream().filter(e->!e.sourceId().equals(attack.sourceId())).toList());
            updated.add(new Entry(attack.sourceId(),old==null ? attack.at() : old.at(),false));
            return new Change<>(updated,true);
        });
    }
    private List<Entry> entries(String victim) {
        var doc=mongo.findById(victim,BudgetDocument.class,BUDGET_COLLECTION);
        return doc==null ? List.of() : doc.entries();
    }
    @Override public long count(String victim,long now) { return entries(victim).stream().filter(e->!e.pending() && e.at()>now-WINDOW_MILLIS).count(); }
    @Override public long occupied(String victim,long now) { return entries(victim).stream().filter(e->e.pending() || e.at()>now-WINDOW_MILLIS).count(); }
    @Override public void evict(long now) {
        for(var doc:mongo.find(Query.query(Criteria.where("entries").elemMatch(Criteria.where("pending").is(false).and("at").lte(now-WINDOW_MILLIS))),BudgetDocument.class,BUDGET_COLLECTION)) {
            mutate(doc.id(),entries->new Change<>(entries.stream().filter(e->e.pending() || e.at()>now-WINDOW_MILLIS).toList(),null));
        }
        // 未确定占位不随时间释放；无事实/占位的来源元数据才可清理。
        for(var source:mongo.find(Query.query(Criteria.where("at").lte(now-WINDOW_MILLIS)),SourceDocument.class,COLLECTION)) {
            if(entries(source.victimId()).stream().noneMatch(e->e.sourceId().equals(source.id())))
                mongo.remove(Query.query(Criteria.where("_id").is(source.id()).and("victimId").is(source.victimId()).and("at").lte(now-WINDOW_MILLIS)),COLLECTION);
        }
    }
    @Override public void clear() { mongo.remove(new Query(),BUDGET_COLLECTION);mongo.remove(new Query(),COLLECTION); }
}
