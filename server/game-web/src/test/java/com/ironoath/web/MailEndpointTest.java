package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.BizException;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.MailClaimAllReq;
import com.ironoath.web.dto.generated.MailClaimAllResp;
import com.ironoath.web.dto.generated.MailListResp;
import com.ironoath.web.dto.generated.MailReadReq;
import com.ironoath.web.dto.generated.MailView;
import com.ironoath.web.dto.generated.OpsMailSendReq;
import com.ironoath.web.dto.generated.OpsMailSendResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.mail.MailStore.MailRecord;
import com.ironoath.web.mail.MailStore.RewardLine;
import com.ironoath.web.mail.MailAppService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：邮件系统（B12 §2）的端到端用例 —— 验收 5（一键领 50 封只发 1 次请求、失败保留并提示）
 *      与验收 8（30 天过期且不误删未过期），外加运营补发这一条「凭空给东西」的通路。
 * 依赖：真实容器（内存存储），只有 HTTP 层用 MockMvc。
 *
 * <p><b>为什么用真发放器而不是假端口</b>：邮件的全部意义在于「附件真的能变成玩家的东西」。
 * 把 {@code RewardService} 换成 mock 的话，本文件所有断言退化成「状态翻了一格」，
 * 而那正是这一族最擅长的假绿（收口清单 #98 的假发放同形）。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class MailEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MailAppService mails;
    @Autowired private MailStore store;
    @Autowired private RewardPorts.Mailbox mailbox;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private InventoryRepository inventories;
    @Autowired private TimeService time;
    @Autowired private ConfigRegistry configs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        store.clear();
    }

    // ---------- 验收 5 ----------

    @Test
    @DisplayName("验收 5：50 封带附件的邮件，一次请求全部领到，且金币真的入账")
    void claimAllDrainsFiftyMailsInOneCall() {
        String playerId = newPlayer();
        seedMails(playerId, 50, 10L);
        long before = goldOf(playerId);
        assertThat(mails.list(playerId).mails()).as("先确认 50 封都在").hasSize(50);

        MailClaimAllResp resp = mails.claimAll(playerId, new MailClaimAllReq(newId()));

        assertThat(resp.claimed()).as("一封请求领到 50 封（逐个领的实现需要 50 次调用）")
                .isEqualTo(50);
        assertThat(resp.failed()).as("没有一封失败").isEmpty();
        assertThat(goldOf(playerId)).as("50 × 10 金币必须真的到账")
                .isEqualTo(before + 500L);
        assertThat(resp.rewards()).as("明细按 type+id 聚合成一条，而不是 50 条重复")
                .singleElement()
                .satisfies(line -> assertThat(line.count()).isEqualTo(500L));
        assertThat(mails.claimAll(playerId, new MailClaimAllReq(newId())).claimed())
                .as("再点一次没有东西可领").isZero();
    }

    @Test
    @DisplayName("验收 5 后半句：领不上的邮件必须留在列表里，并带着人看得懂的原因回来")
    void failedMailStaysAndCarriesAReason() {
        String playerId = newPlayer();
        long before = goldOf(playerId);
        store.save(mail(playerId, "好的那封", List.of(new RewardLine("RESOURCE", "GOLD", 7L, "金币"))));
        store.save(mail(playerId, "坏的那封", List.of(new RewardLine("ITEM", "item_不存在", 3L, "不存在道具"))));

        MailClaimAllResp resp = mails.claimAll(playerId, new MailClaimAllReq(newId()));

        assertThat(resp.claimed()).as("好的那封领上了").isEqualTo(1);
        assertThat(goldOf(playerId)).isEqualTo(before + 7L);
        assertThat(resp.failed()).as("坏的那封不能静默消失").singleElement()
                .satisfies(f -> {
                    assertThat(f.mailId()).isNotBlank();
                    assertThat(f.reason()).as("只回一个 id 玩家不知道该做什么（这就是契约里 failed 收成对象的原因）")
                            .isNotBlank();
                });

        MailListResp after = mails.list(playerId);
        assertThat(after.mails()).as("两封都还在（失败的没被吞，成功的留作记录）").hasSize(2);
        assertThat(byId(after, resp.failed().get(0).mailId()).claimed())
                .as("失败那封必须翻回未领，下一次一键领取还会再试").isFalse();
        assertThat(byId(after, resp.failed().get(0).mailId()).rewards())
                .as("附件不许因为领失败就被改成 0 —— 玩家要看得见自己少了什么").hasSize(1);
    }

    // ---------- 验收 8 ----------

    @Test
    @DisplayName("验收 8：过期邮件被清掉，未过期的一封都不许少（边界含整点那一条）")
    void expiredMailsPurgeWithoutTouchingTheRest() {
        String playerId = newPlayer();
        long now = time.serverNow();
        long day = 86_400_000L;
        store.save(mailAt(playerId, "昨天过期", now - day, now - 1L));
        store.save(mailAt(playerId, "上一毫秒正好到期", now - 2 * day, now));
        store.save(mailAt(playerId, "还剩一天", now - day, now + day));
        store.save(mailAt(playerId, "还剩两天", now, now + 2 * day));

        assertThat(mails.list(playerId).mails()).as("只有两条没过期，且新的在前（createdAt 倒序）")
                .extracting(MailView::title).containsExactly("还剩两天", "还剩一天");
        assertThat(store.count()).as("读一次就把过期的清掉（惰性，不跑定时器）").isEqualTo(2);
        assertThat(mails.list(playerId).mails()).as("没被误删的两封下次读还在").hasSize(2);
    }

    @Test
    @DisplayName("保留天数只住在一个地方：邮件的 expireAt 由 MAIL_RETENTION_DAYS 算出来")
    void retentionComesFromTheTable() {
        String playerId = newPlayer();
        OpsMailSendResp sent = mails.sendByOps(new OpsMailSendReq(newId(), playerId,
                "补偿", "这是一笔补偿", List.of(), "工单-1"));
        long expectedDays = configs.longParam("MAIL_RETENTION_DAYS");

        MailRecord saved = store.findById(playerId, sent.mailId()).orElseThrow();
        assertThat(saved.expireAt() - saved.createdAt())
                .as("表里改这个数，邮件就跟着变；写死在代码里就会出现两处口径")
                .isEqualTo(expectedDays * 86_400_000L);
        assertThat(sent.expireAt()).isEqualTo(saved.expireAt());
    }

    // ---------- 已读与红点 ----------

    @Test
    @DisplayName("标已读只动未读计数；没这一封回自己的码，与「没附件可领」不共用")
    void readUpdatesUnreadOnly() {
        String playerId = newPlayer();
        store.save(mail(playerId, "第一封", List.of(new RewardLine("RESOURCE", "GOLD", 5L, "金币"))));
        store.save(mail(playerId, "第二封", List.of()));
        assertThat(mails.list(playerId).unreadCount()).as("两封都没读").isEqualTo(2);
        assertThat(mails.list(playerId).claimedCount())
                .as("纯通知那封算「没有可领」，但没读过的它仍算未读 —— 两件事各一格")
                .isEqualTo(1);

        String target = mails.list(playerId).mails().get(0).mailId();
        assertThat(mails.markRead(playerId, new MailReadReq(newId(), target)).unreadCount())
                .isEqualTo(1);
        assertThat(mails.markRead(playerId, new MailReadReq(newId(), target)).unreadCount())
                .as("重复标同一封不会再减（幂等）").isEqualTo(1);
        assertThat(store.findById(playerId, target).orElseThrow().readAt()).isNotNull();
        assertThat(mails.hasUnread(playerId)).as("还剩一封未读，红点必须亮").isTrue();
    }

    // ---------- 恰好一次 ----------

    @Test
    @DisplayName("同一 requestId 重投被拒且不会重复发奖；换 id 再点也领不到第二次")
    void claimHappensExactlyOnce() {
        String playerId = newPlayer();
        seedMails(playerId, 3, 20L);
        long before = goldOf(playerId);
        String requestId = newId();

        assertThat(mails.claimAll(playerId, new MailClaimAllReq(requestId)).claimed()).isEqualTo(3);
        assertThat(goldOf(playerId)).isEqualTo(before + 60L);

        assertBizCode(1002, () -> mails.claimAll(playerId, new MailClaimAllReq(requestId)));
        assertThat(mails.claimAll(playerId, new MailClaimAllReq(newId())).claimed())
                .as("换个 requestId 也已经领过了").isZero();
        assertThat(goldOf(playerId)).as("金币总数没有再动").isEqualTo(before + 60L);
    }

    // ---------- 运营补发 ----------

    @Test
    @DisplayName("补发端点：没令牌一律拒绝（回的是「能给任意玩家发东西」的能力）")
    void opsSendRefusesWithoutToken() throws Exception {
        assertThat(codeOf(postJson("/ops/mail/send", new OpsMailSendReq(newId(), "P-x",
                "t", "正文", List.of(), "工单-2"), null)))
                .isEqualTo(1009);
    }

    @Test
    @DisplayName("补发：附件类型不认识就拒（不许等到玩家领取时才发现发不出去）、缺 requestId 拒、重投拒")
    void opsSendValidatesBeforeItWrites() {
        String playerId = newPlayer();

        assertBizCode(1001, () -> mails.sendByOps(new OpsMailSendReq(newId(), playerId,
                "补偿", "正文", List.of(mailReward("NOT_A_TYPE", "GOLD", 1L)), "工单-3")));
        assertThat(store.count()).as("被拒的这一次不该留下任何邮件").isZero();

        String requestId = newId();
        mails.sendByOps(new OpsMailSendReq(requestId, playerId, "补偿", "正文",
                List.of(mailReward("RESOURCE", "GOLD", 1L)), "工单-4"));
        assertBizCode(1002, () -> mails.sendByOps(new OpsMailSendReq(requestId, playerId,
                "补偿", "正文", List.of(mailReward("RESOURCE", "GOLD", 1L)), "工单-4")));
        assertThat(store.count()).isEqualTo(1);

        assertBizCode(1003, () -> mails.sendByOps(new OpsMailSendReq("  ", playerId,
                "补偿", "正文", List.of(), "工单-5")));
    }

    @Test
    @DisplayName("补发回执上的那个 mailId，玩家侧查得回来（旧实现给的是一个查不到的序号）")
    void opsMailIdIsQueryableByThePlayer() throws Exception {
        String playerId = newPlayer();
        JsonNode root = postJson("/ops/mail/send", new OpsMailSendReq(newId(), playerId,
                "客服补偿", "给您补一次损失", List.of(mailReward("RESOURCE", "GOLD", 30L)), "工单-6"),
                "test-ops-token");

        assertThat(root.get("code").asInt()).as("响应=%s", root).isZero();
        String mailId = root.get("data").get("mailId").asText();
        assertThat(mailId).as("回执要能当凭据用").startsWith("mail_").isNotEqualTo("mail_overflow_1");

        MailListResp inbox = mails.list(playerId);
        assertThat(inbox.mails()).extracting(MailView::mailId).contains(mailId);
        assertThat(byId(inbox, mailId).rewards()).singleElement()
                .satisfies(r -> assertThat(r.count()).isEqualTo(30L));
        assertThat(mails.list(playerId).mails()).as("令牌与玩家身份互不影响：收件人读得到")
                .isNotEmpty();
    }

    // ---------- 溢出这一路（B04 验收 2 落到真邮件上） ----------

    @Test
    @DisplayName("发奖溢出转邮件：正文写明每一条是什么、多少、多少天内领，而且这个 id 查得回来")
    void overflowMailNamesEveryItem() {
        String playerId = newPlayer();

        String mailId = mailbox.sendOverflow(playerId,
                List.of(new RewardItem(RewardType.RESOURCE, "GOLD", 123L),
                        new RewardItem(RewardType.ITEM, "item_chest_hero", 2L)),
                RewardContext.toMail("battle", "report-77", "trace-77"));

        MailRecord mail = store.findById(playerId, mailId).orElseThrow();
        assertThat(mail.kind()).isEqualTo("OVERFLOW");
        assertThat(mail.text()).as("B04 验收 2：数量与原因都要在正文里")
                .contains("123").contains("金币").contains("背包")
                .contains(String.valueOf(configs.longParam("MAIL_RETENTION_DAYS")));
        assertThat(mail.sourceRef()).isEqualTo("battle:report-77");
        assertThat(mail.rewards()).hasSize(2);
        assertThat(mails.list(playerId).mails()).as("这封邮件在玩家面板里查得到").hasSize(1);
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(newId(), "dev-" + UUID.randomUUID(),
                "邮件测试", 1_700_000_000_000L, "")).playerId();
    }

    private static String newId() {
        return "req-" + UUID.randomUUID();
    }

    private void seedMails(String playerId, int howMany, long goldEach) {
        for (int i = 0; i < howMany; i++) {
            store.save(mail(playerId, "第 " + i + " 封",
                    List.of(new RewardLine("RESOURCE", "GOLD", goldEach, "金币"))));
        }
    }

    private static MailRecord mail(String playerId, String title, List<RewardLine> rewards) {
        long now = System.currentTimeMillis();
        return mailAt(playerId, title, now, now + 30L * 86_400_000L, rewards);
    }

    private static MailRecord mailAt(String playerId, String title, long createdAt, long expireAt) {
        return mailAt(playerId, title, createdAt, expireAt,
                List.of(new RewardLine("RESOURCE", "GOLD", 1L, "金币")));
    }

    private static MailRecord mailAt(String playerId, String title, long createdAt, long expireAt,
                                     List<RewardLine> rewards) {
        return new MailRecord("mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                playerId, "SYSTEM", title, title + " 的正文", rewards, "test:" + title,
                createdAt, expireAt, null, null);
    }

    private static MailView byId(MailListResp resp, String mailId) {
        return resp.mails().stream().filter(m -> m.mailId().equals(mailId)).findFirst().orElseThrow();
    }

    private long goldOf(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        return save.resource("GOLD").current();
    }

    private static com.ironoath.web.dto.generated.MailReward mailReward(String type, String id, long count) {
        return new com.ironoath.web.dto.generated.MailReward(type, id, count, id);
    }

    /** 业务码断言：邮件这一档要区分「重复请求」「参数不对」「没令牌」，所以按码而不是按异常类型断。 */
    private static void assertBizCode(int expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .as("业务码应为 %d", expected)
                .isEqualTo(expected);
    }

    private static int codeOf(JsonNode root) {
        return root.get("code").asInt();
    }

    private JsonNode postJson(String url, Object body, String opsToken) throws Exception {
        var builder = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(body));
        if (opsToken != null) {
            builder = builder.header("X-Ops-Token", opsToken);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
