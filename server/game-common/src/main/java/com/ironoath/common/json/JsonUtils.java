package com.ironoath.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 职责：全项目统一的 JSON 读写入口（配置表载入、协议序列化、战报存档）。
 * 依赖：jackson-databind（第三方库，非框架，纯 Java 层允许）。
 *
 * <p>严格模式：{@code FAIL_ON_UNKNOWN_PROPERTIES} 打开。配置表或协议里多出一个未定义字段就报错，
 * 而不是静默忽略 —— 静默忽略会让「策划改了字段名但代码没跟上」这类问题一直到线上才暴露。
 *
 * <p>ObjectMapper 线程安全，全局单例复用。禁止在业务代码里 new ObjectMapper（每次 new 都要重建
 * 序列化器缓存，是高并发下的常见性能坑）。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    private JsonUtils() {
    }

    /** 全局共享的 ObjectMapper，需要自定义读取时用（如配置校验器遍历 JsonNode）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("JSON 序列化失败: " + value.getClass().getName(), e);
        }
    }

    /** 带缩进的 JSON，用于生成配置表与战报快照的可读输出。 */
    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("JSON 序列化失败: " + value.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("JSON 反序列化失败: " + type.getName(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("JSON 反序列化失败: " + typeRef.getType(), e);
        }
    }

    public static <T> T fromStream(InputStream in, Class<T> type) {
        try {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 读取失败: " + type.getName(), e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("JSON 解析失败", e);
        }
    }

    public static JsonNode readTree(InputStream in) {
        try {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 解析失败", e);
        }
    }
}
