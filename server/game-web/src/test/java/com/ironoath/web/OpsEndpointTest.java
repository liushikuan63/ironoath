package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.dto.generated.AppVersionReq;
import com.ironoath.web.dto.generated.ConfigManifestReq;
import com.ironoath.web.dto.generated.CrashReportReq;
import com.ironoath.web.dto.generated.TrackBatchReq;
import com.ironoath.web.dto.generated.TrackEvent;
import com.ironoath.web.ops.OpsTokenGuard;
import com.ironoath.web.ops.TrackEventStore;
import com.ironoath.web.ops.TrackFlusher;
import com.ironoath.web.release.ReleaseRulesAssembler;
import com.ironoath.web.service.OpsAppService;
import com.ironoath.web.store.memory.InMemoryTrackStore;

/**
 * 职责：B16 运维域的端到端验证 —— 验收 3（埋点批量）、7（热更生效）、8（强制更新）、9（崩溃上报带 traceId）。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类盯的四条有一个共同特征：功能测试全绿而线上失效</b>。
 * <ol>
 *   <li>埋点逐条上报在功能测试里完全正常（事件确实到了），只在弱网下把客户端拖垮</li>
 *   <li>热更若按版本号判定，「改了内容忘了升版本」时客户端认为自己是最新的 —— 而验收 7 会过，
 *       因为测试通常是「升了版本再改内容」</li>
 *   <li>强制更新若只返回一个布尔，客户端就只能硬编码文案，而提审期文案被要求改时只能发版</li>
 *   <li>崩溃上报若不带 traceId，后台收到的是一堆匿名堆栈，
 *       而线上排查的第一步永远是「这个玩家当时在做什么」</li>
 * </ol>
 *
 * <p><b>埋点断言的是「攒批」而不是「到达」</b>：只断言事件到了库里的话，
 * 逐条上报的实现也会全绿 —— 而那正是 B16 禁止项写了两遍的东西。
 * 所以这里显式断言：不足一批时库里是空的，条目还在缓冲区里。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsEndpointTest {

    private static final String TRACK_URL = "/ops/track/batch";
    private static final String CRASH_URL = "/ops/crash";
    private static final String VERSION_URL = "/ops/app/version";
    private static final String MANIFEST_URL = "/ops/config/manifest";
    private static final String RELOAD_URL = "/ops/config/reload";
    private static final String PLAYER_HEADER = "X-Player-Id";
    /** 与 {@code application-test.yml} 的 {@code ironoath.ops.token} 一致（SeasonSettleAuthTest 同源）。 */
    private static final String OPS_TOKEN = "test-ops-token";
    private static final String TRACE_HEADER = "X-Trace-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private OpsAppService ops;
    @Autowired private TrackFlusher flusher;
    @Autowired private TrackEventStore store;
    @Autowired private ReleaseRulesAssembler assembler;

    @BeforeEach
    void resetStores() {
        // 先冲刷再清空：反过来的话上一个用例残留的缓冲区会在本用例里落库，
        // 于是「不足一批时库里是空的」这条断言会被上一个用例的事件污染
        flusher.flushNow("用例切换");
        ((InMemoryTrackStore) store).clear();
    }

    // ---------- 验收 3：埋点批量 ----------

    @Test
    @DisplayName("验收3：不足一批时事件停在缓冲区里，库里一条都没有（逐条上报的实现会让这条断言变红）")
    void eventsAreBufferedUntilBatchIsFull() throws Exception {
        int maxBatch = (int) configs.longParam("TRACK_BATCH_MAX_SIZE");

        JsonNode data = postTrack(events(maxBatch - 1), "p1");
        assertThat(data.get("accepted").asInt()).isEqualTo(maxBatch - 1);
        assertThat(data.get("failed").asInt()).isZero();
        assertThat(store.eventCount()).as("还差一条才攒满，不该落库").isZero();
        assertThat(flusher.pendingCount()).isEqualTo(maxBatch - 1);

        // 补上最后一条 ⇒ 攒满即落库
        postTrack(events(1), "p1");
        assertThat(store.eventCount()).as("攒满 %d 条触发一次批量写入", maxBatch).isEqualTo(maxBatch);
        assertThat(flusher.pendingCount()).isZero();
    }

    @Test
    @DisplayName("验收3：攒不满时超过 TRACK_BATCH_FLUSH_SECONDS 也要落库，否则低频玩家的事件永远发不出去")
    void bufferedEventsAreFlushedOnTimeout() throws Exception {
        postTrack(events(2), "p2");
        assertThat(store.eventCount()).isZero();

        long flushMillis = configs.longParam("TRACK_BATCH_FLUSH_SECONDS") * 1000L;
        assertThat(flusher.tick(System.currentTimeMillis() + flushMillis - 1000L))
                .as("还差 1 秒才到窗口").isZero();
        assertThat(store.eventCount()).isZero();

        assertThat(flusher.tick(System.currentTimeMillis() + flushMillis + 1000L))
                .as("超过窗口 ⇒ 冲刷").isEqualTo(2);
        assertThat(store.eventCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("批数远小于事件数：这是「批量在生效」的直接证据")
    void batchCountIsMuchSmallerThanEventCount() throws Exception {
        int maxBatch = (int) configs.longParam("TRACK_BATCH_MAX_SIZE");
        int total = maxBatch * 5;
        // flusher 是单例，批数在整个测试类里累加，所以只能看本次请求带来的增量
        int batchesBefore = flusher.batchCount();
        // 一次性发 total 条：客户端本该分成 5 批，这里故意发一批来验证服务端不会被撑爆
        postTrack(events(total), "p3");
        assertThat(store.eventCount()).isEqualTo(total);
        assertThat(flusher.batchCount() - batchesBefore).as("服务端按 %d 条重新攒批", maxBatch).isEqualTo(5);
    }

    @Test
    @DisplayName("B14：超过入口软上限是截断而不是整批拒，且截断条数在 /ops/ingest 上看得见")
    void oversizedBatchIsTruncatedNotRejected() throws Exception {
        // 从表算而不是写死 100：软上限的定义就是"攒批上限 × 倍数"，写死了改参数会以无意义的方式红
        int softLimit = (int) (configs.longParam("TRACK_BATCH_MAX_SIZE")
                * configs.longParam("TRACK_INGEST_SOFT_LIMIT_FACTOR"));
        int total = softLimit + 30;
        long truncatedBefore = ingestHealth().get("truncatedEvents").asLong();

        JsonNode data = postTrack(events(total), "p-truncate");

        assertThat(data.get("accepted").asInt()).as("保留前面 %d 条（事件按发生顺序排）", softLimit)
                .isEqualTo(softLimit);
        assertThat(data.get("failed").asInt())
                .as("截掉的那 30 条计入 failed，而不是把整批变成一次错误 —— 丢看板数据比来噪音糟")
                .isEqualTo(total - softLimit);
        assertThat(store.eventCount()).as("落库的就是保留下来的那一段").isEqualTo(softLimit);

        JsonNode after = ingestHealth();
        assertThat(after.get("softLimitEvents").asInt())
                .as("当前生效的上限要随响应回出来，不要让运维记住一个数").isEqualTo(softLimit);
        assertThat(after.get("truncatedEvents").asLong() - truncatedBefore)
                .as("截断必须留下可查的累计数，否则「洪水必须可见」只是一句注释").isEqualTo(total - softLimit);
    }

    @Test
    @DisplayName("运维读数要令牌：不带与带错都回 OPS_UNAUTHORIZED，而不是回一个默认值")
    void ingestReadoutsRequireOpsToken() throws Exception {
        assertThat(codeOf(getRoot("/ops/ingest", null)))
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(codeOf(getRoot("/ops/ingest", "")))
                .as("带了个头但值是空的，等同于没带").isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(codeOf(getRoot("/ops/pay/debt", "wrong-token")))
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("部分失败是正常返回：事件名为空的那条计入 failed，同批其它条目照常入库")
    void invalidEventsAreCountedNotRejected() throws Exception {
        List<TrackEvent> batch = new ArrayList<>(events(3));
        batch.add(new TrackEvent(" ", 1000L, Map.of()));
        batch.add(new TrackEvent(null, 1001L, Map.of()));

        JsonNode data = postTrack(batch, "p4");
        assertThat(data.get("accepted").asInt()).isEqualTo(3);
        assertThat(data.get("failed").asInt()).as("两条脏数据").isEqualTo(2);
        flusher.flushNow("断言前冲刷");
        assertThat(store.eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("空批被拒（16000）：一个不含任何事件的请求没有存在的理由")
    void emptyBatchIsRejected() throws Exception {
        JsonNode root = postRoot(TRACK_URL, "p5", new TrackBatchReq(List.of()));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.TRACK_BATCH_EMPTY.code());
    }

    @Test
    @DisplayName("§6 全链路：落库的事件带着本次请求的 traceId，玩家报障时凭响应头就能捞出来")
    void storedEventsCarryTheRequestTraceId() throws Exception {
        MvcResult result = mockMvc.perform(post(TRACK_URL)
                        .header(PLAYER_HEADER, "p6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(new TrackBatchReq(events(1)))))
                .andExpect(status().isOk()).andReturn();
        String headerTrace = result.getResponse().getHeader(TRACE_HEADER);
        assertThat(headerTrace).as("TraceIdFilter 必须回写响应头").isNotBlank();

        flusher.flushNow("断言前冲刷");
        List<TrackEventStore.TrackRecord> records = store.recentOf("p6", 10);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).traceId()).isEqualTo(headerTrace);
        assertThat(records.get(0).playerId()).isEqualTo("p6");
        assertThat(records.get(0).serverTs()).as("留存口径按服务端时间算（铁律 5）").isPositive();
    }

    @Test
    @DisplayName("未登录也能上报：playerId 为空时事件仍然入库，那正是「进都没进就走了」这段漏斗的证据")
    void anonymousEventsAreStillStored() throws Exception {
        JsonNode data = postJson(TRACK_URL, new TrackBatchReq(events(1)));
        assertThat(data.get("accepted").asInt()).isEqualTo(1);
        flusher.flushNow("断言前冲刷");
        assertThat(store.eventCount()).isEqualTo(1);
    }

    // ---------- 验收 9：崩溃上报 ----------

    @Test
    @DisplayName("验收9：后台收到完整堆栈 + traceId，且能按 traceId 捞回来")
    void crashReportIsStoredWithFullStackAndTraceId() throws Exception {
        String stack = "java.lang.NullPointerException: Cannot invoke ...\n\tat com.ironoath.Client.tick(Client.ts:42)";
        CrashReportReq req = new CrashReportReq("trace-abc", "空指针", stack, "1.0.0", "world", 1234L);

        JsonNode data = postJson(CRASH_URL, req);
        assertThat(data.get("accepted").asBoolean()).isTrue();

        TrackEventStore.CrashRecord saved = ops.crashOf("trace-abc");
        assertThat(saved.traceId()).isEqualTo("trace-abc");
        assertThat(saved.stack()).as("验收 9 要求「完整」").isEqualTo(stack);
        assertThat(saved.clientVersion()).isEqualTo("1.0.0");
        assertThat(saved.sceneName()).isEqualTo("world");
        assertThat(saved.serverTs()).isPositive();
    }

    @Test
    @DisplayName("崩溃上报幂等：同一条重复上报（客户端重启后补报）不会变成两条记录")
    void crashReportIsIdempotent() throws Exception {
        CrashReportReq req = new CrashReportReq("trace-dup", "崩溃", "stack", "1.0.0", null, 1L);
        postJson(CRASH_URL, req);
        postJson(CRASH_URL, req);
        assertThat(((InMemoryTrackStore) store).crashCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("超长堆栈被截断时把原长写进内容：否则被截断的堆栈看起来和完整的没有区别")
    void overlongStackIsTruncatedWithOriginalLength() throws Exception {
        int cap = (int) assembler.perfBudget().payloadMaxBytes();
        String huge = "x".repeat(cap + 500);
        postJson(CRASH_URL, new CrashReportReq("trace-huge", "崩溃", huge, "1.0.0", "battle", 1L));

        TrackEventStore.CrashRecord saved = ops.crashOf("trace-huge");
        assertThat(saved.stack()).contains("服务端截断").contains(String.valueOf(cap + 500));
        assertThat(saved.stack().length()).isLessThan(huge.length());
    }

    @Test
    @DisplayName("崩溃上报缺项被拒（16003）：没有 traceId 的堆栈在后台无法与任何一次请求对上")
    void incompleteCrashReportIsRejected() throws Exception {
        JsonNode root = postRoot(CRASH_URL, null,
                new CrashReportReq(" ", "崩溃", "stack", "1.0.0", null, 1L));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.CRASH_REPORT_INCOMPLETE.code());
    }

    // ---------- 验收 8：强制更新与灰度 ----------

    @Test
    @DisplayName("验收8：低于最低可玩版本的客户端收到强制更新，且响应里带着可直接展示的文案")
    void outdatedClientIsForcedToUpdateWithNotice() throws Exception {
        String min = configs.stringParam("RELEASE_MIN_SUPPORTED_VERSION");
        String latest = configs.stringParam("RELEASE_LATEST_VERSION");

        JsonNode old = postJson(VERSION_URL, new AppVersionReq("0.0.1", "p7"));
        assertThat(old.get("forceUpdate").asBoolean()).as("0.0.1 低于最低可玩版本 %s", min).isTrue();
        assertThat(old.get("latest").asText()).isEqualTo(latest);
        assertThat(old.get("notice").asText()).as("文案必须下发，不能硬编码进客户端").isNotBlank();

        JsonNode current = postJson(VERSION_URL, new AppVersionReq(latest, "p7"));
        assertThat(current.get("forceUpdate").asBoolean()).isFalse();
        // 不强制更新时不该打扰玩家。序列化可能省略 null 字段，所以「缺失」与「JSON null」都算合格
        JsonNode notice = current.get("notice");
        assertThat(notice == null || notice.isNull()).as("不该下发文案，实际=%s", current).isTrue();

        // 4. 客服/退款入口：本 profile 没配 WECHAT_SUPPORT_* ⇒ 下发 null，但**字段要出现**，
        // 客户端据此显示入口并说明未配置（藏起来等于提审时「没有这个入口」）。
        // 配了的那条路由 SupportEntryEndpointTest 用它自己的上下文验。
        JsonNode support = current.get("support");
        assertThat(support == null || support.isNull())
                .as("未配置时下发 null（缺失或 JSON null 都算），实际=%s", current).isTrue();

        // 攒批策略必须随版本检查下发：客户端没有配置表加载器，写死就是硬编码，而且会与服务端漂移。
        // 下发的是「秒」而不是毫秒 —— 漏了换算的症状是攒批窗口只有 10 毫秒，也就是禁止项说的逐条上报
        JsonNode policy = current.get("trackPolicy");
        assertThat(policy).as("响应必须带埋点攒批策略").isNotNull();
        assertThat(policy.get("maxBatchSize").asInt()).isEqualTo((int) configs.longParam("TRACK_BATCH_MAX_SIZE"));
        assertThat(policy.get("flushSeconds").asInt()).isEqualTo((int) configs.longParam("TRACK_BATCH_FLUSH_SECONDS"));
    }

    @Test
    @DisplayName("灰度与强制更新正交：未登录的人不进灰度（灰度批次里的崩溃必须能归因到具体玩家）")
    void grayExcludesAnonymousAndIsIndependentOfForceUpdate() throws Exception {
        JsonNode anonymous = postJson(VERSION_URL, new AppVersionReq("0.0.1", null));
        assertThat(anonymous.get("forceUpdate").asBoolean()).isTrue();
        assertThat(anonymous.get("grayEnabled").asBoolean()).as("未登录不进灰度").isFalse();

        // 同一个玩家的灰度判定必须每次一致，否则他刷新一次就可能从灰度里掉出去
        boolean first = postJson(VERSION_URL, new AppVersionReq("1.0.0", "stable-player")).get("grayEnabled").asBoolean();
        for (int i = 0; i < 20; i++) {
            assertThat(postJson(VERSION_URL, new AppVersionReq("1.0.0", "stable-player")).get("grayEnabled").asBoolean())
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("缺少客户端版本号被拒（16004）：不知道版本就无法判断要不要强制更新")
    void missingClientVersionIsRejected() throws Exception {
        JsonNode root = postRoot(VERSION_URL, null, new AppVersionReq(" ", "p8"));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RELEASE_VERSION_MISSING.code());
    }

    // ---------- 验收 7：配置热更 ----------

    @Test
    @DisplayName("配置热更：带令牌可调，文件没动过时如实回答「一张都没换」")
    void configReloadReportsWhatItActuallyChanged() throws Exception {
        // 仓库里的表在这条用例里没被改动，所以 changed 必然是空数组 —— 那也是一个要如实说出来的结果
        // （它是「我改的表到底是不是这张」的复核手段），不是错误。
        // 真正的「改了文件就生效」由 ConfigRegistryReloadTest 在临时目录副本上验。
        JsonNode data = okData(postWithOpsToken(RELOAD_URL, OPS_TOKEN));

        assertThat(data.get("changed")).as("没有文件改动就该是空数组：%s", data).isEmpty();
        assertThat(data.get("version").asText()).as("要报出热更后 live 的清单版本").isNotBlank();
    }

    @Test
    @DisplayName("配置热更没令牌一律拒绝：那是能改变全服数值的动作")
    void configReloadRefusesWithoutToken() throws Exception {
        assertThat(codeOf(postWithOpsToken(RELOAD_URL, null)))
                .as("热更能改全服数值，不能是任何人都能打的端点")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("验收7：客户端 hash 与服务端一致时不需要更新；差一张或 hash 不同就要拉新表")
    void manifestReportsOutdatedTablesByHash() throws Exception {
        JsonNode full = postJson(MANIFEST_URL, new ConfigManifestReq(Map.of()));
        assertThat(full.get("version").asText()).isEqualTo(configs.fingerprint());
        JsonNode tables = full.get("tables");
        assertThat(tables.size()).as("清单覆盖全部已加载的表").isEqualTo(configs.tableNames().size());
        assertThat(full.get("outdated").size()).as("客户端一张表都没有 ⇒ 全都要拉").isEqualTo(tables.size());

        // 用服务端下发的 hash 回填 ⇒ 无需更新
        Map<String, String> upToDate = new HashMap<>();
        for (JsonNode meta : tables) {
            upToDate.put(meta.get("name").asText(), meta.get("hash").asText());
        }
        JsonNode none = postJson(MANIFEST_URL, new ConfigManifestReq(upToDate));
        assertThat(none.get("outdated").size()).isZero();

        // 只把 global 的 hash 改掉一位 ⇒ 只有 global 需要更新
        upToDate.put("global", upToDate.get("global") + "-stale");
        JsonNode one = postJson(MANIFEST_URL, new ConfigManifestReq(upToDate));
        assertThat(one.get("outdated").size()).isEqualTo(1);
        assertThat(one.get("outdated").get(0).asText()).isEqualTo("global");
    }

    @Test
    @DisplayName("清单里的版本号与 global 表一致，且每张表都有 hash（缺 hash 的表只能靠版本号判定，那是事故来源）")
    void manifestCarriesVersionAndHashPerTable() throws Exception {
        JsonNode full = postJson(MANIFEST_URL, new ConfigManifestReq(Map.of()));
        for (JsonNode meta : full.get("tables")) {
            String name = meta.get("name").asText();
            assertThat(meta.get("hash").asText()).as("表 %s 缺 hash", name).isNotBlank();
            assertThat(meta.get("version").asText()).as("表 %s 缺版本号", name).isNotBlank();
            if ("global".equals(name)) {
                assertThat(meta.get("version").asText()).isEqualTo(String.valueOf(configs.rawTable("global").version()));
            }
        }
    }

    @Test
    @DisplayName("热更范围与 B16 §5 要求的三类一致：配置表、引导脚本、活动")
    void hotUpdateScopeCoversTheThreeRequiredCategories() {
        assertThat(assembler.hotUpdateScope()).containsExactlyInAnyOrder("CONFIG", "GUIDE", "ACTIVITY");
    }

    // ---------- 辅助 ----------

    private static List<TrackEvent> events(int count) {
        List<TrackEvent> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new TrackEvent("evt_" + i, 1000L + i, Map.of("index", String.valueOf(i))));
        }
        return out;
    }

    private JsonNode postTrack(List<TrackEvent> events, String playerId) throws Exception {
        return okData(postRoot(TRACK_URL, playerId, new TrackBatchReq(events)));
    }

    private JsonNode postJson(String url, Object req) throws Exception {
        return okData(postRoot(url, null, req));
    }

    private JsonNode postRoot(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req));
        if (playerId != null) {
            builder = builder.header(PLAYER_HEADER, playerId);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }

    /**
     * 读运维读数（带对令牌）。
     *
     * <p>令牌字面量与 {@code application-test.yml} 的 {@code ironoath.ops.token} 必须一致 ——
     * 两处不同步的症状是所有鉴权用例都以同一句"令牌不匹配"失败，看不出是测试坏了还是闸门坏了。
     * 与 {@code SeasonSettleAuthTest} 用的是同一个值。
     */
    private JsonNode ingestHealth() throws Exception {
        return okData(getRoot("/ops/ingest", OPS_TOKEN));
    }

    /** 发一条 POST，带运维令牌；{@code opsToken} 传 null 表示根本不带那个头。 */
    private JsonNode postWithOpsToken(String url, String opsToken) throws Exception {
        var builder = post(url).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (opsToken != null) {
            builder = builder.header(OpsTokenGuard.HEADER, opsToken);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 发一条 GET。{@code opsToken} 传 null 表示根本不带那个头。 */
    private JsonNode getRoot(String url, String opsToken) throws Exception {
        var builder = get(url);
        if (opsToken != null) {
            builder = builder.header(OpsTokenGuard.HEADER, opsToken);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int codeOf(JsonNode root) {
        return root.get("code").asInt();
    }
}
