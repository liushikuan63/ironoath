package com.ironoath.core.reward;

import java.util.List;

/**
 * 职责：发放器依赖的四个下游端口 —— 钱袋、背包、邮箱、补偿队列。
 * 依赖：无（纯接口）。
 *
 * <p>为什么把端口定义在 game-core 而不是让发放器直接依赖具体实现：
 * B04 禁止项要求「RewardService 不得直接操作数据库，必须走 ResourceService」。
 * 把这条约束变成<b>编译期事实</b>的办法就是：发放器只能看见这几个接口，
 * 它连数据库的存在都不知道，想绕过也绕不过去。
 *
 * <p>四个端口放在同一个文件里是刻意的 —— 它们是发放器的一个完整协作面，
 * 分散到四个文件反而看不出「少实现一个会怎样」。
 */
public final class RewardPorts {

    private RewardPorts() {
    }

    /**
     * 资源钱袋。实现必须做上限校验与惰性结算，并在变化后发出 RESOURCE_CHANGED 事件。
     *
     * <p>发放器不自己算上限，而是让 {@link #grant} 返回「实际入账量」：
     * 上限、保护量、满仓停产这些规则都属于资源模块，
     * 在发放器里再实现一遍就会出现两套规则，迟早分叉。
     */
    public interface Wallet {

        /**
         * 发放资源。
         *
         * @return 实际入账量（可能小于 amount —— 超出容量的部分被截断）。
         *         绝不允许把资源加到超过上限，也绝不允许出现负数
         */
        long grant(String playerId, String resourceType, long amount, long now);

        /** 当前可用量（已完成惰性结算）。 */
        long available(String playerId, String resourceType, long now);

        /** 容量上限。 */
        long capacity(String playerId, String resourceType, long now);

        /** 受保护量：被掠夺时不可抢的部分（B04 验收 6）。 */
        long protectedAmount(String playerId, String resourceType, long now);

        /**
         * 扣减资源（道具使用、建造消耗都走这里）。
         *
         * @return 实际扣减量；不足时返回 0 并且<b>不做部分扣减</b> ——
         *         扣减必须原子，扣一半会让玩家处于「资源没了但东西也没拿到」的状态
         */
        long deduct(String playerId, String resourceType, long amount, long now);
    }

    /** 背包。实现必须做堆叠上限与容量上限校验。 */
    public interface Bag {

        /**
         * 加入道具。
         *
         * @return 实际入包数量（受堆叠上限与背包容量约束，可能小于 count）
         */
        long add(String playerId, String itemId, long count);

        /** 持有数量。 */
        long countOf(String playerId, String itemId);

        /**
         * 移除道具（使用道具时扣库存）。
         *
         * @return 实际移除数量；持有量不足时返回 0 且不做部分移除（B04 验收 10：不得扣成负数）
         */
        long remove(String playerId, String itemId, long count);

        int capacityUsed(String playerId);

        int capacityMax(String playerId);
    }

    /** 邮箱。溢出奖励转邮件补发（B04 验收 2：邮件正文写明溢出数量与原因）。 */
    public interface Mailbox {

        /**
         * 发送溢出补发邮件。
         *
         * @return 邮件 id
         */
        String sendOverflow(String playerId, List<RewardItem> overflow, RewardContext ctx);
    }

    /** 补偿队列。发放出错时记录，绝不静默（B04 验收 7）。 */
    public interface Compensation {

        /**
         * 记录一次发放失败。
         *
         * @param cause 失败原因，可为 null（例如业务校验不通过而非异常）
         * @return 补偿记录 id，供客服与运维追查
         */
        String record(String playerId, List<RewardItem> failed, RewardContext ctx, Throwable cause);
    }

    /** 特权与碎片等非资源非道具的奖励落地口。 */
    public interface Extras {

        /**
         * 发放一条非资源非道具的奖励。
         *
         * @param now 服务端当前时刻。<b>自 B19 起带上它</b>：特权这一类要按「现在」算到期时刻
         *              （月卡 +30 天），而实现里自己去读墙上时钟等于绕开铁律 5，
         *              也让用例无法把时间钉在某个日界上
         * @return 实际发放数量；无法发放时返回 0。
         *         <b>注意特权（PRIVILEGE）应当返回全部 count</b>：时间没有「装不下」这回事，
         *         返回小于 count 会被发放器当成溢出并转成邮件（那是把 30 天当道具再发一遍）
         */
        long grant(String playerId, RewardType type, String id, long count, long now);
    }
}
