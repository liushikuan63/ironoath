package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.CityCancelReq;
import com.ironoath.web.dto.generated.CityPauseReq;
import com.ironoath.web.dto.generated.CityPauseResp;
import com.ironoath.web.dto.generated.CityResumeReq;
import com.ironoath.web.dto.generated.CityResumeResp;
import com.ironoath.web.dto.generated.CityCancelResp;
import com.ironoath.web.dto.generated.CityCollectReq;
import com.ironoath.web.dto.generated.CityCollectResp;
import com.ironoath.web.dto.generated.CityListResp;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.SpeedUpReq;
import com.ironoath.web.dto.generated.SpeedUpResp;
import com.ironoath.web.service.CityAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：城建域 HTTP 入口（B03 §2 契约）。
 * 依赖：Spring Web、CityAppService。
 *
 * <p>Controller 保持极薄：取 playerId → 调 service → 包 Result。
 * 校验、加锁、幂等、扣资源全在 service 里，因为那些逻辑必须能在没有 HTTP 的情况下被单测覆盖
 * （B03 验收 2 要求并发 10 个请求只有 1 个成功，用 MockMvc 测并发既慢又不稳）。
 *
 * <p>TODO(需确认): playerId 目前从 {@code X-Player-Id} 头取，这是 B01 阶段没有鉴权体系的临时做法。
 * 微信登录与 token 鉴权由 B15（商业化与合规）交付，届时必须改成从已验证的 token 解析，
 * <b>绝不能继续信任请求头</b> —— 否则任何人改个头就能操作别人的城。
 */
@RestController
@RequestMapping("/city")
public class CityController {

    /** 临时身份头。B15 接入微信登录后移除。 */
    public static final String PLAYER_HEADER = "X-Player-Id";

    private final CityAppService cityAppService;

    public CityController(CityAppService cityAppService) {
        this.cityAppService = cityAppService;
    }

    /**
     * 升级或首次建造。
     *
     * <p>失败时返回结构化错误码 + {@code detail} 里的「需要 X，当前 Y」，
     * 客户端直接展示，不显示笼统的「条件不足」（B03 验收 6）。
     */
    @PostMapping("/upgrade")
    public Result<CityUpgradeResp> upgrade(@RequestHeader(PLAYER_HEADER) String playerId,
                                           @RequestBody CityUpgradeReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.upgrade(playerId, req));
    }

    /**
     * 城内列表。
     *
     * <p>这个「读」接口有副作用：它会顺带收割到点的升级并结算离线产出。
     * 惰性结算没有定时器推进状态，状态只能在有人读的时候被推进（B00 陷阱 2）。
     * 客户端因此不需要单独的「领取」轮询 —— 打开城内界面就等于领了一次。
     */
    @GetMapping("/list")
    public Result<CityListResp> list(@RequestHeader(PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.list(playerId));
    }

    /**
     * 加速。免费与付费共用同一接口，用 {@code source} 区分（B03 §3）。
     *
     * <p>共用入口是为了埋点：「加速」只有一种事件、来源作为维度，
     * 而不是四套各自为政的接口让数据分析对不上；同时防刷规则也只需在一处维护。
     */
    @PostMapping("/speedUp")
    public Result<SpeedUpResp> speedUp(@RequestHeader(PLAYER_HEADER) String playerId,
                                       @RequestBody SpeedUpReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.speedUp(playerId, req));
    }

    /** 取消升级，返还 60% 资源（B03 §2 / 验收 4）。 */
    @PostMapping("/cancel")
    public Result<CityCancelResp> cancel(@RequestHeader(PLAYER_HEADER) String playerId,
                                         @RequestBody CityCancelReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.cancel(playerId, req));
    }

    /** 暂停升级（B03 §2：队列中可暂停 / 取消）。暂停不返还资源，只把剩余时间冻在服务端。 */
    @PostMapping("/pause")
    public Result<CityPauseResp> pause(@RequestHeader(PLAYER_HEADER) String playerId,
                                       @RequestBody CityPauseReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.pause(playerId, req));
    }

    /** 恢复升级：把暂停的那段时间还给这栋楼。 */
    @PostMapping("/resume")
    public Result<CityResumeResp> resume(@RequestHeader(PLAYER_HEADER) String playerId,
                                         @RequestBody CityResumeReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.resume(playerId, req));
    }

    /** 收割已到点的升级并结算其离线产出。{@code buildingId} 为空表示收割全部。 */
    @PostMapping("/collect")
    public Result<CityCollectResp> collect(@RequestHeader(PLAYER_HEADER) String playerId,
                                           @RequestBody CityCollectReq req) {
        requirePlayer(playerId);
        return Result.ok(cityAppService.collect(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "缺少 " + PLAYER_HEADER + " 头");
        }
    }
}
