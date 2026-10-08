package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.LevelRewardClaimReq;
import com.ironoath.web.dto.generated.LevelRewardClaimResp;
import com.ironoath.web.dto.generated.LevelRewardListResp;
import com.ironoath.web.levelreward.LevelRewardAppService;

/**
 * 职责：等级奖励域 HTTP 入口（收口清单 #829 三项裁决：表 + 点领取才发 + 新建面板）。
 * 依赖：Spring Web、{@link LevelRewardAppService}。
 *
 * <p><b>只有两个端点</b>：等级本身不是这里推进的（升级走 {@code /city} 那一路），本域只回答
 * 「这一级领过没有」与「把这一级的东西发出去」。没有 {@code POST /level-reward/reach} 这类接口 ——
 * 有它就等于允许客户端自己申报到过哪一级，而那条路一旦存在，领取记录就不再是证据（与任务域
 * 「不得有 POST /quest/progress」同一条理由）。
 */
@RestController
@RequestMapping("/level-reward")
public class LevelRewardController {

    private final LevelRewardAppService levelRewards;

    public LevelRewardController(LevelRewardAppService levelRewards) {
        this.levelRewards = levelRewards;
    }

    /** 领取视图：全部等级行（含锁定与已领）、可领数、主城等级。纯读，不推进状态。 */
    @GetMapping("/list")
    public Result<LevelRewardListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(levelRewards.list(playerId));
    }

    /** 领取一级的奖励。未达等级 / 已领过 / 表里没这一级各有各的业务码；同 requestId 重放只发一份。 */
    @PostMapping("/claim")
    public Result<LevelRewardClaimResp> claim(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody LevelRewardClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(levelRewards.claim(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
