package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.web.dto.generated.AppVersionReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 客服/退款入口配置随版本响应下发（上线检查清单 §二 8/9）。
 *
 * <p><b>本类单独起一个上下文，就是为了把环境变量真的设进去</b>：这条链路的价值全在
 * "配了会下发、没配下发 null"，而在默认 test profile 里两者都只能验到后者。
 * 用属性覆盖来验前者，比在单测里手工 new 一遍装配器更接近真实 —— 出问题的位置
 * 恰好会落在"环境变量读没读到"这一段上，而那一段只有真装配才会经过。
 *
 * <p>两个上下文各验一半：这里验"配了"，{@code OpsEndpointTest} 里那条验"没配时下发 null"。
 */
@SpringBootTest(properties = {
    "WECHAT_SUPPORT_CORP_ID=corp-from-env",
    "WECHAT_SUPPORT_URL=https://work.weixin.qq.com/kf/from-env",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportEntryEndpointTest {

    private static final String VERSION_URL = "/ops/app/version";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("配了 WECHAT_SUPPORT_* ⇒ 版本响应里带上客服入口配置，客户端据此打开客服")
    void configuredSupportIsHandedToTheClient() throws Exception {
        JsonNode root = postJson(VERSION_URL, new AppVersionReq("1.0.0", null));

        assertThat(root.path("code").asInt()).as(root.toString()).isZero();
        JsonNode support = root.get("data").get("support");
        assertThat(support).as("配了就必须下发，否则设置页永远是「未配置」：" + root.get("data")).isNotNull();
        assertThat(support.get("corpId").asText()).isEqualTo("corp-from-env");
        assertThat(support.get("url").asText()).isEqualTo("https://work.weixin.qq.com/kf/from-env");
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        String text = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonUtils.readTree(text);
    }
}
