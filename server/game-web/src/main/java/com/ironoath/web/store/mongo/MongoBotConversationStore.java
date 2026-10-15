package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.ironoath.web.bot.BotConversationStore;

/** 每Bot一文档；接受/完成均以条件更新实现，多写者不重复占槽。 */
@Component
@ConditionalOnProperty(name="ironoath.storage", havingValue="mongo")
public class MongoBotConversationStore implements BotConversationStore {
    public static final String COLLECTION = "bot_conversations";
    public record Slot(@Id String botId, long observedAt, long nextChatAt, Pending pending) { }
    private final MongoTemplate mongo;
    public MongoBotConversationStore(MongoTemplate mongo) { this.mongo = mongo; }

    @Override public boolean offer(Pending pending, long cooldownMillis) {
        Query id = Query.query(Criteria.where("_id").is(pending.botId()));
        mongo.upsert(id, new Update().setOnInsert("observedAt", 0L).setOnInsert("nextChatAt", 0L), Slot.class, COLLECTION);
        Query available = Query.query(Criteria.where("_id").is(pending.botId())
                .and("observedAt").lt(pending.sequence()).and("nextChatAt").lte(pending.sequence())
                .and("pending").is(null));
        boolean accepted = mongo.updateFirst(available, new Update().set("pending", pending)
                .set("observedAt", pending.sequence()).set("nextChatAt", pending.sequence() + cooldownMillis),
                Slot.class, COLLECTION).getModifiedCount() == 1;
        if (!accepted) mongo.updateFirst(id, new Update().max("observedAt", pending.sequence()), Slot.class, COLLECTION);
        return accepted;
    }
    @Override public List<Pending> due(long now, int limit) {
        Query query = Query.query(Criteria.where("pending.dueAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "pending.dueAt", "_id")).limit(limit);
        return mongo.find(query, Slot.class, COLLECTION).stream().map(Slot::pending).toList();
    }
    @Override public List<Pending> dueFor(long now, java.util.Collection<String> botIds, int limit) {
        if (botIds.isEmpty()) return List.of();
        // 空队列不必把全部已知Bot放进IN；有工作时仍先限定身份再取批次，避免未知Bot挤占名额。
        if (!mongo.exists(Query.query(Criteria.where("pending.dueAt").lte(now)), Slot.class, COLLECTION)) return List.of();
        return selectDueKnown(now,botIds,limit);
    }
    @Override public List<Pending> dueForKnownBots(long now,
            java.util.function.Supplier<? extends java.util.Collection<String>> knownBots, int limit) {
        java.util.Objects.requireNonNull(knownBots,"knownBots");
        if (!mongo.exists(Query.query(Criteria.where("pending.dueAt").lte(now)), Slot.class, COLLECTION)) return List.of();
        var ids=java.util.Objects.requireNonNull(knownBots.get(),"knownBots result");
        return ids.isEmpty()?List.of():selectDueKnown(now,ids,limit);
    }
    private List<Pending> selectDueKnown(long now,java.util.Collection<String> botIds,int limit) {
        Query query=Query.query(Criteria.where("_id").in(botIds).and("pending.dueAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC,"pending.dueAt","_id")).limit(limit);
        return mongo.find(query,Slot.class,COLLECTION).stream().map(Slot::pending).toList();
    }
    @Override public boolean complete(String botId, long sequence, long now, long cooldownMillis) {
        Query query = Query.query(Criteria.where("_id").is(botId).and("pending.sequence").is(sequence));
        return mongo.updateFirst(query, new Update().unset("pending").max("nextChatAt", now + cooldownMillis),
                Slot.class, COLLECTION).getModifiedCount() == 1;
    }
    @Override public void clear() { mongo.remove(new Query(), COLLECTION); }
}
