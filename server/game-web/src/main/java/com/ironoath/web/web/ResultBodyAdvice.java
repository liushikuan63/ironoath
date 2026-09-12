package com.ironoath.web.web;

import com.ironoath.common.Result;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.config.GameProperties;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 职责：给所有 {@link Result} 响应统一补上 traceId 与 serverNow，并按环境决定是否剥离 detail。
 * 依赖：Spring Web、game-common。
 *
 * <p>为什么用 ResponseBodyAdvice 而不是在每个 Controller 里手写：
 * traceId 与 serverNow 是<b>每一个</b>响应都必须带的（铁律 10 排查需要、铁律 5 时钟校准需要）。
 * 靠人记得写就一定会漏，漏掉的那个接口会在上线后变成「这个请求查不到日志」。
 *
 * <p>顺带下发 serverNow 让客户端每次请求都能校准一次时钟，
 * 不需要单独的心跳同步接口也能把偏移压到很小。
 */
@RestControllerAdvice
public class ResultBodyAdvice implements ResponseBodyAdvice<Object> {

    private final TimeService timeService;
    private final GameProperties properties;

    public ResultBodyAdvice(TimeService timeService, GameProperties properties) {
        this.timeService = timeService;
        this.properties = properties;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Result<?> result)) {
            return body;
        }
        Result<?> enriched = result
                .withTraceId(TraceContext.traceId())
                .withServerNow(timeService.serverNow());
        // 生产环境剥离 detail：里面常带内部字段名与配置 id，泄漏出去等于给外挂作者送地图
        return properties.exposeDetail() ? enriched : enriched.withoutDetail();
    }
}
