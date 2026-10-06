package com.ironoath.web.store.mongo;

import java.util.Objects;
import java.util.Optional;

import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * 职责：国战战事的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link WarDocument}、{@link WarRulesAssembler}。
 *
 * <p><b>这一档补的是「内核写好了、外层从来没人装配」那一族</b>：{@code WarScoreBoard} 交付以来
 * 在生产里<b>零引用</b>（只有它自己与一份单测），而它的类注释写着「由 game-web 在国战开始时载入、
 * 结束时落盘一次」—— 契约写在注释里、没人执行，于是玩家侧完全不可达。
 * 本类与 {@code InMemoryWarStore}、{@code WarBeansConfig}、{@code MongoStoreConfig#warStore}
 * 一起把那句话变成代码；判据是 {@code scripts/check-core-wiring.sh} 里那条豁免已撤销。
 *
 * <p>与内存版同一条约定：<b>读出来的是副本</b>（每次 {@link #findLatest} 都重新拼一个对象），
 * 所以调用方改完必须 {@link #save}。两套实现的差异由 {@code WarStoreEquivalenceTest} 钉住。
 *
 * <p><b>刻意没有 CAS／重试环</b>：{@code MongoNationStore.settleWeeklyTax} 那一段是「判断 + 写入」
 * 必须在同一个临界区里才成立，而这里没有那种判断 —— 落盘的就是内存板子的当前状态，
 * 整档替换是 {@link WarScoreBoard} 类注释规定的形状（禁止在数据库层聚合积分）。
 * 端口 javadoc 里那条「中途 flush 必须回来加版本」的窗口同样适用于这一份，别在这儿另起一套口径。
 */
public final class MongoWarStore implements WarStore {

    private final MongoTemplate mongo;
    private final WarRulesAssembler rules;
    /** {@link #insertIfNoneActive} 的临界区：见该方法为什么用实例锁而不是 Mongo 唯一索引。 */
    private final Object activeLock = new Object();

    public MongoWarStore(MongoTemplate mongo, WarRulesAssembler rules) {
        this.mongo = mongo;
        this.rules = Objects.requireNonNull(rules, "rules 不得为 null");
    }

    /**
     * 建档。<b>撞键只有 {@code _id} 这一种</b>（这一族没有第二个唯一索引，与 nation 的国名唯一不同），
     * 所以 {@code DuplicateKeyException} 直接翻成 false 就够了 ——
     * {@code MongoNationStore} 那里要把「已建过」与「抢同一个国名」分开报错，是因为后者的含义相反。
     */
    @Override
    public boolean insertIfAbsent(WarScoreBoard board) {
        String warId = WarStore.documentIdOf(requireBoard(board));
        try {
            mongo.insert(WarDocument.fromDomain(warId, board), WarDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 原子地开一场：<b>「有没有未结束的仗」与「插入」收进同一把锁</b>（本存储 bean 的实例锁）。
     *
     * <p><b>为什么这里用实例锁而不是 Mongo 的 partial unique index</b>：本进程就是全服唯一进程
     * （{@code PlayerLock} 是 JVM 内的，这条前提在项目里早就成立），而 partial index 要为此
     * 给文档再加一列"活着的槽位"并在结算时清掉它 —— 那是为假想的横向扩展付的代价。
     * <b>锁只在这一个 bean 实例上有效</b>：端口 javadoc 里写明的是同进程保证，不许把它读成跨进程保证。
     *
     * <p>{@code _id} 撞键（同一毫秒开两场）翻成 false 是兜底，不是主判据。
     */
    @Override
    public boolean insertIfNoneActive(WarScoreBoard board) {
        String warId = WarStore.documentIdOf(requireBoard(board));
        synchronized (activeLock) {
            if (hasActiveWar()) {
                return false;
            }
            try {
                mongo.insert(WarDocument.fromDomain(warId, board), WarDocument.COLLECTION);
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }
    }

    /**
     * 有没有任何一场未结束的仗。<b>按 {@code state.phase} 查而不是"取最新那场再看它的 phase"</b>：
     * 后者会漏掉「旧的一场还没结算、又插进来一场更新的且已结算」这种形状 ——
     * 那个形状今天只能由 {@code insertIfAbsent}（建档口的后门）造出来，
     * 而判据写成"任意一场活的"就不依赖"没人走后门"这条假设。
     *
     * <p>集合里只有个位数文档，这一条不建索引（{@code idx_war_started_at} 服务的是读端点的排序）。
     */
    private boolean hasActiveWar() {
        return mongo.exists(Query.query(Criteria.where("state.phase")
                .ne(WarScoreBoard.Phase.SETTLED.name())), WarDocument.COLLECTION);
    }

    @Override
    public void save(WarScoreBoard board) {
        String warId = WarStore.documentIdOf(requireBoard(board));
        WarDocument next = WarDocument.fromDomain(warId, board);
        Update update = new Update()
                .set("startedAt", next.startedAt())
                .set("state", next.state());
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(warId)),
                update, WarDocument.class, WarDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            throw new IllegalStateException("战事不存在，无法落盘：warId=" + warId
                    + "。建档请走 insertIfAbsent —— save 静默插入会让并发建档插出两份同开场的档，"
                    + "而两份各自算各自的积分与疲劳");
        }
    }

    /**
     * 最近开战的那一场：按 {@code startedAt} 降序取第一条（走 {@code MongoIndexes} 里那条
     * {@code idx_war_started_at}）。
     *
     * <p>读的是 {@code state} 那一份，{@code startedAt} 那一列只用来排序 —— 这一点与内存版
     * {@code InMemoryWarStore.latestSnapshot} 必须同源，否则「按插入顺序取最后一条」这种写法
     * 会在 dev 全绿、在生产给出另一场仗。
     */
    @Override
    public Optional<WarScoreBoard> findLatest() {
        return Optional.ofNullable(latestDocument()).map(this::toDomain);
    }

    /**
     * 惰性结算：<b>判到期、{@code settle()}、整档写回收在 {@code activeLock} 这一把锁里</b>，
     * 与 {@link #insertIfNoneActive}／{@link #recordKills} 共用同一把 —— 三条写路径改的是同一块板子，
     * 分成三把锁就等于「两个人各自打开面板会各结算一次」，而第二次会敲在内核那条
     * 「重复结算会让积分被算两遍」的护栏上，玩家侧表现是面板 500。
     *
     * <p>落盘用 {@code updateFirst} 而不是 {@code save}：主键按 {@code startedAt} 推导，结算不动它，
     * 所以写回必然落在<b>原来那一条档</b>上。这一点是"历史留档"的另一半 —— 若结算变成
     * 删旧插新，{@link #insertIfNoneActive} 之后 {@code insertIfAbsent(那一场)} 就不会再撞键，
     * 而等价测试里那条专门盯这件事的断言会红。
     */
    @Override
    public Optional<WarStore.Settlement> settleIfExpired(long now) {
        synchronized (activeLock) {
            WarDocument doc = latestDocument();
            if (doc == null || doc.state() == null) {
                return Optional.empty();
            }
            WarScoreBoard board = WarScoreBoard.fromSnapshot(doc.state(), rules.rules());
            if (!WarStore.dueToSettle(board, now)) {
                return Optional.of(WarStore.Settlement.notSettled(board));
            }
            // 这份 Result 必须带出去：内核 settle() 只有一次机会算出 winnerId（第二次直接抛），
            // 而 3b-2 的 WAR_SEASON_POINT_WINNER 只认它 —— 见 WarStore.Settlement#result
            WarScoreBoard.Result result = board.settle(now);
            Update update = new Update()
                    .set("startedAt", board.startedAt())
                    .set("state", board.toSnapshot());
            mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.warId())),
                    update, WarDocument.class, WarDocument.COLLECTION);
            return Optional.of(new WarStore.Settlement(board, true, result));
        }
    }

    /**
     * 击杀归属：<b>读、判、改、写回收在 {@code activeLock} 这一把锁里</b>，与
     * {@link #insertIfNoneActive} 共用同一把 —— 所以"刚宣完战的第一场"与"同一秒打完的那一仗"
     * 不会各自拿着旧副本互相盖。
     *
     * <p><b>这一条是整份代码里最热的一次写</b>（每场战斗结算都来一趟），但它仍然是串行的：
     * 一场战斗到一次结算的转化率远低于内核注释里那条"200 QPS 的行军事件"，
     * 而这里<b>没有在数据库层做 SUM</b>（B13 禁止项）—— 加法算在内核对象上，
     * 落盘只是把整份快照换回去，与内存版给玩家的是同一份算出来的数。
     *
     * <p>如果哪天真需要横向扩展，办法是把这一条换成带版本 CAS 的循环或 Mongo 的原子累加口，
     * 而不是把锁挪到服务层 —— 挪上去就退回"两个玩家各拿一把按玩家分的锁"那个窗口。
     */
    @Override
    public WarStore.KillResult recordKills(String killerNationId, String killerPlayerId, long units) {
        if (units <= 0L) {
            return WarStore.KillResult.SKIPPED;
        }
        synchronized (activeLock) {
            WarDocument doc = latestDocument();
            if (doc == null || doc.state() == null
                    || doc.state().phase() == WarScoreBoard.Phase.SETTLED) {
                return WarStore.KillResult.NO_ACTIVE_WAR;
            }
            WarScoreBoard board = WarScoreBoard.fromSnapshot(doc.state(), rules.rules());
            WarStore.KillResult result = WarStore.applyKills(board, killerNationId, killerPlayerId, units);
            Update update = new Update()
                    .set("startedAt", board.startedAt())
                    .set("state", board.toSnapshot());
            mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.warId())),
                    update, WarDocument.class, WarDocument.COLLECTION);
            return result;
        }
    }

    /** 按 {@code startedAt} 取最新那一份文档（走 {@code idx_war_started_at}）；一份都没有时为 null。 */
    private WarDocument latestDocument() {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1);
        return mongo.findOne(query, WarDocument.class, WarDocument.COLLECTION);
    }

    /**
     * 这一对两国最近那一场：把「两方都在参战方里」下推成 {@code $and} 两个独立条件，
     * 按 {@code startedAt} 降序取第一条。
     *
     * <p><b>为什么必须拆成 {@code $and} 而不是 {@code in(a, b)}</b>：Mongo 对数组字段的条件
     * <b>各自独立</b>地对整个数组求值，所以 {@code $and:[{path:a},{path:b}]} 的意思是
     * 「存在一个元素等于 a <b>且</b>存在一个元素等于 b」—— 正是"这一对交过手"。
     * 写成 {@code in(a,b)} 就退化成「有任一元素等于 a <b>或</b> b」，于是只跟 A 打过仗的 C
     * 也会被算进 A-B 那一档，冷却期凭空多出一堵挡错人的墙。
     *
     * <p>不加锁：这是纯读，且它的答案只用来<b>拒绝</b>一次写入（真正的仲裁者仍是
     * {@link #insertIfNoneActive} 那条临界区），读到稍旧一档最多是"这次拒绝的理由晚一分钟生效"，
     * 不会开出两场仗。
     */
    @Override
    public Optional<WarScoreBoard> findLatestBetween(String nationA, String nationB) {
        Query query = new Query(new Criteria().andOperator(
                        Criteria.where("state.nations.nationId").is(nationA),
                        Criteria.where("state.nations.nationId").is(nationB)))
                .with(Sort.by(Sort.Direction.DESC, "startedAt"))
                .limit(1);
        return Optional.ofNullable(mongo.findOne(query, WarDocument.class, WarDocument.COLLECTION))
                .map(this::toDomain);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), WarDocument.COLLECTION);
    }

    private WarScoreBoard toDomain(WarDocument doc) {
        return WarScoreBoard.fromSnapshot(doc.state(), rules.rules());
    }

    private static WarScoreBoard requireBoard(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就没有可落盘的状态");
        }
        return board;
    }
}
