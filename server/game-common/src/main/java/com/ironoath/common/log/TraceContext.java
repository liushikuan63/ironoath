package com.ironoath.common.log;

import java.util.UUID;

/**
 * 职责：链路上下文 —— 在一次请求/一次 Bot tick 内传递 traceId 与 playerId，供中文日志输出。
 * 依赖：无（纯 Java，零框架，不绑定任何日志实现）。
 *
 * <p>铁律 10「中文注释与中文日志：日志需带 traceId、玩家 id、关键入参」。本类只提供上下文存储，
 * 具体日志实现由各层自选：game-web 把 traceId 写入 slf4j MDC，纯 Java 层用 {@link System.Logger}
 * 或直接调用 {@link #prefix()} 拼进消息文本。
 *
 * <p>为什么不用 slf4j MDC：MDC 属于日志框架，把它引进 game-common 就会让纯 Java 层多一个框架依赖。
 * 这里用裸 ThreadLocal，跨层零成本。
 *
 * <p><b>必须在请求结束时 {@link #clear()}</b>，否则线程池复用会把上一个玩家的 traceId 带到下一个请求。
 */
public final class TraceContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    private record Holder(String traceId, String playerId) {
    }

    /** 绑定链路上下文。traceId 传 null 时自动生成。 */
    public static String bind(String traceId, String playerId) {
        String tid = (traceId == null || traceId.isBlank()) ? newTraceId() : traceId;
        HOLDER.set(new Holder(tid, playerId));
        return tid;
    }

    /** 仅绑定 traceId（尚未确定 playerId 的阶段，如网关过滤器）。 */
    public static String bindTrace(String traceId) {
        String tid = (traceId == null || traceId.isBlank()) ? newTraceId() : traceId;
        HOLDER.set(new Holder(tid, null));
        return tid;
    }

    /** 补充 playerId（玩家身份在鉴权后才确定）。 */
    public static void bindPlayer(String playerId) {
        Holder h = HOLDER.get();
        HOLDER.set(new Holder(h == null ? newTraceId() : h.traceId(), playerId));
    }

    /** 当前 traceId；未绑定时生成一个新的并绑定，保证任何日志都有 traceId。 */
    public static String traceId() {
        Holder h = HOLDER.get();
        if (h == null) {
            return bindTrace(null);
        }
        return h.traceId();
    }

    /** 当前 playerId，未绑定时返回 "-"（日志里保留占位，方便按列切分）。 */
    public static String playerId() {
        Holder h = HOLDER.get();
        return (h == null || h.playerId() == null) ? "-" : h.playerId();
    }

    /** 日志前缀，形如 {@code [traceId=xxx playerId=yyy]}。直接拼在中文日志前面。 */
    public static String prefix() {
        return "[traceId=" + traceId() + " playerId=" + playerId() + "]";
    }

    /** 清理上下文。请求结束、Bot tick 结束、异步任务结束时必须调用。 */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 生成 traceId。
     *
     * <p>用 UUID 而非 Rng：traceId 不是游戏内随机事件，不参与复算，
     * 但需要全局唯一且不可预测（防止玩家猜测他人 traceId 去拉别人的日志/战报）。
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
