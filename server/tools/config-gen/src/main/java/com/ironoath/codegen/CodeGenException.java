package com.ironoath.codegen;

/**
 * 职责：代码生成失败异常。
 * 依赖：无。
 */
public class CodeGenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodeGenException(String message) {
        super(message);
    }

    public CodeGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
