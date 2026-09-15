package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.AppVersionReq;
import com.ironoath.web.dto.generated.AppVersionResp;
import com.ironoath.web.dto.generated.ConfigManifestReq;
import com.ironoath.web.dto.generated.ConfigManifestResp;
import com.ironoath.web.dto.generated.CrashDashboardResp;
import com.ironoath.web.dto.generated.CrashDetailResp;
import com.ironoath.web.dto.generated.CrashListResp;
import com.ironoath.web.dto.generated.CrashReportReq;
import com.ironoath.web.dto.generated.CrashReportResp;
import com.ironoath.web.dto.generated.PayDebtResp;
import com.ironoath.web.dto.generated.TrackBatchReq;
import com.ironoath.web.dto.generated.TrackBatchResp;
import com.ironoath.web.dto.generated.TrackIngestResp;
import com.ironoath.web.ops.OpsTokenGuard;
import com.ironoath.web.service.OpsAppService;
import com.ironoath.web.service.PayAppService;

/**
 * 职责：运维与上线域 HTTP 入口（B16）—— 埋点批量上报、崩溃上报、版本检查、配置热更清单，
 *       外加两个<b>只读</b>运维读数端点（埋点入口健康度、支付负债）。
 * 依赖：Spring Web、{@link OpsAppService}、{@link PayAppService}、{@link OpsTokenGuard}。
 *
 * <p><b>本类有两种身份口径，分界是「读错了会怎样」</b>（沿用 {@code SeasonController} 的那条判据）：
 * <ul>
 *   <li><b>客户端上报入口</b>（track/crash/version/manifest）<b>不要求玩家身份</b>：埋点与崩溃上报必须
 *       能在登录之前发出去，版本检查更是启动的第一个请求。玩家身份在这里是可选头 ——
 *       有就带上（事件能归因到人），没有也不拒绝（「进都没进就走了」那段漏斗还有数据）。</li>
 *   <li><b>运维读数入口</b>（ingest、pay/debt）要求 {@code X-Ops-Token}：它们回的是全服聚合数，
 *       不属于任何一个玩家。</li>
 * </ul>
 *
 * <p><b>为什么运维读数放在这里而不是各自的业务 Controller</b>：{@code /pay/**} 在玩家身份拦截器的
 * 管辖之内，而运维调用方没有玩家身份 —— 放在 {@code PayController} 里的结果是要么运维进不来，
 * 要么为了让他进来在支付链路上开一个身份例外（那是比"少一个端点"严重得多的口子）。
 * {@code /ops/} 本来就在身份链路之外，补一道令牌即可。
 *
 * <p><b>版本检查与清单都是 POST 而不是 GET</b>：两者都需要一个请求体
 * （客户端自报版本 / 客户端手里各表的 hash），用 GET 就得把它们塞进查询串，
 * 而 34 张表的 hash 拼起来的查询串会超过大多数网关的 URL 长度限制。
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final OpsAppService ops;
    private final PayAppService pay;
    private final OpsTokenGuard token;

    public OpsController(OpsAppService ops, PayAppService pay, OpsTokenGuard token) {
        this.ops = ops;
        this.pay = pay;
        this.token = token;
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

    /**
     * 配置热更（B16 §5 / 验收 7）：从磁盘重读全部配置表，校验通过才整体替换。
     * 走运维令牌 —— 热更是能改变全服数值的动作，不能是任何人都能打的端点。
     */
    @PostMapping("/config/reload")
    public Result<com.ironoath.web.dto.generated.ConfigReloadResp> reloadConfig(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        token.require(opsToken);
        return Result.ok(ops.reloadConfigs());
    }

    /**
     * 埋点入口健康度（只读，需运维令牌）：当前软上限、累计截断条数、待落库条数、已写库批次数。
     *
     * <p>与上面那几个 POST 的分界就是「要不要请求体」：这一条什么都不用问就能答，所以是 GET。
     * 它要令牌，因为回的是全服聚合数。
     */
    @GetMapping("/ingest")
    public Result<TrackIngestResp> ingest(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken) {
        token.require(opsToken);
        return Result.ok(ops.ingestHealth());
    }

    /**
     * 崩溃率看板（只读，需运维令牌）：<b>按客户端版本分组</b>的崩溃数、启动数与崩溃率。
     *
     * <p>存在理由是 B16 验收 9 的后半句：崩溃上报的写侧一直通，而读侧（{@code crashOf}、
     * {@code findCrash}、{@code crashCount}）在生产代码里零调用点 —— 「后台能收到」变成了
     * 「只有测试能收到」。见收口清单 #135。
     *
     * @param windowSeconds 统计窗口秒数；不传即查满保留期。这里给默认值等于再造一份口径，
     *                      所以可空交由服务端按 {@code DASHBOARD_RETENTION_DAYS} 决定
     */
    @GetMapping("/crash/dashboard")
    public Result<CrashDashboardResp> crashDashboard(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestParam(name = "windowSeconds", required = false) Integer windowSeconds) {
        token.require(opsToken);
        return Result.ok(ops.crashDashboard(windowSeconds));
    }

    /**
     * 最近崩溃明细（只读，需运维令牌）：<b>不带堆栈</b>，带堆栈长度。
     *
     * <p>为什么列表不给堆栈：一条堆栈最长 20KB（payload 预算），20 条就是 400KB，
     * 一条只读端点会因此变成全仓最大的响应，而且在大面积崩溃时最大 —— 那时最需要它。
     * 先在这里挑出要看的那一条，再用 {@code /crash/detail} 按 traceId 取堆栈。
     */
    @GetMapping("/crash/recent")
    public Result<CrashListResp> recentCrashes(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        token.require(opsToken);
        return Result.ok(ops.recentCrashes(limit));
    }

    /**
     * 单条崩溃的完整记录（只读，需运维令牌）：完整堆栈 + traceId，正是验收 9 要求的那个形状。
     * 查不到回 {@code CRASH_REPORT_NOT_FOUND}，不回 null —— 后者与「有一条堆栈为空的记录」分不开。
     */
    @GetMapping("/crash/detail")
    public Result<CrashDetailResp> crashDetail(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestParam(name = "traceId") String traceId) {
        token.require(opsToken);
        return Result.ok(ops.crashDetail(traceId));
    }

    /**
     * 支付负债（只读，需运维令牌）：钱收了、货没发出去的那批订单。
     *
     * <p>存在理由是 {@code unfulfilledCents()} 与 {@code retryQueue()} 此前生产调用点为零 ——
     * 只读、不改任何订单状态，所以这条端点不新增任何写路径。
     *
     * @param limit 明细最多带几笔，服务端另有上限夹住
     */
    @GetMapping("/pay/debt")
    public Result<PayDebtResp> payDebt(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        token.require(opsToken);
        return Result.ok(pay.debt(limit));
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
