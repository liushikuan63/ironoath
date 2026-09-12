package com.ironoath.common;

/**
 * 职责：业务异常 —— 携带 ErrorCode 从任意层抛出，由 game-web 的全局异常处理器统一转成 Result。
 * 依赖：game-common 的 ErrorCode（纯 Java，零框架）。
 *
 * <p>为什么用异常而不是层层返回 Result：玩法逻辑（game-core）里一个操作可能触发十几条前置校验，
 * 逐层返回 Result 会让主干代码被 if 淹没。抛异常 + 顶层统一捕获，主干只写「正常路径」。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 错误详情，用于日志与排查；下发客户端时由 web 层决定是否携带。 */
    private final String detail;

    public BizException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public BizException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public BizException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.msg() + (detail == null ? "" : " | " + detail), cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("BizException 的 errorCode 不得为 null");
        }
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int code() {
        return errorCode.code();
    }

    public String detail() {
        return detail;
    }

    /** 断言工具：条件不成立即抛业务异常，让校验代码保持一行。 */
    public static void requireTrue(boolean condition, ErrorCode errorCode, String detail) {
        if (!condition) {
            throw new BizException(errorCode, detail);
        }
    }

    public static void requireNonNull(Object value, ErrorCode errorCode, String detail) {
        if (value == null) {
            throw new BizException(errorCode, detail);
        }
    }
}
