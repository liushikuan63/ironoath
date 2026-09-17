package com.ironoath.web.hero;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 职责：装备实例的「穿 / 卸」状态变更（B20 §五⑤：穿上不再是把东西从背包里删掉）。
 * 依赖：背包仓储（实例账本的唯一之家）。
 *
 * <p><b>为什么单开一个类而不是让 {@code HeroAppService} 自己拿仓储改</b>：
 * 改实例状态必须同时做对三件事 —— 读最新版本、改完带版本条件写回、放不下时要拒绝。
 * 武将这条链与强化那条链都要动它，两处各写一遍版本礼仪迟早会漏一处
 * （漏的那处的症状是"另一个窗口的改动被覆盖"，而那只在并发时出现，测不出来）。
 *
 * <p><b>{@code worn} 只是格子账的索引</b>：「穿在哪个武将身上」的权威是
 * {@code HeroInstance.equips} 里那个 uid。两处短暂不一致（一次落库中途失败）的后果是
 * 格子数差一格，<b>不是</b>属性算错 —— 属性走 {@link EquipLedger#resolve}，
 * 它看的是槽位里的 uid 能不能解析出来，与 worn 无关。
 */
@Component
public class EquipWearer {

    private static final Logger LOG = LoggerFactory.getLogger(EquipWearer.class);

    private final InventoryRepository inventories;

    public EquipWearer(InventoryRepository inventories) {
        this.inventories = inventories;
    }

    /** 按 uid 取一件，取不到就是"玩家没有这一件"。 */
    public Inventory.EquipInstance require(String playerId, String uid) {
        Inventory bag = requireBag(playerId);
        Inventory.EquipInstance instance = bag.equipInstance(uid);
        if (instance == null) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND,
                    "背包里没有 uid=" + uid + " 这件装备。装备列表请按 uid 取，不要自己编号");
        }
        return instance;
    }

    /**
     * 穿上 {@code toWear}，同时把 {@code toUnwear}（可以是 null）摘下来。一次落库。
     *
     * @throws BizException 这件已经穿着了，或背包放不下摘下来的那件（容量满）
     */
    public void wear(String playerId, String toWear, String toUnwear) {
        Inventory bag = requireBag(playerId);
        long version = inventories.versionOf(playerId);
        if (bag.equipInstance(toWear) == null) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "背包里没有 uid=" + toWear + " 这件装备");
        }
        if (bag.equipInstance(toWear).worn()) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "uid=" + toWear + " 已经穿在某个武将身上，请先把它卸下");
        }
        // 摘下来的那件要占回一格：穿着不占格，所以"有没有格子"只能在这一刻判
        if (toUnwear != null && bag.capacityUsed() + 1 > bag.capacityMax()) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "背包已满，放不下卸下的 " + bag.equipInstance(toUnwear).equipId()
                            + "（" + toUnwear + "）。请先清理背包再换装");
        }
        bag.markWorn(toWear, true);
        if (toUnwear != null) {
            bag.markWorn(toUnwear, false);
        }
        inventories.save(playerId, bag, version);
    }

    /** 单纯卸下（没有换上来的一件）。 */
    public void unwear(String playerId, String uid) {
        Inventory bag = requireBag(playerId);
        long version = inventories.versionOf(playerId);
        if (bag.equipInstance(uid) == null) {
            // 卸不下不该失败得莫名其妙：这一件本来就不在账本里，说明槽位里那个 uid 是悬空的
            LOG.error("【卸下的装备不在账本里】playerId={} uid={} 槽位改动照常落库，"
                    + "但这条 uid 是从哪来的需要查", playerId, uid);
            return;
        }
        if (bag.capacityUsed() + 1 > bag.capacityMax()) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "背包已满，放不下卸下的 " + bag.equipInstance(uid).equipId()
                            + "（" + uid + "）。请先清理背包再卸装备");
        }
        bag.markWorn(uid, false);
        inventories.save(playerId, bag, version);
    }

    private Inventory requireBag(String playerId) {
        return inventories.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND,
                        "玩家 " + playerId + " 还没有背包记录"));
    }
}
