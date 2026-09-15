package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
import com.ironoath.web.dto.generated.CrashDashboardResp;
import com.ironoath.web.dto.generated.CrashDetailResp;
import com.ironoath.web.dto.generated.CrashListItem;
import com.ironoath.web.dto.generated.CrashListResp;
import com.ironoath.web.dto.generated.CrashReportReq;
import com.ironoath.web.dto.generated.CrashReportResp;
import com.ironoath.web.dto.generated.CrashVersionRow;
import com.ironoath.web.dto.generated.TableMeta;
import com.ironoath.web.dto.generated.TrackBatchReq;
import com.ironoath.web.dto.generated.TrackBatchResp;
import com.ironoath.web.dto.generated.TrackEvent;
import com.ironoath.web.dto.generated.TrackIngestResp;
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
    /** 客服/退款入口的部署配置（环境变量来的）。未配置时下发 null，客户端照常显示入口。 */
    private final com.ironoath.web.release.SupportConfig supportConfig;
    /** 配置热更要动的就是这一个实例（消费方持的是同一个引用，换内部引用才让所有人生效）。 */
    private final com.ironoath.config.ConfigRegistry configs;
    /** 配置目录：热更从磁盘重读，所以必须知道启动时读的是哪个目录。 */
    private final com.ironoath.web.config.GameProperties properties;
    /**
     * 本进程启动以来被入口软上限截断掉的事件累计条数。
     *
     * <p>与 {@code SocialPushPublisher.dropped} 同一形状：进程内 AtomicLong + 一个只读出口，
     * 不引 Micrometer、不起定时器（B00 禁 @Scheduled）。出口是 {@code GET /ops/ingest} ——
     * <b>计数没有出口就等于没有计数</b>，那正是 {@code unfulfilledCents()} 此前的处境。
     */
    private final AtomicLong truncatedEvents = new AtomicLong();
    /**
     * 客户端自报的丢弃批数累计（B16 §四 看板的「埋点丢弃数」的客户端那一半）。
     * **与 truncatedEvents 分开计**：一个是"服务端截了多少条"，一个是"客户端在弱网下丢了多少批"，
     * 合成一个数之后没人说得清该去找服务端的容量问题还是客户端的网络问题。
     */
    private final AtomicLong clientDroppedBatches = new AtomicLong();

    public OpsAppService(ReleaseRulesAssembler assembler, TrackFlusher flusher,
                         TrackEventStore store, TimeService timeService,
                         com.ironoath.web.release.SupportConfig supportConfig,
                         com.ironoath.config.ConfigRegistry configs,
                         com.ironoath.web.config.GameProperties properties) {
        this.assembler = assembler;
        this.flusher = flusher;
        this.store = store;
        this.timeService = timeService;
        this.supportConfig = supportConfig;
        this.configs = configs;
        this.properties = properties;
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
        // 软上限：超了就截断，不整批拒。一次战斗本身就产生十几个事件，
        // 拿攒批上限当门槛会把真实战斗事件整批丢掉 —— 丢看板数据比来噪音糟，但洪水必须可见。
        int softLimit = assembler.trackIngestSoftLimit();
        // 客户端自报的丢弃数先记下来：无论这一批收了多少，客户端"丢过"这件事都已经发生了。
        // 缺失读作 0（旧客户端不带这个字段），不做校验失败 —— 一个统计读数不该把上报打回
        long dropped = req.droppedBatches() == null ? 0L : Math.max(0L, req.droppedBatches());
        if (dropped > 0L) {
            long total = clientDroppedBatches.addAndGet(dropped);
            LOG.warn("客户端上报丢弃了 {} 批埋点（累计 {} 批）：弱网下重投缓冲被打满，看板上的漏斗会缺这一段",
                    dropped, total);
        }

        int truncated = 0;
        List<TrackEvent> events = req.events();
        if (events.size() > softLimit) {
            truncated = events.size() - softLimit;
            events = events.subList(0, softLimit);
            long total = truncatedEvents.addAndGet(truncated);
            LOG.warn("埋点入口软上限生效：本批 {} 条，保留 {} 条、截断 {} 条（上限=TRACK_BATCH_MAX_SIZE × "
                            + "TRACK_INGEST_SOFT_LIMIT_FACTOR），traceId={}，累计已截断 {} 条："
                            + "非零只有两种解释 —— 有客户端的攒批策略与本服不同步，或有人在直接刷这个"
                            + "不要求身份的端点，两种都需要人去看",
                    req.events().size(), softLimit, truncated, traceId, total);
        }
        int accepted = 0;
        int failed = truncated;
        for (TrackEvent event : events) {
            if (event == null || event.name() == null || event.name().isBlank()) {
                // 没有名字的事件在分析侧无法归类，却已经占了上报配额，所以直接丢
                failed++;
                continue;
            }
            flusher.offer(new TrackEventStore.TrackRecord(event.name(), playerId, event.ts(), serverNow,
                    traceId, event.params()));
            accepted++;
        }
        if (failed > truncated) {
            LOG.warn("本批埋点丢弃 {} 条（事件名为空），traceId={}：客户端某处调用了没有事件名的上报，"
                    + "这类调用点会静默地一直失败，需要按 traceId 去客户端日志里找",
                    failed - truncated, traceId);
        }
        return new TrackBatchResp(accepted, failed);
    }

    /**
     * 埋点入口的健康度（只读，供 {@code GET /ops/ingest} 用）。
     *
     * <p><b>这一存在的理由是「计数必须有出口」</b>：{@code truncatedEvents} 如果只有测试能读，
     * 它就和此前零调用点的 {@code unfulfilledCents()} 是同一族问题 —— 机制在，没人看。
     * {@code pendingEvents} 与 {@code flushedBatches} 一并带出，是为了让验收 3 的
     * 「批数远小于事件数」变成一个能被查的事实而不是一个断言。
     */
    public TrackIngestResp ingestHealth() {
        return new TrackIngestResp(assembler.trackIngestSoftLimit(), truncatedEvents.get(),
                (int) clientDroppedBatches.get(), flusher.pendingCount(), flusher.batchCount());
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
                .orElseThrow(() -> new BizException(ErrorCode.CRASH_REPORT_NOT_FOUND,
                        "未找到 traceId=" + traceId + " 的崩溃记录"));
    }

    // ---------- 崩溃率看板（B16 §六，收口清单 #135） ----------

    /**
     * 窗口下限（秒）。比这更短的窗口连一次崩溃都装不进来，回一个全零的表比回一个错误更坏 ——
     * 运维会以为「这一分钟很稳」，而实际上什么都没查。
     */
    private static final int MIN_WINDOW_SECONDS = 60;
    /** 明细列表最多带几条。与 {@code PayAppService.DEBT_LIST_MAX} 同一条理由：只读端点也要封顶。 */
    private static final int CRASH_LIST_MAX = 50;
    /**
     * 崩溃率的分母事件名。
     *
     * <p><b>服务端不枚举事件名</b>（字典的唯一归属在客户端 {@code TrackEvents.ts}，
     * 见 {@code ops.schema.json} 里 TrackEvent.name 的说明），所以这一条是一个常量而不是枚举。
     * 它必须是 startup 而不是 login：崩溃发生在登录之前时 login 一条都没有，
     * 那种崩溃会从分母与分子里同时消失，而「进都没进就崩了」恰是最该看见的一类。
     */
    private static final String STARTUP_EVENT = "startup";

    /**
     * 按<b>客户端版本</b>分组的崩溃率（B16 §六 第一条）。
     *
     * <p><b>刻意不回一个全服总崩溃率</b>：灰度只放 5% 时，这一批里崩溃率翻三倍而总量几乎不动，
     * 「灰度看起来很安全」就是这么来的。总量在这里不是粗一点的数，是会误导决策的数。
     *
     * <p><b>除法只在这一处做</b>（铁律 1）：分子是分版本聚合，分母是同一窗口内
     * {@code startup} 事件按 {@code clientVersion} 分组的条数，两者都按 serverTs 落窗（铁律 5）。
     * 分母为 0 时崩溃率是 null 而不是 0 —— 「一次启动都没收到」与「启动了但零崩溃」
     * 必须长得不一样，否则一个没有任何数据的版本会显示成全服最健康的那个。
     *
     * @param windowSeconds 请求的窗口秒数；<b>null 表示查满保留期</b>（默认值不写在控制器里，
     *                      否则「看板查多久」就有两份真相）。给了数值才夹进
     *                      [{@link #MIN_WINDOW_SECONDS}, 保留期最大值]，夹过就 WARN，
     *                      因为「以为查的是 30 天而实际只查了 60 秒」是静默的错
     */
    public CrashDashboardResp crashDashboard(Integer windowSeconds) {
        int max = retentionWindowSeconds();
        int window = windowSeconds == null ? max
                : Math.max(MIN_WINDOW_SECONDS, Math.min(windowSeconds, max));
        if (windowSeconds != null && window != windowSeconds) {
            LOG.warn("崩溃率看板窗口被夹：请求 {} 秒 → 实际 {} 秒（下限 {}、上限=保留期最大值 {} 秒，"
                    + "上限之外的数据已被清理，给更大的窗口只会算出一张「零崩溃」的假表）",
                    windowSeconds, window, MIN_WINDOW_SECONDS, max);
        }
        long since = timeService.serverNow() - window * 1000L;
        Map<String, Long> crashes = store.crashCountByVersion(since);
        Map<String, Long> startups = store.countEventsByParam(since, STARTUP_EVENT, "clientVersion");
        java.util.Set<String> versions = new java.util.TreeSet<>(crashes.keySet());
        versions.addAll(startups.keySet());
        List<CrashVersionRow> rows = new ArrayList<>(versions.size());
        for (String version : versions) {
            long crash = crashes.getOrDefault(version, 0L);
            long startup = startups.getOrDefault(version, 0L);
            rows.add(new CrashVersionRow(version, (int) crash, (int) startup,
                    startup == 0 ? null : (double) crash / startup));
        }
        rows.sort(java.util.Comparator.comparingInt(CrashVersionRow::crashes).reversed()
                .thenComparing(CrashVersionRow::clientVersion));
        return new CrashDashboardResp(window, max, List.copyOf(rows));
    }

    /**
     * 最近的崩溃明细（<b>不带堆栈</b>，理由见 {@code CrashListItem} 的契约说明）。
     *
     * <p>total 与 listed 分开回：合并成一个数，运维就会以为看到的就是全部。
     */
    public CrashListResp recentCrashes(int limit) {
        int capped = Math.max(1, Math.min(limit, CRASH_LIST_MAX));
        List<TrackEventStore.CrashRecord> records = store.recentCrashes(capped);
        List<CrashListItem> items = new ArrayList<>(records.size());
        for (TrackEventStore.CrashRecord record : records) {
            items.add(new CrashListItem(record.traceId(), record.clientVersion(), record.message(),
                    record.sceneName(), record.clientTs(), record.serverTs(),
                    record.stack() == null ? 0 : record.stack().length()));
        }
        return new CrashListResp(store.crashCount(), items.size(), List.copyOf(items));
    }

    /** 按 traceId 取一条崩溃的完整记录（含堆栈）。找不到抛 {@link ErrorCode#CRASH_REPORT_NOT_FOUND}。 */
    public CrashDetailResp crashDetail(String traceId) {
        TrackEventStore.CrashRecord record = crashOf(traceId);
        return new CrashDetailResp(record.traceId(), record.clientVersion(), record.message(),
                record.sceneName(), record.clientTs(), record.serverTs(), record.stack());
    }

    /**
     * 看板窗口的上限 = {@code global.DASHBOARD_RETENTION_DAYS} 的最大值换算的秒数。
     *
     * <p><b>不是我另定的一个数</b>：比保留期更早的事件与崩溃已被 {@code TrackFlusher} 清掉，
     * 允许查那么远只会得到一张零崩溃的表 —— 那正是清库动作最坏的症状：看起来像没事。
     */
    private int retentionWindowSeconds() {
        int maxDays = 0;
        for (int days : assembler.retentionDays()) {
            maxDays = Math.max(maxDays, days);
        }
        return maxDays * 86_400;
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
                verdict.notice(), policy, supportConfig.toEntry());
    }

    // ---------- 配置热更（B16 §5，验收 7） ----------

    /**
     * 全量热更配置表：从磁盘重读一遍，校验通过才整体替换。<b>不重启即生效</b>。
     *
     * <p><b>判定"哪几张变了"用内容 hash，不用版本号</b>：改了内容忘了升版本时版本号看不出来，
     * 而 hash 看得出来（这是 {@code TableMeta} 的既定口径）。hash 由
     * {@link ReleaseRulesAssembler#manifest()} 算，这里前后各取一次 —— 另写一份比对
     * 等于给同一个事实造第二个家，两份迟早不一致。
     *
     * <p><b>每次热更都留逐参数的运营日志</b>（{@code 上线检查清单.md} §二 12：概率不得暗改，
     * 变更要能回答"谁、何时、从多少改到多少"）。表级"哪张变了"回答不了这个问题，
     * 而"有一条日志"和"有一条能答上监管问题的日志"是两回事。
     *
     * <p><b>失败方向</b>：任一张表校验失败就抛（由全局异常处理成 5xx），<b>旧表原样保留</b>。
     * 热更最坏的结果不是"没热上"，而是"热了一半"—— 那会留下一份没人设计过的表组合。
     *
     * @param actor 调用方自报的操作人。可空 —— 运维令牌是全服共享的一个字符串，不区分是谁，
     *              所以这里如实记成「未提供」而不是假装认得出人（真要认人得接部署侧的审计身份）
     */
    public com.ironoath.web.dto.generated.ConfigReloadResp reloadConfigs(String actor) {
        java.util.Map<String, String> before = hashByName();
        java.util.Map<String, com.ironoath.config.RawConfigTable> tablesBefore = snapshotTables();
        try {
            configs.reloadAllFromDirectory(java.nio.file.Path.of(properties.configDir()));
        } catch (RuntimeException e) {
            LOG.error("配置热更失败，已保留旧版本（服务照常跑）：{}", e.toString());
            throw e;
        }
        java.util.Map<String, String> after = hashByName();
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : after.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) {
                changed.add(e.getKey());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                changed.add(name);
            }
        }
        // WARN 级：热更是运维动作，日志要能和"谁在什么时候改了哪张表"对上
        LOG.warn("配置热更完成：操作人={} 清单版本={} 内容变更表={}（空表示文件没动过或改回了原样）",
                actor == null || actor.isBlank() ? "未提供" : actor.trim(),
                assembler.manifest().manifestVersion(), changed);
        for (com.ironoath.config.ConfigChangeAudit.TableChanges changes
                : com.ironoath.config.ConfigChangeAudit.between(tablesBefore, snapshotTables(),
                before, after)) {
            LOG.warn("配置热更明细：操作人={} {}",
                    actor == null || actor.isBlank() ? "未提供" : actor.trim(), changes.format(AUDIT_CAP));
        }
        return new com.ironoath.web.dto.generated.ConfigReloadResp(
                assembler.manifest().manifestVersion(), java.util.List.copyOf(changed));
    }

    /** 一行审计日志最多列几处明细。超出会在行尾写明"另有 N 处未列出"，总数始终在行首。 */
    private static final int AUDIT_CAP = 20;

    /** 当前 live 的全部表快照，供热更前后比对（reload 是原地换引用，所以必须在换之前取）。 */
    private java.util.Map<String, com.ironoath.config.RawConfigTable> snapshotTables() {
        java.util.Map<String, com.ironoath.config.RawConfigTable> out = new java.util.LinkedHashMap<>();
        for (String name : configs.tableNames()) {
            out.put(name, configs.rawTable(name));
        }
        return out;
    }

    /** 表名 → 内容 hash，取自配置清单（唯一的 hash 实现）。 */
    private java.util.Map<String, String> hashByName() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (com.ironoath.core.release.ReleaseGate.TableMeta meta : assembler.manifest().tables()) {
            out.put(meta.name(), meta.hash());
        }
        return out;
    }

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
