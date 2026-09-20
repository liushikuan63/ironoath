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
import com.ironoath.common.time.TimeService;
import com.ironoath.web.dto.generated.SocialSummaryResp;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadListResp;
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
 * <p>写端点一律要 requestId（B00 陷阱 3：没有幂等就等于允许重放）；
 * 唯一的例外是 {@code GET /list} —— 它不改状态，见那个方法。
 */
@RestController
@RequestMapping("/squad")
public class SquadController {

    private final SocialAppService social;
    private final TimeService timeService;

    public SquadController(SocialAppService social, TimeService timeService) {
        this.social = social;
        this.timeService = timeService;
    }

    /** 创建小队。前置：主城 5 级 + 开服 D1（B10 §1）。 */
    @PostMapping("/create")
    public Result<SocialSummaryResp> create(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody SquadCreateReq req) {
        requirePlayer(playerId);
        return Result.ok(social.squadCreate(playerId, req));
    }

    /**
     * 可加入小队的列表（B26 S7）。这是「加入」那一颗按钮的唯一数据来源 ——
     * 在它之前 `/squad/join` 有写口没发现口，没小队的玩家只能自己建一支。
     *
     * <p>本类里唯一的读端点，所以不要 requestId：它不改任何状态，重放只是再读一次。
     */
    @GetMapping("/list")
    public Result<SquadListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(social.squadList(playerId, timeService.serverNow()));
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

    /**
     * 解散小队。上面那句「队长必须先转让或解散」里的另一条腿 —— 在这个端点存在之前，
     * 队长两边都走不通：leave 拒他，而解散没有入口。
     */
    @PostMapping("/disband")
    public Result<SocialSummaryResp> disband(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody SquadSelfReq req) {
        requirePlayer(playerId);
        return Result.ok(social.squadDisband(playerId, req));
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
