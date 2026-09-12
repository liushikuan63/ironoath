package com.ironoath.web.filter;

import com.ironoath.common.log.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 职责：为每个 HTTP 请求绑定 traceId，并透传给客户端与日志。
 * 依赖：Spring Web、game-common 的 TraceContext。
 *
 * <p>铁律 10：日志需带 traceId、玩家 id、关键入参，便于线上排查。
 * 玩家报障时只要提供响应头里的 {@code X-Trace-Id}，就能在日志里捞出这一次请求的完整链路。
 *
 * <p>{@code finally} 里必须清理 ThreadLocal：Tomcat 用线程池，
 * 不清理会把上一个玩家的 traceId 带到下一个请求，排查时看到的是一条根本不存在的链路。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 客户端可自带 traceId（便于端到端串联），否则服务端生成。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** traceId 长度上限。不设限会让攻击者用超长头污染日志与索引。 */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = isUsable(incoming) ? incoming : TraceContext.newTraceId();
        TraceContext.bindTrace(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_TRACE_ID_LENGTH;
    }
}
