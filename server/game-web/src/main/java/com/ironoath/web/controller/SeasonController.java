package com.ironoath.web.controller;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.SeasonSettleReq;
import com.ironoath.web.dto.generated.SeasonSettleResp;
import com.ironoath.web.dto.generated.SeasonStatusResp;
import com.ironoath.web.ops.OpsTokenGuard;
import com.ironoath.web.season.SeasonAppService;
import com.ironoath.web.season.SeasonSettlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：赛季状态查询入口（B14 §二）。依赖：Spring Web、{@link SeasonAppService}。
 *
 * <p><b>同一个类里两种身份口径</b>，而且这个区分本身就是设计：
 * {@code /season/status} 不要身份（全服只读），{@code /season/settle} 要运维令牌（全服写）。
 * 判据不是「像不像同一个域的功能」，而是「读错了会怎样」—— 前者读错什么都没有，
 * 后者写错会影响全服每一个人的赛季奖励。
 *
 * <p><b>{@code /season/status} 不需要 X-Player-Id</b>：时间轴是全服状态，不含任何玩家数据，
 * 客户端在登录后的第一个面板刷新就要能读到它（拿不到赛季阶段就无法解释「为什么现在打不了人」）。
 * 与 {@code /time/sync} 同一条理由：不读存档、无副作用的只读接口不该被身份门槛挡住。
 */
@RestController
@RequestMapping("/season")
public class SeasonController {

    private final SeasonAppService seasons;
    private final SeasonSettlementService settlements;
    private final OpsTokenGuard ops;

    public SeasonController(SeasonAppService seasons, SeasonSettlementService settlements,
                            OpsTokenGuard ops) {
        this.seasons = seasons;
        this.settlements = settlements;
        this.ops = ops;
    }

    /**
     * 赛季当前阶段、第几天、阶段结束倒计时，是否允许 PVP / 王城战，以及（带身份时）我的实时榜名次。
     *
     * <p>{@code X-Player-Id} <b>刻意可选</b>：赛季阶段是全服信息，不该被身份门槛挡住 ——
     * 与 {@code /time/sync} 同一条理由。只有 {@code myRank} 需要身份，没有就回 null。
     */
    @GetMapping("/status")
    public Result<SeasonStatusResp> status(
            @RequestHeader(name = CityController.PLAYER_HEADER, required = false) String playerId) {
        return Result.ok(seasons.status(playerId));
    }

    /**
     * 触发一次赛季结算（B14 §5）。<b>这是运维入口，不是玩家接口</b>，所以先过 {@link OpsTokenGuard}。
     *
     * <p><b>为什么由外部调用而不是定时任务</b>：B14 禁止项明写不要用 {@code @Scheduled} 触发结算，
     * 分层检查也禁止常驻定时调度。于是结算变成一个幂等的、可被外部调度系统重复调用的入口 ——
     * 打三次只发一次（幂等键 = requestId + 领域层的 seasonId:playerId）。
     *
     * <p><b>闸门挡的是身份，不是业务</b>：阶段门与幂等键仍然各自生效（没到结算期就抛、期内重复调用只发一次），
     * 它们管的是「重复调用会不会多花钱」；这道令牌闸门管的是「谁有资格发起一次全服结算」。
     * 少了它，这两道*业务*闸门就是在替一道*身份*闸门补课 —— 结算期一放宽或幂等键换算法，
     * 这个端点就会悄悄变成对外可写的口子，而没有任何东西会报错。
     */
    @PostMapping("/settle")
    public Result<SeasonSettleResp> settle(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestBody SeasonSettleReq req) {
        ops.require(opsToken);
        return Result.ok(settlements.settle(req));
    }
}
