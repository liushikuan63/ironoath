package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.CardClaimReq;
import com.ironoath.web.dto.generated.CardClaimResp;
import com.ironoath.web.dto.generated.CardStatusResp;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.CreateOrderResp;
import com.ironoath.web.dto.generated.FundClaimReq;
import com.ironoath.web.dto.generated.FundClaimResp;
import com.ironoath.web.dto.generated.FundStatusResp;
import com.ironoath.web.dto.generated.OrderStatusResp;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.dto.generated.PayRetryReq;
import com.ironoath.web.dto.generated.PricesResp;
import com.ironoath.web.service.PayAppService;

/**
 * 职责：支付域 HTTP 入口（B15）—— 价格表、下单、回调、补单、订单状态。
 * 依赖：Spring Web、{@link PayAppService}。
 *
 * <p><b>{@code /pay/callback} 不校验玩家身份</b>：调用方是渠道服务器而不是客户端，
 * 它的可信性完全靠 sign 验签。给它加上 X-Player-Id 头反而是错的 ——
 * 渠道不知道我们的玩家 id，而一个「必须带头」的回调端点会在真实对接时收不到任何回调。
 *
 * <p><b>其余四个端点都要求玩家身份</b>，且订单归属在 service 层校验：
 * 别人的订单一律回「不存在」而不是「不属于你」，后者等于给了一个探测订单号的接口，
 * 而订单号里含玩家 id 与时间戳。
 */
@RestController
@RequestMapping("/pay")
public class PayController {

    private final PayAppService pay;
    private final com.ironoath.web.pay.PaidClaimsAppService claims;

    public PayController(PayAppService pay, com.ironoath.web.pay.PaidClaimsAppService claims) {
        this.pay = pay;
        this.claims = claims;
    }

    /** 价格表。**客户端不得内置任何价格**，否则调价必须发版，而发版前的旧客户端会显示旧价却按新价扣款。 */
    @GetMapping("/prices")
    public Result<PricesResp> prices() {
        return Result.ok(pay.prices());
    }

    /** 下单。价格由服务端按 productId 查表，客户端传的价格一律忽略。 */
    @PostMapping("/order")
    public Result<CreateOrderResp> createOrder(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                               @RequestBody CreateOrderReq req) {
        requirePlayer(playerId);
        return Result.ok(pay.createOrder(playerId, req));
    }

    /**
     * 支付回调（渠道服务器调用，无玩家身份）。
     *
     * <p><b>验签 → 改状态 → 落库，然后才发货</b>：回调有时限，
     * 超时会微信判失败并重试，于是「发货慢」会变成「重复发货的诱因」。
     */
    @PostMapping("/callback")
    public Result<OrderStatusResp> callback(@RequestBody PayCallbackReq req) {
        return Result.ok(pay.callback(req));
    }

    /** 订单状态。<b>客户端在支付返回后必须轮询它</b>，不能凭客户端回调直接显示已到账。 */
    @GetMapping("/order")
    public Result<OrderStatusResp> status(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestParam("orderId") String orderId) {
        requirePlayer(playerId);
        return Result.ok(pay.status(playerId, orderId));
    }

    /** 手工触发一次补单（客服入口用）。 */
    @PostMapping("/retry")
    public Result<OrderStatusResp> retry(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                         @RequestBody PayRetryReq req) {
        requirePlayer(playerId);
        return Result.ok(pay.retry(playerId, req));
    }

    // ---------- B19 付费权益：月卡日包与成长基金档位 ----------

    /**
     * 月卡状态（是否在有效期、今天领过没有、现在点会领几天、当前生效的免广告与队列加成）。
     *
     * <p><b>读路径不拿玩家锁</b>：它只做一次读档，权益是否生效由"读出来的到期时刻 vs 服务端当前时刻"
     * 现推，没有读改写。给读路径加锁只会把一个纯查询变成"别人卡住我 3 秒"。
     */
    @GetMapping("/card")
    public Result<CardStatusResp> cardStatus(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(claims.cardStatus(playerId));
    }

    /** 领月卡日包（漏领的天数在有效期内一次补发，上限是剩余有效天数）。 */
    @PostMapping("/card/claim")
    public Result<CardClaimResp> claimCard(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody CardClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(claims.claimCard(playerId, req));
    }

    /** 成长基金六档的解锁与领取状态。没买过也返回档位表，玩家要能看见"买了之后有什么"。 */
    @GetMapping("/fund")
    public Result<FundStatusResp> fundStatus(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(claims.fundStatus(playerId));
    }

    /** 领一档成长基金（一次一档，主城等级达标才领得到）。 */
    @PostMapping("/fund/claim")
    public Result<FundClaimResp> claimFund(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody FundClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(claims.claimFund(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
