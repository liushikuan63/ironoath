package com.ironoath.core.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：四个发放端口的<b>内存实现</b> —— 供 dev/test 使用，也是单测里验证发放器行为的替身。
 * 依赖：仅 {@link RewardPorts}（纯 Java，零框架、零数据库）。
 *
 * <p>放在 game-core 而不是 game-web 的 test 目录，是因为它同时是
 * 「发放器可脱离容器单测」的证明：如果这些端口只能由 Spring Bean 实现，
 * 那 RewardGrantor 就无法在纯 JUnit 环境里跑，B00 铁律 2 就被破坏了。
 *
 * <p>行为与生产实现保持一致的关键点：
 * <ul>
 *   <li>{@link InMemoryWallet#grant} 受容量上限约束，超出部分不入账（返回实际入账量）</li>
 *   <li>{@link InMemoryWallet#deduct} 不足时返回 0 且<b>不做部分扣减</b>（扣减必须原子）</li>
 *   <li>{@link InMemoryBag#remove} 持有量不足时返回 0，绝不扣成负数（B04 验收 10）</li>
 *   <li>邮箱与补偿队列都保留完整记录，供验收 2 与验收 7 断言</li>
 * </ul>
 */
public final class InMemoryRewardPorts {

    private InMemoryRewardPorts() {
    }

    /** 资源钱袋的内存实现。 */
    public static final class InMemoryWallet implements RewardPorts.Wallet {

        /** playerId → (资源 id → [current, cap, protectedAmount]) */
        private final Map<String, Map<String, long[]>> balances = new ConcurrentHashMap<>();

        /** 配置一个玩家的资源状态，供测试准备数据。 */
        public void seed(String playerId, String resourceType, long current, long cap, long protectedAmount) {
            balances.computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                    .put(resourceType, new long[]{current, cap, protectedAmount});
        }

        private long[] slot(String playerId, String resourceType) {
            Map<String, long[]> byType = balances.get(playerId);
            long[] slot = byType == null ? null : byType.get(resourceType);
            if (slot == null) {
                throw new IllegalArgumentException("玩家 " + playerId + " 没有资源 " + resourceType
                        + "，请先用 seed() 准备（生产实现里这对应存档初始化）");
            }
            return slot;
        }

        @Override
        public long grant(String playerId, String resourceType, long amount, long now) {
            if (amount < 0L) {
                throw new IllegalArgumentException("发放量不得为负：" + amount);
            }
            long[] slot = slot(playerId, resourceType);
            long room = slot[1] - slot[0];
            long actual = Math.min(amount, Math.max(0L, room));
            slot[0] += actual;
            return actual;
        }

        @Override
        public long available(String playerId, String resourceType, long now) {
            return slot(playerId, resourceType)[0];
        }

        @Override
        public long capacity(String playerId, String resourceType, long now) {
            return slot(playerId, resourceType)[1];
        }

        @Override
        public long protectedAmount(String playerId, String resourceType, long now) {
            return slot(playerId, resourceType)[2];
        }

        @Override
        public long deduct(String playerId, String resourceType, long amount, long now) {
            if (amount < 0L) {
                throw new IllegalArgumentException("扣减量不得为负：" + amount);
            }
            long[] slot = slot(playerId, resourceType);
            if (slot[0] < amount) {
                // 不足则完全不扣：扣一半会让玩家处于「资源没了但东西也没拿到」的状态
                return 0L;
            }
            slot[0] -= amount;
            return amount;
        }
    }

    /** 背包的内存实现。 */
    public static final class InMemoryBag implements RewardPorts.Bag {

        /** playerId → (道具 id → 数量) */
        private final Map<String, Map<String, Long>> items = new ConcurrentHashMap<>();
        private final Map<String, Integer> capacityMax = new ConcurrentHashMap<>();
        private final Map<String, Long> stackMax = new ConcurrentHashMap<>();

        public void seedCapacity(String playerId, int max) {
            capacityMax.put(playerId, max);
        }

        /** 配置某道具的堆叠上限，对应 item 表的 stackMax。 */
        public void seedStackMax(String itemId, long max) {
            stackMax.put(itemId, max);
        }

        public void seedItem(String playerId, String itemId, long count) {
            items.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(itemId, count);
        }

        @Override
        public long add(String playerId, String itemId, long count) {
            if (count < 0L) {
                throw new IllegalArgumentException("入包数量不得为负：" + count);
            }
            Map<String, Long> mine = items.computeIfAbsent(playerId, k -> new LinkedHashMap<>());
            long current = mine.getOrDefault(itemId, 0L);
            long limit = stackMax.getOrDefault(itemId, Long.MAX_VALUE);
            long room = Math.max(0L, limit - current);
            long actual = Math.min(count, room);
            if (actual > 0L) {
                mine.put(itemId, current + actual);
            }
            return actual;
        }

        @Override
        public long countOf(String playerId, String itemId) {
            Map<String, Long> mine = items.get(playerId);
            return mine == null ? 0L : mine.getOrDefault(itemId, 0L);
        }

        @Override
        public long remove(String playerId, String itemId, long count) {
            if (count < 0L) {
                throw new IllegalArgumentException("移除数量不得为负：" + count);
            }
            Map<String, Long> mine = items.get(playerId);
            long current = mine == null ? 0L : mine.getOrDefault(itemId, 0L);
            if (current < count) {
                return 0L;   // 不足则完全不移除，绝不扣成负数（B04 验收 10）
            }
            mine.put(itemId, current - count);
            return count;
        }

        @Override
        public int capacityUsed(String playerId) {
            Map<String, Long> mine = items.get(playerId);
            if (mine == null) {
                return 0;
            }
            return (int) mine.values().stream().filter(v -> v > 0L).count();
        }

        @Override
        public int capacityMax(String playerId) {
            return capacityMax.getOrDefault(playerId, Integer.MAX_VALUE);
        }
    }

    /** 邮箱的内存实现，保留全部溢出记录供断言。 */
    public static final class InMemoryMailbox implements RewardPorts.Mailbox {

        /** 一封溢出补发邮件。 */
        public record OverflowMail(String mailId, String playerId, List<RewardItem> overflow,
                                   String source, String sourceRef) {
        }

        private final List<OverflowMail> sent = new ArrayList<>();
        private int seq;

        @Override
        public synchronized String sendOverflow(String playerId, List<RewardItem> overflow, RewardContext ctx) {
            String mailId = "mail_overflow_" + (++seq);
            sent.add(new OverflowMail(mailId, playerId, List.copyOf(overflow), ctx.source(), ctx.sourceRef()));
            return mailId;
        }

        public List<OverflowMail> sent() {
            return List.copyOf(sent);
        }

        public OverflowMail last() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }
    }

    /** 补偿队列的内存实现，保留全部失败记录供断言（B04 验收 7）。 */
    public static final class InMemoryCompensation implements RewardPorts.Compensation {

        /** 一条补偿记录。 */
        public record Entry(String compensationId, String playerId, List<RewardItem> failed,
                            String source, String traceId, String cause) {
        }

        private final List<Entry> entries = new ArrayList<>();
        private int seq;

        @Override
        public synchronized String record(String playerId, List<RewardItem> failed,
                                          RewardContext ctx, Throwable cause) {
            String id = "comp_" + (++seq);
            entries.add(new Entry(id, playerId, List.copyOf(failed), ctx.source(), ctx.traceId(),
                    cause == null ? "业务校验未通过" : cause.getMessage()));
            return id;
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }

        public Entry last() {
            return entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }
    }

    /** 非资源非道具奖励的内存实现（武将碎片、体力、特权）。 */
    public static final class InMemoryExtras implements RewardPorts.Extras {

        /** (playerId, type, id) → 累计数量 */
        private final Map<String, Long> totals = new ConcurrentHashMap<>();
        private final List<String> rejected = new ArrayList<>();

        /** 标记某个 (type,id) 不可发放，用于测试失败路径。 */
        public void reject(RewardType type, String id) {
            rejected.add(type.name() + "|" + id);
        }

        @Override
        public long grant(String playerId, RewardType type, String id, long count) {
            if (rejected.contains(type.name() + "|" + id)) {
                throw new IllegalStateException("模拟发放失败：" + type + "/" + id);
            }
            totals.merge(key(playerId, type, id), count, Long::sum);
            return count;
        }

        public long totalOf(String playerId, RewardType type, String id) {
            return totals.getOrDefault(key(playerId, type, id), 0L);
        }

        private static String key(String playerId, RewardType type, String id) {
            return playerId + "|" + type.name() + "|" + id;
        }
    }
}
