package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.EquipForgeReq;
import com.ironoath.web.dto.generated.EquipForgeResp;
import com.ironoath.web.dto.generated.EquipInstanceListView;
import com.ironoath.web.equip.EquipAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：装备实例的对外端点（B20 块② S3）。两个入口，全部判定在应用层与领域层（铁律 3）。
 * 依赖：{@link EquipAppService}。
 *
 * <p><b>与 {@code /hero/equip} 的分工</b>：这里管"哪一件、强到几"，那里管"穿在谁身上"。
 * 分成两个控制器不是因为装备与武将是两套系统（它们共用同一份实例账本），而是因为
 * <b>请求的主语不同</b>：一个的宾语是武将槽位，另一个的宾语是一件 uid。
 * 揉成一个端点的代价是"卸下要传什么、强化又传什么"挤在同一个 DTO 里，
 * 而这两种失败要回给玩家的是两句不同的话。
 *
 * <p><b>清单端点必须存在的原因</b>：客户端要传 uid 就先得能拿到 uid。没有这一个读口，
 * {@code /equip/forge} 就是一个只有服务端自己能调的端点 —— 那种"接口齐了但没人能用"的形状，
 * {@code check-endpoint-paths} 看不见（它是单向卡口）。
 */
@RestController
@RequestMapping("/equip")
public class EquipController {

    private final EquipAppService equipAppService;

    public EquipController(EquipAppService equipAppService) {
        this.equipAppService = equipAppService;
    }

    /** 这个玩家的全部装备实例（包里的与穿着的都在，靠 {@code wornByHeroId} 区分）。 */
    @GetMapping("/instances")
    public Result<EquipInstanceListView> instances(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(equipAppService.list(playerId));
    }

    /** 强化一件装备一级：扣铁、抬等级、必成（§五②）。 */
    @PostMapping("/forge")
    public Result<EquipForgeResp> forge(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody EquipForgeReq req) {
        requirePlayer(playerId);
        return Result.ok(equipAppService.forge(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
