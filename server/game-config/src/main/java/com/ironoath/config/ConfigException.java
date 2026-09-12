package com.ironoath.config;

import java.util.List;

/**
 * 职责：配置表校验/读取失败异常 —— 携带<b>全部</b>错误项，而非第一条。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>B01 三条硬要求之 3：配置表启动期全量校验，校验失败必须拒绝启动，并且一次性列出全部错误字段。
 * 只报第一条会让策划「改一个字段、重启一次、再发现下一个」，一张 200 行的表能来回折腾一整天。
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 全部错误项，每项形如 {@code [表名#行id] 字段: 原因}。 */
    private final List<String> errors;

    public ConfigException(String message) {
        this(message, List.of());
    }

    public ConfigException(String message, List<String> errors) {
        super(format(message, errors));
        this.errors = List.copyOf(errors);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.errors = List.of();
    }

    public List<String> errors() {
        return errors;
    }

    public int errorCount() {
        return errors.size();
    }

    /** 拼成多行文本，直接打进启动日志，让策划一眼看到所有问题。 */
    private static String format(String message, List<String> errors) {
        if (errors.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message)
                .append("（共 ").append(errors.size()).append(" 处错误）\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append("  ").append(i + 1).append(") ").append(errors.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
