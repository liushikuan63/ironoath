package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.AppVersionReq;
import com.ironoath.web.dto.generated.AppVersionResp;
import com.ironoath.web.dto.generated.ConfigManifestReq;
import com.ironoath.web.dto.generated.ConfigManifestResp;
import com.ironoath.web.dto.generated.CrashReportReq;
import com.ironoath.web.dto.generated.CrashReportResp;
import com.ironoath.web.dto.generated.TrackBatchReq;
import com.ironoath.web.dto.generated.TrackBatchResp;
import com.ironoath.web.service.OpsAppService;

/**
 * 职责：运维与上线域 HTTP 入口（B16）—— 埋点批量上报、崩溃上报、版本检查、配置热更清单。
 * 依赖：Spring Web、{@link OpsAppService}。
 *
 * <p><b>四个端点全都不要求玩家身份</b>：埋点与崩溃上报必须能在登录之前发出去，
 * 版本检查更是启动的第一个请求。玩家身份在这里是一个<b>可选头</b> ——
 * 有就带上（于是事件能归因到人），没有也不拒绝（于是「进都没进就走了」这一段漏斗还有数据）。
 * 这与其它所有 Controller 的口径刻意不同：那些接口没有身份就无法工作，这些接口没有身份仍然有价值。
 *
 * <p><b>版本检查与清单都是 POST 而不是 GET</b>：两者都需要一个请求体
 * （客户端自报版本 / 客户端手里各表的 hash），用 GET 就得把它们塞进查询串，
 * 而 34 张表的 hash 拼起来的查询串会超过大多数网关的 URL 长度限制。
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final OpsAppService ops;

    public OpsController(OpsAppService ops) {
        this.ops = ops;
    }

    /**
     * 埋点批量上报（B16 §3：客户端攒够 TRACK_BATCH_MAX_SIZE 条或超过 TRACK_BATCH_FLUSH_SECONDS 秒触发）。
     *
     * <p><b>部分失败也返回 200</b>：非法条目计入响应的 failed，合法条目照常入库。
     * 一批里有一条脏数据就让整批 4xx，等于用一条脏数据换掉九条好数据。
     */
    @PostMapping("/track/batch")
    public Result<TrackBatchResp> trackBatch(
            @RequestHeader(name = CityController.PLAYER_HEADER, required = false) String playerId,
            @RequestBody TrackBatchReq req) {
        return Result.ok(ops.trackBatch(req, playerId));
    }

    /** 崩溃上报（B16 §6，验收 9：后台能收到完整堆栈 + traceId）。 */
    @PostMapping("/crash")
    public Result<CrashReportResp> crash(@RequestBody CrashReportReq req) {
        return Result.ok(ops.reportCrash(req));
    }

    /** 版本检查（验收 8：低版本客户端收到强制更新提示且无法进入游戏）。 */
    @PostMapping("/app/version")
    public Result<AppVersionResp> appVersion(@RequestBody AppVersionReq req) {
        return Result.ok(ops.checkVersion(req));
    }

    /** 配置清单与需要更新的表（验收 7：改配置不改包生效）。 */
    @PostMapping("/config/manifest")
    public Result<ConfigManifestResp> configManifest(@RequestBody ConfigManifestReq req) {
        return Result.ok(ops.configManifest(req));
    }
}
