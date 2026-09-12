package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.BagListResp;
import com.ironoath.web.dto.generated.ItemUseReq;
import com.ironoath.web.dto.generated.ItemUseResp;
import com.ironoath.web.dto.generated.OpenBatchReq;
import com.ironoath.web.dto.generated.OpenBatchResp;
import com.ironoath.web.service.BagAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：背包与道具域 HTTP 入口（B04 §2 契约）。
 * 依赖：Spring Web、BagAppService。
 *
 * <p>Controller 保持极薄：取 playerId → 调 service → 包 Result。
 * 校验、加锁、幂等、扣道具、发奖全在 service 里，这样它们能在没有 HTTP 的情况下被单测覆盖。
 *
 * <p>{@code /bag}、{@code /item} 两组路径挂在同一个 Controller 上：
 * 它们是同一个领域（背包里的东西怎么看、怎么用），拆成两个类只会让「道具相关逻辑在哪」变得难找。
 *
 * <p>TODO(需确认): playerId 仍从 {@code X-Player-Id} 头取，这是没有鉴权体系时的临时做法。
 * 微信登录与 token 鉴权由 B15 交付，届时必须改成从已验证的 token 解析 ——
 * <b>开箱与用道具是直接产出价值的操作，信任请求头等于把刷道具的入口敞开</b>。
 */
@RestController
public class BagController {

    private final BagAppService bagAppService;

    public BagController(BagAppService bagAppService) {
        this.bagAppService = bagAppService;
    }

    /**
     * 背包列表（B04 §3）。
     *
     * @param type 分页过滤：SPEEDUP / RESOURCE / CHEST / MATERIAL / BUFF；省略或空串表示全部。
     *             拼错的类型名会返回 PARAM_INVALID 而不是空列表 —— 静默返回空列表
     *             会让客户端以为「背包里什么都没有」，那比报错难查得多。
     */
    @GetMapping("/bag/list")
    public Result<BagListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                    @RequestParam(name = "type", required = false,
                                            defaultValue = BagAppService.PAGE_ALL) String type) {
        requirePlayer(playerId);
        return Result.ok(bagAppService.list(playerId, type));
    }

    /**
     * 使用道具（B04 §4）。行为按道具类型分派：
     * 加速类需要 {@code targetId}；资源类支持一次用 N 个；宝箱请走 {@code /item/openBatch}。
     */
    @PostMapping("/item/use")
    public Result<ItemUseResp> use(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody ItemUseReq req) {
        requirePlayer(playerId);
        return Result.ok(bagAppService.useItem(playerId, req));
    }

    /**
     * 批量开箱（B04 验收 3：一次开 100 个只发 1 次请求；验收 4：同 seed 结果完全一致）。
     *
     * <p>响应里回 {@code seed}：它是「结果可复现」在生产环境唯一的可查凭证 ——
     * 玩家申诉「开了 100 个什么都没出」时，用 (seed, count) 重跑 ChestOpener 就能逐条还原。
     * 种子只对已发生的那一次有意义，对下一次没有预测价值。
     */
    @PostMapping("/item/openBatch")
    public Result<OpenBatchResp> openBatch(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody OpenBatchReq req) {
        requirePlayer(playerId);
        return Result.ok(bagAppService.openBatch(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
