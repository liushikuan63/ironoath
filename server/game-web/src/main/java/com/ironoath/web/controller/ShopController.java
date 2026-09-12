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
import com.ironoath.web.dto.generated.ShopBuyReq;
import com.ironoath.web.dto.generated.ShopBuyResp;
import com.ironoath.web.dto.generated.ShopCurrency;
import com.ironoath.web.dto.generated.ShopListResp;
import com.ironoath.web.service.ShopAppService;

/**
 * 职责：商店 HTTP 入口（B02 商店表 / B10 验收 8）。依赖：Spring Web、{@link ShopAppService}。
 *
 * <p><b>路径为什么是 {@code /shop/*} 而不是 {@code /squad/shopBuy} + {@code /alliance/shopBuy}
 * 或 {@code /social/shopBuy}</b>：这三份说法原本同时存在（协议注释写前两个、客户端打第三个、
 * 而服务端一个都没有实现），而它们互相矛盾的原因不是有人写错，是<b>商店被当成了社交的附属功能</b>。
 * 实际形状是：四种货币里只有两种属于社交组织，金币页占 13 行里的 10 行。
 * 所以商店自成一域，货币用 {@code currency} 参数区分 —— 一处路径、一份货架语义。
 * 客户端 {@code socialShopBuy} 已随之改名为 {@code shopBuy}（它此前没有任何调用点，改名零成本）。
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    private final ShopAppService shop;

    public ShopController(ShopAppService shop) {
        this.shop = shop;
    }

    /**
     * 某个货币页签的货架。
     *
     * <p>币种<b>按字符串接收再自己收窄</b>：直接绑定成枚举的话，一个拼错的值会被 Spring 判成
     * 400 类型不匹配，绕过统一的结果封装，客户端拿到的是一个没有错误码、也没有中文提示的空响应。
     */
    @GetMapping("/list")
    public Result<ShopListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestParam("currency") String currency) {
        requirePlayer(playerId);
        return Result.ok(shop.list(playerId, currencyOf(currency)));
    }

    /** 兑换。会扣货币并发道具，所以必须带 requestId 幂等。 */
    @PostMapping("/buy")
    public Result<ShopBuyResp> buy(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody ShopBuyReq req) {
        requirePlayer(playerId);
        return Result.ok(shop.buy(playerId, req));
    }

    private static ShopCurrency currencyOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "currency 不得为空：取值 " + java.util.Arrays.toString(ShopCurrency.values()));
        }
        try {
            return ShopCurrency.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "未知的商店货币 " + raw + "：取值 " + java.util.Arrays.toString(ShopCurrency.values()));
        }
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
