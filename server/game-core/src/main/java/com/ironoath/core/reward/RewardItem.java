package com.ironoath.core.reward;

/**
 * 职责：一条奖励（类型 + id + 数量）。
 * 依赖：无（纯数据）。
 *
 * <p>数量是 {@code long} 而不是 {@code int}：资源奖励动辄百万级（后期一次活动奖励几百万粮草），
 * int 上限 21 亿看着够，但累加多次奖励或做「数量 × 倍率」时很容易越过，
 * 而溢出后变成负数会让玩家资源凭空减少 —— 这类 bug 极难复现。
 * 全程 long，禁止 double（B04 禁止项）。
 *
 * @param type  奖励类型，决定分派到哪个子系统
 * @param id    目标 id，语义随 type 变化（资源 id / 道具 id / 稀有度 / 特权标识）
 * @param count 数量，必须为正
 */
public record RewardItem(RewardType type, String id, long count) {

    public RewardItem {
        if (type == null) {
            throw new IllegalArgumentException("RewardType 不得为 null");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("奖励 id 不得为空，type=" + type);
        }
        if (count <= 0L) {
            // 0 或负数一律拒绝：负数奖励等于扣减，而扣减必须走 ResourceService 的显式接口，
            // 混进发奖路径会让「发奖失败回滚」与「主动扣减」两种语义纠缠不清
            throw new IllegalArgumentException("奖励数量必须为正，type=" + type + ", id=" + id
                    + ", count=" + count);
        }
    }

    /** 按新数量复制一份（用于拆分溢出部分）。 */
    public RewardItem withCount(long newCount) {
        return new RewardItem(type, id, newCount);
    }
}
