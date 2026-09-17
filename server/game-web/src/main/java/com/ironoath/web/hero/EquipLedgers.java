package com.ironoath.web.hero;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import org.springframework.stereotype.Component;

/**
 * 职责：按玩家装配一份 {@link EquipLedger}（"这个玩家此刻有哪几件装备、各强化到几级"）。
 * 依赖：背包仓储（唯一的实例账本）+ 配置表（查装备行与每级增益）。
 *
 * <p><b>为什么单开这么一个装配器</b>：读装备的入口有四处（武将面板、战力刷新、战斗编队映射、
 * 强化本身）。四处各写一遍「查背包 → 建快照」，就会分成四种「背包读不到时怎么办」——
 * 其中一种必然是"当成没有装备"，而那正是 §五⑤ 明令不许出现的读法。集中一处之后，
 * 那条规则只有一处可以写错。
 *
 * <p><b>一次请求一份快照，不缓存</b>：实例账本随穿卸与强化而变，缓存会立刻变成第二个真相；
 * 而一次请求最多读它个位数次，省下的那次 round trip 不值得拿正确性去换。
 */
@Component
public class EquipLedgers {

    private final InventoryRepository inventories;
    private final ConfigRegistry configs;

    public EquipLedgers(InventoryRepository inventories, ConfigRegistry configs) {
        this.inventories = inventories;
        this.configs = configs;
    }

    /**
     * 这个玩家的装备快照。没有背包记录 = 一件也没有（新号在未初始化之前也会走到这里，
     * 那不是错误，所以不抛）。
     */
    public EquipLedger of(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return EquipLedger.empty(configs);
        }
        Inventory bag = inventories.findByPlayerId(playerId).orElse(null);
        return EquipLedger.of(bag, configs);
    }

    /** 已经手上拿着背包时的入口（强化那条链本来就要读背包，不该再读第二次）。 */
    public EquipLedger ofBag(Inventory bag) {
        return EquipLedger.of(bag, configs);
    }
}
