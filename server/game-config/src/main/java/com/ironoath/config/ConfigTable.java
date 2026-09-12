package com.ironoath.config;

import java.util.List;
import java.util.Map;

/**
 * 职责：一张<b>强类型</b>配置表的运行期视图（B02 输入输出契约）。
 * 依赖：无（纯数据容器）。
 *
 * <p>不可变：{@code rows} 与 {@code byId} 在构造时就冻结成不可变集合。
 * 这是热更安全的前提 —— {@link ConfigRegistry#reload} 用「构建新表 → 原子替换引用」实现，
 * 进行中的请求继续持有旧表引用，读到的是一致快照，不会读到改了一半的数据（B02 验收 9）。
 *
 * @param name    表名
 * @param version 表版本号
 * @param rows    全部行，顺序与配置表一致
 * @param byId    主键索引
 * @param <T>     生成的配置类型（{@code com.ironoath.config.cfg.*Cfg}）
 */
public record ConfigTable<T>(
        String name,
        int version,
        List<T> rows,
        Map<String, T> byId) {

    public ConfigTable {
        rows = List.copyOf(rows);
        byId = Map.copyOf(byId);
    }

    /**
     * 按 id 取配置。
     *
     * <p><b>找不到直接抛 {@link ConfigException}，绝不返回 null</b>（B02 强制约束）：
     * 静默的 null 会在运行时变成 NullPointerException 或者 0，扩散到整个数值系统，极难排查。
     * 一个「攻击力变成 0」的兵种比一个启动就报错的配置表危险得多。
     */
    public T get(String id) {
        T row = byId.get(id);
        if (row == null) {
            throw new ConfigException("配置表[" + name + "]中不存在 id=" + id
                    + "，现有主键=" + byId.keySet());
        }
        return row;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    public int size() {
        return rows.size();
    }
}
