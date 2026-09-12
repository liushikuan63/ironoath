package com.ironoath.web.reward;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.reward.RewardPorts;

/**
 * 职责：把 {@link RewardPorts.Bag} 端口适配到真实背包（Inventory + InventoryRepository）。
 * 依赖：game-core 的背包聚合与仓储、game-config（读堆叠上限）。
 *
 * <p>堆叠上限每次从配置读取而不是缓存在背包里：item 表热更后，
 * 缓存的上限会与配置分叉，玩家就会看到「明明写着能堆 999 个却只能堆 100 个」。
 *
 * <p>容量上限与堆叠上限是两件事，都要校验：
 * <ul>
 *   <li>堆叠上限管「一种道具最多几个」——防止单一道具无限囤积</li>
 *   <li>容量上限管「几种道具」——这是付费扩容点（B04 开放问题 1 的答案：做上限）</li>
 * </ul>
 */
public final class PlayerBag implements RewardPorts.Bag {

    private final InventoryRepository inventories;
    private final ConfigRegistry configs;

    public PlayerBag(InventoryRepository inventories, ConfigRegistry configs) {
        if (inventories == null || configs == null) {
            throw new IllegalArgumentException("InventoryRepository 与 ConfigRegistry 都不得为 null");
        }
        this.inventories = inventories;
        this.configs = configs;
    }

    @Override
    public long add(String playerId, String itemId, long count) {
        if (count <= 0L) {
            throw new IllegalArgumentException("入包数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        Inventory bag = loadOrCreate(playerId);
        long version = inventories.versionOf(playerId);

        // 新道具要先检查格子：容量已满时不能再开新格子，否则「背包满了」这个约束形同虚设
        boolean isNewSlot = bag.countOf(itemId) == 0L;
        if (isNewSlot && bag.capacityUsed() >= bag.capacityMax()) {
            return 0L;   // 格子已满，全部溢出 → 发放器转邮件
        }

        long actual = bag.add(itemId, count, stackMaxOf(itemId));
        if (actual > 0L) {
            inventories.save(playerId, bag, version);
        }
        return actual;
    }

    @Override
    public long countOf(String playerId, String itemId) {
        return inventories.findByPlayerId(playerId)
                .map(bag -> bag.countOf(itemId))
                .orElse(0L);
    }

    @Override
    public long remove(String playerId, String itemId, long count) {
        if (count <= 0L) {
            throw new IllegalArgumentException("移除数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        Inventory bag = inventories.findByPlayerId(playerId).orElse(null);
        if (bag == null) {
            return 0L;
        }
        long version = inventories.versionOf(playerId);
        // Inventory.remove 是原子的：不足则完全不扣，绝不扣成负数（B04 验收 10）
        long removed = bag.remove(itemId, count);
        if (removed > 0L) {
            inventories.save(playerId, bag, version);
        }
        return removed;
    }

    @Override
    public int capacityUsed(String playerId) {
        return inventories.findByPlayerId(playerId).map(Inventory::capacityUsed).orElse(0);
    }

    @Override
    public int capacityMax(String playerId) {
        return inventories.findByPlayerId(playerId)
                .map(Inventory::capacityMax)
                .orElse((int) configs.longParam("BAG_INITIAL_CAPACITY"));
    }

    /** 从 item 表读堆叠上限。道具不在表里就直接报错 —— 静默当成「无上限」会让配置漏填变成刷道具漏洞。 */
    private long stackMaxOf(String itemId) {
        ItemCfg item = configs.get(ItemCfg.class, itemId);
        return item.stackMax();
    }

    private Inventory loadOrCreate(String playerId) {
        return inventories.findByPlayerId(playerId).orElseGet(() -> {
            Inventory fresh = Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY"));
            inventories.insertIfAbsent(playerId, fresh);
            return inventories.findByPlayerId(playerId)
                    .orElseThrow(() -> new IllegalStateException("背包创建后立即读不到: " + playerId));
        });
    }
}
