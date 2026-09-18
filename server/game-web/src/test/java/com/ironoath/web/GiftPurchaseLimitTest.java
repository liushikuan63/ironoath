package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.pay.GiftTriggerMarks;
import com.ironoath.web.service.PayAppService;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：礼包的两道下单闸（B19 S3-iii）：报价没过期 + 今天没买满。
 * 依赖：真实服务（内存存储），直接驱动 `PayAppService.createOrder`。
 *
 * <p><b>为什么必须在下单侧断言</b>：拦在发货就晚了 —— 玩家已经付过钱，而"已付不退"是一条绝对纪律。
 * 所以这里的每条失败都断言**错误码**（15011 / 15012），而不是"抛了异常就算过"。
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class GiftPurchaseLimitTest {

    private static final String GIFT_PRODUCT = "gift_stuck_supply";
    private static final long MINUTE = 60_000L;

    @Autowired private PayAppService pay;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private com.ironoath.config.ConfigRegistry configs;

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "礼包限购",
                1_700_000_000_000L, "")).playerId();
    }

    /** 按事件源的形状打一次触发（打标器只改调用方那份存档，由调用方保存）。 */
    private void trigger(String playerId, GiftCfg.Trigger trigger, long at) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        GiftTriggerMarks.markOn(save, trigger, at);
        players.save(save);
    }

    private String order(String playerId) {
        return pay.createOrder(playerId, new CreateOrderReq(
                "req-" + UUID.randomUUID(), GIFT_PRODUCT, 1, null)).orderId();
    }

    @Test
    @DisplayName("买一次成功，同一天再买被拒在 15011：钱还没花出去就被拦下")
    void secondPurchaseSameDayIsRejectedBeforePaying() {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        trigger(playerId, GiftCfg.Trigger.STUCK_STAGE, now);

        assertThat(order(playerId)).as("触发在有效期内、今天还没买过 ⇒ 第一单能下").isNotBlank();

        assertThatThrownBy(() -> order(playerId))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .as("必须是「今天买过了」这一码，而不是普通的参数错").isEqualTo(ErrorCode.PAY_GIFT_DAILY_LIMIT));
    }

    @Test
    @DisplayName("跨天恢复可买：账本按自然日（UTC+8）清空，不是距上次 24 小时")
    void nextDayRestoresThePurchase() {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        trigger(playerId, GiftCfg.Trigger.STUCK_STAGE, now);
        order(playerId);

        // 把账本挪到"昨天"：等价于玩家睡了一觉（用真实的日切跑一遍要等一天）
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        String yesterday = DayKey.of(now - 24L * 60L * 60_000L);
        com.ironoath.core.player.PlayerGiftPopup before = save.giftPopup();
        save.setGiftPopup(new com.ironoath.core.player.PlayerGiftPopup(before.lastShowAt(),
                before.showsByGift(), before.triggeredAt(), yesterday, before.purchasedCountByGift()));
        players.save(save);
        // 触发也要刷新到"现在"：昨天的触发早就过窗了
        trigger(playerId, GiftCfg.Trigger.STUCK_STAGE, System.currentTimeMillis());

        assertThat(order(playerId)).as("新的一天 + 新的触发 ⇒ 又能买").isNotBlank();
    }

    @Test
    @DisplayName("报价过窗被拒在 15012：买不了的原因是「这次机会过期了」，而不是「商品下架了」")
    void expiredOfferIsRejectedWithItsOwnCode() {
        String playerId = newPlayer();
        // 61 分钟前触发（表里 offerTtlMinutes=60）
        trigger(playerId, GiftCfg.Trigger.STUCK_STAGE, System.currentTimeMillis() - 61L * MINUTE);

        assertThatThrownBy(() -> order(playerId))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.PAY_GIFT_OFFER_EXPIRED));
    }

    @Test
    @DisplayName("非礼包档不受这两道闸影响：月卡没有触发窗口这回事")
    void nonGiftProductsAreUnaffected() {
        String playerId = newPlayer();

        assertThat(pay.createOrder(playerId, new CreateOrderReq(
                "req-" + UUID.randomUUID(), "monthly_card", 1, null)).orderId())
                .as("月卡不读礼包触发：它没有「过窗」，也没买过").isNotBlank();
        assertThat(configs.all(GiftCfg.class)).as("夹具前提：gift 表有行").isNotEmpty();
    }
}
