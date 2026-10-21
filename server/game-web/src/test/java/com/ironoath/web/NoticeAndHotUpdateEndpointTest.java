package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NoticeCfg;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.release.ReleaseGate;
import com.ironoath.web.controller.CityController;
import com.ironoath.web.notice.NoticeService;
import com.ironoath.web.release.HotUpdateTableService;
import com.ironoath.web.release.ReleaseRulesAssembler;

/**
 * 收口清单 #2287 ⑤ / V26-1 的判据：运营公告（展示型）+ 客户端热更白名单（**表级**）。
 *
 * <p><b>每条判据都能失败</b>：
 * <ul>
 *   <li>① 窗口判定漏了 ⇒ 测试夹具里的 probe_expired 会被端点吐给玩家</li>
 *   <li>② 白名单校验漏了 ⇒ 玩法表 {@code global} 会被公开下载（那正是「客户端不抄配置表」的红线）</li>
 *   <li>③ manifest 没按白名单过滤 ⇒ 清单里会出现取不到内容的表名</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class NoticeAndHotUpdateEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private NoticeService notices;
    @Autowired private HotUpdateTableService tables;
    @Autowired private ReleaseRulesAssembler release;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService time;

    @BeforeEach
    void installIsolatedNoticeRows() {
        install(time.serverNow(), "测试公告正文");
    }

    @AfterEach
    void restoreRepositoryNoticeTable() throws Exception {
        configs.reload("notice", NoticeCfg.class, Files.readString(configFile("notice.json")));
    }

    @Test
    @DisplayName("公告只给当前生效的：过期夹具行不返回")
    void noticeListSkipsExpiredRows() {
        var live = notices.list().notices();
        assertThat(live).hasSize(2);
        assertThat(live).extracting(n -> n.id())
                .as("过期的公告绝不能出现在列表里")
                .doesNotContain("probe_expired");
        assertThat(live).extracting(n -> n.id()).containsExactly("probe_high", "probe_low");
    }

    @Test
    @DisplayName("公告的排序由服务端算：priority 数字大者在前（客户端不重排）")
    void noticeListIsSortedByPriorityDescending() {
        var live = notices.list().notices();
        for (int i = 1; i < live.size(); i++) {
            assertThat(live.get(i - 1).priority())
                    .as("第 %d 条的 priority 不得小于第 %d 条", i, i - 1)
                    .isGreaterThanOrEqualTo(live.get(i).priority());
        }
    }

    @Test
    @DisplayName("端点：GET /notice/list 返回生效公告，且不吐内部字段 why")
    void noticeEndpointServesLiveRows() throws Exception {
        String body = mockMvc.perform(get("/notice/list")
                        .header(CityController.PLAYER_HEADER, "p1")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JsonUtils.readTree(body);
        assertThat(root.get("code").asInt()).as("业务码必须为 0，实际：" + body).isZero();
        JsonNode list = root.get("data").get("notices");
        assertThat(list.isArray()).as("notices 必须是数组").isTrue();
        for (JsonNode item : list) {
            assertThat(item.has("why")).as("why 是给改表的人看的内部说明，不下发").isFalse();
        }
        boolean sawExpired = false;
        for (JsonNode item : list) {
            if ("probe_expired".equals(item.get("id").asText())) {
                sawExpired = true;
            }
        }
        assertThat(sawExpired).as("过期的公告不能被端点吐出来").isFalse();
    }

    @Test
    @DisplayName("取表内容：白名单内的表给整表 JSON 与版本号")
    void tableEndpointServesWhitelistedTable() throws Exception {
        String body = mockMvc.perform(get("/config/table")
                        .param("name", "notice")
                        .header(CityController.PLAYER_HEADER, "p1")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JsonUtils.readTree(body);
        assertThat(root.get("code").asInt()).as("业务码必须为 0，实际：" + body).isZero();
        JsonNode data = root.get("data");
        assertThat(data.get("name").asText()).isEqualTo("notice");
        assertThat(data.get("version").asInt()).isEqualTo(2);
        assertThat(data.get("hash").asText()).isEqualTo(release.clientManifest().table("notice").hash());
        JsonNode table = JsonUtils.readTree(data.get("json").asText());
        assertThat(table.get("table").asText()).as("整表 JSON 要与本地表同形").isEqualTo("notice");
        assertThat(table.get("rows").isArray()).as("rows 必须是数组").isTrue();
        assertThat(table.get("rows").size()).as("三条只在测试夹具中的公告").isEqualTo(3);
        for (JsonNode row : table.get("rows")) {
            assertThat(row.has("why")).as("内部文档字段不能进入客户端下载内容").isFalse();
        }
    }

    @Test
    @DisplayName("取表内容：白名单外的表（玩法/数值表）一律回「没有这张配置表」")
    void tableEndpointRefusesTablesOutsideTheWhitelist() throws Exception {
        for (String name : new String[] {"global", "stage", "item", "missing"}) {
            String body = mockMvc.perform(get("/config/table")
                            .param("name", name)
                            .header(CityController.PLAYER_HEADER, "p1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode root = JsonUtils.readTree(body);
            assertThat(root.get("code").asInt())
                    .as("表 " + name + " 不在白名单里，必须被拒")
                    .isEqualTo(ErrorCode.CONFIG_NOT_FOUND.code());
        }
    }

    @Test
    @DisplayName("manifest 只列白名单内的表（客户端知道表名却取不到内容 ⇒ 两边口径打架）")
    void manifestOnlyListsWhitelistedTables() {
        ReleaseGate.Manifest manifest = release.clientManifest();
        assertThat(manifest.tables()).extracting(ReleaseGate.TableMeta::name)
                .as("清单只列白名单内的表")
                .containsExactly("notice");
        assertThat(release.manifest().tables()).hasSize(configs.tableNames().size());
    }

    @Test
    @DisplayName("出厂公告为空：不发布虚构活动权益，客户端拿空数组而不是占位文案")
    void emptyDefaultIsValid() throws Exception {
        configs.reload("notice", NoticeCfg.class, Files.readString(configFile("notice.json")));
        assertThat(notices.list().notices()).isEmpty();
        JsonNode table = JsonUtils.readTree(tables.table("notice").json());
        assertThat(table.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("同版本改文案也必须换 hash；错窗口与误填 global 在热更前拒并保留旧配置")
    void contentHashAndReloadGuardsFailClosed() throws Exception {
        long now = time.serverNow();
        String before = tables.table("notice").hash();
        String version = release.clientManifest().manifestVersion();
        install(now, "新文案");
        assertThat(tables.table("notice").version()).isEqualTo(2);
        assertThat(tables.table("notice").hash()).isNotEqualTo(before);
        assertThat(release.clientManifest().manifestVersion()).isNotEqualTo(version);
        String currentHash = tables.table("notice").hash();

        String invalidWindow = noticeJson(now, "坏窗口")
                .replace("\"endAt\":" + (now + 600_000), "\"endAt\":" + (now - 20_000));
        assertThatThrownBy(() -> configs.reload("notice", NoticeCfg.class, invalidWindow))
                .isInstanceOf(ConfigException.class).hasMessageContaining("公告生效窗口无效");
        assertThat(tables.table("notice").hash()).isEqualTo(currentHash);

        ObjectNode global = (ObjectNode) JsonUtils.readTree(Files.readString(configFile("global.json")));
        for (JsonNode row : global.get("rows")) {
            if ("HOT_UPDATE_TABLES".equals(row.get("id").asText())) {
                ((ObjectNode) row).put("value", "global");
            }
        }
        assertThatThrownBy(() -> configs.reload("global", GlobalCfg.class, JsonUtils.toJson(global)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非展示表");
        assertThat(configs.stringParam("HOT_UPDATE_TABLES")).isEqualTo("notice");
        assertThat(release.clientManifest().tables()).extracting(ReleaseGate.TableMeta::name)
                .containsExactly("notice");
    }

    private void install(long now, String body) {
        configs.reload("notice", NoticeCfg.class, noticeJson(now, body));
    }

    private static String noticeJson(long now, String body) {
        return """
                {"table":"notice","version":2,"comment":"V26 测试夹具",
                 "fieldTypes":{"id":"STRING_KEY","title":"STRING","body":"STRING",
                               "startAt":"LONG_NONNEG","endAt":"LONG_NONNEG",
                               "priority":"LONG_NONNEG","why":"STRING"},
                 "rows":[
                   {"id":"probe_low","title":"低","body":"%s","startAt":%d,"endAt":%d,"priority":1,"why":"夹具"},
                   {"id":"probe_high","title":"高","body":"高优先级","startAt":%d,"endAt":%d,"priority":5,"why":"夹具"},
                   {"id":"probe_expired","title":"旧","body":"过期","startAt":%d,"endAt":%d,"priority":9,"why":"夹具"}
                 ]}
                """.formatted(body,
                now - 10_000, now + 600_000,
                now - 10_000, now + 600_000,
                now - 20_000, now - 1);
    }

    private static Path configFile(String name) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            Path file = cursor.resolve("contract/config").resolve(name);
            if (Files.isRegularFile(file)) return file;
        }
        throw new IllegalStateException("找不到 contract/config/" + name);
    }
}
