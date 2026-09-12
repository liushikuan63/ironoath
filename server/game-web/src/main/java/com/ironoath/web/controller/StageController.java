package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.ChallengeStageReq;
import com.ironoath.web.dto.generated.ChallengeStageResp;
import com.ironoath.web.dto.generated.StageListResp;
import com.ironoath.web.dto.generated.SweepReq;
import com.ironoath.web.dto.generated.SweepResp;
import com.ironoath.web.service.StageAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：章节副本域 HTTP 入口（B09 §4/§6）。
 * 依赖：Spring Web、{@link StageAppService}。
 *
 * <p><b>扫荡是一个请求跑完最多 10 次战斗</b>（验收 9：扫荡 10 次只发 1 次请求）。
 * 若让客户端逐次发 10 个请求，每一次都要走幂等、加锁、结算，
 * 弱网下会有几次超时 —— 玩家看到的是「扫荡了 7 次」这种无法解释的结果，
 * 而服务端日志里 10 次都成功了。
 */
@RestController
@RequestMapping("/stage")
public class StageController {

    private final StageAppService stageAppService;

    public StageController(StageAppService stageAppService) {
        this.stageAppService = stageAppService;
    }

    /** 全部关卡的进度与解锁状态（含当前体力，因为「能不能打」同时取决于两者）。 */
    @GetMapping("/list")
    public Result<StageListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(stageAppService.list(playerId));
    }

    /** 挑战一关。 */
    @PostMapping("/challenge")
    public Result<ChallengeStageResp> challenge(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody ChallengeStageReq req) {
        requirePlayer(playerId);
        return Result.ok(stageAppService.challenge(playerId, req));
    }

    /** 扫荡（需三星通关）。1~10 次合并成一个请求。 */
    @PostMapping("/sweep")
    public Result<SweepResp> sweep(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody SweepReq req) {
        requirePlayer(playerId);
        return Result.ok(stageAppService.sweep(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
