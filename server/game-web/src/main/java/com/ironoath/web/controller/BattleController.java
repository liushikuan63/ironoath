package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.BattleReportListResp;
import com.ironoath.web.dto.generated.BattleReportResp;
import com.ironoath.web.dto.generated.ReportShareReq;
import com.ironoath.web.dto.generated.ReportShareResp;
import com.ironoath.common.time.TimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：战报域 HTTP 入口（B09）。
 * 依赖：Spring Web、{@link BattleReportService}。
 *
 * <p><b>列表与详情分成两个端点</b>：详情含逐回合的兵力、损失、乘区与技能触发，
 * 一场 8 回合的战斗是几十个数字。若列表直接带上详情，
 * 一次「看看最近的战报」就会下发几百 KB —— 而 B07 给地图视野定的 20KB 上限
 * 正是为了让弱网玩家不被一次响应卡住，战报列表没有理由例外。
 *
 * <p>两个都是 GET：战报是纯读取，不改变任何状态（过期清理由服务内部惰性触发，
 * 与 {@code /city/list}、{@code /army/list} 的「读的时候顺带推进」是同一套口径）。
 */
@RestController
@RequestMapping("/battle")
public class BattleController {

    private final BattleReportService battleReportService;
    private final TimeService timeService;

    public BattleController(BattleReportService battleReportService, TimeService timeService) {
        this.battleReportService = battleReportService;
        this.timeService = timeService;
    }

    /** 我的战报列表，按时间倒序。 */
    @GetMapping("/reports")
    public Result<BattleReportListResp> reports(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(battleReportService.list(playerId));
    }

    /** 一份战报的完整回放数据（B05 的客户端 BattlePlayback 消费它）。 */
    @GetMapping("/report")
    public Result<BattleReportResp> report(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestParam("reportId") String reportId) {
        requirePlayer(playerId);
        return Result.ok(battleReportService.open(playerId, reportId));
    }

    /**
     * 把一份自己的战报分享到小队 / 联盟频道（B22 §一 2）。
     *
     * <p><b>为什么是 POST 而不是 GET</b>：它会往频道里写一条消息（并且要过限流与内容送检），
     * 是有副作用的一次动作 —— 与 {@code /chat/send} 同一类，而不是列表那种纯读。
     */
    @PostMapping("/share")
    public Result<ReportShareResp> share(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                         @RequestBody ReportShareReq req) {
        requirePlayer(playerId);
        if (req.channel() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        return Result.ok(battleReportService.share(playerId, req, timeService.serverNow()));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
