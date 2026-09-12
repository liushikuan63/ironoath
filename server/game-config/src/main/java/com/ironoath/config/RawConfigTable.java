package com.ironoath.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：一张<b>未定型</b>的配置表 —— 保持 JSON 原始形态，供校验器与生成器使用。
 * 依赖：game-common 的 JsonUtils（间接）、本模块的 ConfigException。
 *
 * <p>与 {@link ConfigTable}（强类型、面向业务代码）的分工：
 * <ul>
 *   <li>{@code RawConfigTable}：校验期与生成期的工作对象，字段还是 JsonNode，
 *       因为此时类型尚未确定，校验器要能报出「这个字段类型不对」</li>
 *   <li>{@code ConfigTable<T>}：校验通过后装配出的运行期对象，业务代码只应该看到它</li>
 * </ul>
 *
 * <p>校验通过后才构造，因此 {@code rowsById} 保证无重复主键、无 null 值。
 * 加载后全程只读，可安全地被多线程共享。
 *
 * @param name     表名
 * @param version  表版本号，热更与回滚依据
 * @param status   ACTIVE / RESERVED。预留表允许 rows 为空（只建结构，数据由后续批次填）
 * @param comment  表说明
 * @param rows     原始行（保持配置表中的顺序）
 * @param rowsById 主键索引
 */
public record RawConfigTable(
        String name,
        int version,
        String status,
        String comment,
        List<JsonNode> rows,
        Map<String, JsonNode> rowsById) {

    public RawConfigTable {
        rows = List.copyOf(rows);
        rowsById = Map.copyOf(rowsById);
    }

    /** 从已通过校验的 JSON 根节点构造。 */
    public static RawConfigTable of(String tableName, JsonNode root) {
        int version = root.get("version").asInt();
        String status = root.hasNonNull("status") ? root.get("status").asText() : "ACTIVE";
        String comment = root.hasNonNull("comment") ? root.get("comment").asText() : "";
        JsonNode rowsNode = root.get("rows");

        List<JsonNode> rows = new ArrayList<>(rowsNode.size());
        Map<String, JsonNode> index = new LinkedHashMap<>();
        if (rowsNode != null) {
            for (JsonNode row : rowsNode) {
                rows.add(row);
                index.put(row.get("id").asText(), row);
            }
        }
        return new RawConfigTable(tableName, version, status, comment, rows, index);
    }

    /**
     * 按主键取原始行。
     *
     * @throws ConfigException 当 id 不存在 —— 绝不返回 null（B01 硬约束）
     */
    public JsonNode row(String id) {
        JsonNode row = rowsById.get(id);
        if (row == null) {
            throw new ConfigException("配置表[" + name + "]中不存在 id=" + id
                    + "，现有主键=" + rowsById.keySet());
        }
        return row;
    }

    public boolean has(String id) {
        return rowsById.containsKey(id);
    }

    public Set<String> ids() {
        return rowsById.keySet();
    }

    public int size() {
        return rows.size();
    }

    /** 预留表：结构已定稿但数据由后续批次填充，允许 rows 为空。 */
    public boolean isReserved() {
        return "RESERVED".equals(status);
    }
}
