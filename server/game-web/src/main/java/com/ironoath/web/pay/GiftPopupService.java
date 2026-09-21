package com.ironoath.web.pay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PopupThrottle;
import com.ironoath.core.player.PlayerGiftPopup;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.GiftPopupResp;

/**
 * 职责：读时决定要不要弹礼包、弹哪个（B19 S3-ii）。
 * 依赖：gift 表、玩家存档（频控那一位）、`PopupThrottle`（纯 Java 判定）、`BotRegistry`。
 *
 * <p><b>为什么"弹不弹"是读时算的</b>：一次惰性结算可能同时收完三栋楼，事件侧直接弹就会连弹三包 ——
 * 而「全局冷却 10 分钟」这条恰好是为挡它设的。所以事件源只记"什么时候触发过"，弹不弹在这里算。
 *
 * <p><b>只回"弹哪个包 + 哪一档 + 报价什么时候过期"</b>：内容住在 `product_reward`，
 * 在这里再抄一份就会写"弹窗说 2 张建造令、实际发到 1 张"，而这条分叉只在玩家付费之后才暴露（§四 禁止项）。
 *
 * <p><b>判定与记账分开</b>：只有真的回 {@code popup=true} 的那一次才把弹出时刻写进存档；
 * 被频控压住时一个字都不写 —— 否则"问了一下没弹"会白吃配额。
 *
 * <p><b>Bot 走真实身份</b>：{@code shouldShow} 的 {@code viewerIsBot} 必填参数存在的意义
 * 就是让"忘了判断"在编译期报错，这里传注册表的判定而不是写死 false。
 */
@Service
public class GiftPopupService {

    private static final Logger LOG = LoggerFactory.getLogger(GiftPopupService.class);

    /** 与其它写存档的路径同一把玩家锁。 */
    private static final long LOCK_TIMEOUT_MS = 3_000L;

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final TimeService timeService;
    private final BotRegistry bots;
    private final PopupThrottle throttle;

    public GiftPopupService(ConfigRegistry configs, PlayerRepository players, PlayerLock playerLock,
                            TimeService timeService, BotRegistry bots, PopupThrottle throttle) {
        this.configs = configs;
        this.players = players;
        this.playerLock = playerLock;
        this.timeService = timeService;
        this.bots = bots;
        this.throttle = throttle;
    }

    /**
     * 这一屏弹不弹。
     *
     * <p>走玩家锁：判定要读存档那一位，而"弹了"要写回去 —— 读 → 判 → 写必须在同一把锁里，
     * 否则同一玩家的两个并发请求会各弹一次、都把同一条旧状态当基线。
     */
    public GiftPopupResp popup(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> decide(playerId, now));
    }

    private GiftPopupResp decide(String playerId, long now) {
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在：" + playerId));
        PlayerGiftPopup state = save.giftPopup();
        Long firstChargedAt = save.paid().firstChargedAt();
        PopupThrottle run = PopupThrottle.forPlayer(throttle.rules(), playerId, state,
                firstChargedAt == null ? 0L : firstChargedAt);
        // 合规判定只许委托注册表（check-no-bot-privilege）：humanOnly 对 Bot 返回 null。
        // 自己写 if (isBot) 分支会被那道门拦下 —— 而且这里本来也不需要"分支"，只是一个入参
        boolean viewerIsBot = bots.humanOnly(playerId, "付费弹窗频控") == null;

        long minRetrySec = -1L;
        for (GiftCfg row : configs.all(GiftCfg.class)) {
            long triggerAt = state.triggeredAtOf(row.trigger().name());
            if (triggerAt <= 0L) {
                continue;
            }
            long ttlMillis = row.offerTtlMinutes() * 60_000L;
            if (now - triggerAt >= ttlMillis) {
                // 这次触发的报价已过期：不做常驻挂件，等下一次触发
                continue;
            }
            PopupThrottle.Verdict verdict = run.shouldShow(playerId, row.id(), viewerIsBot, now);
            if (verdict.allowed()) {
                save.setGiftPopup(run.snapshotOf(playerId, now).withShown(row.id(), now));
                players.save(save);
                LOG.info("礼包弹窗触发 playerId={} 礼包={} 商品={} 报价到期={}（触发于 {}）",
                        playerId, row.id(), row.productId(), triggerAt + ttlMillis, triggerAt);
                return new GiftPopupResp(true, row.id(), row.productId(), productName(row.productId()),
                        triggerAt + ttlMillis, 0L, now);
            }
            long retrySec = (verdict.retryAfterMillis() + 999L) / 1_000L;
            minRetrySec = minRetrySec < 0L ? retrySec : Math.min(minRetrySec, retrySec);
        }
        return new GiftPopupResp(false, null, null, null, null, Math.max(0L, minRetrySec), now);
    }

    /**
     * 这一档商品的**显示名**（`pay_product.name`）。
     *
     * <p>为什么要下发：客户端不查表。2026-09-21 复检抓到的形态是弹窗印
     * 「商品 gift_building_celebration」—— 表里那一行明明写着「落成贺礼」，
     * 而玩家看到的是内部编号（#255/#268 同族：显示名只有一个真源，就是服务端这里）。
     *
     * <p>查不到行时回 null，**绝不回 id**：宁可少一行字，也不把内部编号印给玩家。
     */
    private String productName(String productId) {
        if (productId == null) {
            return null;
        }
        for (PayProductCfg row : configs.all(PayProductCfg.class)) {
            if (productId.equals(row.id())) {
                return row.name();
            }
        }
        return null;
    }
}
