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
import com.ironoath.web.dto.generated.GuideProgressReq;
import com.ironoath.web.dto.generated.GuideProgressResp;
import com.ironoath.web.dto.generated.GuideScriptResp;
import com.ironoath.web.guide.GuideAppService;

/**
 * 职责：新手引导域 HTTP 入口（B18）—— 拉脚本、报进度。
 * 依赖：Spring Web、{@link GuideAppService}。
 *
 * <p><b>只有两条</b>：没有「这一步我做完了没有」的查询端点 —— 客户端本来就不该知道判据，
 * 判据不下发（{@code guide.schema.json} 约束 2）。也没有「重置引导」「跳过全部」这类：
 * 跳过有 {@code SKIP} 上报，重置属于运营手段（要的是改存档，不是开一个无鉴权写口）。
 *
 * <p><b>{@code /guide/script} 刻意不接 {@code ?version=} 查询参数</b>：B18 §一.1 那份草案写了它，
 * 用途是「版本相同可以不必重下」。这份脚本七步、响应实测不到 1 KB（验收 7 会量），
 * 省下来的量是零，而一个不改变任何行为的查询参数会变成接口上的假象 ——
 * 下一个人会以为缓存已经做了。等步骤多到需要分页时，参数与分页一起上。
 * 客户端该做的比较仍然成立：响应里的 {@code version} 与自己手上那份对比，相同就不必重画。
 */
@RestController
@RequestMapping("/guide")
public class GuideController {

    private final GuideAppService guide;

    public GuideController(GuideAppService guide) {
        this.guide = guide;
    }

    /** 拉取脚本：步骤序列、版本、这个玩家的续传位置、这个账号还该不该看引导。 */
    @GetMapping("/script")
    public Result<GuideScriptResp> script(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(guide.script(playerId));
    }

    /** 上报一步（做完或跳过）。推进与否由服务端按任务账本判，requestId 幂等。 */
    @PostMapping("/progress")
    public Result<GuideProgressResp> progress(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody GuideProgressReq req) {
        requirePlayer(playerId);
        return Result.ok(guide.progress(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少玩家身份（" + CityController.PLAYER_HEADER + "）");
        }
    }
}
