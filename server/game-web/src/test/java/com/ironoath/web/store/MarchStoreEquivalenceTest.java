package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.mongo.MongoMarchStore;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 职责：行军存储在<b>内存与 Mongo 两套实现上必须给出同一个结果</b>（跨实现比对，不是各跑各的）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>为什么这条链路值得单独一份</b>：行军是唯一"跨会话长生命周期"的实体 —— B07 验收 1 说的
 * 就是杀进程重进后所有队伍要按真实剩余时间继续。内存与 Mongo 在这里的任何一点行为差异，
 * 都表现为"dev 全绿、prod 上队伍凭空消失或永远回不来"，而这两件事在生产上没有任何日志会指向存储层。
 *
 * <p>特别的一条是 {@code rallyId}：它不在构造器也不在 {@code restore} 里，历史上只能靠深拷贝里
 * 一行显式复制带过去（漏掉的后果写在 {@link March.Snapshot} 上：集结合并返程把全部幸存兵力
 * 记到发起人名下，成员的兵有去无回）。所以这里必须有一次<b>落库再读回</b>的往返断言。
 */
class MarchStoreEquivalenceTest {

    /** 两侧都必须用同一个 chunkSize 算 chunk 键，测试里固定成 8（2 的幂）。 */
    private static final int CHUNK_SIZE = 8;
    private static final long T0 = 1_800_000_000_000L;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    /** 每条用例前清文档（不删集合，否则索引跟着没）：跨实现比条数时，共享库会数出不同的数。 */
    @BeforeEach
    void clearCollection() {
        if (db != null) {
            db.template().remove(new Query(), com.ironoath.web.store.mongo.MarchDocument.COLLECTION);
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Test
    @DisplayName("列表按出发时刻升序、同刻按 id：两套实现给出的顺序必须一模一样")
    void orderingIsIdenticalAcrossImplementations() {
        // 故意乱序插入，且两条 startAt 相同来考验次级排序键
        List<March> in = new ArrayList<>();
        in.add(march("m-c", T0 + 500L));
        in.add(march("m-a", T0 + 100L));
        in.add(march("m-b", T0 + 100L));

        assertThat(idsInOrder(memory(in))).as("内存版顺序").isEqualTo(idsInOrder(mongo(in)));
        assertThat(idsInOrder(memory(in))).containsExactly("m-a", "m-b", "m-c");
    }

    @Test
    @DisplayName("读回来的对象是副本：就地改一笔而不保存，两套实现都不许让它泄漏进库")
    void readIsACopyInBothImplementations() {
        MarchRepository memory = new InMemoryMarchStore(CHUNK_SIZE);
        MarchRepository mongo = newMongoStore();
        memory.insertIfAbsent(march("m-copy", T0));
        mongo.insertIfAbsent(march("m-copy", T0));

        for (MarchRepository store : List.of(memory, mongo)) {
            March seen = store.findById("m-copy").orElseThrow();
            seen.restore(seen.arriveAt(), null, null, null, 0L, March.Status.RETURNING, null,
                    Map.of("unit_infantry_t1", 999_999L));
            March fresh = store.findById("m-copy").orElseThrow();
            assertThat(fresh.status())
                    .as("%s：改了副本没 save，库里的状态跟着变了就等于乐观锁形同虚设",
                            store.getClass().getSimpleName())
                    .isEqualTo(March.Status.MARCHING);
        }
    }

    @Test
    @DisplayName("过期版本被拒、并发插入只有一个赢家、删除是幂等的：两套实现同结论")
    void versionAndInsertionRulesMatch() {
        for (MarchRepository store : List.of(new InMemoryMarchStore(CHUNK_SIZE), newMongoStore())) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(march("m-lock", T0));
            long v0 = store.versionOf("m-lock");

            March a = store.findById("m-lock").orElseThrow();
            March b = store.findById("m-lock").orElseThrow();
            a.restore(a.arriveAt(), null, null, null, a.load(), March.Status.GATHERING, null, a.units());
            assertThat(store.save(a, v0)).as("%s 首次保存要推进版本", label).isGreaterThan(v0);

            b.restore(b.arriveAt(), null, null, null, b.load(), March.Status.RETURNING, null, b.units());
            assertThatThrownBy(() -> store.save(b, v0))
                    .as("%s：旧版本提交必须失败，否则两次行军更新会互相整份覆盖", label)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(store.findById("m-lock").orElseThrow().status())
                    .as("%s：被拒的那次不许留半个写入", label).isEqualTo(March.Status.GATHERING);

            assertThat(store.insertIfAbsent(march("m-lock", T0))).as("%s 重复插入应为 false", label)
                    .isFalse();
            store.delete("m-lock");
            store.delete("m-lock");   // 幂等：不许抛
        }
    }

    /** chunk 键两侧必须一致：不一致时 viewport 在生产上会静默漏掉所有行军实体。 */
    @Test
    @DisplayName("按 chunk 查在途队伍：两套实现命中同一批，且用的是同一个 chunk 键")
    void chunkLookupUsesTheSameKeysInBothImplementations() {
        March near = march("m-chunk-1", T0);                              // 起终点都在附近
        March far = marchBetween("m-chunk-2", T0 + 1L, Coord.of(400, 400), Coord.of(410, 410));
        String nearKey = near.from().chunkKey(CHUNK_SIZE);
        String farKey = far.to().chunkKey(CHUNK_SIZE);
        String absentKey = Coord.of(77, 77).chunkKey(CHUNK_SIZE);         // 与两条都不搭的键

        MarchRepository memory = new InMemoryMarchStore(CHUNK_SIZE);
        MarchRepository mongo = newMongoStore();
        for (MarchRepository store : List.of(memory, mongo)) {
            store.insertIfAbsent(near);
            store.insertIfAbsent(far);
        }

        for (String key : List.of(nearKey, farKey, absentKey)) {
            List<String> fromMemory = idsIn(memory.findByChunkKeys(List.of(key)));
            List<String> fromMongo = idsIn(mongo.findByChunkKeys(List.of(key)));
            assertThat(fromMongo)
                    .as("chunk 键 %s：Mongo 侧算出的键与内存版不一致 ⇒ 生产上会静默漏发行军实体", key)
                    .isEqualTo(fromMemory);
        }
        // 端口口径是「终点或起点落在给定 chunk 内」，所以两侧各命中一条、都不命中要空
        assertThat(idsIn(memory.findByChunkKeys(List.of(nearKey)))).containsExactly("m-chunk-1");
        assertThat(idsIn(memory.findByChunkKeys(List.of(farKey)))).containsExactly("m-chunk-2");
        assertThat(idsIn(memory.findByChunkKeys(List.of(absentKey)))).isEmpty();
        assertThat(memory.activeCountOf("P-1")).isEqualTo(mongo.activeCountOf("P-1"));
    }

    /** 这条是本轮真正的目的：rallyId 历史上只靠深拷贝里一行手工复制带着走。 */
    @Test
    @DisplayName("rallyId 落库再读回必须还在：漏了它，集结合并返程会把成员的兵记到发起人名下")
    void rallyBindingSurvivesTheRoundTripInBothImplementations() {
        for (MarchRepository store : List.of(new InMemoryMarchStore(CHUNK_SIZE), newMongoStore())) {
            String label = store.getClass().getSimpleName();
            March rally = march("m-rally", T0);
            rally.attachToRally("rally-77");
            store.insertIfAbsent(rally);

            March back = store.findById("m-rally").orElseThrow();
            assertThat(back.isRallyMarch())
                    .as("%s：isRallyMarch() 变 false 会让返程把全部幸存兵力记到发起人名下，"
                            + "成员的兵有去无回，而整条链一个错都不报", label).isTrue();
            assertThat(back.rallyId()).isEqualTo("rally-77");
        }
    }

    // ---------- 夹具 ----------

    private List<March> memory(List<March> seeds) {
        MarchRepository store = new InMemoryMarchStore(CHUNK_SIZE);
        seeds.forEach(store::insertIfAbsent);
        return store.findByPlayerId("P-1");
    }

    private List<March> mongo(List<March> seeds) {
        MarchRepository store = newMongoStore();
        seeds.forEach(store::insertIfAbsent);
        return store.findByPlayerId("P-1");
    }

    private static List<String> idsInOrder(List<March> marches) {
        return idsIn(marches);
    }

    private static List<String> idsIn(List<March> marches) {
        List<String> out = new ArrayList<>();
        marches.forEach(m -> out.add(m.id()));
        return out;
    }

    private static March march(String id, long startAt) {
        return marchBetween(id, startAt, Coord.of(10, 10), Coord.of(40, 40));
    }

    private static March marchBetween(String id, long startAt, Coord from, Coord to) {
        return new March(id, "P-1", from, to, startAt, startAt + 60_000L,
                Map.of("unit_infantry_t1", 100L), List.of("hero_ssr_01"),
                1_000L, 120, March.TargetType.RESOURCE, "res_node_1", March.Action.GATHER);
    }

    private static MarchRepository newMongoStore() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：本机没有可用的 MongoDB（" + TestMongo.uri() + "），"
                        + "行军存储的跨实现等价性今天没有被检查过");
        return new MongoMarchStore(db.template(), CHUNK_SIZE);
    }
}
