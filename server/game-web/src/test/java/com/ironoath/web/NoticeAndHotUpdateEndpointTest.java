package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
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
 *   <li>① 窗口判定漏了 ⇒ 过期的 {@code notice_expired_sample} 会被端点吐给玩家</li>
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

    @Test
    @DisplayName("公告只给当前生效的：过期的行不返回（负控数据是表里真有的那一行）")
    void noticeListSkipsExpiredRows() {
        var live = notices.list().notices();
        assertThat(live).as("至少有一条长期有效的公告").isNotEmpty();
        assertThat(live).extracting(n -> n.id())
                .as("过期的公告绝不能出现在列表里")
                .doesNotContain("notice_expired_sample");
        assertThat(live).extracting(n -> n.id()).contains("notice_welcome");
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
            if ("notice_expired_sample".equals(item.get("id").asText())) {
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
        assertThat(data.get("version").asInt()).isPositive();
        JsonNode table = JsonUtils.readTree(data.get("json").asText());
        assertThat(table.get("table").asText()).as("整表 JSON 要与本地表同形").isEqualTo("notice");
        assertThat(table.get("rows").isArray()).as("rows 必须是数组").isTrue();
        assertThat(table.get("rows").size()).as("notice 表三行（含那条过期的负控数据）").isEqualTo(3);
    }

    @Test
    @DisplayName("取表内容：白名单外的表（玩法/数值表）一律回「没有这张配置表」")
    void tableEndpointRefusesTablesOutsideTheWhitelist() throws Exception {
        for (String name : new String[] {"global", "stage", "item"}) {
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
        ReleaseGate.Manifest manifest = release.manifest();
        assertThat(manifest.tables()).extracting(ReleaseGate.TableMeta::name)
                .as("清单只列白名单内的表")
                .containsExactly("notice");
    }
}
