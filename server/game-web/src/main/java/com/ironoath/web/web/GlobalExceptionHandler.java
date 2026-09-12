package com.ironoath.web.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.log.TraceContext;
import com.ironoath.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 职责：全局异常处理 —— 把任意层抛出的异常统一转成 {@link Result}，并打中文日志。
 * 依赖：Spring Web、game-common、game-config。
 *
 * <p>HTTP 状态码约定（刻意与「REST 教科书」不同，理由如下）：
 * <ul>
 *   <li><b>业务失败返回 200</b> + 非 0 的 code：资源不足、等级不够、目标战力超区间这类
 *       是游戏的正常状态而不是 HTTP 异常。客户端 NetModule 只需要一条「解析 body 看 code」
 *       的路径，不必同时处理 4xx/5xx 与业务码两套错误语义，重试策略也不会误判
 *       （4xx 通常不重试，但「资源不足」在玩家卖东西之后就该重试成功）。</li>
 *   <li><b>系统故障返回 5xx</b>：只有这类才触发客户端的指数退避重试与离线队列。</li>
 * </ul>
 *
 * <p>traceId 与 serverNow 由 {@link ResultBodyAdvice} 统一补齐，这里不用管。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：预期内的失败，WARN 级别，不打堆栈（堆栈对「资源不足」这种没有信息量）。 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBiz(BizException e) {
        LOG.warn("{} 业务失败 code={} msg={} detail={}",
                TraceContext.prefix(), e.code(), e.errorCode().msg(), e.detail());
        return Result.fail(e);
    }

    /** 配置异常：正常运行期不该出现（启动期已全量校验），出现即视为严重故障。 */
    @ExceptionHandler(ConfigException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleConfig(ConfigException e) {
        LOG.error("{} 配置异常，共 {} 处错误：{}",
                TraceContext.prefix(), e.errorCount(), e.getMessage());
        return Result.fail(ErrorCode.CONFIG_INVALID, e.getMessage());
    }

    /** 请求体解析失败：JSON 格式错、字段类型错。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        LOG.warn("{} 请求体解析失败：{}", TraceContext.prefix(), e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID, "请求体格式错误");
    }

    /** 参数类型不匹配（如路径变量传了非数字）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        LOG.warn("{} 参数类型不匹配 name={} value={}",
                TraceContext.prefix(), e.getName(), e.getValue());
        return Result.fail(ErrorCode.PARAM_INVALID, "参数 " + e.getName() + " 类型错误");
    }

    /** 非法参数：属于代码 bug 或客户端未按契约调用，需要看堆栈定位。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        LOG.error("{} 非法入参（疑似代码 bug 或客户端未按契约调用）：{}",
                TraceContext.prefix(), e.getMessage(), e);
        return Result.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    /**
     * 缺少必需的请求头（最典型的是身份头 {@code X-Player-Id}）。
     *
     * <p>没有这个处理器时它会掉进兜底分支变成 <b>HTTP 500 + SYSTEM_ERROR</b>，
     * 而客户端 NetModule 对 5xx 的策略是指数退避重试 + 进离线队列 ——
     * 缺请求头是客户端没按契约调用，重试一万次也不会成功。
     * 把一个永久性的客户端错误报成临时性的服务端故障，会让重试逻辑空转、
     * 离线队列被塞满无用请求，排查时还会误以为是服务端挂了。
     * 所以按本类的既有约定归为 PARAM_INVALID（200 + 非 0 code，不触发重试）。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        LOG.warn("{} 缺少必需的请求头 name={}", TraceContext.prefix(), e.getHeaderName());
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少必需的请求头 " + e.getHeaderName());
    }

    /** 缺少必需的查询参数。理由同 {@link #handleMissingHeader}：客户端契约错误，不该报成 5xx。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        LOG.warn("{} 缺少必需的查询参数 name={}", TraceContext.prefix(), e.getParameterName());
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少必需的查询参数 " + e.getParameterName());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoHandlerFoundException e) {
        LOG.warn("{} 接口不存在 {} {}", TraceContext.prefix(), e.getHttpMethod(), e.getRequestURL());
        return Result.fail(ErrorCode.SYSTEM_ERROR, "接口不存在");
    }

    /**
     * 路径不存在。Spring 6.1 起「没有控制器匹配」抛的是 {@code NoResourceFoundException}
     * （由静态资源兜底处理器抛出），<b>不再是</b>上面的 {@code NoHandlerFoundException} ——
     * 少了这个处理器，扫路径的机器人、客户端 typo、旧版本调已下线端点
     * 全都会掉进兜底分支变成 <b>HTTP 500 + 系统繁忙</b>：
     * 监控上的 5xx 告警被噪音灌满，而真正的问题（有人在调不存在的接口）被伪装成服务端故障。
     *
     * <p>与缺请求头那一条同一类判断：永久性的客户端错误不该报成临时性的服务端故障。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        LOG.warn("{} 接口不存在 {} {}", TraceContext.prefix(), e.getHttpMethod(), e.getResourcePath());
        return Result.fail(ErrorCode.SYSTEM_ERROR, "接口不存在");
    }

    /** 兜底：任何未预料的异常都必须在日志里留下完整堆栈，并给客户端一个可读的中文提示。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception e) {
        LOG.error("{} 未处理异常，需要人工介入：{}", TraceContext.prefix(), e.getMessage(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR, e.getClass().getSimpleName());
    }
}
