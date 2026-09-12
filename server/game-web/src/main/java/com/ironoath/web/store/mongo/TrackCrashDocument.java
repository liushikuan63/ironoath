package com.ironoath.web.store.mongo;

import com.ironoath.web.ops.TrackEventStore;
import org.springframework.data.annotation.Id;

/**
 * 职责：一条崩溃上报的 MongoDB 文档。
 * 依赖：{@link TrackEventStore.CrashRecord}。
 *
 * <p>{@code _id = traceId} 是<b>幂等键本身</b>：端口写的是"同一 traceId 重复上报返回 false"
 * （客户端崩溃后重启会补报同一条），而 {@code _id} 唯一约束是这个语义最省的实现 ——
 * 与内存版 {@code putIfAbsent} 同一条，只是这里跨进程也成立。
 * 这与 {@link TrackEventDocument} 的随机键相反，且方向是对的：<b>事件不该被去重，证据必须被去重</b>。
 */
public record TrackCrashDocument(
        @Id String traceId,
        long serverTs,
        TrackEventStore.CrashRecord crash) {

    /** 集合名。 */
    public static final String COLLECTION = "track_crash";

    static TrackCrashDocument of(TrackEventStore.CrashRecord crash) {
        return new TrackCrashDocument(crash.traceId(), crash.serverTs(), crash);
    }
}
