package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.StaminaBuyReq;
import com.ironoath.web.dto.generated.StaminaBuyResp;
import com.ironoath.web.dto.generated.StaminaResp;
import com.ironoath.web.service.StaminaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：体力域 HTTP 入口（B09 §5）。
 * 依赖：Spring Web、{@link StaminaService}。
 *
 * <p>单独一个 Controller 而不是塞进 PlayerController：体力是打野与关卡的消耗闸门，
 * B09-B 的关卡端点会围绕它长出来，届时「体力相关的接口在哪」应当有一个明确答案。
 *
 * <p><b>{@code GET /stamina} 是个会写库的读</b>：恢复量靠惰性结算推进，
 * 容量随主城等级变化也要在这里写回。与 {@code /city/list}、{@code /army/list} 同一套口径 ——
 * 服务端没有定时器，状态只能在有人读的时候被推进（B00 陷阱 2）。
 */
@RestController
@RequestMapping("/stamina")
public class StaminaController {

    private final StaminaService staminaService;

    public StaminaController(StaminaService staminaService) {
        this.staminaService = staminaService;
    }

    /** 体力面板：当前值、上限、恢复速率、下一点恢复时刻、今日已购次数与下一次单价。 */
    @GetMapping
    public Result<StaminaResp> view(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(staminaService.view(playerId));
    }

    /**
     * 用金币购买体力。
     *
     * <p>合并多次购买为一次请求：连买 5 次只扣一次锁、只写一次存档，
     * 而不是五次读改写互相打架（乐观锁会让其中四次失败，玩家看到的是「购买失败」）。
     */
    @PostMapping("/buy")
    public Result<StaminaBuyResp> buy(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                      @RequestBody StaminaBuyReq req) {
        requirePlayer(playerId);
        return Result.ok(staminaService.buy(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
