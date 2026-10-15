package com.ironoath.web.store.mongo;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotProfileStore;

/** Mongo画像是跨实例身份权威；登记/更新原子，不以进程缓存把另一个实例的Bot当真人。 */
@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="mongo")
public class MongoBotProfileStore implements BotProfileStore {
    public static final String COLLECTION="bot_profiles";
    public record ProfileDocument(@Id String id,BotProfile profile) { }
    private final MongoTemplate mongo;
    public MongoBotProfileStore(MongoTemplate mongo) { this.mongo=mongo; }
    private Query id(String id) { return Query.query(Criteria.where("_id").is(id)); }
    @Override public Optional<BotProfile> find(String id) {
        if(id==null) return Optional.empty();
        return Optional.ofNullable(mongo.findById(id,ProfileDocument.class,COLLECTION)).map(ProfileDocument::profile);
    }
    @Override public Collection<BotProfile> all() {
        return mongo.find(new Query().with(Sort.by("_id")),ProfileDocument.class,COLLECTION)
                .stream().map(ProfileDocument::profile).toList();
    }
    @Override public Collection<String> ids() {
        Query query=new Query().with(Sort.by("_id"));
        // @Id String may be stored as ObjectId. Keep the original profile ID, including hex case.
        query.fields().include("profile.botId").exclude("_id");
        return mongo.find(query,org.bson.Document.class,COLLECTION).stream()
                .map(row->row.get("profile",org.bson.Document.class).getString("botId")).toList();
    }
    @Override public int size() { return Math.toIntExact(mongo.count(new Query(),COLLECTION)); }
    @Override public Set<String> existingIds(Collection<String> playerIds) {
        if(playerIds==null || playerIds.isEmpty())return Set.of();
        Set<String> requested=new LinkedHashSet<>();
        for(String id:playerIds)if(id!=null)requested.add(id);
        if(requested.isEmpty())return Set.of();
        Query query=Query.query(Criteria.where("_id").in(requested));
        query.fields().include("_id");
        Set<String> found=new HashSet<>();
        for(var row:mongo.find(query,org.bson.Document.class,COLLECTION))found.add(row.getString("_id"));
        return Set.copyOf(found);
    }
    @Override public BotProfile put(BotProfile profile) {
        var old=mongo.findAndModify(id(profile.botId()),new Update().set("profile",profile),
                FindAndModifyOptions.options().upsert(true),ProfileDocument.class,COLLECTION);
        return old==null?null:old.profile();
    }
    @Override public boolean remove(String id) {
        return id!=null&&mongo.findAndRemove(id(id),ProfileDocument.class,COLLECTION)!=null;
    }
    @Override public void clear() { mongo.remove(new Query(),COLLECTION); }
}
