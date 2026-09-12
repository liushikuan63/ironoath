package com.ironoath.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责：本工程自定义配置项（前缀 {@code ironoath}）。
 * 依赖：Spring Boot。
 *
 * <p>注意区分两类配置：
 * <ul>
 *   <li><b>本类</b>：部署相关（配置表在哪、用哪种存储）—— 随环境变化，属于运维参数</li>
 *   <li><b>contract/config/*.json</b>：游戏数值 —— 随策划调整，属于游戏内容（铁律 1）</li>
 * </ul>
 * 游戏数值绝不放进 application.yml，否则策划改一个数字就要重新部署服务端。
 *
 * @param configDir 配置表目录，默认 {@code contract/config}；相对路径会向上回溯查找（见 ConfigRegistry）
 * @param storage   存储实现：{@code memory}（单测/本地零依赖）或 {@code mongo}（生产）
 * @param exposeDetail 是否向客户端下发 Result.detail 排查信息。prod 必须为 false
 */
@ConfigurationProperties(prefix = "ironoath")
public record GameProperties(String configDir, String storage, boolean exposeDetail) {

    /** 存储实现标识。 */
    public static final String STORAGE_MEMORY = "memory";
    public static final String STORAGE_MONGO = "mongo";

    public GameProperties {
        if (configDir == null || configDir.isBlank()) {
            configDir = "contract/config";
        }
        if (storage == null || storage.isBlank()) {
            storage = STORAGE_MEMORY;
        }
    }

    public boolean isMongo() {
        return STORAGE_MONGO.equalsIgnoreCase(storage);
    }
}
