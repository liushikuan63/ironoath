package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.PlayerInitResp;
import com.ironoath.web.dto.generated.PowerDetailResp;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：玩家域 HTTP 入口 —— B01 全链路的起点，以及 B08 的战力明细面板。
 * 依赖：Spring Web、PlayerInitService、PowerRefreshService。
 *
 * <p>Controller 保持极薄：只做「收 body → 调 service → 包 Result」。
 * 参数校验在 service 里做（铁律 2：服务端必须独立校验，且不依赖客户端的任何提示），
 * traceId 与 serverNow 由 {@link com.ironoath.web.web.ResultBodyAdvice} 统一补齐。
 */
@RestController
@RequestMapping("/player")
public class PlayerController {

    private final PlayerInitService playerInitService;
    private final PowerRefreshService powerRefreshService;

    public PlayerController(PlayerInitService playerInitService,
                            PowerRefreshService powerRefreshService) {
        this.playerInitService = playerInitService;
        this.powerRefreshService = powerRefreshService;
    }

    /**
     * 初始化或登录玩家。
     *
     * <p>同一 deviceId 重复调用返回同一份存档（等同登录）；
     * 同一 requestId 重复调用只创建一次玩家（B01 验收 11）。
     */
    @PostMapping("/init")
    public Result<PlayerInitResp> init(@RequestBody PlayerInitReq req) {
        return Result.ok(playerInitService.init(req));
    }

    /**
     * 战力明细（B08 §1：UI 必须能点开看明细）。
     *
     * <p><b>这是个会写库的 GET</b>：战力是城建 + 部队 + 武将的派生值，
     * 读的时候必须先把惰性状态结算掉（刚完成的升级、刚训完的兵），
     * 否则玩家点开面板看到的是上一次的数字 —— 而他点开面板的时机，
     * 恰好就是刚做完那件事、最想看到变化的时刻。
     * 与 {@code /city/list}、{@code /army/list} 是同一套口径。
     */
    @GetMapping("/power")
    public Result<PowerDetailResp> power(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return Result.ok(powerRefreshService.detail(playerId));
    }
}
