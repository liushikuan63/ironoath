package com.ironoath.web.store.mongo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import com.ironoath.web.bot.BotBehaviorStore;

/** 每字段原子更新，回应使用max与旧值判归属，不覆盖并发写入的其他水位。 */
@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="mongo")
public class MongoBotBehaviorStore implements BotBehaviorStore {
    public static final String COLLECTION = "bot_behavior";
    public record BehaviorDocument(@Id String id,Long reactedUpTo,String greetedAlliance,Long lastSeekAllianceAt) {
        State state() { return new State(reactedUpTo,greetedAlliance,lastSeekAllianceAt); }
    }
    private final MongoTemplate mongo;
    public MongoBotBehaviorStore(MongoTemplate mongo) { this.mongo = mongo; }
    private Query id(String id) { return Query.query(Criteria.where("_id").is(id)); }
    @Override public State find(String id) {
        var saved = mongo.findById(id,BehaviorDocument.class,COLLECTION);
        return saved == null ? State.EMPTY : saved.state();
    }
    @Override public boolean advanceReaction(String id,long hitAt) {
        var previous = mongo.findAndModify(id(id),new Update().max("reactedUpTo",hitAt),
                FindAndModifyOptions.options().upsert(true),BehaviorDocument.class,COLLECTION);
        return previous == null || previous.reactedUpTo() == null || previous.reactedUpTo() < hitAt;
    }
    @Override public void recordGreeting(String id,String allianceId) {
        mongo.upsert(id(id),new Update().set("greetedAlliance",allianceId),COLLECTION);
    }
    @Override public boolean trySeekAlliance(String id,long now,long interval) {
        // 先确保空记录；setOnInsert不会覆盖已有字段。真正的节流领取是第二条原子条件更新。
        mongo.upsert(id(id),new Update().setOnInsert("lastSeekAllianceAt",null),COLLECTION);
        var allowed = new Criteria().orOperator(Criteria.where("lastSeekAllianceAt").is(null),
                Criteria.where("lastSeekAllianceAt").lte(now-interval));
        var previous = mongo.findAndModify(id(id).addCriteria(allowed),new Update().set("lastSeekAllianceAt",now),
                FindAndModifyOptions.options(),BehaviorDocument.class,COLLECTION);
        return previous != null;
    }
    @Override public void clear() { mongo.remove(new Query(),COLLECTION); }
}
