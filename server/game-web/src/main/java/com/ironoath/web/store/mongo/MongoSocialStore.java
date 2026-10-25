package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Squad;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B10 社交域的 MongoDB 实现 —— 小队、联盟、入盟申请、帮助请求、社交事件、聊天记录、集结。
 * 依赖：Spring Data MongoDB、{@link SocialRulesAssembler}（规则是运行时输入，不入档）。
 *
 * <p><b>每个方法都返回重建出来的副本</b>：读到的对象与库里的文档没有任何共享引用，
 * "改了没 save"在两套实现下都是同一个结果（丢掉那笔改动）。这条由
 * {@code SocialStoreEquivalenceTest} 在内存与 Mongo 两侧逐条钉住。
 *
 * <p><b>唯一性收在集合的索引上</b>：小队名、联盟名、联盟标签各有一条唯一索引
 * （见 {@link MongoIndexes#ensure}），入盟申请用 {@code allianceId:playerId} 复合主键。
 * "先查后写"两条语句之间总有竞态窗口，索引是唯一能在并发下仍然成立的保证；
 * 捕获 {@link DuplicateKeyException} 之后给出与内存实现逐字相同的拒绝文案。
 *
 * <p><b>并发口径（2026-09-12 起）</b>：三个 {@code save} 都是乐观锁（CAS）——
 * 建档走 {@code insert}（{@code expectedVersion<=0} 表示"我认为它还不存在"），
 * 更新走"{@code _id} + {@code version} 同时匹配"的原子 {@code $set}，
 * 匹配不到就区分"不存在"与"版本被推进"并拒绝，而不是静默覆盖。
 * 每次更新都把文档的全部字段重新 set 一遍（{@code *Document#toUpdate}），
 * 漏字段与 CAS 是两类不同的错误，前者由逐字段往返测试兜住。
 */
public final class MongoSocialStore implements SocialStore {

    private final MongoTemplate mongo;
    private final SocialRulesAssembler rules;

    public MongoSocialStore(MongoTemplate mongo, SocialRulesAssembler rules) {
        this.mongo = Objects.requireNonNull(mongo, "mongo 不得为 null");
        this.rules = Objects.requireNonNull(rules, "rules 不得为 null");
    }

    // ---------- 小队 ----------

    @Override
    public long saveSquad(Squad squad, long expectedVersion) {
        Objects.requireNonNull(squad, "squad 不得为 null");
        if (mongo.exists(Query.query(Criteria.where("name").is(squad.name()).and("_id").ne(squad.id())),
                SquadDocument.class, SquadDocument.COLLECTION)) {
            throw squadNameConflict(squad.name());
        }
        SquadDocument document = SquadDocument.fromDomain(squad);
        if (expectedVersion <= 0L) {
            try {
                mongo.insert(document, SquadDocument.COLLECTION);
            } catch (DuplicateKeyException e) {
                // 两种可能：_id 撞上（并发建档）或 name 撞上（并发同名）。
                // 唯一索引是并发下唯一还成立的保证：两个请求同时通过上面的 exists 检查时，
                // 第二个会撞在这里 —— 抛与内存实现相同的文案，而不是把 DuplicateKey 漏给上层
                if (mongo.exists(Query.query(Criteria.where("_id").is(squad.id())),
                        SquadDocument.class, SquadDocument.COLLECTION)) {
                    throw alreadyExists("小队", squad.id(),
                            storedVersion(SquadDocument.COLLECTION, squad.id()));
                }
                throw squadNameConflict(squad.name());
            }
            return squad.version();
        }
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(squad.id())
                        .and("version").is(expectedVersion)),
                document.toUpdate(), SquadDocument.class, SquadDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            rejectAsStaleOrMissing("小队", squad.id(), expectedVersion, SquadDocument.COLLECTION);
        }
        return squad.version();
    }

    @Override
    public Optional<Squad> squadOf(String playerId) {
        return Optional.ofNullable(mongo.findOne(
                        Query.query(Criteria.where("members.playerId").is(playerId)),
                        SquadDocument.class, SquadDocument.COLLECTION))
                .map(document -> document.toDomain(rules.squadRules()));
    }

    /**
     * 一批玩家所属小队，一次 $in 查询 —— 与 {@link #squadOf} 打在同一个索引字段上
     * （{@code idx_social_squad_member}，见 {@code MongoIndexes}），所以省掉的是往返不是扫描量。
     *
     * <p>同一份档案里可能有多个要查的成员（联盟成员装配就是一整队人一起问）：档案只读一次，
     * 命中的人各自拿到一份副本。
     *
     * <p><b>不设排序</b>：{@link #squadOf} 用的 {@code findOne} 也没有排序，两口的"一个玩家同时挂在
     * 两支小队里"（脏数据才可能）取哪一支的口径都是自然顺序 —— 加一段排序反而会让两版在
     * 好数据之外的分支上给出不同答案。
     */
    @Override
    public Map<String, Squad> squadsOf(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Map.of();
        }
        Set<String> distinct = new LinkedHashSet<>(playerIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<SquadDocument> documents = mongo.find(
                Query.query(Criteria.where("members.playerId").in(distinct)),
                SquadDocument.class, SquadDocument.COLLECTION);
        Map<String, Squad> out = new LinkedHashMap<>();
        for (SquadDocument document : documents) {
            Squad squad = document.toDomain(rules.squadRules());
            for (String memberId : distinct) {
                if (squad.memberIds().contains(memberId)) {
                    out.put(memberId, squad.copy());
                }
            }
        }
        return out;
    }

    @Override
    public Optional<Squad> squadById(String squadId) {
        return Optional.ofNullable(mongo.findById(squadId, SquadDocument.class, SquadDocument.COLLECTION))
                .map(document -> document.toDomain(rules.squadRules()));
    }

    /**
     * 全部未解散小队，按 id 升序。
     *
     * <p>解散的判据放在 Java 侧（读档之后）而不是写进 {@code Criteria}：这份库里存量档的
     * {@code disbandedAt} 依赖 {@code saveSquad} 的 {@code $set} 白名单，白名单漏一次字段
     * （B25-S2 的 {@code autoTrain} 就是这么栽的）就会让查询条件对老档恒不成立 —— 而那样
     * 错掉的方向是「列表少一行」，比内存版安静。判据写在领域对象上是两版共用的同一句。
     */
    @Override
    public List<Squad> allSquads() {
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, "_id"));
        List<Squad> out = new ArrayList<>();
        for (SquadDocument document : mongo.find(query, SquadDocument.class, SquadDocument.COLLECTION)) {
            Squad squad = document.toDomain(rules.squadRules());
            if (!squad.isDisbanded()) {
                out.add(squad);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean squadNameTaken(String name) {
        return mongo.exists(Query.query(Criteria.where("name").is(name)),
                SquadDocument.class, SquadDocument.COLLECTION);
    }

    /**
     * 成员离开后的收尾。Mongo 侧没有独立的 playerId → squadId 索引（成员查询直接扫 members 数组），
     * 所以只有"小队已解散"这一半需要动作：整档删除，名字唯一索引随之释放。
     * 普通离队/被踢的成员表已经在 {@code saveSquad} 里更新过。
     *
     * <p>identity-exempt: 参数里的 playerId 在这份实现里没有可做的事 —— 端口要清的那张反查索引
     * 只有内存版才有（见 {@code InMemorySocialStore}），这里"什么都不做"才是与内存版等价。
     */
    @Override
    public void unbindSquadMember(String squadId, String playerId) {
        SquadDocument document = mongo.findById(squadId, SquadDocument.class, SquadDocument.COLLECTION);
        if (document != null && document.toDomain(rules.squadRules()).isDisbanded()) {
            mongo.remove(Query.query(Criteria.where("_id").is(squadId)), SquadDocument.COLLECTION);
        }
    }

    // ---------- 联盟 ----------

    @Override
    public long saveAlliance(Alliance alliance, long expectedVersion) {
        return saveAlliance(alliance,expectedVersion,null);
    }

    @Override
    public long saveAlliance(Alliance alliance,long expectedVersion,long changedAt) {
        return saveAlliance(alliance,expectedVersion,Long.valueOf(changedAt));
    }

    private long saveAlliance(Alliance alliance,long expectedVersion,Long changedAt) {
        Objects.requireNonNull(alliance, "alliance 不得为 null");
        if (mongo.exists(Query.query(Criteria.where("name").is(alliance.name())
                        .and("_id").ne(alliance.id())), AllianceDocument.class,
                AllianceDocument.COLLECTION)) {
            throw allianceNameConflict(alliance.name());
        }
        if (mongo.exists(Query.query(Criteria.where("tag").is(alliance.tag())
                        .and("_id").ne(alliance.id())), AllianceDocument.class,
                AllianceDocument.COLLECTION)) {
            throw allianceTagConflict(alliance.tag());
        }
        if (expectedVersion <= 0L) {
            List<AllianceDocument.MemberEpochEntry> epochs=changedAt==null?List.of():alliance.memberIds().stream()
                    .map(playerId->new AllianceDocument.MemberEpochEntry(playerId,changedAt,Long.MAX_VALUE)).toList();
            AllianceDocument document=AllianceDocument.fromDomain(alliance,epochs);
            try {
                mongo.insert(document, AllianceDocument.COLLECTION);
            } catch (DuplicateKeyException e) {
                if (mongo.exists(Query.query(Criteria.where("_id").is(alliance.id())),
                        AllianceDocument.class, AllianceDocument.COLLECTION)) {
                    throw alreadyExists("联盟", alliance.id(),
                            storedVersion(AllianceDocument.COLLECTION, alliance.id()));
                }
                // 两条唯一索引共用同一个异常类型，靠"谁被占了"分辨是哪一条撞的 ——
                // 不分辨就会把"标签重复"报成"名字重复"，而玩家的处置完全不同
                if (mongo.exists(Query.query(Criteria.where("name").is(alliance.name())
                                .and("_id").ne(alliance.id())), AllianceDocument.class,
                        AllianceDocument.COLLECTION)) {
                    throw allianceNameConflict(alliance.name());
                }
                throw allianceTagConflict(alliance.tag());
            }
            return alliance.version();
        }
        AllianceDocument stored=changedAt==null?null:mongo.findById(alliance.id(),AllianceDocument.class,
                AllianceDocument.COLLECTION);
        List<AllianceDocument.MemberEpochEntry> epochs=changedAt==null?null:
                applyMembershipChanges(stored,alliance,changedAt);
        AllianceDocument document=AllianceDocument.fromDomain(alliance,epochs==null?List.of():epochs);
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(alliance.id())
                        .and("version").is(expectedVersion)),
                changedAt==null?document.toUpdate():document.toUpdateWithMembershipEpochs(),
                AllianceDocument.class, AllianceDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            rejectAsStaleOrMissing("联盟", alliance.id(), expectedVersion, AllianceDocument.COLLECTION);
        }
        return alliance.version();
    }

    private static List<AllianceDocument.MemberEpochEntry> applyMembershipChanges(
            AllianceDocument before,Alliance after,long at) {
        if(before==null)throw new IllegalStateException("联盟不存在，无法记录成员历史 id="+after.id());
        if(at<0)throw new IllegalArgumentException("成员变更时刻不得为负");
        List<AllianceDocument.MemberEpochEntry> epochs=new ArrayList<>(before.membershipEpochs()==null
                ?List.of():before.membershipEpochs());
        Set<String> oldMembers=new LinkedHashSet<>();
        for(var member:before.members())oldMembers.add(member.playerId());
        Set<String> newMembers=new LinkedHashSet<>(after.memberIds());
        for(String playerId:oldMembers)if(!newMembers.contains(playerId)) {
            for(int i=epochs.size()-1;i>=0;i--) {
                var epoch=epochs.get(i);
                if(playerId.equals(epoch.playerId()) && epoch.untilAt()==Long.MAX_VALUE) {
                    if(at<epoch.fromAt())throw new IllegalStateException("联盟退会时刻早于加入时刻 playerId="+playerId);
                    epochs.set(i,new AllianceDocument.MemberEpochEntry(playerId,epoch.fromAt(),at));
                    break;
                }
            }
        }
        for(String playerId:newMembers)if(!oldMembers.contains(playerId)) {
            if(epochs.stream().anyMatch(epoch->playerId.equals(epoch.playerId())
                    && epoch.untilAt()==Long.MAX_VALUE))
                throw new IllegalStateException("联盟已有未闭合成员时间段 playerId="+playerId);
            epochs.add(new AllianceDocument.MemberEpochEntry(playerId,at,Long.MAX_VALUE));
        }
        epochs.sort(Comparator.comparing(AllianceDocument.MemberEpochEntry::playerId)
                .thenComparingLong(AllianceDocument.MemberEpochEntry::fromAt));
        return List.copyOf(epochs);
    }

    @Override
    public Optional<Alliance> allianceOf(String playerId) {
        return Optional.ofNullable(mongo.findOne(
                        Query.query(Criteria.where("members.playerId").is(playerId)),
                        AllianceDocument.class, AllianceDocument.COLLECTION))
                .map(document -> document.toDomain(rules.allianceRules()));
    }

    @Override
    public List<AllianceMemberEpoch> allianceMemberEpochs(String allianceId) {
        if(allianceId==null || allianceId.isBlank())return List.of();
        AllianceDocument document=mongo.findById(allianceId,AllianceDocument.class,AllianceDocument.COLLECTION);
        if(document==null)return List.of();
        return safeMembershipEpochs(document).stream()
                .map(epoch->new AllianceMemberEpoch(epoch.playerId(),epoch.fromAt(),epoch.untilAt()))
                .sorted(Comparator.comparingLong(AllianceMemberEpoch::fromAt).thenComparing(AllianceMemberEpoch::playerId))
                .toList();
    }

    @Override
    public List<AllianceMembershipEpoch> allianceMembershipEpochs(String playerId) {
        if(playerId==null || playerId.isBlank())return List.of();
        List<AllianceMembershipEpoch> out=new ArrayList<>();
        List<AllianceDocument> documents=mongo.find(Query.query(Criteria.where("membershipEpochs.playerId").is(playerId)),
                AllianceDocument.class,AllianceDocument.COLLECTION);
        for(var document:documents)for(var epoch:safeMembershipEpochs(document)) {
            if(playerId.equals(epoch.playerId()))out.add(new AllianceMembershipEpoch(document.allianceId(),
                    epoch.fromAt(),epoch.untilAt()));
        }
        out.sort(Comparator.comparingLong(AllianceMembershipEpoch::fromAt)
                .thenComparing(AllianceMembershipEpoch::allianceId));
        return List.copyOf(out);
    }

    @Override
    public List<AllianceMembershipEpoch> allianceMembershipEpochs(
            String playerId,String currentAllianceId,long observedAt) {
        if(playerId==null || playerId.isBlank() || currentAllianceId==null || currentAllianceId.isBlank())
            return allianceMembershipEpochs(playerId);
        if(observedAt<0)throw new IllegalArgumentException("成员观察时刻不得为负");
        for(int attempt=0;attempt<3;attempt++) {
            AllianceDocument current=mongo.findById(currentAllianceId,AllianceDocument.class,
                    AllianceDocument.COLLECTION);
            if(current==null || current.members().stream().noneMatch(member->playerId.equals(member.playerId())))
                return allianceMembershipEpochs(playerId);
            List<AllianceDocument.MemberEpochEntry> epochs=new ArrayList<>(safeMembershipEpochs(current));
            if(epochs.stream().anyMatch(epoch->playerId.equals(epoch.playerId())
                    && epoch.untilAt()==Long.MAX_VALUE))return allianceMembershipEpochs(playerId);
            List<AllianceMembershipEpoch> historical=allianceMembershipEpochs(playerId);
            if(historical.stream().anyMatch(epoch->epoch.untilAt()==Long.MAX_VALUE))
                throw new IllegalStateException("玩家已有未闭合联盟成员时间段 playerId="+playerId);
            epochs.add(new AllianceDocument.MemberEpochEntry(playerId,observedAt,Long.MAX_VALUE));
            var update=mongo.updateFirst(Query.query(Criteria.where("_id").is(currentAllianceId)
                            .and("version").is(current.version())
                            .and("members.playerId").is(playerId)),
                    new Update().set("membershipEpochs",List.copyOf(epochs)),
                    AllianceDocument.class,AllianceDocument.COLLECTION);
            if(update.getMatchedCount()>0)return allianceMembershipEpochs(playerId);
        }
        throw new IllegalStateException("联盟成员历史基线并发更新失败 playerId="+playerId+" allianceId="+currentAllianceId);
    }

    private static List<AllianceDocument.MemberEpochEntry> safeMembershipEpochs(AllianceDocument document) {
        return document.membershipEpochs()==null?List.of():document.membershipEpochs();
    }

    /**
     * 一批玩家各自所属的联盟，一次 {@code members.playerId} 的 $in 查询 —— 与 {@link #allianceOf}
     * 打在同一个索引字段上（{@code idx_social_alliance_member}，见 {@code MongoIndexes}），
     * 所以省掉的是往返不是扫描量（与 {@link #squadsOf} 同一条）。
     *
     * <p>一个盟可以有多个被点名的成员：档案只读一次，命中的人各自拿到一份副本。
     * 刻意不设排序 —— {@link #allianceOf} 的 {@code findOne} 也没有排序，两版在"一个人挂在两个盟里"
     * （脏数据才可能）这种分支上取的口径必须相同。
     */
    @Override
    public Map<String, Alliance> alliancesOf(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Map.of();
        }
        Set<String> distinct = new LinkedHashSet<>(playerIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<AllianceDocument> documents = mongo.find(
                Query.query(Criteria.where("members.playerId").in(distinct)),
                AllianceDocument.class, AllianceDocument.COLLECTION);
        Map<String, Alliance> out = new LinkedHashMap<>();
        for (AllianceDocument document : documents) {
            Alliance alliance = document.toDomain(rules.allianceRules());
            for (String memberId : distinct) {
                if (alliance.memberIds().contains(memberId)) {
                    out.put(memberId, alliance.copy());
                }
            }
        }
        return out;
    }

    @Override
    public List<Alliance> allAlliances() {
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, "_id"));
        List<Alliance> out = new ArrayList<>();
        for (AllianceDocument document : mongo.find(query, AllianceDocument.class,
                AllianceDocument.COLLECTION)) {
            if(document.disbandedAt()<=0)out.add(document.toDomain(rules.allianceRules()));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<Alliance> allianceById(String allianceId) {
        AllianceDocument document=mongo.findById(allianceId,AllianceDocument.class,AllianceDocument.COLLECTION);
        return Optional.ofNullable(document).filter(value->value.disbandedAt()<=0)
                .map(value -> value.toDomain(rules.allianceRules()));
    }

    @Override
    public boolean allianceNameTaken(String name) {
        return mongo.exists(Query.query(Criteria.where("name").is(name)),
                AllianceDocument.class, AllianceDocument.COLLECTION);
    }

    @Override
    public boolean allianceTagTaken(String tag) {
        return mongo.exists(Query.query(Criteria.where("tag").is(tag)),
                AllianceDocument.class, AllianceDocument.COLLECTION);
    }

    /**
     * Mongo 侧成员查询直接走 members 数组，没有需要解绑的独立索引。
     *
     * <p>identity-exempt: 与 {@link #unbindSquadMember} 同一条 —— 端口那个参数是为内存版的
     * 反查索引准备的，这里刻意什么都不做（做一次删除反而会掩盖"忘记 save"的错误）。
     */
    @Override
    public void unbindAllianceMember(String allianceId, String playerId) {
        // 有意为空：这只是内存实现里那张反查索引的收尾动作。
        // 成员表本身在 saveAlliance 时已经更新，这里再做一次删除会掩盖"忘记 save"的错误。
    }

    @Override
    public void removeAlliance(Alliance alliance) {
        Objects.requireNonNull(alliance, "alliance 不得为 null");
        AllianceDocument before=mongo.findById(alliance.id(),AllianceDocument.class,AllianceDocument.COLLECTION);
        if(before==null)return;
        if(before.disbandedAt()>0)return;
        long closedAt=alliance.disbandedAt();
        if(closedAt<=0)throw new IllegalArgumentException("解散联盟必须带有效disbandedAt");
        long expectedVersion=alliance.version()-1L;
        if(before.version()!=expectedVersion)
            rejectAsStaleOrMissing("联盟",alliance.id(),expectedVersion,AllianceDocument.COLLECTION);
        List<AllianceDocument.MemberEpochEntry> epochs=new ArrayList<>(safeMembershipEpochs(before));
        for(var member:before.members()) {
            for(int i=epochs.size()-1;i>=0;i--) {
                var epoch=epochs.get(i);
                if(member.playerId().equals(epoch.playerId()) && epoch.untilAt()==Long.MAX_VALUE) {
                    if(closedAt<epoch.fromAt())throw new IllegalStateException("联盟解散时刻早于成员加入时刻 playerId="+member.playerId());
                    epochs.set(i,new AllianceDocument.MemberEpochEntry(member.playerId(),epoch.fromAt(),closedAt));
                    break;
                }
            }
        }
        AllianceDocument archived=AllianceDocument.fromDomain(alliance,epochs);
        String archiveKey="__disbanded__"+UUID.randomUUID();
        Update update=archived.toUpdateWithMembershipEpochs().set("name",archiveKey).set("tag",archiveKey);
        var result=mongo.updateFirst(Query.query(Criteria.where("_id").is(alliance.id())
                        .and("version").is(expectedVersion)),update,AllianceDocument.class,AllianceDocument.COLLECTION);
        if(result.getMatchedCount()==0)
            rejectAsStaleOrMissing("联盟",alliance.id(),expectedVersion,AllianceDocument.COLLECTION);
    }

    // ---------- 解散保护期（验收 7） ----------

    @Override
    public void protectFromCreating(String playerId, long until) {
        // $max：只延长不缩短。upsert 保证第一次解散就建档，不需要另走一次插入
        mongo.upsert(Query.query(Criteria.where("_id").is(playerId)),
                new Update().max("disbandProtectedUntil", until),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public long disbandProtectedUntil(String playerId) {
        SocialPlayerDocument document = mongo.findById(playerId, SocialPlayerDocument.class,
                SocialPlayerDocument.COLLECTION);
        return document == null || document.disbandProtectedUntil() == null
                ? 0L : document.disbandProtectedUntil();
    }

    // ---------- 入盟申请 ----------

    @Override
    public boolean addApplication(String allianceId, String playerId) {
        try {
            mongo.insert(SocialApplicationDocument.of(allianceId, playerId),
                    SocialApplicationDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<String> applicantsOf(String allianceId) {
        // 按 _id（= allianceId + ":" + playerId）升序：与内存版同一句"按 id 升序"的口径
        Query query = new Query(Criteria.where("allianceId").is(allianceId))
                .with(Sort.by(Sort.Direction.ASC, "_id"));
        List<String> out = new ArrayList<>();
        for (SocialApplicationDocument document : mongo.find(query, SocialApplicationDocument.class,
                SocialApplicationDocument.COLLECTION)) {
            out.add(document.playerId());
        }
        return List.copyOf(out);
    }
    @Override
    public boolean removeApplication(String allianceId, String playerId) {
        return mongo.remove(Query.query(Criteria.where("_id")
                        .is(SocialApplicationDocument.keyOf(allianceId, playerId))),
                SocialApplicationDocument.COLLECTION).getDeletedCount() > 0L;
    }

    @Override
    public boolean hasApplication(String allianceId, String playerId) {
        return mongo.exists(Query.query(Criteria.where("_id")
                        .is(SocialApplicationDocument.keyOf(allianceId, playerId))),
                SocialApplicationDocument.class, SocialApplicationDocument.COLLECTION);
    }

    @Override
    public int pendingApplicationCount(String allianceId) {
        return (int) mongo.count(Query.query(Criteria.where("allianceId").is(allianceId)),
                SocialApplicationDocument.COLLECTION);
    }

    // ---------- 聊天 ----------

    @Override
    public void appendChat(String channelKey, ChatMessage message, int historyCap) {
        Objects.requireNonNull(message, "message 不得为 null");
        if (historyCap < 1) {
            return;
        }
        if (mongo.exists(Query.query(Criteria.where("_id").is(channelKey)
                        .and("messages.messageId").is(message.messageId())),
                ChatChannelDocument.class, ChatChannelDocument.COLLECTION)) {
            throw new IllegalStateException("聊天消息 id 在同一个频道里重复了：channel="
                    + channelKey + " messageId=" + message.messageId()
                    + " —— id 必须由生成方保证唯一（见 SocialAppService.chatSend），撞了会静默丢消息");
        }
        // 一次原子写完成"追加 + 只留最后 historyCap 条"：先读整段历史再写回会丢掉并发写入的另一条消息
        // $push + $each + $slice 是 Mongo 的原子组合写法：Spring Data 当前版本的 Update 没有
        // 独立的 slice 方法，直接用 Document 表达与驱动 API 是同一件事
        org.bson.Document push = new org.bson.Document("$each",
                List.of(ChatChannelDocument.entryOf(message)))
                .append("$slice", -historyCap);
        mongo.upsert(Query.query(Criteria.where("_id").is(channelKey)),
                new Update().push("messages", push),
                ChatChannelDocument.class, ChatChannelDocument.COLLECTION);
    }

    @Override
    public List<ChatMessage> chat(String channelKey, String beforeMessageId, int limit) {
        ChatChannelDocument document = mongo.findById(channelKey, ChatChannelDocument.class,
                ChatChannelDocument.COLLECTION);
        if (document == null || document.messages() == null || document.messages().isEmpty()) {
            return List.of();
        }
        List<ChatChannelDocument.MessageEntry> all = document.messages();
        int end = cursorEnd(all, beforeMessageId);
        int start = Math.max(0, end - limit);
        List<ChatMessage> out = new ArrayList<>(Math.max(0, end - start));
        for (ChatChannelDocument.MessageEntry entry : all.subList(start, end)) {
            out.add(ChatChannelDocument.domainOf(entry));
        }
        return List.copyOf(out);
    }

    @Override
    public boolean appendChatOrdered(String channelKey, ChatMessage message, int historyCap, long sequence) {
        if (historyCap < 1 || sequence <= 0) throw new IllegalArgumentException("投递容量/序号必须为正");
        Query id = Query.query(Criteria.where("_id").is(channelKey));
        // 初次建档只用_id，避免条件upsert在已投递时触发重复主键。
        mongo.upsert(id, new Update().setOnInsert("messages", List.of()), ChatChannelDocument.class,
                ChatChannelDocument.COLLECTION);
        String field = deliveryField(message.senderId());
        Criteria unseen = new Criteria().orOperator(Criteria.where(field).exists(false), Criteria.where(field).lt(sequence));
        Query guarded = Query.query(new Criteria().andOperator(Criteria.where("_id").is(channelKey), unseen));
        org.bson.Document push = new org.bson.Document("$each", List.of(ChatChannelDocument.entryOf(message)))
                .append("$slice", -historyCap);
        return mongo.updateFirst(guarded, new Update().push("messages", push).set(field, sequence),
                ChatChannelDocument.class, ChatChannelDocument.COLLECTION).getModifiedCount() == 1;
    }

    @Override
    public long chatDeliverySequence(String channelKey, String senderId) {
        String field = deliveryField(senderId);
        Query query = Query.query(Criteria.where("_id").is(channelKey));
        query.fields().include(field);
        org.bson.Document doc = mongo.findOne(query, org.bson.Document.class, ChatChannelDocument.COLLECTION);
        if (doc == null || !(doc.get("deliverySequences") instanceof org.bson.Document sequences)) return 0L;
        Object value = sequences.get(field.substring("deliverySequences.".length()));
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String deliveryField(String senderId) {
        // UUID编码不含Mongo路径分隔符；不将原始昵称或任意用户文本作为字段名。
        return "deliverySequences.p" + java.util.UUID.nameUUIDFromBytes(
                senderId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    @Override
    public boolean hasMore(String channelKey, String beforeMessageId, int limit) {
        ChatChannelDocument document = mongo.findById(channelKey, ChatChannelDocument.class,
                ChatChannelDocument.COLLECTION);
        if (document == null || document.messages() == null) {
            return false;
        }
        return cursorEnd(document.messages(), beforeMessageId) > limit;
    }

    /**
     * 游标定位：返回"这条之前"的截止下标。找不到（已被保留窗口淘汰、或根本是别处的 id）
     * 一律返回 0 —— 与内存实现共用同一条语义，退化到最新一页会让客户端把刚才那一屏追加两遍。
     */
    private static int cursorEnd(List<ChatChannelDocument.MessageEntry> all, String beforeMessageId) {
        if (beforeMessageId == null) {
            return all.size();
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).messageId().equals(beforeMessageId)) {
                return i;
            }
        }
        return 0;
    }

    // ---------- 社交事件（验收 5 / 12） ----------

    @Override
    public void pushEvent(String playerId, SocialEvent event) {
        Objects.requireNonNull(event, "event 不得为 null");
        mongo.upsert(Query.query(Criteria.where("_id").is(playerId)),
                new Update().push("unreadEvents", SocialPlayerDocument.entryOf(event)),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public List<SocialEvent> unreadEvents(String playerId) {
        SocialPlayerDocument document = mongo.findById(playerId, SocialPlayerDocument.class,
                SocialPlayerDocument.COLLECTION);
        if (document == null || document.unreadEvents() == null) {
            return List.of();
        }
        return List.copyOf(SocialPlayerDocument.domainOf(document.unreadEvents()));
    }

    @Override
    public int ackEvents(String playerId, List<String> eventIds) {
        SocialPlayerDocument document = mongo.findById(playerId, SocialPlayerDocument.class,
                SocialPlayerDocument.COLLECTION);
        if (document == null || document.unreadEvents() == null || document.unreadEvents().isEmpty()) {
            return 0;
        }
        boolean all = eventIds == null || eventIds.isEmpty();
        int before = document.unreadEvents().size();
        if (all) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(playerId)),
                    new Update().set("unreadEvents", List.of()),
                    SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
            return before;
        }
        int matched = 0;
        for (SocialPlayerDocument.EventEntry entry : document.unreadEvents()) {
            if (eventIds.contains(entry.eventId())) {
                matched++;
            }
        }
        if (matched > 0) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(playerId)),
                    new Update().pull("unreadEvents",
                            Query.query(Criteria.where("eventId").in(eventIds))),
                    SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
        }
        return matched;
    }

    // ---------- 集结（B10 §5） ----------

    @Override
    public RallyDeparturePlan putRallyDepartureIfAbsent(RallyDeparturePlan plan) {
        try {
            mongo.insert(new RallyDepartureDocument(plan.rallyId(), plan), RallyDepartureDocument.COLLECTION);
            return plan;
        } catch (DuplicateKeyException e) {
            return rallyDepartureOf(plan.rallyId()).orElseThrow(() -> e);
        }
    }

    @Override
    public Optional<RallyDeparturePlan> rallyDepartureOf(String rallyId) {
        return Optional.ofNullable(mongo.findById(rallyId, RallyDepartureDocument.class,
                RallyDepartureDocument.COLLECTION)).map(RallyDepartureDocument::plan);
    }

    @Override
    public List<RallyDeparturePlan> pendingRallyDepartures() {
        return mongo.find(new Query().with(Sort.by(Sort.Order.asc("plan.departAt"), Sort.Order.asc("_id"))),
                RallyDepartureDocument.class, RallyDepartureDocument.COLLECTION).stream()
                .map(RallyDepartureDocument::plan).toList();
    }

    @Override
    public RallyDeparturePlan attachRallyDepartureMarch(String rallyId,
                                    com.ironoath.core.march.March.Snapshot march) {
        RallyDeparturePlan found = rallyDepartureOf(rallyId).orElseThrow();
        if (!found.marchId().equals(march.id()) || !rallyId.equals(march.rallyId())) {
            throw new IllegalArgumentException("出发快照必须匹配计划的集结与固定行军身份");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(rallyId).and("plan.march").is(null)),
                new Update().set("plan.march", march), RallyDepartureDocument.class,
                RallyDepartureDocument.COLLECTION);
        return rallyDepartureOf(rallyId).orElseThrow();
    }

    @Override
    public void removeRallyDeparture(String rallyId) {
        mongo.remove(Query.query(Criteria.where("_id").is(rallyId)), RallyDepartureDocument.COLLECTION);
    }

    @Override
    public RallySettlement putRallySettlementIfAbsent(RallySettlement settlement) {
        try {
            mongo.insert(new RallySettlementDocument(settlement.settlementId(), settlement.groupId(),
                    settlement), RallySettlementDocument.COLLECTION);
            return settlement;
        } catch (DuplicateKeyException e) {
            RallySettlementDocument found = mongo.findById(settlement.settlementId(),
                    RallySettlementDocument.class, RallySettlementDocument.COLLECTION);
            if (found == null) { throw e; }
            return found.settlement();
        }
    }

    @Override
    public List<RallySettlement> pendingRallySettlementsOf(String groupId) {
        return mongo.find(Query.query(Criteria.where("groupId").is(groupId))
                        .with(Sort.by(Sort.Direction.ASC, "_id")),
                RallySettlementDocument.class, RallySettlementDocument.COLLECTION).stream()
                .map(RallySettlementDocument::settlement).toList();
    }

    @Override
    public void removeRallySettlement(String settlementId) {
        mongo.remove(Query.query(Criteria.where("_id").is(settlementId)),
                RallySettlementDocument.COLLECTION);
    }

    @Override
    public long saveRally(Rally rally, long expectedVersion) {
        Objects.requireNonNull(rally, "rally 不得为 null");
        RallyDocument document = RallyDocument.fromDomain(rally);
        if (expectedVersion <= 0L) {
            try {
                mongo.insert(document, RallyDocument.COLLECTION);
            } catch (DuplicateKeyException e) {
                throw alreadyExists("集结", rally.rallyId(),
                        storedVersion(RallyDocument.COLLECTION, rally.rallyId()));
            }
            return rally.version();
        }
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(rally.rallyId())
                        .and("version").is(expectedVersion)),
                document.toUpdate(), RallyDocument.class, RallyDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            rejectAsStaleOrMissing("集结", rally.rallyId(), expectedVersion, RallyDocument.COLLECTION);
        }
        return rally.version();
    }

    @Override
    public Optional<Rally> rallyOf(String rallyId) {
        return Optional.ofNullable(mongo.findById(rallyId, RallyDocument.class, RallyDocument.COLLECTION))
                .map(RallyDocument::toDomain);
    }

    @Override
    public List<Rally> preparingRalliesOf(String groupId) {
        Query query = Query.query(Criteria.where("groupId").is(groupId)
                        .and("status").is(Rally.Status.PREPARING.name()))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"));
        return domainOf(query);
    }

    @Override
    public List<Rally> dueRallies(long now) {
        Query query = Query.query(Criteria.where("status").is(Rally.Status.PREPARING.name())
                        .and("prepareUntil").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"));
        List<Rally> out = new ArrayList<>();
        for (Rally rally : domainOf(query)) {
            if (rally.dueAt(now)) {
                out.add(rally);
            }
        }
        return List.copyOf(out);
    }

    private List<Rally> domainOf(Query query) {
        List<Rally> out = new ArrayList<>();
        for (RallyDocument document : mongo.find(query, RallyDocument.class, RallyDocument.COLLECTION)) {
            out.add(document.toDomain());
        }
        return out;
    }

    // ---------- 帮助请求 ----------

    @Override
    public void putHelpRequest(HelpRequest request) {
        Objects.requireNonNull(request, "request 不得为 null");
        mongo.save(HelpRequestDocument.fromDomain(request), HelpRequestDocument.COLLECTION);
    }

    @Override
    public Optional<HelpRequest> helpRequest(String requestId) {
        return Optional.ofNullable(mongo.findById(requestId, HelpRequestDocument.class,
                        HelpRequestDocument.COLLECTION))
                .map(HelpRequestDocument::toDomain);
    }

    @Override
    public List<String> peerPlayerIds(String playerId) {
        List<String> peers = new ArrayList<>();
        Optional<Squad> squad = squadOf(playerId);
        if (squad.isPresent()) {
            for (String member : squad.get().memberIds()) {
                if (!member.equals(playerId)) {
                    peers.add(member);
                }
            }
        }
        Optional<Alliance> alliance = allianceOf(playerId);
        if (alliance.isPresent()) {
            for (String member : alliance.get().memberIds()) {
                if (!member.equals(playerId) && !peers.contains(member)) {
                    peers.add(member);
                }
            }
        }
        return List.copyOf(peers);
    }

    @Override
    public void removeHelpRequest(String requestId) {
        mongo.remove(Query.query(Criteria.where("_id").is(requestId)),
                HelpRequestDocument.COLLECTION);
    }

    @Override
    public List<HelpRequest> helpRequests() {
        List<HelpRequest> out = new ArrayList<>();
        for (HelpRequestDocument document : mongo.findAll(HelpRequestDocument.class,
                HelpRequestDocument.COLLECTION)) {
            out.add(document.toDomain());
        }
        return List.copyOf(out);
    }

    @Override
    public void markHelped(String requestId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(requestId)),
                new Update().inc("helpedCount", 1),
                HelpRequestDocument.class, HelpRequestDocument.COLLECTION);
    }

    // ---------- 举报与拉黑（B22 §一 3） ----------

    @Override
    public void appendReport(ReportRecord report) {
        if (report == null) {
            throw new IllegalArgumentException("举报记录不得为 null");
        }
        mongo.insert(SocialReportDocument.fromDomain(report), SocialReportDocument.COLLECTION);
    }

    @Override
    public List<ReportRecord> reportsSince(long sinceMillis, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 排序必须和内存版逐键一致：时间倒序，同刻按 reportId 降序。
        // 少了第二键，同一毫秒内的两条举报顺序就由 Mongo 自然序决定 —— 两端各自测试都不会红
        List<SocialReportDocument> docs = mongo.find(
                Query.query(Criteria.where(SocialReportDocument.FIELD_CREATED_AT).gte(sinceMillis))
                        .with(Sort.by(Sort.Order.desc(SocialReportDocument.FIELD_CREATED_AT),
                                Sort.Order.desc("_id"))).limit(limit),
                SocialReportDocument.class, SocialReportDocument.COLLECTION);
        List<ReportRecord> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.toDomain()));
        return List.copyOf(out);
    }

    @Override
    public int reportTotalSince(long sinceMillis) {
        return (int) mongo.count(Query.query(
                        Criteria.where(SocialReportDocument.FIELD_CREATED_AT).gte(sinceMillis)),
                SocialReportDocument.COLLECTION);
    }

    @Override
    public int reportCount(String reporterId, String targetPlayerId, long sinceMillis) {
        if (reporterId == null || targetPlayerId == null) {
            return 0;
        }
        return (int) mongo.count(Query.query(Criteria.where(SocialReportDocument.FIELD_REPORTER)
                        .is(reporterId)
                        .and(SocialReportDocument.FIELD_TARGET).is(targetPlayerId)
                        .and(SocialReportDocument.FIELD_CREATED_AT).gte(sinceMillis)),
                SocialReportDocument.COLLECTION);
    }

    @Override
    public void block(String playerId, String targetPlayerId) {
        if (playerId == null || targetPlayerId == null) {
            throw new IllegalArgumentException("playerId / targetPlayerId 不得为 null");
        }
        // $addToSet 而不是 push：重复拉黑同一个人不该在名单里出现两次；
        // 而它**保留已有元素的位置**（不会把老条目挪到末尾），与内存版的 LinkedHashSet 同一语义 ——
        // 否则"最近的在前"这个顺序在两个实现里会不一样
        mongo.upsert(Query.query(Criteria.where("_id").is(playerId)),
                new Update().addToSet(SocialPlayerDocument.FIELD_BLOCKED, targetPlayerId),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public void unblock(String playerId, String targetPlayerId) {
        if (playerId == null || targetPlayerId == null) {
            throw new IllegalArgumentException("playerId / targetPlayerId 不得为 null");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(playerId)),
                new Update().pull(SocialPlayerDocument.FIELD_BLOCKED, targetPlayerId),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public List<String> blockedPlayers(String playerId) {
        SocialPlayerDocument document = playerId == null ? null
                : mongo.findById(playerId, SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
        if (document == null || document.blockedPlayerIds() == null
                || document.blockedPlayerIds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(document.blockedPlayerIds());
        Collections.reverse(out);
        return List.copyOf(out);
    }

    @Override
    public void follow(String playerId, String targetPlayerId) {
        if (playerId == null || targetPlayerId == null) {
            throw new IllegalArgumentException("playerId / targetPlayerId 不得为 null");
        }
        // 与拉黑同一套语义：$addToSet 幂等且保留已有元素的位置（"最近的在前"靠读取时反转）
        mongo.upsert(Query.query(Criteria.where("_id").is(playerId)),
                new Update().addToSet(SocialPlayerDocument.FIELD_FOLLOWED, targetPlayerId),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public void unfollow(String playerId, String targetPlayerId) {
        if (playerId == null || targetPlayerId == null) {
            throw new IllegalArgumentException("playerId / targetPlayerId 不得为 null");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(playerId)),
                new Update().pull(SocialPlayerDocument.FIELD_FOLLOWED, targetPlayerId),
                SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
    }

    @Override
    public List<String> followedPlayers(String playerId) {
        SocialPlayerDocument document = playerId == null ? null
                : mongo.findById(playerId, SocialPlayerDocument.class, SocialPlayerDocument.COLLECTION);
        if (document == null || document.followedPlayerIds() == null
                || document.followedPlayerIds().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(document.followedPlayerIds());
        Collections.reverse(out);
        return List.copyOf(out);
    }

    @Override
    public boolean hasBlocked(String blocker, String blocked) {
        if (blocker == null || blocked == null) {
            return false;
        }
        List<String> mine = blockedPlayers(blocker);
        return mine.contains(blocked);
    }

    // ---------- 测试与运维 ----------

    /**
     * 与内存实现的计数口径一致：小队数 + 联盟数 + 聊天频道数 + 帮助请求数。
     * 注意是频道数而不是消息数 —— 计数只用于测试与体检，口径不同会让两侧的断言对不上。
     */
    @Override
    public int counts() {
        long squads = mongo.count(new Query(), SquadDocument.COLLECTION);
        long alliances = mongo.count(new Query(), AllianceDocument.COLLECTION);
        long channels = mongo.count(new Query(), ChatChannelDocument.COLLECTION);
        long helps = mongo.count(new Query(), HelpRequestDocument.COLLECTION);
        return (int) (squads + alliances + channels + helps);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), SocialReportDocument.COLLECTION);
        mongo.remove(new Query(), SquadDocument.COLLECTION);
        mongo.remove(new Query(), AllianceDocument.COLLECTION);
        mongo.remove(new Query(), RallyDocument.COLLECTION);
        mongo.remove(new Query(), RallySettlementDocument.COLLECTION);
        mongo.remove(new Query(), RallyDepartureDocument.COLLECTION);
        mongo.remove(new Query(), ChatChannelDocument.COLLECTION);
        mongo.remove(new Query(), SocialPlayerDocument.COLLECTION);
        mongo.remove(new Query(), SocialApplicationDocument.COLLECTION);
        mongo.remove(new Query(), HelpRequestDocument.COLLECTION);
    }

    /**
     * 乐观锁失败时把两种原因分开说清（与内存实现逐字相同）：<b>记录不存在</b>与
     * <b>版本被别人的写入推进了</b>不是一回事 —— 前者说明调用方拿着一个已被删除的对象，
     * 后者说明重读重试就能继续。写成一句会让运维分不清是数据被删还是有人并发写。
     */
    private void rejectAsStaleOrMissing(String what, String id, long expectedVersion, String collection) {
        Long stored = storedVersion(collection, id);
        if (stored == null) {
            throw new IllegalStateException(what + "不存在，无法按版本更新：id=" + id
                    + "，期望版本=" + expectedVersion);
        }
        throw new IllegalStateException("乐观锁冲突：" + what + " id=" + id
                + "，存储版本=" + stored + "，提交版本=" + expectedVersion
                + "。请重读后重试：这类改动跨玩家，PlayerLock 是按玩家的，拦不住这里");
    }

    /**
     * 库里当前的版本号；没有这条记录返回 null。
     *
     * <p>只投影 version 一个字段：这是失败路径上的诊断查询，不需要把整档读出来。
     * <b>文档没有 version 字段</b>（本轮之前写入的历史数据）时返回 -1 —— 那是一个必然冲突的版本，
     * 错误信息会把它显示出来，提醒操作者需要迁移或重建，而不是静默放行。
     */
    private Long storedVersion(String collection, String id) {
        org.bson.Document existing = mongo.getCollection(collection)
                .find(new org.bson.Document("_id", id))
                .projection(new org.bson.Document("version", 1))
                .first();
        if (existing == null) {
            return null;
        }
        Object version = existing.get("version");
        return version instanceof Number number ? number.longValue() : -1L;
    }

    private static IllegalStateException alreadyExists(String what, String id, Long storedVersion) {
        return new IllegalStateException(what + "已存在，不能用 expectedVersion<=0 建档：id=" + id
                + "，存储版本=" + storedVersion);
    }
    private static IllegalStateException squadNameConflict(String name) {
        return new IllegalStateException("小队名已被其它小队占用：" + name
                + "。名字是玩家查找小队的唯一入口，两支同名小队等于其中一支从名字上消失");
    }

    private static IllegalStateException allianceNameConflict(String name) {
        return new IllegalStateException("联盟名已被其它联盟占用：" + name
                + "。联盟名是玩家查找联盟的唯一入口，两个联盟共用一名等于其中一个从名字上消失");
    }

    private static IllegalStateException allianceTagConflict(String tag) {
        return new IllegalStateException("联盟标签已被其它联盟占用：" + tag
                + "。标签是外交与战报里指代联盟的短标识，重复会让指代出现歧义");
    }
}
