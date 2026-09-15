package com.ironoath.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 职责：配置表两次快照之间的**逐参数差异**，用于「概率不得暗改」那条留痕要求
 *       （{@code 上线检查清单.md} §二 12：概率变更需留运营日志 —— 谁、何时、从多少改到多少）。
 * 依赖：{@link RawConfigTable}（已是 id → 行 的索引，不需要重新解析 JSON）。
 *
 * <p><b>为什么要到字段级而不是停在"哪张表变了"</b>：热更清单已经能回答"哪张表换了 hash"，
 * 而监管问的是<b>"SSR 概率从 1.2% 改成了多少"</b>。表级答案对这个问题没有用，
 * 而"看起来有日志"比没日志更糟 —— 它会让人觉得这一条已经闭环了。
 *
 * <p><b>这是一个纯函数，不写日志也不落库</b>：留痕的读者今天只有日志采集链路（在仓库之外），
 * 所以本类只负责把差异算准、说清楚；"要不要再开一个可查询的审计存储、保留多久、谁能查"
 * 是另一个需要裁决的问题，不在这里替它决定。
 *
 * <p><b>截断必须说出来</b>：一行日志放不下 34 张表的全部改动，所以每条最多列 {@code cap} 处，
 * 并在尾部写明还有几处没列 —— 而<b>总数始终在行首</b>。被截断的是明细，不是"这次改了多少"这个事实。
 */
public final class ConfigChangeAudit {

    /** 一个字段的前后值（都是 JSON 文本，数字/字符串/嵌套对象统一按文本比，避免类型分支）。 */
    public record FieldChange(String rowId, String field, String before, String after) {
    }

    /**
     * 一张表的变更。
     *
     * @param addedRows   只在 after 里出现的行 id（新增一行不是"改了某个字段"，得分开报）
     * @param removedRows 只在 before 里出现的行 id
     */
    public record TableChanges(String table, int beforeVersion, int afterVersion,
                               String beforeHash, String afterHash,
                               List<String> addedRows, List<String> removedRows,
                               List<FieldChange> changed) {

        /** 一共改了多少处（含增删行）。用来判断明细是不是被截断了。 */
        public int totalChanges() {
            return addedRows.size() + removedRows.size() + changed.size();
        }

        /**
         * 渲染成一行日志。
         *
         * @param cap 明细最多列几处；超出部分在行尾写明"另有 N 处未列出"
         */
        public String format(int cap) {
            StringBuilder out = new StringBuilder();
            out.append("表=").append(table)
                    .append(" 版本=").append(beforeVersion).append("→").append(afterVersion)
                    .append(" 变更处数=").append(totalChanges())
                    .append("（改字段=").append(changed.size())
                    .append(" 新增行=").append(addedRows.size())
                    .append(" 删除行=").append(removedRows.size()).append(')')
                    .append(" hash=").append(beforeHash).append("→").append(afterHash);
            int shown = 0;
            for (FieldChange c : changed) {
                if (shown >= cap) {
                    break;
                }
                out.append(" | ").append(c.rowId()).append('.').append(c.field())
                        .append(':').append(c.before()).append("→").append(c.after());
                shown++;
            }
            for (String id : addedRows) {
                if (shown >= cap) {
                    break;
                }
                out.append(" | 新增 ").append(id);
                shown++;
            }
            for (String id : removedRows) {
                if (shown >= cap) {
                    break;
                }
                out.append(" | 删除 ").append(id);
                shown++;
            }
            if (totalChanges() > shown) {
                out.append(" | 另有 ").append(totalChanges() - shown).append(" 处未列出（总数见行首）");
            }
            return out.toString();
        }
    }

    private ConfigChangeAudit() {
    }

    /**
     * 算出两份快照之间的逐表差异。只返回真有变化的表，且顺序稳定（按表名字典序）——
     * 审计行会被人和脚本一起读，顺序不稳会让"两次同样的热更打出两行不同的日志"。
     *
     * @param hashBefore 表名 → 内容 hash（来自配置清单，本类不算 hash，避免造第二个 hash 实现）
     * @param hashAfter  同上，热更之后那一份
     */
    public static List<TableChanges> between(Map<String, RawConfigTable> before,
                                             Map<String, RawConfigTable> after,
                                             Map<String, String> hashBefore,
                                             Map<String, String> hashAfter) {
        Set<String> tables = new TreeSet<>();
        tables.addAll(before.keySet());
        tables.addAll(after.keySet());
        List<TableChanges> out = new ArrayList<>();
        for (String name : tables) {
            RawConfigTable oldTable = before.get(name);
            RawConfigTable newTable = after.get(name);
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<FieldChange> changed = new ArrayList<>();
            diffRows(oldTable, newTable, added, removed, changed);
            String oldHash = hashBefore.get(name);
            String newHash = hashAfter.get(name);
            boolean hashMoved = oldHash != null && newHash != null && !oldHash.equals(newHash);
            if (added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !hashMoved) {
                // 三样都空且 hash 也没动 = 这张表真的没变。注意 hashMoved 单独判一下：
                // 只动注释/版本这类变化不会体现在行上，但它在监管眼里同样是"这张表被换了"
                continue;
            }
            out.add(new TableChanges(name,
                    oldTable == null ? -1 : oldTable.version(),
                    newTable == null ? -1 : newTable.version(),
                    oldHash, newHash, List.copyOf(added), List.copyOf(removed), List.copyOf(changed)));
        }
        return List.copyOf(out);
    }

    private static void diffRows(RawConfigTable before, RawConfigTable after,
                                 List<String> added, List<String> removed, List<FieldChange> changed) {
        Map<String, JsonNode> oldRows = before == null ? Map.of() : before.rowsById();
        Map<String, JsonNode> newRows = after == null ? Map.of() : after.rowsById();
        Set<String> ids = new LinkedHashSet<>(new TreeSet<>(oldRows.keySet()));
        ids.addAll(new TreeSet<>(newRows.keySet()));
        for (String id : ids) {
            JsonNode oldRow = oldRows.get(id);
            JsonNode newRow = newRows.get(id);
            if (oldRow == null) {
                added.add(id);
                continue;
            }
            if (newRow == null) {
                removed.add(id);
                continue;
            }
            for (String field : fieldNames(oldRow, newRow)) {
                String b = valueOf(oldRow, field);
                String a = valueOf(newRow, field);
                if (!b.equals(a)) {
                    changed.add(new FieldChange(id, field, b, a));
                }
            }
        }
    }

    private static Set<String> fieldNames(JsonNode oldRow, JsonNode newRow) {
        Set<String> names = new java.util.TreeSet<>();
        addAll(names, oldRow);
        addAll(names, newRow);
        return names;
    }

    private static void addAll(Set<String> target, JsonNode row) {
        Iterator<String> it = row.fieldNames();
        while (it.hasNext()) {
            target.add(it.next());
        }
    }

    /** 缺字段与"字段是 null"都读成空串：两者对玩家的效果一样（没配），而区别会让日志多出一列噪音。 */
    private static String valueOf(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.toString();
    }
}
