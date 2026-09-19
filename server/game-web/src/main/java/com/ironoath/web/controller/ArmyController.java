package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.ArmyListResp;
import com.ironoath.web.dto.generated.ArmyUnitReq;
import com.ironoath.web.dto.generated.AutoTrainReq;
import com.ironoath.web.dto.generated.AutoTrainResp;
import com.ironoath.web.dto.generated.TrainCancelResp;
import com.ironoath.web.dto.generated.TrainReq;
import com.ironoath.web.dto.generated.TrainResp;
import com.ironoath.web.dto.generated.TreatReq;
import com.ironoath.web.dto.generated.TreatResp;
import com.ironoath.web.service.ArmyAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：军队域 HTTP 入口 —— 训练队列、医院与治疗（B05 §二）。
 * 依赖：Spring Web、ArmyAppService。
 *
 * <p>Controller 保持极薄：取 playerId → 调 service → 包 Result。
 * 校验、加锁、幂等、扣资源、阶级解锁判定全在 service 里。
 *
 * <p><b>{@code /army/list} 是有副作用的「读」</b>：它会顺带收割到点的训练与治疗。
 * 服务端不跑定时器，状态只能在有人读的时候被推进（B00 陷阱 2）——
 * 与 {@code /city/list}、{@code /resource/detail} 是同一套惰性结算纪律。
 * 客户端因此不需要为「训练完成」做轮询，打开军队界面就等于领了一次。
 */
@RestController
@RequestMapping("/army")
public class ArmyController {

    private final ArmyAppService armyAppService;

    public ArmyController(ArmyAppService armyAppService) {
        this.armyAppService = armyAppService;
    }

    /** 军队总览：全部 20 个兵种（含未解锁的，带解锁提示）、带兵上限、队列与医院状态。 */
    @GetMapping("/list")
    public Result<ArmyListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.list(playerId));
    }

    /** 开始训练。时间 = 单位时间 × count（B05 §二），批量不等于加速。 */
    @PostMapping("/train")
    public Result<TrainResp> train(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody TrainReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.train(playerId, req));
    }

    /**
     * 开关自动续训 / 自动补兵（B25 裁决③(a)）。
     *
     * <p>它保存的是一份**有预算的策略**（兵种、每批数量、还剩几批、补兵目标、停止原因），
     * 不是一个布尔开关；执行发生在 {@code /army/list} 那次惰性结算里，走的是真人那条
     * {@code train}（真扣资源、真占队列、真算时长）—— 自动只是"谁按的确认键"不同。
     */
    @PostMapping("/autoTrain")
    public Result<AutoTrainResp> autoTrain(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody AutoTrainReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.autoTrain(playerId, req));
    }

    /** 取消训练，按比例返还资源（与城建取消同一口径）。 */
    @PostMapping("/cancel")
    public Result<TrainCancelResp> cancel(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody ArmyUnitReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.cancel(playerId, req));
    }

    /**
     * 加速训练。必须带 {@code itemId}（item 表 effectKind=REDUCE_TRAIN_SECONDS 的道具）。
     *
     * <p><b>不接受客户端传来的 seconds</b>：接受它等于把「加速多少」的决定权交给客户端，
     * 改一下请求体就能瞬间训完（B00 铁律 3：服务器权威）。
     * 联盟帮助等免费加速由 B10 落地后走独立入口，届时秒数同样由服务端算。
     */
    @PostMapping("/speedUp")
    public Result<TrainResp> speedUp(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestBody ArmyUnitReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.speedUp(playerId, req));
    }

    /** 开始治疗全部伤兵。耗时与资源都由服务端按配置算。 */
    @PostMapping("/treat")
    public Result<TreatResp> treat(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody TreatReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.treat(playerId, req));
    }

    /** 加速治疗。口径同 {@code /army/speedUp}：秒数只能来自道具配置。 */
    @PostMapping("/treatSpeedUp")
    public Result<TreatResp> treatSpeedUp(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody ArmyUnitReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.treatSpeedUp(playerId, req));
    }

    /** 收割已完成的治疗：伤兵归队。归队不受带兵上限约束（这些兵本来就是玩家的）。 */
    @PostMapping("/collectTreated")
    public Result<TreatResp> collectTreated(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody TreatReq req) {
        requirePlayer(playerId);
        return Result.ok(armyAppService.collectTreated(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
