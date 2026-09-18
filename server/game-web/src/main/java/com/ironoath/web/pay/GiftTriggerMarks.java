package com.ironoath.web.pay;

import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.core.player.PlayerSave;

/**
 * 职责：事件源唯一允许做的事 —— 往**调用方手里那份存档**写一个"什么时候触发过"（B19 S3-ii）。
 * 依赖：无（纯静态，连 Spring 都不需要）。
 *
 * <p><b>为什么是纯静态而不是一个"自带玩家锁的服务"</b>：第一版把它做成了
 * "自己取锁、自己读一份存档、自己存回去"，结果在别人的读-改-写窗口里推进了版本号 ——
 * 调用方 `CityAppService.load` 的持有者手里还是旧副本，随后照常 `players.save(player)`
 * 就撞乐观锁（实测 `存储版本=3，提交版本=2`，20 条用例连带红）。
 * 与 `PlayerGuide` / `PlayerPaid` / `PlayerTech` 那三个槽位同一条纪律：
 * **改状态只改调用方那份 save，由它的持有者持久化**。
 *
 * <p><b>为什么这里不判 Bot</b>：`check-no-bot-privilege` 要求 Bot 判定只许走注册表自己的出口
 * （`BotRegistry.humanOnly(...)` 那一族），在注册表之外出现 `isBot` 会让合规卡口红。
 * 而这里本来也不需要判：付费弹窗的判定那一侧已经拒 Bot（`PopupThrottle.shouldShow` 的
 * `viewerIsBot` 分支），给一个 Bot 的存档留一条触发时刻是无害的噪声，不值得为它破一次门规。
 *
 * <p><b>为什么不直接弹窗</b>：一次惰性结算可能同时收完三栋楼，事件侧直接弹就会连弹三包 ——
 * 而「全局冷却 10 分钟」这条恰好是为挡它设的。弹不弹、弹哪个由 `GiftPopupService` 读时算。
 */
public final class GiftTriggerMarks {

    private GiftTriggerMarks() {
    }

    /**
     * 记一次触发（三类之一）。
     *
     * <p>幂等天然成立：同一类触发重复发生只留最近那一次时刻 —— 报价 TTL 从它算，
     * 留着旧时刻会让玩家隔几天还能翻出一份过期报价（见 {@code PlayerGiftPopup#withTriggered}）。
     *
     * <p><b>调用方必须真的把这份 save 存下去</b>，否则这一次触发就丢了。三个落点各自的持久化点
     * 见 `B19_付费发货与礼包弹窗.md` §6.1 补⑧。
     */
    public static void markOn(PlayerSave save, GiftCfg.Trigger trigger, long now) {
        if (save == null) {
            throw new IllegalArgumentException("save 不得为 null（打标改的是调用方手里那份存档）");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("trigger 不得为 null");
        }
        if (now <= 0L) {
            throw new IllegalArgumentException("now 必须是服务端时刻，实际=" + now);
        }
        save.setGiftPopup(save.giftPopup().withTriggered(trigger.name(), now));
    }
}
