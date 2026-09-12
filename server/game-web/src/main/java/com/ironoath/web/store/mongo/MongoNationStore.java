package com.ironoath.web.store.mongo;

import com.ironoath.core.nation.Nation;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 职责：国家存储的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link NationDocument}、{@link NationRulesAssembler}。
 *
 * <p><b>补的是 #16 里"国库账目纠纷"那一档</b>：内存版重启即丢，而 B13 §3 验收 5 与
 * 官职权限都建立在"这个国家有哪些联盟、谁在哪个位子、库里有多少钱"之上。
 *
 * <p>与内存版同一条约定：<b>读出来的是副本</b>（每次 {@link #findById} 都重新拼一个对象），
 * 所以调用方改完必须 {@link #save}；同理 {@link #settleWeeklyTax} 改完，调用方要重读才能看到
 * 那笔入账（{@code NationAppService#settleTax} 就是这么做的）。
 *
 * <p><b>一条已知但没有被这次改动关闭的并发窗口，写在这里而不是藏起来</b>：
 * {@link #settleWeeklyTax} 用「按 {@code state.lastTaxWeekKey} + {@code state.treasury} 条件写入」
 * 做比较并交换，所以<b>同一周被收两次</b>这件事在多实例下也成立地挡住了（B13 验收 5 要的正是它）；
 * 但端口上的 {@link #save} 是整档替换、没有版本号，因此"一次结算与一次任命真同时发生"时
 * 后写的会盖掉先写的。内存版靠整库监视器把两者串了起来，Mongo 版做不到同一件事。
 * 要彻底关掉得给 {@code Nation} 加版本号（与 {@code CityState} 等四个版本化仓储同一条契约），
 * 那是聚合与端口的双改动，记在收口清单里排后面做 —— <b>不要因为这句话以为现在会重复收税</b>，
 * 重复收税这条已经被 CAS 挡住，剩下的只是同周内一次写覆盖另一次写。
 */
public final class MongoNationStore implements NationStore {

    /** 周税 CAS 的最试次数：只可能因为"另一个实例也在结算同一周"而重来，正常应为 1。 */
    private static final int TAX_CAS_ATTEMPTS = 5;

    private final MongoTemplate mongo;
    private final NationRulesAssembler rules;

    public MongoNationStore(MongoTemplate mongo, NationRulesAssembler rules) {
        this.mongo = mongo;
        this.rules = java.util.Objects.requireNonNull(rules, "rules 不得为 null");
    }

    @Override
    public boolean insertIfAbsent(Nation nation) {
        if (nation == null) {
            throw new IllegalArgumentException("nation 不得为 null");
        }
        try {
            mongo.insert(NationDocument.fromDomain(nation), NationDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            // 撞号有两种，含义相反：_id 撞上="这个国家已经建过"（insertIfAbsent 正常返回 false）；
            // name 撞上=两个国家抢一个国名（必须响亮拒绝，与内存版同一条）。
            // 不分开的话，第二种会被静默吞成 false，而调用方只会看到"没建成"，永远不知道原因
            if (mongo.exists(Query.query(Criteria.where("_id").is(nation.id())),
                    NationDocument.COLLECTION)) {
                return false;
            }
            throw new IllegalStateException("国名已被其它国家占用，无法保存：" + nation.name()
                    + "。国名是 findByName 的唯一入口，两个国家共用一个名字等于其中一个从名字上消失", e);
        }
    }

    @Override
    public long save(Nation nation, long expectedVersion) {
        if (nation == null) {
            throw new IllegalArgumentException("nation 不得为 null");
        }
        Nation.Snapshot next = bumped(nation);
        Query query = Query.query(Criteria.where("_id").is(nation.id())
                .and("state.version").is(expectedVersion));
        Update update = new Update()
                .set("name", next.name())
                .set("memberAllianceIds", List.copyOf(nation.memberAllianceIds()))
                .set("state", next);
        var result = mongo.updateFirst(query, update, NationDocument.class, NationDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            rejectAsStaleOrMissing(nation.id(), expectedVersion);
        }
        return next.version();
    }

    /**
     * 落库那份：版本在"要去写库的那份拷贝"上推进，且与调用方对象脱钩。
     *
     * <p>与 {@code MongoStageProgressStore} 同一条：不动调用方的对象，所以拿同一个对象连写两次
     * 第二次必撞锁 —— 那正是乐观锁要拦的形状（内存版也改成这一条，见 {@code InMemoryNationStore.save}）。
     */
    private Nation.Snapshot bumped(Nation nation) {
        Nation copy = Nation.fromSnapshot(nation.snapshot(), rules.rules());
        copy.incrementVersion();
        return copy.snapshot();
    }

    private void rejectAsStaleOrMissing(String nationId, long expectedVersion) {
        Long stored = storedVersionOrNull(nationId);
        if (stored == null) {
            throw new IllegalStateException("国家不存在，无法更新：nationId=" + nationId
                    + "。建档请走 insertIfAbsent —— save 静默插入会让并发建档插出两份档");
        }
        throw new IllegalStateException("乐观锁冲突：nationId=" + nationId
                + "，存储版本=" + stored + "，提交版本=" + expectedVersion
                + "。请重读后重试：两个官员同时改同一个国家时，PlayerLock 是按玩家的，拦不住这里");
    }

    @Override
    public long settleWeeklyTax(String nationId, long now) {
        if (nationId == null) {
            return 0L;   // 与内存版同一条：查不到就是没收到钱，不让驱动抛一个另一种异常
        }
        long weekKey = com.ironoath.common.time.WeekKey.number(now);
        for (int attempt = 0; attempt < TAX_CAS_ATTEMPTS; attempt++) {
            NationDocument doc = mongo.findById(nationId, NationDocument.class,
                    NationDocument.COLLECTION);
            if (doc == null) {
                return 0L;
            }
            Nation before = Nation.fromSnapshot(doc.state(), rules.rules());
            long versionBefore = before.version();
            long weekBefore = before.snapshot().lastTaxWeekKey();
            long credited = before.collectTax(weekKey, now);
            if (before.snapshot().lastTaxWeekKey() == weekBefore) {
                return 0L;   // 本周已结过（或更早），一次写入都不该发生
            }
            Update update = new Update()
                    .set("name", before.name())
                    .set("state", bumped(before));
            // 版本条件而不是"猜哪几个字段变了"：这一版 CAS 挡住的是**所有**并发写入，
            // 包括同一瞬间的一次任命 —— 原先按 (周键, 国库) 两个字段比对，
            // 只挡得住重复收税，挡不住任命与结算互相覆盖（收口清单 #54 记的那道窗口）
            var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(nationId)
                            .and("state.version").is(versionBefore)),
                    update, NationDocument.class, NationDocument.COLLECTION);
            if (result.getMatchedCount() > 0L) {
                return credited;
            }
            // 没抢到：别人刚改过这一档，重读再试（下一轮多半就"本周已结过"而返回 0）
        }
        return 0L;
    }

    @Override
    public Optional<Nation> findById(String nationId) {
        if (nationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findById(nationId, NationDocument.class,
                NationDocument.COLLECTION)).map(this::toDomain);
    }

    @Override
    public Optional<Nation> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("name").is(name)),
                NationDocument.class, NationDocument.COLLECTION)).map(this::toDomain);
    }

    @Override
    public Optional<Nation> findByAlliance(String allianceId) {
        if (allianceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("memberAllianceIds").is(allianceId)),
                NationDocument.class, NationDocument.COLLECTION)).map(this::toDomain);
    }

    @Override
    public List<Nation> all() {
        // 国家总数上限是 global.NATION_MAX_PER_KINGDOM（个位数），所以这里不需要分页
        List<Nation> out = new ArrayList<>();
        mongo.findAll(NationDocument.class, NationDocument.COLLECTION).forEach(d -> out.add(toDomain(d)));
        return out;
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), NationDocument.COLLECTION);
    }

    /**
     * 库里当前版本；文档不存在时返回 null（"没建过"与"版本旧了"是两件事，报错必须分得开 ——
     * 前者是调用方走错方法，后者是要重读重试）。
     *
     * <p><b>刻意不做字段投影</b>：这条只在冲突分支上跑，一国家一份文档也不大；
     * 而投影成 {@code state.version} 之后 Spring Data 仍然要实例化整个 {@code NationDocument}，
     * 嵌套的 {@code Snapshot} 有原始类型字段、拿不到值就抛
     * {@code MappingInstantiationException} —— 报错分支自己把真实原因盖掉了，正是要避免的形状。
     * （{@code MongoStageProgressStore} 那边同样投影是安全的，因为 version 是文档顶层列、
     * 没有嵌套 record 需要实例化。）
     */
    private Long storedVersionOrNull(String nationId) {
        NationDocument doc = mongo.findById(nationId, NationDocument.class, NationDocument.COLLECTION);
        return doc == null || doc.state() == null ? null : doc.state().version();
    }

    private Nation toDomain(NationDocument doc) {
        return Nation.fromSnapshot(doc.state(), rules.rules());
    }
}
