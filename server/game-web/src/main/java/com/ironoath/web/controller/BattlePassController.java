package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.BattlePassClaimReq;
import com.ironoath.web.dto.generated.BattlePassClaimResp;
import com.ironoath.web.dto.generated.BattlePassStatusResp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：赛季战令的两个端点（B24 块②）—— 读状态、领一档。
 * 依赖：{@link BattlePassService}。
 *
 * <p>端点只有两个是刻意的：**积分不由客户端上报**（它只从任务与活动的领取里长出来），
 * 所以没有"提交进度"这类端口 —— 那种端口在客户端手里就是一个刷分入口。
 */
@RestController
@RequestMapping("/battlePass")
public class BattlePassController {

    private final BattlePassService battlePass;

    public BattlePassController(BattlePassService battlePass) {
        this.battlePass = battlePass;
    }

    /** 本赛季战令全貌：积分、付费线解锁位、20 档（含未达成的）。 */
    @GetMapping("/status")
    public Result<BattlePassStatusResp> status(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(battlePass.status(playerId));
    }

    /** 领某一档的某一条线。带 requestId 幂等。 */
    @PostMapping("/claim")
    public Result<BattlePassClaimResp> claim(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody BattlePassClaimReq req) {
        requirePlayer(playerId);
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "领取战令奖励必须带 requestId");
        }
        return Result.ok(battlePass.claim(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "缺少玩家身份");
        }
    }
}
