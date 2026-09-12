package com.ironoath.core.bag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：玩家背包聚合根 —— 道具持有量、堆叠上限、容量上限（B04 §3）。
 * 依赖：无（纯 Java，game-core 只依赖 game-common）。
 *
 * <p><b>堆叠上限由调用方传入</b>而不是存在背包里：上限来自 item 表的 stackMax 字段，
 * 属于配置数据。背包若自己存一份，配置热更后就会出现两个真相。
 * 这与 game-core 一贯的约定一致 —— 核心逻辑接受「已解析好的参数」，不自己查配置。
 *
 * <p>两条语义与发奖相反，必须分清：
 * <ul>
 *   <li>{@link #add} 是<b>尽力入包</b>：受堆叠上限与容量约束，返回实际入包量，
 *       差额由发放器转邮件（B04 验收 2）</li>
 *   <li>{@link #remove} 是<b>原子扣减</b>：不足则完全不扣并返回 0，绝不扣成负数
 *       也绝不扣一半（B04 验收 10、禁止项「不要让资源出现负数」）</li>
 * </ul>
 * 理由是两个方向的失败后果不对称：少给了可以补发邮件，多扣了玩家会直接投诉且难以追回。
 */
public final class Inventory {

    /** itemId → 持有量。用 LinkedHashMap 保持插入顺序，让背包列表的展示顺序稳定可复现。 */
    private final Map<String, Long> counts = new LinkedHashMap<>();

    /** 背包格子容量上限。0 表示无上限（不推荐：那是放弃了付费扩容点，见 B04 开放问题 1）。 */
    private int capacityMax;

    public Inventory(int capacityMax) {
        if (capacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + capacityMax);
        }
        this.capacityMax = capacityMax;
    }

    /** 空背包。 */
    public static Inventory empty(int capacityMax) {
        return new Inventory(capacityMax);
    }

    public int capacityMax() {
        return capacityMax;
    }

    public void setCapacityMax(int capacityMax) {
        if (capacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + capacityMax);
        }
        if (capacityMax < capacityUsed()) {
            // 缩容到已用量以下会让「容量」这个约束当场失效，玩家看到的背包会超格
            throw new IllegalArgumentException("capacityMax 不得小于已用格子数："
                    + capacityMax + " < " + capacityUsed());
        }
        this.capacityMax = capacityMax;
    }

    /** 已占用的格子数（持有量 > 0 的道具种类数）。 */
    public int capacityUsed() {
        int used = 0;
        for (long count : counts.values()) {
            if (count > 0L) {
                used++;
            }
        }
        return used;
    }

    public long countOf(String itemId) {
        requireItemId(itemId);
        return counts.getOrDefault(itemId, 0L);
    }

    public boolean isEmpty() {
        return counts.values().stream().noneMatch(c -> c > 0L);
    }

    /**
     * 加入道具。
     *
     * @param itemId   道具 id，对应 item 表的行 id
     * @param count    期望加入量，必须为正
     * @param stackMax 该道具的堆叠上限，来自配置。&lt;= 0 表示不限制
     * @return 实际入包量，可能小于 count（受堆叠上限约束）
     */
    public long add(String itemId, long count, long stackMax) {
        requireItemId(itemId);
        if (count <= 0L) {
            throw new IllegalArgumentException("入包数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        long current = counts.getOrDefault(itemId, 0L);
        long room = stackMax <= 0L ? Long.MAX_VALUE - current : Math.max(0L, stackMax - current);
        long actual = Math.min(count, room);
        if (actual > 0L) {
            counts.put(itemId, current + actual);
        }
        return actual;
    }

    /**
     * 移除道具（使用道具、合成、出售都走这里）。
     *
     * @return 实际移除量；持有量不足时返回 0 且<b>不做部分移除</b>
     */
    public long remove(String itemId, long count) {
        requireItemId(itemId);
        if (count <= 0L) {
            throw new IllegalArgumentException("移除数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        long current = counts.getOrDefault(itemId, 0L);
        if (current < count) {
            return 0L;
        }
        long rest = current - count;
        if (rest == 0L) {
            // 归零的条目直接移除而不是留一个 0：capacityUsed 靠「条目数」计算，
            // 留着一堆 0 会让背包看起来是满的
            counts.remove(itemId);
        } else {
            counts.put(itemId, rest);
        }
        return count;
    }

    /** 只读快照，顺序与插入顺序一致。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        counts.forEach((k, v) -> {
            if (v > 0L) {
                copy.put(k, v);
            }
        });
        return Map.copyOf(copy);
    }

    /** 深拷贝，用于并发修改前的快照与回滚。 */
    public Inventory copy() {
        Inventory copy = new Inventory(capacityMax);
        copy.restore(snapshot(), capacityMax);
        return copy;
    }

    /** 供仓储反序列化写回。业务代码不要用。 */
    public void restore(Map<String, Long> restored, int restoredCapacityMax) {
        if (restoredCapacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + restoredCapacityMax);
        }
        counts.clear();
        if (restored != null) {
            restored.forEach((itemId, count) -> {
                if (count == null || count < 0L) {
                    throw new IllegalArgumentException("道具 " + itemId + " 的数量非法：" + count);
                }
                if (count > 0L) {
                    counts.put(itemId, count);
                }
            });
        }
        this.capacityMax = restoredCapacityMax;
    }

    private static void requireItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不得为空");
        }
    }

    @Override
    public String toString() {
        return "Inventory(格子=" + capacityUsed() + "/" + capacityMax + ", 道具=" + counts + ")";
    }
}
