package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.SocialSummaryResp;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadMemberReq;
import com.ironoath.web.dto.generated.SquadSelfReq;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：小队域 HTTP 入口（B10 §1）。
 * 依赖：Spring Web、{@link SocialAppService}。
 *
 * <p><b>全部返回 SocialSummaryResp 而不是各自的小视图</b>：小队的每个操作都会同时改动
 * 「我的小队」「红点」「未读事件」三块数据（加入会通知队友、踢人会生成事件），
 * 返回局部视图的话客户端要再拉一次汇总才能对齐 —— 而那次拉取在弱网下可能失败，
 * 于是面板上会出现「成员列表已经更新了但红点还是旧的」这种半新半旧的状态。
 *
 * <p>所有端点都是写操作，一律要 requestId（B00 陷阱 3：没有幂等就等于允许重放）。
 */
@RestController
@RequestMapping("/squad")
public class SquadController {

    private final SocialAppService social;

    public SquadController(SocialAppService social) {
        this.social = social;
    }

    /** 创建小队。前置：主城 5 级 + 开服 D1（B10 §1）。 */
    @PostMapping("/create")
    public Result<SocialSummaryResp> create(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody SquadCreateReq req) {
        requirePlayer(playerId);
        return Result.ok(social.squadCreate(playerId, req));
    }

    /** 加入小队。人数上限按「小队等级 + 队长主城等级」两个条件算（验收 3）。 */
    @PostMapping("/join")
    public Result<SocialSummaryResp> join(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody SquadIdReq req) {
        requirePlayer(playerId);
        requireText(req.squadId(), "squadId");
        return Result.ok(social.squadJoin(playerId, req));
    }

    /** 退出小队。队长不能直接退，必须先转让或解散。 */
    @PostMapping("/leave")
    public Result<SocialSummaryResp> leave(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody SquadSelfReq req) {
        requirePlayer(playerId);
        return Result.ok(social.squadLeave(playerId, req));
    }

    /** 踢人。权限位 SQUAD/KICK_MEMBER 由 role_permission 表裁决（验收 4）。 */
    /**
     * 转让队长。客户端早就绑了 {@code POST /squad/transfer}，在这个端点存在之前，
     * 那个按钮点了就是 404 —— 而契约与客户端方法都在，看不出少了东西。
     */
    @PostMapping("/transfer")
    public Result<SocialSummaryResp> transfer(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                              @RequestBody SquadMemberReq req) {
        requirePlayer(playerId);
        requireText(req.memberId(), "memberId");
        return Result.ok(social.squadTransfer(playerId, req));
    }

    @PostMapping("/kick")
    public Result<SocialSummaryResp> kick(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody SquadMemberReq req) {
        requirePlayer(playerId);
        requireText(req.memberId(), "memberId");
        return Result.ok(social.squadKick(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
    }
}
