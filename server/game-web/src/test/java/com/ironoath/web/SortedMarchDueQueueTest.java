package com.ironoath.web;

import com.ironoath.web.store.memory.SortedMarchDueQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：行军到期队列的验证 —— B07 验收 2（1000 支队伍同时到期，无漏触发、无重复触发）
 * 与验收 11（服务端无 per-march 定时器）的数据结构一侧。
 * 依赖：纯 JUnit，不需要容器。
 *
 * <p><b>验收 11 的另一半由 CI 静态检查守</b>：scripts/check-layering.sh 扫全服务端运行期模块，
 * 出现 {@code new Timer(} / {@code newScheduledThreadPool} / {@code scheduleAtFixedRate} /
 * {@code scheduleWithFixedDelay} / {@code setInterval(} 就直接失败。
 * 本类验的是「不用定时器也能正确触发」—— 到期由 {@code dueBefore} 拉取，
 * 而不是由回调推送，所以队列本身一个线程都不需要。
 */
class SortedMarchDueQueueTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("验收2：1000 支队伍同一时刻到期，分批取完无漏触发、无重复触发")
    void thousandSimultaneousDueAreAllDeliveredExactlyOnce() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        for (int i = 0; i < 1000; i++) {
            queue.schedule("march_" + i, NOW);
        }
        assertThat(queue.size()).isEqualTo(1000);
        assertThat(queue.sizeAt(NOW)).as("同一时刻的多支行军必须都登记在这个桶里").isEqualTo(1000);

        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        int rounds = 0;
        while (true) {
            List<String> due = queue.dueBefore(NOW, 200);
            if (due.isEmpty()) {
                break;
            }
            rounds++;
            for (String marchId : due) {
                if (!seen.add(marchId)) {
                    duplicates.add(marchId);
                }
                // 处理完才撤销：先删再处理的话，处理失败的那支就永远没人再管了
                queue.cancel(marchId);
            }
            assertThat(due.size()).as("每批不得超过 limit").isLessThanOrEqualTo(200);
        }
        assertThat(duplicates).as("不能有重复触发").isEmpty();
        assertThat(seen).as("1000 支必须全部被触发，漏一支玩家的队伍就永远停在半路").hasSize(1000);
        assertThat(rounds).as("应当分 5 批取完").isEqualTo(5);
        assertThat(queue.size()).as("全部撤销后队列必须空").isZero();
    }

    @Test
    @DisplayName("按到期时刻升序返回：先到期的先处理，否则晚出发的队伍会插队")
    void dueAreReturnedInAscendingOrder() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        queue.schedule("late", NOW + 3000L);
        queue.schedule("early", NOW + 1000L);
        queue.schedule("middle", NOW + 2000L);

        assertThat(queue.dueBefore(NOW + 999L, 10)).as("都还没到点").isEmpty();
        assertThat(queue.dueBefore(NOW + 1500L, 10)).containsExactly("early");
        assertThat(queue.dueBefore(NOW + 2500L, 10)).containsExactly("early", "middle");
        assertThat(queue.dueBefore(NOW + 3000L, 10))
                .as("到期时刻 == now 就算已到期，差一毫秒会让「刚好到点」的队伍多等一轮")
                .containsExactly("early", "middle", "late");
    }

    @Test
    @DisplayName("改期是原子的：不会出现「已取消但还没重新登记」的窗口把队伍漏掉")
    void rescheduleIsAtomic() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        queue.schedule("m1", NOW + 10_000L);
        assertThat(queue.dueBefore(NOW + 20_000L, 10)).containsExactly("m1");

        // 加速：把到期时刻提前
        queue.reschedule("m1", NOW + 5_000L);
        assertThat(queue.size()).as("改期不增加队列长度").isEqualTo(1);
        assertThat(queue.sizeAt(NOW + 10_000L)).as("旧时刻的桶必须被清空，否则会重复触发").isZero();
        assertThat(queue.dueBefore(NOW + 5_000L, 10)).containsExactly("m1");

        // 召回：把到期时刻推后
        queue.reschedule("m1", NOW + 50_000L);
        assertThat(queue.dueBefore(NOW + 20_000L, 10)).as("推后之后不该再到期").isEmpty();
        assertThat(queue.dueBefore(NOW + 50_000L, 10)).containsExactly("m1");
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销不存在的 id 是幂等的（行军到家后清理会重复撤销）")
    void cancelIsIdempotent() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        queue.cancel("never_scheduled");
        queue.schedule("m1", NOW + 1000L);
        queue.cancel("m1");
        queue.cancel("m1");
        assertThat(queue.size()).isZero();
        assertThat(queue.dueBefore(NOW + 9999L, 10)).isEmpty();
    }

    @Test
    @DisplayName("空桶必须被移除：否则 TreeMap 越积越多空列表，扫描越来越慢而队列长度看起来正常")
    void emptyBucketsAreRemoved() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        for (int i = 0; i < 100; i++) {
            queue.schedule("m" + i, NOW + i);
        }
        for (int i = 0; i < 100; i++) {
            queue.cancel("m" + i);
        }
        assertThat(queue.size()).isZero();
        for (int i = 0; i < 100; i++) {
            assertThat(queue.sizeAt(NOW + i)).as("时刻 %d 的桶必须已被移除", i).isZero();
        }
        // 全空之后 dueBefore 仍然要能正常工作
        assertThat(queue.dueBefore(NOW + 1000L, 10)).isEmpty();
    }

    @Test
    @DisplayName("非法入参在调用点就拒绝")
    void rejectsInvalidInput() {
        SortedMarchDueQueue queue = new SortedMarchDueQueue();
        assertThatThrownBy(() -> queue.schedule("", NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marchId");
        assertThatThrownBy(() -> queue.schedule("m1", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("到期时刻");
        assertThatThrownBy(() -> queue.dueBefore(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .as("limit=0 会让一次都取不出来，到期扫描静默失效").hasMessageContaining("limit");
    }
}
