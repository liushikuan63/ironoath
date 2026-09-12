package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import com.ironoath.web.store.mongo.MongoMarchDueQueue;

/**
 * 职责：行军到期队列在内存与 Mongo 上必须给出同一个结果（B07 验收 2 的存储侧证明）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p>这条队列是"重启后行军队列还在不在"的直接判据：{@code MarchAppService} 的到期推进
 * 完全依赖 {@code dueBefore}。队列丢了不会让存档消失，只是所有在途队伍停在半路 ——
 * 症状比丢档更安静，所以需要独立于行军仓储的等价测试。
 */
class MarchDueQueueEquivalenceTest {

    private static TestMongo db;

    @BeforeAll
    static void setUp() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearQueue() {
        if (db != null) {
            db.template().remove(new org.springframework.data.mongodb.core.query.Query(),
                    com.ironoath.web.store.mongo.MarchDueDocument.COLLECTION);
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
    @DisplayName("登记与到期扫描：只回 now 之前到期的，按到期时刻升序，且不删除记录")
    void scheduleAndDueBeforeMatch() {
        for (MarchDueQueue queue : bothQueues()) {
            String label = queue.getClass().getSimpleName();
            queue.schedule("m-late", 5_000L);
            queue.schedule("m-early", 1_000L);
            queue.schedule("m-mid", 3_000L);

            assertThat(queue.dueBefore(500L, 10)).as("%s：还没到点", label).isEmpty();
            assertThat(queue.dueBefore(3_000L, 10)).as("%s：含等于 now 的那条", label)
                    .containsExactly("m-early", "m-mid");
            assertThat(queue.size()).as("%s：取过不删除，size 不变", label).isEqualTo(3);
            assertThat(queue.dueBefore(10_000L, 2)).as("%s：limit 截断且仍按到期升序", label)
                    .containsExactly("m-early", "m-mid");
        }
    }

    @Test
    @DisplayName("改期是一次覆盖：同 id 只留最新时刻，旧时刻不再被扫出来")
    void rescheduleOverwrites() {
        for (MarchDueQueue queue : bothQueues()) {
            String label = queue.getClass().getSimpleName();
            queue.schedule("m-1", 1_000L);
            queue.reschedule("m-1", 9_000L);

            assertThat(queue.size()).as("%s：仍是同一条", label).isEqualTo(1);
            assertThat(queue.dueBefore(2_000L, 10)).as("%s：旧时刻已失效", label).isEmpty();
            assertThat(queue.dueBefore(9_000L, 10)).as("%s：新时刻生效", label)
                    .containsExactly("m-1");
        }
    }

    @Test
    @DisplayName("取消幂等：撤销存在的和不存在的结果一致，都不报错")
    void cancelIsIdempotent() {
        for (MarchDueQueue queue : bothQueues()) {
            String label = queue.getClass().getSimpleName();
            queue.schedule("m-1", 1_000L);
            queue.cancel("m-1");
            queue.cancel("m-1");

            assertThat(queue.size()).as("%s：已清空", label).isZero();
            assertThat(queue.dueBefore(9_999L, 10)).as("%s：取不到", label).isEmpty();
        }
    }

    @Test
    @DisplayName("非法参数与 limit<=0：两套实现抛同一类异常")
    void invalidArgumentsAreRefused() {
        for (MarchDueQueue queue : bothQueues()) {
            String label = queue.getClass().getSimpleName();
            assertThatThrownBy(() -> queue.schedule("  ", 1_000L))
                    .as("%s：空 id", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> queue.schedule("m-1", 0L))
                    .as("%s：非正到期时刻", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> queue.cancel(null))
                    .as("%s：空 id 撤销", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> queue.dueBefore(10L, 0))
                    .as("%s：limit=0", label).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private List<MarchDueQueue> bothQueues() {
        requireMongo();
        return List.of(new SortedMarchDueQueue(), new MongoMarchDueQueue(db.template()));
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 行军到期队列在 Mongo 上的等价性今天没有被验证");
    }
}