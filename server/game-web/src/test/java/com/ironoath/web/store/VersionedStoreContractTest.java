package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：把「版本化仓储」的四条共同语义做成一份可复用的契约，每套实现子类化后跑同一组断言。
 * 依赖：JUnit 5 + AssertJ（不起 Spring 上下文）。
 *
 * <p><b>为什么需要它</b>：{@code DEVELOPMENT.md} §四 写着内存与 Mongo「语义等价」，
 * 而在此之前<b>没有任何一条测试检查过这件事</b>。它一旦不成立，症状从来不是报错，而是：
 * <ul>
 *   <li>读返回活对象 → 调用方不 save 也改到了库里的数据 → 内存版单测全绿，
 *       换成 Mongo 后同一处改动<b>静默丢档</b>（收口清单 #25 已抓到一例）；</li>
 *   <li>版本比对写成「先读后判再写」→ 并发两个请求都通过检查 → 后写的把先写的整份覆盖，
 *       表现是「我的建筑升级没保存上」；</li>
 *   <li>{@code insertIfAbsent} 写成先查后插 → 并发首次登录插出两份存档，道具随机落在其中一份上。</li>
 * </ul>
 * 这三条都是「每个仓储各自手写一遍、然后各自漂移」的形状。有了契约，补一套 Mongo 实现时
 * 子类化一次就能证明等价，而不是在注释里承诺等价。
 *
 * <p>本类是抽象类：JUnit 5 不会直接跑它，由下面每个 {@code *StoreContractTest} 子类继承这四条。
 */
abstract class VersionedStoreContractTest<T> {

    /** 存储名，出现在所有断言消息里（失败时必须能一眼看出是哪套实现）。 */
    protected abstract String storeName();

    /** 每个用例都用全新的存储实例，用例之间不共享状态。 */
    protected abstract void freshStore();

    /** 首次落库（必须走 insertIfAbsent，不允许用 save 兜）。 */
    protected abstract boolean insertInitialState();

    /** 读一次。读不到即视为契约前提被破坏，直接抛 AssertionError。 */
    protected abstract StoreHandle<T> read();

    /** 一个随「改一次状态」单调变化的量：建筑数、兵力、道具总数、城等级… */
    protected abstract long observe(T state);

    /** 就地改一下，使 {@link #observe} 变化。 */
    protected abstract void bump(T state);

    /** 以 {@code handle.readVersion()} 为期望版本落库；版本不匹配时抛 {@link IllegalStateException}。 */
    protected abstract void persist(StoreHandle<T> handle);

    /** 库里当前的版本，口径必须与 {@link #read()} 取到的版本一致。 */
    protected abstract long storedVersion();

    // ---------- 契约四条 ----------

    @Test
    @DisplayName("读返回副本：不 save 的改动不得泄漏进库里")
    void readReturnsACopyNotTheLiveRecord() {
        freshStore();
        insertInitialState();
        long baseline = observe(read().state());

        StoreHandle<T> handle = read();
        bump(handle.state());
        long touched = observe(handle.state());
        assertThat(touched).as("%s：bump 没改到本地副本，这条用例等于什么都没测", storeName())
                .isNotEqualTo(baseline);

        assertThat(observe(read().state()))
                .as("%s：读到的对象被就地改了一笔且没有 save，库里却跟着变了 —— "
                        + "内存版这样会掩盖「忘记持久化」，换到 Mongo 上同一处改动就是静默丢档",
                        storeName())
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("保存成功会推进版本，并且内容真的落库")
    void savePersistsAndBumpsVersion() {
        freshStore();
        insertInitialState();
        long versionAtStart = storedVersion();

        StoreHandle<T> handle = read();
        bump(handle.state());
        long mutated = observe(handle.state());
        persist(handle);

        assertThat(observe(read().state())).as("%s：保存的内容必须读得回来", storeName())
                .isEqualTo(mutated);
        assertThat(storedVersion()).as("%s：保存之后版本必须前进", storeName())
                .isGreaterThan(versionAtStart);
    }

    /** 「后写覆盖先写」的专用探针：三条里最容易写错的一条。 */
    @Test
    @DisplayName("过期版本的保存被拒绝，且不许留下半个写入")
    void staleVersionIsRejectedWithoutPartialWrite() {
        freshStore();
        insertInitialState();

        StoreHandle<T> first = read();
        StoreHandle<T> second = read();
        assertThat(second.readVersion())
                .as("%s：两个读句柄本该看到同一个版本", storeName())
                .isEqualTo(first.readVersion());

        bump(first.state());
        persist(first);
        long winnerValue = observe(read().state());

        bump(second.state());
        assertThatThrownBy(() -> persist(second))
                .as("%s：拿旧版本提交必须失败，否则并发的两次写会互相整份覆盖", storeName())
                .isInstanceOf(IllegalStateException.class);

        assertThat(observe(read().state()))
                .as("%s：被拒的那次写入不能留下任何痕迹（哪怕一半）", storeName())
                .isEqualTo(winnerText(winnerValue));
    }

    @Test
    @DisplayName("并发首次插入只有一个赢家")
    void insertIfAbsentHasExactlyOneWinner() throws Exception {
        freshStore();
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                jobs.add(this::insertInitialState);
            }
            for (Callable<Boolean> job : jobs) {
                results.add(pool.submit(job));
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).as("并发插入跑不完，先查实现").isTrue();
        }

        long winners = 0L;
        for (Future<Boolean> result : results) {
            winners += Boolean.TRUE.equals(unwrap(result)) ? 1L : 0L;
        }
        assertThat(winners)
                .as("%s：insertIfAbsent 若写成「先查后插」，并发下会有多个赢家 —— "
                        + "于是同一个玩家能建出两份存档，道具与建筑随机落在其中一份上", storeName())
                .isEqualTo(1L);
    }

    /** 让失败输出里同时出现「胜者值」与被比较的当前值（AssertJ 只打印参数）。 */
    private long winnerValue(long observed) {
        return observed;
    }

    private long winnerText(long observed) {
        return winnerValue(observed);
    }

    private static Boolean unwrap(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发插入被中断", e);
        } catch (ExecutionException e) {
            throw new AssertionError("并发插入抛异常了：赢者通吃的前提是不许抛"
                    + "（重复主键必须翻译成 false）", e.getCause());
        }
    }
}
