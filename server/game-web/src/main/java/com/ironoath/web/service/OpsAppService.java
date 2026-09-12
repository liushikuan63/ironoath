package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.release.ReleaseGate;
import com.ironoath.web.dto.generated.AppVersionReq;
import com.ironoath.web.dto.generated.AppVersionResp;
import com.ironoath.web.dto.generated.ConfigManifestReq;
import com.ironoath.web.dto.generated.ConfigManifestResp;
import com.ironoath.web.dto.generated.CrashReportReq;
import com.ironoath.web.dto.generated.CrashReportResp;
import com.ironoath.web.dto.generated.TableMeta;
import com.ironoath.web.dto.generated.TrackBatchReq;
import com.ironoath.web.dto.generated.TrackBatchResp;
import com.ironoath.web.dto.generated.TrackEvent;
import com.ironoath.web.dto.generated.TrackPolicy;
import com.ironoath.web.ops.TrackEventStore;
import com.ironoath.web.ops.TrackFlusher;
import com.ironoath.web.release.ReleaseRulesAssembler;

/**
 * 职责：B16 运维域应用服务 —— 埋点落库、崩溃上报、版本检查与灰度、配置热更清单。
 * 依赖：{@link ReleaseRulesAssembler}、{@link TrackFlusher}、{@link TrackEventStore}、TimeService。
 *
 * <p><b>本域全部接口都不校验身份、不改游戏状态</b>：埋点与崩溃上报必须能在登录之前、
 * 甚至在存档已经损坏的情况下也能发出去。若把它们挂在需要玩家身份的链路上，
 * 那么「进不去游戏」这类最需要上报的故障恰恰上报不了。
 *
 * <p><b>埋点的部分失败是正常返回而不是异常</b>：一批里有一条事件名为空就让整批 4xx，
 * 等于用一条脏数据换掉九条好数据。所以非法条目计入 {@code failed}，合法条目照常入库 ——
 * 只有「整批都不该存在」（空批）才抛错误码。
 *
 * <p><b>客户端时间戳只用于同批内排序</b>：留存、时长、跨天全部按服务端时间算（铁律 5）。
 * 客户端时钟能被玩家改，能被改的时间就不能作为口径 ——
 * 而 D1 留存如果按客户端时间算，玩家把时间往后调一天就能「刷」出一次留存。
 */
@Service
public class OpsAppService {

    private static final Logger LOG = LoggerFactory.getLogger(OpsAppService.class);

    private final ReleaseRulesAssembler assembler;
    private final TrackFlusher flusher;
    private final TrackEventStore store;
    private final TimeService timeService;

    public OpsAppService(ReleaseRulesAssembler assembler, TrackFlusher flusher,
                         TrackEventStore store, TimeService timeService) {
        this.assembler = assembler;
        this.flusher = flusher;
        this.store = store;
        this.timeService = timeService;
    }

    // ---------- 埋点（B16 §3，验收 3） ----------

    /**
     * 收下一批埋点。
     *
     * @param playerId 可空：启动、登录前的事件还没有玩家身份，而那批事件恰恰是
     *                 「进都没进就走了」这一段漏斗的全部证据
     */
    public TrackBatchResp trackBatch(TrackBatchReq req, String playerId) {
        if (req == null || req.events() == null || req.events().isEmpty()) {
            throw new BizException(ErrorCode.TRACK_BATCH_EMPTY, "events 不得为空");
        }
        long serverNow = timeService.serverNow();
        String traceId = TraceContext.traceId();
        int accepted = 0;
        int failed = 0;
        for (TrackEvent event : req.events()) {
            if (event == null || event.name() == null || event.name().isBlank()) {
                // 没有名字的事件在分析侧无法归类，却已经占了上报配额，所以直接丢
                failed++;
                continue;
            }
            flusher.offer(new TrackEventStore.TrackRecord(event.name(), playerId, event.ts(), serverNow,
                    traceId, event.params()));
            accepted++;
        }
        if (failed > 0) {
            LOG.warn("本批埋点丢弃 {} 条（事件名为空），traceId={}：客户端某处调用了没有事件名的上报，"
                    + "这类调用点会静默地一直失败，需要按 traceId 去客户端日志里找", failed, traceId);
        }
        return new TrackBatchResp(accepted, failed);
    }

    /** 某玩家最近的事件（排查用）。limit 由调用方给，禁止全量返回。 */
    public List<TrackEventStore.TrackRecord> recentEvents(String playerId, int limit) {
        return store.recentOf(playerId, limit);
    }

    // ---------- 崩溃上报（B16 §6，验收 9） ----------

    /**
     * 收下一条崩溃上报。<b>同步落库</b>，不进攒批缓冲区：
     * 崩溃是低频事件，而它是线上唯一能解释「玩家为什么再也进不来」的证据。
     * 让证据在缓冲区里等 10 秒，等于在进程被杀时把它一起丢掉 ——
     * 而客户端崩溃后紧接着的往往就是进程退出。
     */
    public CrashReportResp reportCrash(CrashReportReq req) {
        if (req == null || isBlank(req.traceId()) || isBlank(req.message()) || isBlank(req.stack())
                || isBlank(req.clientVersion())) {
            throw new BizException(ErrorCode.CRASH_REPORT_INCOMPLETE,
                    "traceId / message / stack / clientVersion 四项都不得为空");
        }
        // 客户端本应在发送前就把堆栈压进 payload 预算（见 ops 协议 CrashReportReq.stack 的说明），
        // 这一层是针对「客户端没照做」的兜底。截断时把原长写进落库内容 ——
        // 否则一条被截断的堆栈在后台看起来和一条完整的没有区别，而缺失的往往正是最深的那一帧。
        int stackCap = (int) assembler.perfBudget().payloadMaxBytes();
        String stack = req.stack().length() > stackCap
                ? req.stack().substring(0, stackCap) + "...(服务端截断，原长 " + req.stack().length() + " 字符)"
                : req.stack();
        boolean fresh = store.saveCrash(new TrackEventStore.CrashRecord(
                req.traceId(), req.message(), stack, req.clientVersion(), req.sceneName(),
                req.ts(), timeService.serverNow()));
        if (fresh) {
            // 用 error 级别：崩溃上报必须能在日志告警里被看见，而不是埋在 info 流里
            LOG.error("收到客户端崩溃上报 traceId={} version={} scene={} message={}",
                    req.traceId(), req.clientVersion(), req.sceneName(), req.message());
        }
        return new CrashReportResp(true);
    }

    /** 按 traceId 取崩溃记录（验收 9：后台能收到完整堆栈 + traceId）。 */
    public TrackEventStore.CrashRecord crashOf(String traceId) {
        return store.findCrash(traceId)
                .orElseThrow(() -> new BizException(ErrorCode.CRASH_REPORT_INCOMPLETE,
                        "未找到 traceId=" + traceId + " 的崩溃记录"));
    }

    // ---------- 版本检查与灰度（B16 §5，验收 8） ----------

    /**
     * 检查客户端版本。<b>每次都现装配规则</b>：灰度比例与强制更新文案必须能热更 ——
     * 线上出事时把灰度从 20% 调回 0% 这件事，等一次发版再做是没有意义的。
     */
    public AppVersionResp checkVersion(AppVersionReq req) {
        if (req == null || isBlank(req.clientVersion())) {
            throw new BizException(ErrorCode.RELEASE_VERSION_MISSING, "clientVersion 不得为空");
        }
        ReleaseGate gate = new ReleaseGate(assembler.gateRules());
        ReleaseGate.Verdict verdict = gate.check(req.clientVersion(), req.playerId());
        // 攒批策略随版本检查一起下发：客户端没有配置表加载器，这两个数字若不来自服务端就只能写死，
        // 而写死的策略会与服务端悄悄漂移（症状是「客户端发 50 条，服务端按 10 条攒批」，谁都没报错）
        TrackPolicy policy = new TrackPolicy(assembler.trackBatchMaxSize(), assembler.trackFlushSeconds());
        return new AppVersionResp(verdict.latestVersion(), verdict.forceUpdate(), verdict.grayEnabled(),
                verdict.notice(), policy);
    }

    // ---------- 配置热更（B16 §5，验收 7） ----------

    /**
     * 返回配置清单与需要更新的表。<b>过期判定由服务端算</b>：
     * 「比 hash 不比版本号」这条规则只能存在一份，
     * 若让客户端自己比，规则就有两份，而其中一份迟早会退化成比版本号。
     */
    public ConfigManifestResp configManifest(ConfigManifestReq req) {
        ReleaseGate.Manifest manifest = assembler.manifest();
        List<String> outdated = ReleaseGate.outdatedTables(manifest,
                req == null ? null : req.tableHashes());
        List<TableMeta> metas = new ArrayList<>(manifest.tables().size());
        for (ReleaseGate.TableMeta meta : manifest.tables()) {
            metas.add(new TableMeta(meta.name(), meta.version(), meta.hash()));
        }
        return new ConfigManifestResp(manifest.manifestVersion(), List.copyOf(metas), List.copyOf(outdated));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
