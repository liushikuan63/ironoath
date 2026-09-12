package com.ironoath.web.ops;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.track.TrackBatcher;
import com.ironoath.web.release.ReleaseRulesAssembler;

/**
 * 职责：驱动 {@link TrackBatcher} —— 把跨请求到达的埋点条目攒成批，再一次性写库（B16 §3 服务端落库）。
 * 依赖：game-core 的攒批策略、{@link TrackEventStore} 端口、TimeService。
 *
 * <p><b>客户端已经攒过一批了，服务端为什么还要再攒一次</b>：客户端那一批解决的是 HTTP 请求数，
 * 这一批解决的是数据库写入数。开服第一分钟数千个客户端同时上报启动与登录事件，
 * 每个请求一次 bulk write 就是每秒上千次写入；跨请求再攒一层，
 * 写入次数由「事件产生速率」变成「攒批窗口」决定 ——
 * 这正是 B16 禁止项「不要在主线程做同步 IO」想要的效果：
 * 不是把 IO 挪到别的线程去排队，而是根本不产生那么多 IO。
 *
 * <p><b>本类刻意不起定时器</b>：B03 验收 9 与 {@code scripts/check-layering.sh} 禁止服务端有任何
 * 常驻定时调度，理由是产出必须惰性结算、行军必须走延迟队列。埋点冲刷确实不是业务结算
 * （它碰不到任何游戏状态），但那条 CI 卡口是<b>无条件</b>的，而给它开一个豁免名单的代价
 * 比这里省下的那点延迟大得多 —— 名单一旦存在，下一个想加定时任务的人就会往里塞。
 * 所以这里用与全项目一致的惰性策略：<b>有人来的时候顺便推进一下</b>。
 *
 * <p>代价说清楚：若埋点流量完全停止，缓冲区里最后不足一批的事件会等到下一次上报或进程关闭才落库。
 * 这是可接受的 —— B16 §3 的「10 条或 10 秒触发」约束的是<b>客户端</b>的上报频率（那才是弱网负担的来源），
 * 服务端的攒批只为降低写入次数，它的延迟不出现在任何一条验收标准里。
 *
 * <p><b>进程关闭时必须冲刷</b>：缓冲区里最后那几条往往正是「服务为什么停了」的证据，
 * 让它们随进程一起消失，等于在最需要日志的时刻丢掉了日志。
 */
@Component
public class TrackFlusher implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(TrackFlusher.class);

    private final TrackBatcher<TrackEventStore.TrackRecord> batcher;
    private final TrackEventStore store;
    private final TimeService timeService;
    /** 留着是为了每次冲刷时现读保留天数：那个数是配置，不是启动时冻住的常量。 */
    private final ReleaseRulesAssembler assembler;

    public TrackFlusher(ReleaseRulesAssembler assembler, TrackEventStore store, TimeService timeService) {
        this.batcher = new TrackBatcher<>(assembler.trackRules());
        this.store = store;
        this.timeService = timeService;
        this.assembler = assembler;
        LOG.info("埋点攒批已启动：{} 条或 {} 毫秒触发一批（来源 global.TRACK_BATCH_MAX_SIZE / "
                        + "TRACK_BATCH_FLUSH_SECONDS）。惰性驱动，不起定时器",
                batcher.rules().maxBatchSize(), batcher.rules().flushIntervalMillis());
    }

    /**
     * 收下一条埋点。<b>先推进到点的批，再入队新条目</b> ——
     * 反过来的话，新条目会把队首的时刻刷新掉，于是那一批永远等不到自己的窗口。
     *
     * <p>{@code synchronized}：多个请求线程会同时碰缓冲区。
     * 这是进程内锁，不是 B13 禁止项说的「用 synchronized 做跨节点锁」——
     * 每个节点攒自己的批，各写各的，不存在跨节点协调的需要。
     */
    public synchronized void offer(TrackEventStore.TrackRecord record) {
        long now = timeService.serverNow();
        TrackBatcher.Batch<TrackEventStore.TrackRecord> due = batcher.tick(now);
        if (due != null) {
            write(due);
        }
        TrackBatcher.Batch<TrackEventStore.TrackRecord> full = batcher.track(record, now);
        if (full != null) {
            write(full);
        }
    }

    /** 到点冲刷。惰性驱动，所以由调用方（请求路径、测试、关闭钩子）触发。 */
    public synchronized int tick(long now) {
        TrackBatcher.Batch<TrackEventStore.TrackRecord> batch = batcher.tick(now);
        return batch == null ? 0 : write(batch);
    }

    /** 立即冲刷（进程关闭、运维手工触发、单测断言前）。 */
    public synchronized int flushNow(String reason) {
        TrackBatcher.Batch<TrackEventStore.TrackRecord> batch = batcher.flushNow(reason);
        return batch == null ? 0 : write(batch);
    }

    private int write(TrackBatcher.Batch<TrackEventStore.TrackRecord> batch) {
        List<TrackEventStore.TrackRecord> items = batch.items();
        int written = store.saveBatch(items);
        if (written < items.size()) {
            LOG.warn("埋点批量落库少写了 {} 条（{}）：存储侧已丢弃，分析漏斗会少一环，需要人去看",
                    items.size() - written, batch.reason());
        }
        purgeExpired(timeService.serverNow());
        return written;
    }

    /**
     * 按保留期清理。<b>由冲刷路径顺带驱动，不起定时器</b>（B00 陷阱 2，与战报
     * {@code purgeExpired}、订单 {@code expireUnpaid} 同一形状）。
     *
     * <p>保留天数取 {@code DASHBOARD_RETENTION_DAYS} 的<b>最大值</b>：这张表里写着 1,3,7,30，
     * 而 D30 要求留存事件必须还在（那条 {@code why} 自己写了"直接决定了埋点数据的保留时长下限"）。
     * 取平均或取第一个都会让 D30 算不出来，而且算不出来时看板只会显示一个空缺的格子，不报错。
     */
    private void purgeExpired(long now) {
        long retentionMillis = maxRetentionDays() * 86_400_000L;
        long cutoff = now - retentionMillis;
        int removedEvents = store.purgeOlderThan(cutoff);
        int removedCrashes = store.purgeCrashesOlderThan(cutoff);
        if (removedEvents > 0 || removedCrashes > 0) {
            LOG.info("埋点按保留期清理 事件={} 崩溃={} 保留天数={} 截止={}",
                    removedEvents, removedCrashes, maxRetentionDays(), cutoff);
        }
    }

    private int maxRetentionDays() {
        return assembler.retentionDays().stream().mapToInt(Integer::intValue).max().orElseThrow(
                () -> new IllegalStateException("DASHBOARD_RETENTION_DAYS 为空，算不出埋点保留期"));
    }

    /** 当前攒了多少条未落库。健康度指标。 */
    public synchronized int pendingCount() {
        return batcher.pendingCount();
    }

    /** 已交出多少批。批数远小于条目数说明攒批在生效。 */
    public synchronized int batchCount() {
        return batcher.batchCount();
    }

    @Override
    public void destroy() {
        int flushed = flushNow("进程关闭");
        LOG.info("埋点攒批已停止，关闭前冲刷 {} 条", flushed);
    }
}
