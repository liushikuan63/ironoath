package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.ExileReq;
import com.ironoath.web.dto.generated.ExileResp;
import com.ironoath.web.dto.generated.GatherResp;
import com.ironoath.web.dto.generated.MarchIdReq;
import com.ironoath.web.dto.generated.MarchListResp;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchResp;
import com.ironoath.web.dto.generated.RecallResp;
import com.ironoath.web.dto.generated.ScoutListResp;
import com.ironoath.web.dto.generated.ScoutReq;
import com.ironoath.web.dto.generated.SearchTargetsReq;
import com.ironoath.web.dto.generated.SearchTargetsResp;
import com.ironoath.web.dto.generated.ViewportReq;
import com.ironoath.web.dto.generated.ViewportResp;
import com.ironoath.web.service.ExileAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.TargetSearchService;
import com.ironoath.web.service.WorldAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：世界地图与行军域 HTTP 入口（B07 §二 契约）。
 * 依赖：Spring Web、WorldAppService、MarchAppService。
 *
 * <p>地图与行军合在一个 Controller 里：它们是同一个领域（大地图上的东西怎么看、怎么动），
 * 拆成两个类只会让「行军相关的逻辑在哪」变得难找。
 *
 * <p><b>{@code /world/viewport} 用 POST 而不是 GET</b>：请求体里带客户端持有的
 * chunk 版本列表（最多 9 项），GET 的查询串放得下但会让 URL 变成一坨不可读的转义字符，
 * 而且 CDN/代理可能对长 URL 截断 —— 截断的后果是客户端版本丢失、服务端全量下发、
 * payload 超限，一连串问题从一个「用 GET 更 RESTful」的决定开始。
 *
 * <p><b>每个写入口都会先推进到期行军</b>（{@code processDue}）：
 * 服务端没有常驻定时器，世界状态只能在有人请求时被推进（B00 陷阱 2、B03 验收 9）。
 */
@RestController
@RequestMapping("/world")
public class WorldController {

    private final WorldAppService worldAppService;
    private final MarchAppService marchAppService;
    private final TargetSearchService targetSearchService;
    private final ExileAppService exileAppService;
    /**
     * Bot tick 的<b>兜底</b>驱动（收口清单 §五 C1；权威驱动是运维调度按秒级打 {@code POST /bot/tick}）。
     *
     * <p><b>为什么这一脚挂在控制器而不是 {@link WorldAppService}</b>：受击反应要转调
     * {@code MarchAppService}，而它依赖 {@code WorldAppService} —— 让服务层反过来依赖 Bot 运行时
     * 就是构造环。控制器是依赖图的顶层（没有任何 bean 依赖它），放在这里既不断链也不成环。
     * 节流与"任何失败都不影响本次读图"都在 {@code BotRuntimeService} 内部兜住。
     */
    private final com.ironoath.web.bot.BotRuntimeService botRuntime;

    public WorldController(WorldAppService worldAppService, MarchAppService marchAppService,
                           TargetSearchService targetSearchService, ExileAppService exileAppService,
                           com.ironoath.web.bot.BotRuntimeService botRuntime) {
        this.worldAppService = worldAppService;
        this.marchAppService = marchAppService;
        this.targetSearchService = targetSearchService;
        this.exileAppService = exileAppService;
        this.botRuntime = botRuntime;
    }

    /**
     * 分块视野下发（B07 §1）。
     *
     * <p>只返回 3×3 个 chunk、只返回版本号比客户端更高的那些、只返回已探索块的实体。
     * payload 超过上限时剩余块标为 stale，客户端下次再取 —— 绝不截半下发。
     *
     * <p><b>「只返回已探索块的实体」有一个例外</b>：暴虐档到达「坐标不再受迷雾保护」的那些人
     * （B08 §4 第一档），他们的城会照常出现在未探索块里。块仍然列在 {@code fogChunks} 中 ——
     * 它确实没被探索过，谎报成已探索会让客户端永久保留一块本不该有的视野。
     */
    @PostMapping("/viewport")
    public Result<ViewportResp> viewport(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody ViewportReq req) {
        requirePlayer(playerId);
        ViewportResp resp = worldAppService.viewport(playerId, req);
        // 读图是这条服务里最频繁的请求，Bot 的 tick 兜底顺带搭在这里（权威驱动仍是 /bot/tick）。
        // 排在 viewport 之后：让刚发生的 Bot 动作（升级、迁城）在这一响应里就已经可见
        botRuntime.driveFromRequestPath(resp.serverNow());
        return Result.ok(resp);
    }

    /**
     * 流亡迁城（B08 §5 反击工具箱第 4 条）：免费随机落点 + 落地免战 + 3 天滚动冷却。
     *
     * <p>玩家<b>不能指定落点</b>，所以请求体里只有一个幂等键 —— 落点由服务端种子推导并随响应下发，
     * 事后可以凭 seed 复算「为什么落在那一格」。
     */
    @PostMapping("/exile")
    public Result<ExileResp> exile(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody ExileReq req) {
        requirePlayer(playerId);
        return Result.ok(exileAppService.exile(playerId, req));
    }

    /** 我的全部行军 + 家坐标。首次调用会顺带完成落位。 */
    @GetMapping("/marches")
    public Result<MarchListResp> marches(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(marchAppService.list(playerId));
    }

    /** 发起行军。时长与到达时刻都由服务端算，客户端不参与（B07 禁止项：不要用客户端定时器决定到达）。 */
    @PostMapping("/march")
    public Result<MarchResp> march(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                  @RequestBody MarchReq req) {
        requirePlayer(playerId);
        return Result.ok(marchAppService.send(playerId, req));
    }

    /** 侦查。侦查就是一支 action=SCOUT 的行军（B07 §3：需派侦察兵、消耗时间）。 */
    @PostMapping("/scout")
    public Result<MarchResp> scout(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                  @RequestBody ScoutReq req) {
        requirePlayer(playerId);
        return Result.ok(marchAppService.scout(playerId, req));
    }

    /** 召回。返回耗时 = 已行军距离 / 速度，兵力零损失（B07 验收 7）。 */
    @PostMapping("/recall")
    public Result<RecallResp> recall(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                    @RequestBody MarchIdReq req) {
        requirePlayer(playerId);
        return Result.ok(marchAppService.recall(playerId, req));
    }

    /** 结束采集并带负载返程。采集量受负载上限与 GATHER_FILL_SECONDS 约束。 */
    @PostMapping("/collectGather")
    public Result<GatherResp> collectGather(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody MarchIdReq req) {
        requirePlayer(playerId);
        return Result.ok(marchAppService.collectGather(playerId, req));
    }

    /**
     * 我的侦查报告（含已过期的，带 expired 标记）。
     *
     * <p>过期报告也返回：「我曾经侦查过这里」本身是有用的信息，
     * 而 B07 验收 9 要的是「置灰且不可用于决策」，不是「消失」。
     */
    @GetMapping("/reports")
    public Result<ScoutListResp> reports(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(worldAppService.reports(playerId));
    }

    /**
     * 搜索可攻击目标（B08 §8）。
     *
     * <p><b>这是战力圈层校验的三个统一入口之一</b>（另两个是发起攻击与发起集结）。
     * 三处必须给出同一个答案，否则玩家会遇到「列表里能选、点了却说打不了」——
     * 所以判定全部走 {@code PowerBandGuard}，本类与 TargetSearchService 都不自己算区间。
     *
     * <p>响应里<b>只有距离档位与资源档位，没有任何精确数值</b>（验收 12）：
     * 精确距离能让客户端离线推演行军时间，精确库存等于把侦查才该拿到的情报白送。
     */
    @PostMapping("/searchTargets")
    public Result<SearchTargetsResp> searchTargets(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody SearchTargetsReq req) {
        requirePlayer(playerId);
        return Result.ok(targetSearchService.search(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
