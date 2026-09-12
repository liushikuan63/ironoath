package com.ironoath.common;

/**
 * 职责：统一响应封装，与 contract/proto/player.schema.json 的 ApiResult 一一对应。
 * 依赖：game-common 的 ErrorCode（纯 Java，零框架）。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code code} 0 表示成功，非 0 见 {@link ErrorCode}</li>
 *   <li>{@code msg} 中文提示，可直接展示给玩家</li>
 *   <li>{@code data} 业务负载，失败时为 null</li>
 *   <li>{@code traceId} 链路 id，客户端报障时带上它即可在服务端日志定位（铁律 10）</li>
 *   <li>{@code serverNow} 服务端时间戳，顺带下发以持续校准客户端时钟（铁律 5）</li>
 *   <li>{@code detail} 排查用详情，仅 dev/test 环境下发，prod 由 web 层置 null</li>
 * </ul>
 *
 * <p>用 record 而非可变类：Result 一旦构造就不该被改写，避免某一层偷偷改 code 造成排查困难。
 */
public record Result<T>(int code, String msg, T data, String traceId, long serverNow, String detail) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.code(), ErrorCode.OK.msg(), data, null, 0L, null);
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.OK.code(), ErrorCode.OK.msg(), null, null, 0L, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        if (errorCode == null) {
            throw new IllegalArgumentException("Result.fail 的 errorCode 不得为 null");
        }
        if (errorCode.isSuccess()) {
            throw new IllegalArgumentException("Result.fail 不接受成功码 OK");
        }
        return new Result<>(errorCode.code(), errorCode.msg(), null, null, 0L, detail);
    }

    public static <T> Result<T> fail(BizException e) {
        return fail(e.errorCode(), e.detail());
    }

    /** 返回带 traceId 的新实例（record 不可变，用 wither 模式）。 */
    public Result<T> withTraceId(String newTraceId) {
        return new Result<>(code, msg, data, newTraceId, serverNow, detail);
    }

    /** 返回带服务端时间戳的新实例。 */
    public Result<T> withServerNow(long newServerNow) {
        return new Result<>(code, msg, data, traceId, newServerNow, detail);
    }

    /** 生产环境剥离 detail，避免内部信息外泄。 */
    public Result<T> withoutDetail() {
        return new Result<>(code, msg, data, traceId, serverNow, null);
    }

    public boolean isSuccess() {
        return code == ErrorCode.OK.code();
    }
}
