package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.GiftPopupResp;
import com.ironoath.web.pay.GiftPopupService;

/**
 * 职责：礼包弹窗的读侧出口（B19 S3-ii）。一个只读端点，独立于 {@code PayController}。
 *
 * <p><b>为什么与支付控制器分开</b>：绑定关系不同 —— 支付那几个端点操作的是订单与权益，
 * 而这里问的是"现在弹不弹"。分开之后卡口与客户端路由都能一眼看出这是"看"不是"买"。
 *
 * <p><b>幂等</b>：GET 本身不改钱；弹一次会写"弹出时刻"这一位，而这是**可重复的读** ——
 * 同一次触发被读两次，第二次会被全局冷却挡掉（10 分钟）。
 */
@RestController
@RequestMapping("/gift")
public class GiftController {

    private final GiftPopupService gifts;

    public GiftController(GiftPopupService gifts) {
        this.gifts = gifts;
    }

    /** 这一屏弹不弹、弹哪个。判定与记账都在服务里做，控制器只转手。 */
    @GetMapping("/popup")
    public Result<GiftPopupResp> popup(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        return Result.ok(gifts.popup(playerId));
    }
}
