package com.ironoath.web.tech;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.controller.CityController;
import com.ironoath.web.dto.generated.TechCancelReq;
import com.ironoath.web.dto.generated.TechCancelResp;
import com.ironoath.web.dto.generated.TechListView;
import com.ironoath.web.dto.generated.TechResearchReq;
import com.ironoath.web.dto.generated.TechResearchResp;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：个人科技的 HTTP 边界（B20 块①）。三个端点，一一对应 {@link TechAppService} 的三个动作。
 * 依赖：科技服务；身份头沿用 {@link CityController#PLAYER_HEADER}（B15 接微信登录后一起换，不在这里另立一头）。
 *
 * <p>控制器保持极薄：校验、加锁、幂等、扣资源全在 service 里 —— 那些逻辑必须能在没有 HTTP 的
 * 情况下被单测覆盖（B00 铁律 2 的同一处取舍，与城建/军队控制器同形）。
 *
 * <p>{@code GET /tech/list} <b>有副作用</b>（顺带结算到期研究），这不是疏忽：完成时刻靠惰性结算推进，
 * 一个"纯读"的列表端点会把已经研究完的东西留在「进行中」，而下一次任何写入都会把它结掉 ——
 * 读数与写数不一致比"多点一次"更糟。与 {@code GET /city/list} 同一条口径。
 */
@RestController
@RequestMapping("/tech")
public class TechController {

    private final TechAppService techAppService;

    public TechController(TechAppService techAppService) {
        this.techAppService = techAppService;
    }

    /** 整棵科技树 + 当前队列 + 学院等级。 */
    @GetMapping("/list")
    public Result<TechListView> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(techAppService.list(playerId));
    }

    /** 开始研究下一等级。 */
    @PostMapping("/research")
    public Result<TechResearchResp> research(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody TechResearchReq req) {
        requirePlayer(playerId);
        return Result.ok(techAppService.research(playerId, req));
    }

    /** 取消当前研究（按城建同一比例返还）。 */
    @PostMapping("/cancel")
    public Result<TechCancelResp> cancel(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody TechCancelReq req) {
        requirePlayer(playerId);
        return Result.ok(techAppService.cancel(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
