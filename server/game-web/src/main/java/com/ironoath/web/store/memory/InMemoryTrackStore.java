package com.ironoath.web.store.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ironoath.web.ops.TrackEventStore;

/**
 * 职责：埋点与崩溃上报的内存存储（dev / 单测零依赖启动）。
 * 依赖：{@link TrackEventStore} 端口。
 *
 * <p><b>与其它内存存储同一套约定</b>：进程重启后数据全丢，生产必须设 {@code ironoath.storage=mongo}。
 * 但埋点这一份的丢失后果比战报轻 —— 它不影响任何数值正确性，
 * 影响的只是「上线第一天能不能看懂玩家为什么走」。
 *
 * <p><b>崩溃记录不设上限</b>：崩溃是低频事件，而它是线上唯一能解释
 * 「玩家为什么再也进不来」的证据。给证据设一个会被冲掉的上限，
 * 等于在最需要它的时候（大面积崩溃）恰好把它删光 —— 崩溃越多，最早的越先被挤掉。
 *
 * <p><b>{@link #recentOf} 是倒序线性扫描</b>：内存版不维护 per-player 索引，
 * 因为那样一来淘汰最旧事件时要同时从两个结构里删，而双结构淘汰是最容易写出
 * 「索引里留着一条主表已经没有的记录」的地方。生产版由 MongoDB 的 playerId 索引解决 ——
 * 这正是「语义必须一致、实现可以不同」的典型例子。
 */
public final class InMemoryTrackStore implements TrackEventStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTrackStore.class);

    private final int eventCap;
    private final Deque<TrackRecord> events = new ArrayDeque<>();
    private final Map<String, CrashRecord> crashesByTrace = new LinkedHashMap<>();
    private int droppedCount;
    private boolean dropWarned;

    /**
     * @param eventCap 事件保留条数上限。来源 global.TRACK_STORE_MAX_EVENTS ——
     *                 内存版必须有一个兜底上限，否则一次长时间压测就会把 dev 进程的堆吃光，
     *                 而症状会被读成「服务端有内存泄漏」，排查方向完全错
     */
    public InMemoryTrackStore(int eventCap) {
        if (eventCap < 1) {
            throw new IllegalArgumentException("eventCap 必须 >= 1，实际=" + eventCap);
        }
        this.eventCap = eventCap;
    }

    @Override
    public synchronized int saveBatch(List<TrackRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (TrackRecord record : records) {
            if (record == null) {
                continue;
            }
            events.addLast(record);
            written++;
            while (events.size() > eventCap) {
                events.pollFirst();
                droppedCount++;
                if (!dropWarned) {
                    dropWarned = true;
                    LOG.warn("内存埋点存储已达上限 {} 条，开始丢弃最旧事件。这只在 dev/单测出现；"
                            + "生产必须设 ironoath.storage=mongo，且保留期不得短于 "
                            + "global.DASHBOARD_RETENTION_DAYS 的最大值，否则 D30 留存算不出来", eventCap);
                }
            }
        }
        return written;
    }

    @Override
    public synchronized List<TrackRecord> recentOf(String playerId, int limit) {
        if (playerId == null || playerId.isBlank() || limit < 1) {
            return List.of();
        }
        List<TrackRecord> out = new ArrayList<>();
        // 先按倒序收集（保留"同刻并列时后落的在前"这个稳定次序），再按 serverTs 排。
        // 端口承诺的是"按服务端落库时刻倒序"，而原先直接沿着 deque 倒着走 = 按插入序，
        // 两者只在"时刻严格递增"时才等价 —— 补报或同一批里取到同一个时刻就会给出错的顺序，
        // 而排查时看错一条事件的先后，比少给几条更难发现
        var iterator = events.descendingIterator();
        while (iterator.hasNext()) {
            TrackRecord record = iterator.next();
            if (playerId.equals(record.playerId())) {
                out.add(record);
            }
        }
        if (out.size() > 1) {
            out.sort(java.util.Comparator.comparingLong(TrackRecord::serverTs).reversed());
        }
        return List.copyOf(out.size() > limit ? out.subList(0, limit) : out);
    }

    @Override
    public synchronized int eventCount() {
        return events.size();
    }

    @Override
    public synchronized boolean saveCrash(CrashRecord crash) {
        if (crash == null) {
            throw new IllegalArgumentException("crash 不得为 null");
        }
        return crashesByTrace.putIfAbsent(crash.traceId(), crash) == null;
    }

    @Override
    public synchronized Optional<CrashRecord> findCrash(String traceId) {
        return Optional.ofNullable(crashesByTrace.get(traceId));
    }

    @Override
    public synchronized int purgeOlderThan(long cutoffMillis) {
        int removed = 0;
        // 判据是 serverTs 而不是"从队首弹出"：队首是按落库顺序排的，两者通常一致，
        // 但客户端补报会让一条旧事件晚落库 —— 按顺序弹就会误删新数据、漏删旧数据
        var iterator = events.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().serverTs() < cutoffMillis) {
                iterator.remove();
                removed++;
            }
        }
        // 刻意不计入 droppedCount：那个数的含义是"因超出条数上限而丢"，是"该换 Mongo"的警报；
        // 按保留期清理是正常行为，混进去会让警报再也读不出它原本的意思
        return removed;
    }

    @Override
    public synchronized int purgeCrashesOlderThan(long cutoffMillis) {
        int removed = 0;
        var iterator = crashesByTrace.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().serverTs() < cutoffMillis) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized int crashCount() {
        return crashesByTrace.size();
    }

    @Override
    public synchronized Map<String, Long> crashCountByVersion(long sinceMillis) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CrashRecord crash : crashesByTrace.values()) {
            if (crash.serverTs() >= sinceMillis) {
                out.merge(TrackEventStore.groupKey(crash.clientVersion()), 1L, Long::sum);
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public synchronized Map<String, Long> countEventsByParam(long sinceMillis, String eventName,
                                                             String paramKey) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (TrackRecord record : events) {
            if (record.serverTs() >= sinceMillis && record.name().equals(eventName)) {
                out.merge(TrackEventStore.groupKey(record.params().get(paramKey)), 1L, Long::sum);
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public synchronized List<TrackRecord> recentByName(String eventName, int limit) {
        if (eventName == null || eventName.isBlank() || limit < 1) {
            return List.of();
        }
        List<TrackRecord> out = new ArrayList<>();
        for (TrackRecord record : events) {
            if (record.name().equals(eventName)) {
                out.add(record);
            }
        }
        // 与 recentOf 同一条：端口承诺的是"按 serverTs 倒序"，而 deque 的迭代序只是落库顺序，
        // 补报或同批同刻时两者不等价 —— 排查时看错先后比少给几条更难发现
        out.sort(java.util.Comparator.comparingLong(TrackRecord::serverTs).reversed());
        return List.copyOf(out.size() > limit ? out.subList(0, limit) : out);
    }

    @Override
    public synchronized int countByName(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return 0;
        }
        int n = 0;
        for (TrackRecord record : events) {
            if (record.name().equals(eventName)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized List<CrashRecord> recentCrashes(int limit) {
        if (limit < 1) {
            return List.of();
        }
        // 插入序 + 稳定排序 = 同一毫秒并列时先落的在前。生产版（Mongo）按 _id 定序，
        // 两者只在"并列"时不同 —— 与 recentOf 一样，并列次序不是契约。
        List<CrashRecord> out = new ArrayList<>(crashesByTrace.values());
        out.sort(java.util.Comparator.comparingLong(CrashRecord::serverTs).reversed());
        return List.copyOf(out.size() > limit ? out.subList(0, limit) : out);
    }

    /** 因超出保留上限而丢弃的事件数。非零说明该换 Mongo 了。 */
    public synchronized int droppedCount() {
        return droppedCount;
    }

    @Override
    public synchronized void clear() {
        events.clear();
        crashesByTrace.clear();
        droppedCount = 0;
        dropWarned = false;
    }
}
