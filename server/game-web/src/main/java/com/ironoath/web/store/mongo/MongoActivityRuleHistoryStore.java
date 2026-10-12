package com.ironoath.web.store.mongo;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.ironoath.web.activity.ActivityRuleHistoryStore;

/** 规则历史单文档，追加以版本CAS保护；不扫玩家、不修改验收账号。 */
public final class MongoActivityRuleHistoryStore implements ActivityRuleHistoryStore {
    public static final String COLLECTION = "activity_rule_history";
    private static final String KEY = "activity";
    public record Document(@Id String id, int version, List<Revision> revisions) { }
    private final MongoTemplate mongo;
    public MongoActivityRuleHistoryStore(MongoTemplate mongo) { this.mongo = mongo; }
    @Override public List<Revision> load() {
        Document doc = mongo.findById(KEY, Document.class, COLLECTION);
        if (doc == null) return List.of();
        if (doc.revisions() == null || doc.version() != doc.revisions().size()) {
            throw new IllegalStateException("活动规则历史版本与内容不一致，拒绝猜测旧权益");
        }
        return List.copyOf(doc.revisions());
    }
    @Override public boolean saveIfVersion(int version, List<Revision> revisions) {
        if (revisions.size() != version + 1) throw new IllegalArgumentException("规则历史必须逐笔追加");
        if (version == 0) {
            try { mongo.insert(new Document(KEY, 1, revisions), COLLECTION); return true; }
            catch (DuplicateKeyException concurrent) { return false; }
        }
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(KEY).and("version").is(version)),
                new Update().set("version", revisions.size()).set("revisions", revisions), COLLECTION)
                .getModifiedCount() == 1;
    }
}
