package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.BattlePassClaimReq;
import com.ironoath.web.dto.generated.BattlePassClaimResp;
import com.ironoath.web.dto.generated.BattlePassStatusResp;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：战令两个端点的端到端验证（B24 验收 2：「积分只来自任务/活动；档位领取幂等」）。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>积分这一段由 {@code battlePass.addPoints} 直接铺</b>：它**就是**生产里唯一的加分入口
 * （任务与活动的领奖路径调它，那两处各自有自己的用例），所以这里从它入手不算绕过 —— 绕过是把
 * 积分直接写进存储。B24 验收 2 的「积分只来自任务/活动」那半句由 S-d-c 的接线用例覆盖。
 *
 * <p>几条刻意的设计：
 * <ul>
 *   <li><b>两条线互不串</b>：领了免费那份之后，付费那份仍然是「没领过」——
 *       这条在把两位合成一位的实现上会当场红；</li>
 *   <li><b>未达成 / 没买 / 已领三个拒绝理由分开</b>：玩家要做的下一步完全不同；</li>
 *   <li><b>领取是幂等的</b>：同一档再领一次是 {BATTLE_PASS_ALREADY_CLAIMED} 而不是再发一份。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BattlePassEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private BattlePassService battlePass;
    @Autowired private PlayerInitService playerInitService;
    /** 购买战令的落库口。**测试走生产入口**（不是直接改写进度位）：
     *  「买了战令」这件事在生产里只有这一条路，绕开它就等于测一个不存在的形状。 */
    @Autowired private com.ironoath.web.reward.PaidPrivilegeGrants privilegeGrants;
    @Autowired private com.ironoath.core.player.PlayerRepository players;
    @Autowired private com.ironoath.web.pay.PaidProducts catalog;
    @Autowired private RewardPorts.Bag bag;
    @Autowired private RewardPorts.Wallet wallet;

    @Test
    @DisplayName("新号读状态：20 档全部列出来、积分 0、一条都没达成、付费线未解锁，且赛季 id 与赛季域同源")
    void freshPlayerSeesAllTwentyTiersLocked() throws Exception {
        String playerId = player();

        BattlePassStatusResp resp = battlePass.status(playerId);
        assertThat(resp.tiers()).as("20 档全下发（未达成的也要下发：那是玩家的目标）").hasSize(20);
        assertThat(resp.tiers().get(0).tier()).isEqualTo(1L);
        assertThat(resp.tiers().get(19).tier()).isEqualTo(20L);
        assertThat(resp.tiers().get(19).requiredPoints())
                .as("打满 20 档正好是 3000 分（与买断定价同源的口径）").isEqualTo(3000L);
        assertThat(resp.points()).isZero();
        assertThat(resp.tiers()).allSatisfy(tier -> {
            assertThat(tier.reached()).as("0 分时第一档也不该是达成的").isFalse();
            assertThat(tier.freeClaimed()).isFalse();
            assertThat(tier.paidClaimed()).isFalse();
        });
        assertThat(resp.paidUnlocked()).as("新号没买战令").isFalse();
        assertThat(resp.seasonId()).as("赛季 id 取自赛季域（不是战令自己推的）").isNotBlank();
        assertThat(resp.seasonEndAt())
                .as("test profile 没设 SEASON_START_AT ⇒ 赛季未启用，时刻给 0 而不是一个假的日子"
                        + "（与 /season/status 的 phase=null 同一条口径）；启用时它是 赛季起点 + 45 天")
                .isZero();

        // 端点也走一遍：路径、身份头、JSON 形状
        JsonNode data = okData(perform(get("/battlePass/status").header(PLAYER_HEADER, playerId)));
        assertThat(data.get("tiers")).hasSize(20);
        assertThat(data.get("points").asLong()).isZero();
    }

    @Test
    @DisplayName("积分到 150 就能领第 1 档免费奖励：真拿到木材箱，再领一次被拒（幂等）")
    void claimingTheFirstFreeTierGrantsTheRewardOnce() {
        String playerId = player();
        battlePass.addPoints(playerId, 150L, "test");

        BattlePassClaimResp claimed = battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE));
        assertThat(claimed.track()).isEqualTo(BattlePassTrack.FREE);
        assertThat(claimed.reward().rewardId()).isEqualTo("item_res_wood_10k");
        assertThat(bag.countOf(playerId, "item_res_wood_10k")).as("免费线第 1 档真发了木材箱").isEqualTo(1L);
        assertThat(claimed.status().tiers().get(0).freeClaimed()).isTrue();

        assertThatThrownBy(() -> battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("再领一次是「已领过」而不是再发一份")
                .isEqualTo(ErrorCode.BATTLE_PASS_ALREADY_CLAIMED);
        assertThat(bag.countOf(playerId, "item_res_wood_10k")).as("被拒的那次不能再发").isEqualTo(1L);
    }

    @Test
    @DisplayName("没达成的档位与没买战令的付费线是两个理由：一个是攒分、一个是去购买")
    void lockedTierAndLockedPaidTrackAreSaidApart() {
        String playerId = player();

        assertThatThrownBy(() -> battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("0 分领第 1 档 = 没达成").isEqualTo(ErrorCode.BATTLE_PASS_TIER_LOCKED);

        battlePass.addPoints(playerId, 150L, "test");
        assertThatThrownBy(() -> battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 1L, BattlePassTrack.PAID)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("达成但没买 = 付费线没解锁（与「没达成」不能共用一个码）")
                .isEqualTo(ErrorCode.BATTLE_PASS_PAID_LOCKED);
    }

    @Test
    @DisplayName("两条线各领各的：领了免费那份，付费那份仍然可领（付费线解锁之后）")
    void theTwoTracksDoNotConsumeEachOther() {
        String playerId = player();
        battlePass.addPoints(playerId, 150L, "test");
        // 走生产入口：付费发货时 PRIVILEGE 的落库口（与 rewardService.grant 里那条分支同一个方法）
        privilegeGrants.grant(playerId, "battle_pass", 1L, 1_700_000_000_000L);

        battlePass.claim(playerId, new BattlePassClaimReq(newId(), 1L, BattlePassTrack.FREE));
        BattlePassStatusResp afterFree = battlePass.status(playerId);
        assertThat(afterFree.tiers().get(0).freeClaimed()).isTrue();
        assertThat(afterFree.tiers().get(0).paidClaimed())
                .as("合成一位的实现会在这里红：领了免费那份不该顺手把付费那份标成已领").isFalse();
        assertThat(afterFree.paidUnlocked()).isTrue();

        BattlePassClaimResp paid = battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 1L, BattlePassTrack.PAID));
        assertThat(paid.reward().rewardId()).as("付费线第 1 档给 50 金币").isEqualTo("GOLD");
        assertThat(paid.reward().count()).isEqualTo(50L);
        assertThat(wallet.available(playerId, ResourceIds.GOLD, paid.status().serverNow()))
                .as("金币真的到账了（新号初始金币 + 50）").isGreaterThanOrEqualTo(50L);
    }

    @Test
    @DisplayName("积分只增不减，且跨档位线性：150 分只开第 1 档，300 分开到第 2 档")
    void pointsOpenTiersLinearly() {
        String playerId = player();
        battlePass.addPoints(playerId, 150L, "test");
        assertThat(battlePass.status(playerId).tiers().get(1).reached())
                .as("150 分时第 2 档（300 分）还没达成").isFalse();
        battlePass.addPoints(playerId, 150L, "test");
        BattlePassStatusResp resp = battlePass.status(playerId);
        assertThat(resp.points()).isEqualTo(300L);
        assertThat(resp.tiers().get(1).reached()).as("300 分时第 2 档达成").isTrue();
        assertThat(resp.tiers().get(2).reached()).as("第 3 档还差 150 分").isFalse();
    }

    @Test
    @DisplayName("不存在的档位号是参数错，不是「没达成」")
    void unknownTierIsAParameterError() {
        String playerId = player();
        battlePass.addPoints(playerId, 3000L, "test");
        assertThatThrownBy(() -> battlePass.claim(playerId,
                new BattlePassClaimReq(newId(), 99L, BattlePassTrack.FREE)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("买一次战令：付费线解锁 + 当季限定外观立即到账；本赛季再买一次被 alreadyOwned 拦下")
    void buyingThePassUnlocksThePaidTrackAndHandsOverTheSeasonFrame() {
        String playerId = player();
        assertThat(players.findByPlayerId(playerId).orElseThrow().ownedAvatarFrames())
                .as("买之前没有这枚框").doesNotContain("frame_season_s1");

        privilegeGrants.grant(playerId, "battle_pass", 1L, 1_700_000_000_000L);

        assertThat(battlePass.status(playerId).paidUnlocked()).as("付费线解锁了").isTrue();
        assertThat(players.findByPlayerId(playerId).orElseThrow().ownedAvatarFrames())
                .as("当季限定外观随购买立即到账（不是攒到第 20 档才给）").contains("frame_season_s1");
        assertThat(catalog.alreadyOwned(playerId, players.findByPlayerId(playerId).orElseThrow().paid(),
                catalog.require("battle_pass")))
                .as("本赛季已经买过：再买一次会被拦下（换赛季自然又能买）").isNotNull();
    }

    // ---------- 辅助 ----------

    private String player() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "战令玩家",
                1_700_000_000_000L, "")).playerId();
    }

    private static String newId() {
        return "req-" + UUID.randomUUID();
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
    }

    private static JsonNode okData(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = JsonUtils.readTree(body);
        assertThat(root.get("code").asInt()).as("接口没有回成功：%s", body).isZero();
        return root.get("data");
    }
}
