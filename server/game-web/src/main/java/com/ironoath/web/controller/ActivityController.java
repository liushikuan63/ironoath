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
import com.ironoath.web.activity.ActivityAppService;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.ActivityClaimResp;
import com.ironoath.web.dto.generated.ActivityListResp;

/**
 * 职责：活动域 HTTP 入口（B17）—— 列表与领取。
 * 依赖：Spring Web、{@link ActivityAppService}。
 *
 * <p><b>只有两条</b>：签到没有自己的端点 —— 七日登录 / 月常守望就是表里 `activityType=LOGIN_STREAK`
 * 的两行，走的是同一个 {@code /activity/claim}。多一个 {@code /signin} 就是第二套窗口语义，
 * 而"连续七天"这句话会在两个地方各实现一遍。成就也没有自己的端点：它并入 {@code /quest/list}。
 *
 * <p><b>没有「预览/结算」这类端点或字段</b>：三个状态（RUNNING / CLAIMABLE / EXPIRED）都由列表下发，
 * 客户端不判、也不问"我能不能领"。
 */
@RestController
@RequestMapping("/activity")
public class ActivityController {

    private final ActivityAppService activities;

    public ActivityController(ActivityAppService activities) {
        this.activities = activities;
    }

    /** 活动列表（含已过期的行：轮到下一轮之前它看得见、领不了）。 */
    @GetMapping("/list")
    public Result<ActivityListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(activities.list(playerId));
    }

    /** 领取一行活动的奖励。requestId 幂等：同一次领取重投只会拿到同一份结果。 */
    @PostMapping("/claim")
    public Result<ActivityClaimResp> claim(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody ActivityClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(activities.claim(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少玩家身份（" + CityController.PLAYER_HEADER + "）");
        }
    }
}
