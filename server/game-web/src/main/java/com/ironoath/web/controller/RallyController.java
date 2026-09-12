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
import com.ironoath.web.dto.generated.AllianceRallyReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyListResp;
import com.ironoath.web.dto.generated.RallyResp;
import com.ironoath.web.dto.generated.SquadRallyReq;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：集结域 HTTP 入口（B10 §5）—— 发起（小队 / 联盟）、加入、退出、取消、详情、列表。
 * 依赖：Spring Web、{@link SocialAppService}。
 *
 * <p><b>单独一个 Controller 而不是塞进 SquadController / AllianceController</b>：
 * 集结横跨两个层级（B13 还有国家级），按发起层级拆到两个 Controller 里，
 * 「集结相关的接口在哪」就没有一个确定答案了 —— 而加入/退出/取消这三个操作
 * 与发起层级完全无关，拆开只会让它们出现在两处或者随便挑一处。
 *
 * <p><b>四个写端点都要 requestId</b>：加入会锁定兵力（当场从城内军队扣除），
 * 重放一次就会锁两遍，玩家的兵会凭空少一份。这是本项目所有写接口的同一条纪律。
 */
@RestController
@RequestMapping("/rally")
public class RallyController {

    private final SocialAppService social;

    public RallyController(SocialAppService social) {
        this.social = social;
    }

    /** 发起小队集结。权限位 perm_squad_start_rally（队长与副队长，普通队员不行）。 */
    @PostMapping("/squad")
    public Result<RallyResp> squad(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody SquadRallyReq req) {
        requirePlayer(playerId);
        return Result.ok(social.squadRally(playerId, req));
    }

    /** 发起联盟集结。权限位 perm_alliance_start_rally；人数上限与准备时长按配置夹住而不是拒绝。 */
    @PostMapping("/alliance")
    public Result<RallyResp> alliance(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                      @RequestBody AllianceRallyReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceRally(playerId, req));
    }

    /** 加入集结并承诺兵力（承诺即锁定）。 */
    @PostMapping("/join")
    public Result<RallyResp> join(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                  @RequestBody RallyJoinReq req) {
        requirePlayer(playerId);
        return Result.ok(social.rallyJoin(playerId, req));
    }

    /** 退出集结，承诺的兵力原路退回。发起人退出等于取消（没有人能替他指出兵）。 */
    @PostMapping("/quit")
    public Result<RallyResp> quit(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                  @RequestBody RallyJoinReq req) {
        requirePlayer(playerId);
        return Result.ok(social.rallyQuit(playerId, req));
    }

    /** 取消集结（只有发起人）。所有参与者的兵力原路退回。 */
    @PostMapping("/cancel")
    public Result<RallyResp> cancel(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                    @RequestBody RallyJoinReq req) {
        requirePlayer(playerId);
        return Result.ok(social.rallyCancel(playerId, req));
    }

    /** 集结详情。到点的集结会先被处理，所以返回的状态永远是当前的。 */
    @GetMapping
    public Result<RallyResp> view(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                  @RequestParam("rallyId") String rallyId) {
        requirePlayer(playerId);
        return Result.ok(social.rallyView(playerId, rallyId));
    }

    /** 我所在的小队与联盟里进行中的集结（面板列表）。 */
    @GetMapping("/list")
    public Result<RallyListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(social.preparingRallies(playerId));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
