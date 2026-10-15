package com.ironoath.web.store.mongo;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoDatabaseUtils;
import org.springframework.data.mongodb.core.DbCallback;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        // Use the point-read converter rather than guessing hex/case aliases from returned BSON IDs.
        var converter=mongo.getConverter();
        var mapped=new QueryMapper(converter).getMappedObject(query.getQueryObject(),
                converter.getMappingContext().getRequiredPersistentEntity(ProfileDocument.class));
        var mappedIds=mapped.get("_id",org.bson.Document.class).getList("$in",Object.class);
        if(!hasSimpleCollation())return existingIdsWithCollation(List.copyOf(requested),mapped,mappedIds);
        Set<Object> physicalIds=new HashSet<>();
        for(var row:mongo.query(ProfileDocument.class).inCollection(COLLECTION)
                .as(org.bson.Document.class).matching(query).all())physicalIds.add(row.get("_id"));
        Set<String> found=new LinkedHashSet<>();
        int index=0;
        for(String id:requested)if(physicalIds.contains(mappedIds.get(index++)))found.add(id);
        return Set.copyOf(found);
    }
    /** Collection metadata is read anew; neither identities nor collation are cached across calls. */
    private boolean hasSimpleCollation() {
        if(TransactionSynchronizationManager.isSynchronizationActive()
                || MongoDatabaseUtils.isTransactionActive(mongo.getMongoDatabaseFactory()))return false;
        Document metadata;
        try {
            metadata=mongo.execute((DbCallback<Document>)database->database.listCollections()
                    .filter(new Document("name",COLLECTION)).first());
        } catch(RuntimeException failure) {
            // Only an unauthorized metadata command is a capability fallback. Authentication/network failures propagate.
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                if(cause instanceof com.mongodb.MongoCommandException command && command.getErrorCode()==13)return false;
            throw failure;
        }
        if(metadata==null || !COLLECTION.equals(metadata.get("name")) || !"collection".equals(metadata.get("type"))
                || !(metadata.get("options") instanceof Document options))return false;
        if(!options.containsKey("collation"))return true;
        return options.get("collation") instanceof Document collation && "simple".equals(collation.get("locale"));
    }
    /** Mongo compares mapped keys using its own collation; returned indices identify the original request labels. */
    private Set<String> existingIdsWithCollation(List<String> labels,Document mapped,List<Object> mappedIds) {
        var key=new Document("$arrayElemAt",List.of("$requestedKeys","$$index"));
        var matches=new Document("$cond",List.of(
                new Document("$lte",List.of(new Document("$size","$present"),new Document("$size","$missing"))),
                new Document("$in",List.of(key,"$present")),
                new Document("$not",List.of(new Document("$in",List.of(key,"$missing"))))));
        var indices=new Document("$filter",new Document("input",new Document("$range",List.of(0,new Document("$size","$requestedKeys"))))
                .append("as","index").append("cond",matches));
        var pipeline=List.of(new Document("$match",mapped),new Document("$project",new Document("_id",1)),
                new Document("$group",new Document("_id",null).append("present",new Document("$addToSet","$_id"))),
                new Document("$set",new Document("requestedKeys",new Document("$literal",mappedIds))),
                new Document("$set",new Document("missing",new Document("$setDifference",List.of("$requestedKeys","$present")))),
                new Document("$project",new Document("_id",0).append("indices",indices)));
        Document row=mongo.execute(COLLECTION,collection->collection.aggregate(pipeline).first());
        if(row==null)return Set.of();
        Set<String> found=new LinkedHashSet<>();
        for(int index:row.getList("indices",Integer.class))found.add(labels.get(index));
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
