package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.ResourceDetailResp;
import com.ironoath.web.service.ResourceAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：资源明细面板 HTTP 入口（B04 §2）。
 * 依赖：Spring Web、ResourceAppService。
 *
 * <p>这个面板是 B04 明说的「转化关键 UI」：玩家看懂「我为什么产这么点」，
 * 才会去升级对应建筑或买对应礼包。技术上唯一难的地方是明细之和必须精确等于实际产出
 * （验收 5 要求误差 0），所以本接口不自己算任何东西，
 * 只把结算时用过的那份产率原样下发 —— 详见 {@link ResourceAppService} 的类注释。
 */
@RestController
@RequestMapping("/resource")
public class ResourceController {

    private final ResourceAppService resourceAppService;

    public ResourceController(ResourceAppService resourceAppService) {
        this.resourceAppService = resourceAppService;
    }

    /**
     * 资源明细（当前存量、容量、保护额度、每小时产出及其逐项分解、是否满仓）。
     *
     * <p>这是个有副作用的「读」：它会完成惰性结算与到点收割。
     * 服务端不跑定时器，状态只能在有人读的时候被推进（B00 陷阱 2）。
     */
    @GetMapping("/detail")
    public Result<ResourceDetailResp> detail(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return Result.ok(resourceAppService.detail(playerId));
    }
}
