package com.ironoath.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.pay.PayOrderStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.pay.MidasGameItemSigner;
import com.ironoath.web.security.WeChatCodeExchanger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Validates Tencent MiniGame GameItem callback signatures and order data before fulfillment. */
@Service
public final class MidasGameItemWebhookService {
    public static final String GAME_ITEM_EVENT = "minigame_game_pay_goods_deliver_notify";
    public static final String PAYMENT_SUCCESS = "TRANSACTION.SUCCESS";
    public static final String PAYMENT_FAILED = "TRANSACTION.PAYERROR";
    private static final Logger LOG = LoggerFactory.getLogger(MidasGameItemWebhookService.class);

    private final ObjectMapper json;
    private final MidasGameItemSigner signer;
    private final PayOrderStore orders;
    private final PlayerRepository players;
    private final PayAppService payments;

    public MidasGameItemWebhookService(ObjectMapper json, MidasGameItemSigner signer,
                                       PayOrderStore orders, PlayerRepository players,
                                       PayAppService payments) {
        this.json = json;
        this.signer = signer;
        this.orders = orders;
        this.players = players;
        this.payments = payments;
    }

    /**
     * Tencent's callback is a flat eventType/event/payload/payEventSig/outTradeNo object. The original
     * payload string is authenticated as-is; parsed values are only used after its HMAC has passed.
     */
    public boolean accept(JsonNode notification, String applicationId, String backendConfigId,
                          String timestamp, String platformSignature) {
        String outTradeNo = null;
        try {
            if (!signer.verifyPlatformCallbackSignature(timestamp, platformSignature))
                return reject("平台时间戳签名无效", null);
            String eventType = text(notification, "eventType");
            if (!PAYMENT_SUCCESS.equals(eventType) && !PAYMENT_FAILED.equals(eventType))
                return reject("支付结果类型无效", null);
            String event = text(notification, "event");
            if (!GAME_ITEM_EVENT.equals(event)) return reject("事件类型不符", null);
            String payloadText = text(notification, "payload");
            String payEventSig = text(notification, "payEventSig");
            String notifiedOutTradeNo = text(notification, "outTradeNo");
            if (payloadText == null || payEventSig == null || notifiedOutTradeNo == null)
                return reject("缺少回调字段", notifiedOutTradeNo);

            JsonNode payload = json.readTree(payloadText);
            String openId = text(payload, "OpenId");
            outTradeNo = text(payload, "OutTradeNo");
            if (openId == null || outTradeNo == null || !outTradeNo.matches("[0-9a-f]{32}"))
                return reject("订单映射或玩家标识无效", outTradeNo);
            if (!outTradeNo.equals(notifiedOutTradeNo))
                return reject("外层与签名Payload订单号不一致", outTradeNo);

            PayOrder order = orders.findByOutTradeNo(outTradeNo);
            if (order == null || order.line().quote() == null)
                return reject("平台订单号未映射", outTradeNo);
            PayOrder.Quote quote = order.line().quote();
            if (!outTradeNo.equals(quote.outTradeNo()))
                return reject("平台订单号与冻结报价不符", outTradeNo);
            if (!signer.verifyPayEventSig(event, payloadText, payEventSig, quote.env()))
                return reject("PayEventSig验签失败", outTradeNo);
            if (!signer.verifyCallbackIdentity(applicationId, backendConfigId, quote.env()))
                return reject("SAS应用或后端配置身份不匹配", outTradeNo);

            PlayerSave player = players.findByPlayerId(order.playerId()).orElse(null);
            if (player == null || !Objects.equals(player.deviceId(),
                    WeChatCodeExchanger.WECHAT_ACCOUNT_PREFIX + openId))
                return reject("回调玩家不匹配订单归属", outTradeNo);

            JsonNode goods = payload.path("GoodsInfo");
            if (!order.line().productId().equals(text(goods, "ProductId"))
                    || !goods.path("Quantity").canConvertToInt()
                    || goods.path("Quantity").intValue() != order.line().count()
                    || !goods.path("OrderSource").canConvertToInt()
                    || goods.path("OrderSource").intValue() != 1)
                return reject("回调商品或份数与冻结订单不符", outTradeNo);
            long originalPrice = order.line().totalCents();
            if (!matchesCents(text(goods, "OrigPrice"), originalPrice))
                return reject("回调金额无效", outTradeNo);
            long actualPrice = cents(text(goods, "ActualPrice"));
            if (actualPrice < 0 || actualPrice > originalPrice)
                return reject("实付金额超出冻结订单金额", outTradeNo);
            JsonNode payInfo = payload.path("PayInfo");
            if (!payInfo.isObject() || text(payInfo, "MchOrderNo") == null)
                return reject("支付信息缺失", outTradeNo);

            // Failure events are authenticated and mapped but never marked paid or fulfilled.
            if (PAYMENT_FAILED.equals(eventType)) return true;
            String transactionId = text(payInfo, "TransactionId");
            // The signed payload's transaction ID is optional; the stable unique order ID is the fallback.
            if (transactionId == null) transactionId = outTradeNo;
            payments.callbackFromVerifiedGameItem(order.orderId(), transactionId);
            return true;
        } catch (Exception e) {
            LOG.warn("GameItem 发货通知处理失败 outTradeNo={} reason={}", outTradeNo,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private static long cents(String amount) {
        if (amount == null) return -1L;
        try {
            return new BigDecimal(amount).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean matchesCents(String amount, long expected) {
        return cents(amount) == expected;
    }

    private static boolean reject(String reason, String outTradeNo) {
        LOG.warn("GameItem 发货通知拒绝 outTradeNo={} reason={}", outTradeNo, reason);
        return false;
    }

    private static String text(JsonNode object, String field) {
        if (object == null || !object.isObject()) return null;
        JsonNode node = object.get(field);
        if (node == null || node.isNull() || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }
}
