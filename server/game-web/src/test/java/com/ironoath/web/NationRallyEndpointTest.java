package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.social.Rally;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationRallyReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：V22-a 国家层集结的服务端通路 —— 发起、加入、列表、政策读口，四条都真跑。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类盯的核心是「界面上亮着的数与服务端夹出来的数是同一个数」</b>。国家层的人数上限
 * 不是一个常量而是一个折叠值（{@code min(配置上限, 本国实有人数)}），V24 还要在这个点上继续叠
 * 科技与已购永久格。所以这里的断言全部<b>先读 {@code /rally/policy} 拿 cap、再拿 cap±1 去打写口</b>，
 * 一条都不写死 49/50 —— 写死数字的用例在 V24 落地那天会变成一批假红，把"上限会抬"这件事
 * 钉成"上限坏了"。
 *
 * <p><b>与 {@code RallyEndpointTest} 的一处夹具差异</b>：那一族把集结目标选成发起人自己的城
 * （圈层校验要一个真实目标，而自己打自己的战力比恰是 1.0）；国家层<b>不能</b>这样选，
 * 因为攻击闸门按「同国联盟不得互相攻击」把本国目标判掉了（理由见 {@link #request(int)}）。
 * 本类改用野怪格 —— 空地与野怪不构成 PVP，直接放行，而组织流程与"打谁"无关。
 *
 * <p><b>三条硬关各有一条点名的用例</b>（它们都是"不报错但功能不存在"的形状，全靠现跑才抓得到）：
 * ① {@code requireMembership} 的 NATION 支原先写死 false ⇒ {@link #memberCanJoinTheNationalRally}；
 * ② {@code preparingRallies} 原先只有小队与联盟两支 ⇒ {@link #listShowsTheNationalRally}；
 * ③ {@code initiateRally} 原先两分支分派 ⇒ {@link #nationalRallyUsesTheNationalRulesNotTheAllianceOnes}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NationRallyEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String UNIT = "unit_infantry_t1";
    /** 集结目标格：世界 512×512 里的一处野怪格，与 {@code BotSocialRaidTest} 用的是同一处。 */
    private static final int TARGET_X = 400;
    private static final int TARGET_Y = 400;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private com.ironoath.web.service.SocialAppService social;
    @Autowired private SocialStore socialStore;
    @Autowired private com.ironoath.web.nation.NationStore nationStore;
    @Autowired private com.ironoath.web.service.PowerRefreshService powerRefreshService;

    /** 联盟名/标签与国名的序号：同一轮里要建好几个组织，重名会在 NAME_TAKEN 上报错而测不到逻辑。 */
    private int seq;

    /** 撞锁注入器（见 {@link #injectConflicts}）；null 表示这一支用例没换过 store 字段。 */
    private SaveRallyConflicts conflicts;
    private final List<Runnable> seamRestorers = new ArrayList<>();
    private SocialStore realStore;
    private Object installedProxy;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        nationStore.clear();
        seq = 0;
    }

    /**
     * 还原 {@code SocialAppService.store}。这个 Spring 上下文在同一台 JVM 里被别的用例类共用，
     * 没还原等于给下一支笔留一颗地雷（它拿到的 store 会 randomly 撞锁）。
     * 断言写在还原之后：还原失败必须当场红，而不是留到下一个用例以"看不懂的红"的形式出现。
     */
    @AfterEach
    void restoreSettlingSeam() throws Exception {
        for (Runnable restore : seamRestorers) { restore.run(); }
        seamRestorers.clear();
        if (conflicts == null) {
            return;
        }
        storeField().set(social, realStore);
        assertThat(storeField().get(social)).as("注入器已还原成真实现").isSameAs(realStore);
        conflicts = null;
    }

    private static java.lang.reflect.Field storeField() throws NoSuchFieldException {
        java.lang.reflect.Field field = SocialAppService.class.getDeclaredField("store");
        field.setAccessible(true);
        return field;
    }

    // ---------- 读口与写口同源 ----------

    @Test
    @DisplayName("同刻比对：/rally/policy 的 nation.maxMembers 与发起后 RallyView.maxMembers 相等")
    void policyAndWritePathReportTheSameNationalCap() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);

        JsonNode policy = get200("/rally/policy", nation.king).get("nation");
        int cap = policy.get("maxMembers").asInt();

        // 故意越界上报：写口必须夹到读口刚说出来的那个数，而不是别的东西
        JsonNode rally = post200("/rally/nation", nation.king,
                request(cap + 9)).get("rally");

        assertThat(rally.get("maxMembers").asInt())
                .as("写口夹出来的上限必须与读口刚报的是同一个数（写口绕开折叠点自己算就会红）")
                .isEqualTo(cap);
        assertThat(policy.get("canStart").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("国家层的 cap 是「配置上限与本国实有人数的小值」：人少的国拿不到配置原值")
    void capIsFoldedWithTheRealHeadcountNotTheConfiguredCeiling() throws Exception {
        Nation small = nation(3);
        JsonNode smallPolicy = get200("/rally/policy", small.king).get("nation");
        assertThat(smallPolicy.get("maxMembers").asInt())
                .as("3 个人的国，上限就是 3 —— 不是表里那个更大的数")
                .isEqualTo(3);

        Nation bigger = nation(5);
        JsonNode biggerPolicy = get200("/rally/policy", bigger.king).get("nation");
        assertThat(biggerPolicy.get("maxMembers").asInt())
                .as("换成人更多的国，cap 跟着涨 ⇒ 证明这个数真的来自人数折叠，不是常量")
                .isEqualTo(5);
    }

    // ---------- 边界：界值来自 policy，不写死 ----------

    @Test
    @DisplayName("上限两侧各一条：cap-1 原样成立、cap+1 被夹回 cap（两侧都从 policy 现读）")
    void boundaryOnBothSidesOfTheFoldedCap() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 2_000L);
        int cap = get200("/rally/policy", nation.king).get("nation").get("maxMembers").asInt();

        JsonNode below = post200("/rally/nation", nation.king,
                request(cap - 1)).get("rally");
        assertThat(below.get("maxMembers").asInt())
                .as("上限之下（cap-1）服务端不改动发起人填的数")
                .isEqualTo(cap - 1);

        JsonNode above = post200("/rally/nation", nation.king,
                request(cap + 1)).get("rally");
        assertThat(above.get("maxMembers").asInt())
                .as("上限之上（cap+1）夹到 cap 而不是拒绝：越界拒绝会让玩家以为集结功能坏了")
                .isEqualTo(cap);
    }

    @Test
    @DisplayName("同一毫秒连发两次集结：id 必须互异（唯一性不靠时间戳）")
    void rallyIdsDoNotCollideWithinTheSameMillisecond() {
        // 机制复现，不是现场抽样：把"同玩家 + 同毫秒"这个确定态直接造出来。
        // 旧实现（rally_<player>_<now>）在这一步只会给出一个 id ⇒ 断言 50 必红；
        // 而靠"全量批跑三趟撞红两次"那种抽样，抽不到就会被当成"没这回事"（台账 #802 实测）。
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 50; i += 1) {
            seen.add(SocialAppService.rallyIdOf("P-same", 1_700_000_000_000L));
        }
        assertThat(seen).as("同输入 50 次必须给出 50 个互异的 id").hasSize(50);
        assertThat(seen.iterator().next())
                .as("毫秒仍留在 id 里：日志与工单都按 rally_<玩家>_<时刻> grep")
                .startsWith("rally_P-same_1700000000000_");
    }

    @Test
    @DisplayName("同一名国王连着发两次国家集结：两次都成且 id 互异（旧实现第二次报 10057）")
    void twoConsecutiveNationalRalliesBothSucceed() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);

        JsonNode first = post200("/rally/nation", nation.king, request(3)).get("rally");
        JsonNode second = post200("/rally/nation", nation.king, request(3)).get("rally");

        assertThat(second.get("rallyId").asText())
                .as("第二次不该被第一次的建档挡下来")
                .isNotEqualTo(first.get("rallyId").asText());
    }

    @Test
    @DisplayName("亡国即结清（V22 验收④）：解散国家后承诺的兵退回原主，那支集结不再挂在人身上")
    void disbandingTheNationRefundsTheRallyItWasHolding() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        long before = troopsOf(nation.king);

        JsonNode rally = post200("/rally/nation", nation.king, request(3)).get("rally");
        String rallyId = rally.get("rallyId").asText();
        assertThat(troopsOf(nation.king))
                .as("发起即锁定：承诺的兵当场从城内扣掉（这是「锁着」的读数，不是猜的）")
                .isLessThan(before);

        post200("/nation/disband", nation.king,
                java.util.Map.of("requestId", newRequestId()));

        assertThat(troopsOf(nation.king))
                .as("亡国之后兵必须回家。不结清的症状是：兵锁在一支谁也列不出来的集结上"
                        + "（亡国后 /rally/list 会过滤掉已解散的国，而 expireIfDue 只在有人读它时才跑）")
                .isEqualTo(before);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("解散后这一支不再挂在国王的面板上")
                .allMatch(r -> !rallyId.equals(r.get("rallyId").asText()));
    }

    @Test
    @DisplayName("退国结清（裁决 2026-10-08：只补退国，被开除留后）：发起人随自己盟退出 ⇒ 这一支取消、兵回家")
    void leavingTheNationCancelsTheRallyItsInitiatorStarted() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        long before = troopsOf(nation.king);

        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        assertThat(troopsOf(nation.king)).as("发起即锁定").isLessThan(before);

        post200("/nation/leave", nation.king, java.util.Map.of("requestId", newRequestId()));

        assertThat(troopsOf(nation.king))
                .as("人已经不在国里，兵却还锁在「本国集结」上 —— 症状是别人以为还在等他，"
                        + "而他自己再也点不进这一支")
                .isEqualTo(before);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("他发起的那一支随退国取消，不再挂在面板上")
                .allMatch(r -> !rallyId.equals(r.get("rallyId").asText()));
    }

    @Test
    @DisplayName("退国只退「他这一份」：非发起人退出时集结仍然成立，别人的兵不许被一起退掉")
    void aNonInitiatorLeavingRefundsOnlyHisOwnTroops() throws Exception {
        Nation nation = nation(3);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        long mateBefore = troopsOf(mate);

        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        assertThat(troopsOf(mate)).as("加入即锁定他的兵").isLessThan(mateBefore);
        long kingCommitted = troopsOf(nation.king);

        int settled = social.settleNationalRalliesForMembers(nation.nationId, List.of(mate),
                System.currentTimeMillis());

        assertThat(settled).as("只该结清一条").isEqualTo(1);
        assertThat(troopsOf(mate)).as("他的兵回家").isEqualTo(mateBefore);
        assertThat(troopsOf(nation.king))
                .as("国王的兵必须仍然锁着 —— 走错成整国取消会把他一起退掉，那会误伤无关的联盟")
                .isEqualTo(kingCommitted);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("集结还在等别人，不能因为一个人退国就消失")
                .anyMatch(r -> rallyId.equals(r.get("rallyId").asText()));

        // 重入必须无害：结清是按"当前这本账"重读后再写的，同一个人被再处理一次不该又退一遍兵
        //（台账 #831 的"重试那份是新读的副本，所以不会重复入账"这一句，用最便宜的方式钉住它的可观察面）
        long afterFirst = troopsOf(mate);
        int settledAgain = social.settleNationalRalliesForMembers(nation.nationId, List.of(mate),
                System.currentTimeMillis());
        assertThat(settledAgain).as("他已经不在这支集结里了 ⇒ 第二次一条都不该结").isZero();
        assertThat(troopsOf(mate)).as("再结一次不许又退一遍（那等于凭空造兵）").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("联盟解散（不是主动退国）也要结清该盟成员的国家集结 —— 独立审查抓出的漏网（台账 #830）")
    void disbandingTheAllianceAlsoSettlesTheNationRallyItWasHolding() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        long before = troopsOf(nation.king);

        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        assertThat(troopsOf(nation.king)).as("发起即锁定").isLessThan(before);

        // 解散联盟这条路与 /nation/leave 不同：它不经过 leave，但同样会让联盟脱离国家
        post200("/alliance/disband", nation.king,
                java.util.Map.of("requestId", newRequestId()));

        assertThat(troopsOf(nation.king))
                .as("联盟没了 ⇒ 这一支「本国集结」再也无人能列出、也永远不会出发；不结清就是兵被锁死")
                .isEqualTo(before);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("解散后这一支不再挂在面板上")
                .allMatch(r -> !rallyId.equals(r.get("rallyId").asText()));
    }

    // ---------- 结清路径的重试支路（台账 #833） ----------

    @Test
    @DisplayName("结清撞锁一趟：重试必须靠重读写进去，兵只退一次")
    void conflictThenRetryRefundsExactlyOnceOnTheInitiatorSide() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        long idle = troopsOf(nation.king);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        assertThat(troopsOf(nation.king)).as("发起即锁定").isLessThan(idle);

        injectConflicts(rallyId, 1);
        int settled = social.settleNationalRalliesForMembers(nation.nationId, List.of(nation.king),
                System.currentTimeMillis());

        assertThat(conflicts.targetedWrites)
                .as("第一趟撞锁、第二趟写进去 ⇒ 恰好两次带版本写；三趟说明重试失控，零趟说明这一支根本没走")
                .isEqualTo(2);
        assertThat(settled).as("这一支结清了").isEqualTo(1);
        assertThat(troopsOf(nation.king))
                .as("退款不许翻倍：多退的那部分是凭空造出来的兵，而少退就是兵锁死")
                .isEqualTo(idle);
        assertThat(socialStore.rallyOf(rallyId)).as("库里那一支已取消")
                .map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("取消后不再挂在面板上")
                .allMatch(r -> !rallyId.equals(r.get("rallyId").asText()));
    }

    @Test
    @DisplayName("非发起人那一支撞锁重试：只退他这一份，别人的兵继续锁着、集结继续等人")
    void conflictThenRetryRefundsOnlyTheQuitter() throws Exception {
        Nation nation = nation(3);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        long mateIdle = troopsOf(mate);
        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        long kingLocked = troopsOf(nation.king);
        assertThat(troopsOf(mate)).as("加入即锁定他的兵").isLessThan(mateIdle);

        injectConflicts(rallyId, 1);
        int settled = social.settleNationalRalliesForMembers(nation.nationId, List.of(mate),
                System.currentTimeMillis());

        assertThat(conflicts.targetedWrites).isEqualTo(2);
        assertThat(settled).isEqualTo(1);
        assertThat(troopsOf(mate)).as("他的兵回家且只回一次").isEqualTo(mateIdle);
        assertThat(troopsOf(nation.king))
                .as("走成整国取消就把国王那份一起退了 —— 那是误伤无关的人")
                .isEqualTo(kingLocked);
        assertThat(socialStore.rallyOf(rallyId)).as("一个人退出不足以取消整支集结")
                .map(Rally::status).contains(Rally.Status.PREPARING);
        assertThat(socialStore.rallyOf(rallyId).orElseThrow().participant(mate))
                .as("他的名字从参与者表里摘掉了，否则第二个人退国还会再退他一遍").isNull();

        assertThat(social.settleNationalRalliesForMembers(nation.nationId, List.of(mate),
                System.currentTimeMillis()))
                .as("同一个人再处理一次一条都不结").isZero();
        assertThat(troopsOf(mate)).as("再结一次不许又退一遍兵").isEqualTo(mateIdle);
    }

    @Test
    @DisplayName("连撞两趟：异常必须冒出去，不许静默放弃结清")
    void secondConflictPropagatesInsteadOfQuietlySkippingTheRefund() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        long locked = troopsOf(nation.king);

        injectConflicts(rallyId, 2);
        assertThatThrownBy(() -> social.settleNationalRalliesForMembers(
                nation.nationId, List.of(nation.king), System.currentTimeMillis()))
                .as("第二趟还撞就交给上层：吞掉的话这一支既没取消也没退款，而调用方以为成功了")
                .isInstanceOf(IllegalStateException.class);

        assertThat(conflicts.targetedWrites)
                .as("只试两趟就收口（第三趟意味着重试没有上限）").isEqualTo(2);
        assertThat(socialStore.rallyOf(rallyId)).as("写没成功 ⇒ 库里状态一格没动")
                .map(Rally::status).contains(Rally.Status.PREPARING);
        assertThat(troopsOf(nation.king)).as("写没成功 ⇒ 不许退款").isEqualTo(locked);
    }

    @Test
    @DisplayName("国家解散结清连撞两趟：国家保持原样，同一个 requestId 能在冲突结束后重试")
    void nationDisbandRemainsRetryableWhenTheSettleKeepsConflicting() throws Exception {
        Nation nation = nation(3);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 2_000L);
        giveTroops(mate, 800L);
        long kingIdle = troopsOf(nation.king);
        long mateIdle = troopsOf(mate);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        long kingLocked = troopsOf(nation.king);
        long mateLocked = troopsOf(mate);
        var before = nationStore.findById(nation.nationId).orElseThrow().snapshot();
        var req = java.util.Map.of("requestId", newRequestId());

        injectConflicts(rallyId, 2);
        JsonNode failed = postExpectServerFailure("/nation/disband", nation.king, req);

        assertThat(failed.get("code").asInt()).isEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(conflicts.targetedWrites).as("两趟撞锁后必须把失败交回调用方").isEqualTo(2);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().snapshot())
                .as("结清失败不能先存亡国：成员、官职、国库、日志与冷却必须全部保持原样")
                .isEqualTo(before);
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status).contains(Rally.Status.PREPARING);
        assertThat(troopsOf(nation.king)).as("结清没写成功，国王的兵还锁着").isEqualTo(kingLocked);
        assertThat(troopsOf(mate)).as("参与者的兵也不能先退").isEqualTo(mateLocked);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("列表读取先重放未结清计划，已恢复取消的集结不再列出")
                .noneMatch(r -> rallyId.equals(r.get("rallyId").asText()));
        assertThat(troopsOf(nation.king)).isEqualTo(kingIdle);
        assertThat(troopsOf(mate)).isEqualTo(mateIdle);

        // 注入器只撞前两次，第三次真实写入；复用 requestId 验证失败没有烧掉幂等键。
        post200("/nation/disband", nation.king, req);
        assertThat(conflicts.targetedWrites).as("重试只再写一趟，不能重复结清").isEqualTo(3);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isTrue();
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(nation.king)).as("恢复后国王的兵恰好退一次").isEqualTo(kingIdle);
        assertThat(troopsOf(mate)).as("恢复后参与者的兵恰好退一次").isEqualTo(mateIdle);
    }

    @Test
    @DisplayName("国家解散结清只撞一趟：重读后成功解散，全体参与者各退一份兵")
    void nationDisbandRetriesOneConflictAndRefundsEveryParticipantOnce() throws Exception {
        Nation nation = nation(3);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 2_000L);
        giveTroops(mate, 800L);
        long kingIdle = troopsOf(nation.king);
        long mateIdle = troopsOf(mate);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        assertThat(troopsOf(nation.king)).as("前提：国王的兵已锁定").isLessThan(kingIdle);
        assertThat(troopsOf(mate)).as("前提：参与者的兵已锁定").isLessThan(mateIdle);

        injectConflicts(rallyId, 1);
        post200("/nation/disband", nation.king, java.util.Map.of("requestId", newRequestId()));

        assertThat(conflicts.targetedWrites).as("第一趟冲突，重读后第二趟成功").isEqualTo(2);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isTrue();
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(nation.king)).as("国王的兵只退一次").isEqualTo(kingIdle);
        assertThat(troopsOf(mate)).as("参与者的兵只退一次").isEqualTo(mateIdle);
    }

    @Test
    @DisplayName("退国整笔原子（#831 口径）：结清失败时国家那一笔写没发生")
    void leaveIsAtomicWhenTheSettleKeepsConflicting() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        long locked = troopsOf(nation.king);

        injectConflicts(rallyId, 2);
        JsonNode root = postExpectServerFailure("/nation/leave", nation.king,
                java.util.Map.of("requestId", newRequestId()));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(conflicts.targetedWrites).isEqualTo(2);

        var stored = nationStore.findById(nation.nationId);
        assertThat(stored).as("夹具前提：国家本身还在（下面比的是成员表动没动）").isPresent();
        assertThat(stored.orElseThrow().hasAlliance(nation.allianceId))
                .as("先结清再写国家：结清失败 ⇒ nations.save 根本没跑，国籍一个字没改")
                .isTrue();
        assertThat(stored.orElseThrow().isDisbanded()).as("国家没被写成已解散").isFalse();
        assertThat(troopsOf(nation.king)).as("兵仍然锁着，退款没跑").isEqualTo(locked);
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status)
                .contains(Rally.Status.PREPARING);
    }

    @Test
    @DisplayName("联盟解散那条入口同理（detachFromNation）：结清失败 ⇒ 联盟没解散、国家没改")
    void allianceDisbandIsAtomicWhenTheSettleKeepsConflicting() throws Exception {
        Nation nation = nation(3);
        giveTroops(nation.king, 2_000L);
        String rallyId = post200("/rally/nation", nation.king, request(3))
                .get("rally").get("rallyId").asText();
        long locked = troopsOf(nation.king);

        injectConflicts(rallyId, 2);
        JsonNode root = postExpectServerFailure("/alliance/disband", nation.king,
                java.util.Map.of("requestId", newRequestId()));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(conflicts.targetedWrites).isEqualTo(2);

        var stored = nationStore.findByAlliance(nation.allianceId);
        assertThat(stored)
                .as("结清失败 ⇒ detachFromNation 里 nations.save 根本没跑：联盟还挂在国家的成员表上")
                .isPresent();
        assertThat(stored.orElseThrow().hasAlliance(nation.allianceId)).isTrue();
        assertThat(socialStore.allianceOf(nation.king)).as("联盟还在，解散没跑一半")
                .isPresent();
        assertThat(troopsOf(nation.king)).isEqualTo(locked);
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status)
                .contains(Rally.Status.PREPARING);
    }

    // ---------- 一个国里有两个联盟（台账 #834，端点级） ----------

    @Test
    @DisplayName("端点级：B 盟退国只结清 B，A 盟发起的那一支仍在准备中、A 的兵没被退")
    void leavingAnAllianceFromATwoAllianceNationDoesNotTouchTheOtherOne() throws Exception {
        Nation nation = nation(3);
        SecondAlliance second = joinSecondAlliance(nation);

        giveTroops(nation.king, 2_000L);
        String kingRally = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        long kingLocked = troopsOf(nation.king);

        giveTroops(second.leader, 2_000L);
        giveTroops(second.mate, 1_200L);
        long leaderIdle = troopsOf(second.leader), mateIdle = troopsOf(second.mate);
        String secondRally = post200("/rally/nation", second.leader, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", second.mate, new RallyJoinReq(newRequestId(), secondRally,
                List.of(new RallyTroop(UNIT, 300L)), List.of()));
        assertThat(troopsOf(second.leader)).as("B 自己发起即锁定").isLessThan(leaderIdle);
        assertThat(troopsOf(second.mate)).as("B 的成员加入即锁定").isLessThan(mateIdle);

        post200("/nation/leave", second.leader, java.util.Map.of("requestId", newRequestId()));

        assertThat(socialStore.rallyOf(secondRally)).as("B 自己发起的那一支随退国取消")
                .map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(second.leader)).as("发起人那份由 refundAll 退").isEqualTo(leaderIdle);
        assertThat(troopsOf(second.mate)).as("参与者那份也由同一笔 refundAll 退，且只退一次")
                .isEqualTo(mateIdle);

        assertThat(socialStore.rallyOf(kingRally))
                .as("A 盟那一支与这次退国无关：走成整国取消就是误伤无关的联盟")
                .map(Rally::status).contains(Rally.Status.PREPARING);
        assertThat(troopsOf(nation.king)).as("A 的兵必须仍然锁着").isEqualTo(kingLocked);
        assertThat(get200("/rally/list", nation.king).get("rallies"))
                .as("A 的面板还看得见自己那一支")
                .anyMatch(r -> kingRally.equals(r.get("rallyId").asText()));

        var stored = nationStore.findById(nation.nationId).orElseThrow();
        assertThat(stored.hasAlliance(nation.allianceId)).isTrue();
        assertThat(stored.hasAlliance(second.allianceId)).as("只有 B 离开了").isFalse();
    }

    @Test
    @DisplayName("端点级：B 盟解散同样只结清 B（#830 那条入口在多联盟国家上的读数）")
    void disbandingAnAllianceFromATwoAllianceNationDoesNotTouchTheOtherOne() throws Exception {
        Nation nation = nation(3);
        SecondAlliance second = joinSecondAlliance(nation);

        giveTroops(nation.king, 2_000L);
        String kingRally = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        long kingLocked = troopsOf(nation.king);

        giveTroops(second.leader, 2_000L);
        long leaderIdle = troopsOf(second.leader);
        String secondRally = post200("/rally/nation", second.leader, request(4))
                .get("rally").get("rallyId").asText();
        assertThat(troopsOf(second.leader)).isLessThan(leaderIdle);

        post200("/alliance/disband", second.leader, java.util.Map.of("requestId", newRequestId()));

        assertThat(socialStore.rallyOf(secondRally)).map(Rally::status)
                .contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(second.leader)).isEqualTo(leaderIdle);
        assertThat(socialStore.rallyOf(kingRally)).map(Rally::status)
                .contains(Rally.Status.PREPARING);
        assertThat(troopsOf(nation.king)).as("解散一个盟不该退别的盟的兵").isEqualTo(kingLocked);

        var stored = nationStore.findById(nation.nationId).orElseThrow();
        assertThat(stored.hasAlliance(second.allianceId)).isFalse();
        assertThat(stored.memberAllianceCount()).as("国还在，只剩 A 一个成员联盟").isEqualTo(1);
    }

    // ---------- 装配：国家那一档不能静默用联盟的 ----------

    @Test
    @DisplayName("国家集结按国家那一档装配：scope=NATION、groupId=国家 id，且不会被当成联盟层")
    void nationalRallyUsesTheNationalRulesNotTheAllianceOnes() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);

        JsonNode rally = post200("/rally/nation", nation.king,
                request(4)).get("rally");

        assertThat(rally.get("scope").asText()).isEqualTo("NATION");
        assertThat(rally.get("groupId").asText())
                .as("groupId 必须是国家 id：写成联盟 id 的话，requireMembership 与面板列表都会查错组织")
                .isEqualTo(nation.nationId);
        assertThat(rally.get("groupId").asText()).isNotEqualTo(nation.allianceId);
        assertThat(rally.get("initiatorId").asText()).isEqualTo(nation.king);
        assertThat(rally.get("joinedCount").asInt()).isEqualTo(1);
        assertThat(rally.get("status").asText()).isEqualTo("PREPARING");
    }

    // ---------- 加入 ----------

    @Test
    @DisplayName("本国成员能加入国家集结（requireMembership 的 NATION 支）")
    void memberCanJoinTheNationalRally() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        giveTroops(nation.mates.get(0), 800L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();
        long before = troopsOf(nation.mates.get(0));

        JsonNode after = post200("/rally/join", nation.mates.get(0),
                new RallyJoinReq(newRequestId(), rallyId,
                        List.of(new RallyTroop(UNIT, 200L)), List.of())).get("rally");

        assertThat(after.get("joinedCount").asInt())
                .as("加入成功：这一支原先写死 false，任何人都加不进去")
                .isEqualTo(2);
        assertThat(after.get("members").get(1).asText()).isEqualTo(nation.mates.get(0));
        assertThat(troopsOf(nation.mates.get(0)))
                .as("承诺即锁定：加入者的兵必须当场扣走")
                .isEqualTo(before - 200L);
    }

    @Test
    @DisplayName("别国的人加不进来：归属按国家 id 判，不是按联盟判")
    void outsiderFromAnotherNationCannotJoin() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        Nation other = nation(3);
        giveTroops(other.king, 1_000L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();
        long before = troopsOf(other.king);

        JsonNode root = postRaw("/rally/join", other.king,
                new RallyJoinReq(newRequestId(), rallyId,
                        List.of(new RallyTroop(UNIT, 200L)), List.of()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RALLY_NOT_FOUND.code());
        assertThat(root.get("detail").asText())
                .as("说清是「组织不对」，而不是让人去查集结还在不在")
                .contains("另一个组织");
        assertThat(troopsOf(other.king))
                .as("被拒时一个兵都不该动")
                .isEqualTo(before);
    }

    // ---------- 面板列表 ----------

    @Test
    @DisplayName("面板列表读得到国家集结（preparingRallies 的第三支）")
    void listShowsTheNationalRally() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();

        JsonNode rallies = get200("/rally/list", nation.king).get("rallies");
        List<String> ids = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        rallies.forEach(node -> {
            ids.add(node.get("rallyId").asText());
            scopes.add(node.get("scope").asText());
        });

        assertThat(ids)
                .as("原先只有小队与联盟两支 ⇒ 症状是「发得出去、面板永远看不到」")
                .contains(rallyId);
        assertThat(scopes).contains("NATION");
    }

    // ---------- 权限位 ----------

    @Test
    @DisplayName("MEMBER 档被拒：msg 不含职位主张、缺的那一位放在 detail 里（读口同样灰键）")
    void memberTierCannotStartANationalRallyAndTheReasonIsReadable() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(mate, 1_000L);

        JsonNode policy = get200("/rally/policy", mate).get("nation");
        assertThat(policy.get("canStart").asBoolean())
                .as("读口与写口看同一张 role_permission")
                .isFalse();
        assertThat(policy.get("reason").asText()).as("给玩家的是一句人话，不出现权限码与字段名")
                .doesNotContain("START_RALLY").doesNotContain("NATION");

        JsonNode root = postRaw("/rally/nation", mate, request(4));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(root.get("msg").asText())
                .as("msg 是通用的那句：把「需要什么职位」写死在这里等于在代码里抄一份权限表")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.msg())
                .doesNotContain("国王").doesNotContain("官职");
        assertThat(root.get("detail").asText())
                .as("缺哪个权限位放进 detail，那里是查表得出的")
                .contains("START_RALLY");
    }

    @Test
    @DisplayName("OFFICER 档（大将军）可发起：权限位来自表而不是写死的职位清单")
    void officerTierCanStartANationalRally() throws Exception {
        Nation nation = nation(4);
        String general = nation.mates.get(0);
        giveTroops(general, 1_000L);
        post200("/nation/appoint", nation.king,
                new NationAppointReq(newRequestId(), general, NationOffice.GENERAL));

        assertThat(get200("/rally/policy", general).get("nation").get("canStart").asBoolean())
                .as("B13 §46 把「调动集结」给了大将军 ⇒ 表里 OFFICER 档开，读口就必须跟着开")
                .isTrue();

        JsonNode rally = post200("/rally/nation", general, request(4)).get("rally");
        assertThat(rally.get("scope").asText()).isEqualTo("NATION");
        assertThat(rally.get("initiatorId").asText()).isEqualTo(general);
        assertThat(rally.get("groupId").asText()).isEqualTo(nation.nationId);
    }

    @Test
    @DisplayName("亡国：第二人的军队存档失败，重试补完退兵且不重退第一人")
    void disbandRecoversWhenARecipientsArmySaveFails() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(2);
        installSeam(social, "armies", ArmyRepository.class, armies, (method, args) -> {
            if (method.getName().equals("save") && args[0].equals(mate)
                    && failures.getAndDecrement() > 0) {
                throw new IllegalStateException("injected Army save unavailable");
            }
            return invoke(armies, method, args);
        });
        var req = java.util.Map.of("requestId", newRequestId());
        postExpectServerFailure("/nation/disband", nation.king, req);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isFalse();
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
        assertThat(troopsOf(mate)).isEqualTo(600L);
        post200("/nation/disband", nation.king, req);
        assertThat(troopsOf(nation.king)).as("已落档的退款不重复").isEqualTo(1_000L);
        assertThat(troopsOf(mate)).as("已取消集结的退款仍有恢复入口").isEqualTo(800L);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isTrue();
    }

    @Test
    @DisplayName("亡国：军队写入成功但响应抛异常，读回幂等键后继续且不重退")
    void disbandHandlesAnArmySaveWithUnknownOutcome() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        installSeam(social, "armies", ArmyRepository.class, armies, (method, args) -> {
            Object result = invoke(armies, method, args);
            if (method.getName().equals("save") && args[0].equals(nation.king)
                    && writes.incrementAndGet() == 1) {
                throw new IllegalStateException("injected acknowledgement lost after Army commit");
            }
            return result;
        });
        post200("/nation/disband", nation.king, java.util.Map.of("requestId", newRequestId()));
        assertThat(writes.get()).isEqualTo(1);
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
        assertThat(troopsOf(mate)).isEqualTo(800L);
    }

    @Test
    @DisplayName("解散扫描期间官员发起须等国家锁，醒来重查已亡国且不扣兵")
    void disbandAndAnOfficerStartingARallyUseTheSameNationLock() throws Exception {
        Nation nation = nation(4);
        String general = nation.mates.get(0);
        giveTroops(general, 1_000L);
        post200("/nation/appoint", nation.king,
                new NationAppointReq(newRequestId(), general, NationOffice.GENERAL));
        var scanned = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        installSeam(social, "store", SocialStore.class, socialStore, (method, args) -> {
            Object result = invoke(socialStore, method, args);
            if (method.getName().equals("preparingRalliesOf") && args[0].equals(nation.nationId)) {
                scanned.countDown();
                if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            }
            return result;
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var disband = executor.submit(() -> post200("/nation/disband", nation.king,
                    java.util.Map.of("requestId", newRequestId())));
            assertThat(scanned.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var start = executor.submit(() -> postRaw("/rally/nation", general, request(4)));
            try {
                assertThatThrownBy(() -> start.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            disband.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(start.get(3, java.util.concurrent.TimeUnit.SECONDS).get("code").asInt()).isNotZero();
            assertThat(socialStore.preparingRalliesOf(nation.nationId)).isEmpty();
            assertThat(troopsOf(general)).isEqualTo(1_000L);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("多支集结第二支冲突：重试只补剩余集结，第一支不重退")
    void disbandRecoversAfterOneOfSeveralRalliesWasSettled() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 2_000L);
        String first = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        String second = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        injectConflicts(second, 2);
        var req = java.util.Map.of("requestId", newRequestId());
        postExpectServerFailure("/nation/disband", nation.king, req);
        assertThat(socialStore.rallyOf(first)).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(nation.king)).isEqualTo(1_700L);
        post200("/nation/disband", nation.king, req);
        assertThat(troopsOf(nation.king)).isEqualTo(2_000L);
        assertThat(socialStore.rallyOf(second)).map(Rally::status).contains(Rally.Status.CANCELLED);
    }

    @Autowired private com.ironoath.core.lock.PlayerLock playerLocks;

    @Test
    @DisplayName("官员先持国家锁完成发起：并发亡国随后扫描到新集结并退兵")
    void officerStartBeforeDisbandIsIncludedInTheLockedScan() throws Exception {
        Nation nation = nation(4);
        String general = nation.mates.get(0);
        giveTroops(general, 1_000L);
        post200("/nation/appoint", nation.king,
                new NationAppointReq(newRequestId(), general, NationOffice.GENERAL));
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        installSeam(social, "store", SocialStore.class, socialStore, (method, args) -> {
            Object result = invoke(socialStore, method, args);
            if (method.getName().equals("saveRally") && args[0] instanceof Rally rally
                    && rally.scope() == Rally.Scope.NATION && rally.initiatorId().equals(general)) {
                written.countDown();
                if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            }
            return result;
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = executor.submit(() -> post200("/rally/nation", general, request(4)));
            assertThat(written.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var disband = executor.submit(() -> post200("/nation/disband", nation.king,
                    java.util.Map.of("requestId", newRequestId())));
            try {
                assertThatThrownBy(() -> disband.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            String rallyId = start.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    .get("rally").get("rallyId").asText();
            disband.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(socialStore.rallyOf(rallyId)).map(Rally::status).contains(Rally.Status.CANCELLED);
            assertThat(troopsOf(general)).isEqualTo(1_000L);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("成员玩家锁被占用时国王仍可退款，不反向拿成员锁形成死锁")
    void nationalRefundDoesNotAcquireAnotherPlayersLock() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> playerLocks.runLocked(mate, 3_000L, () -> {
                held.countDown();
                try {
                    if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(held.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var disband = executor.submit(() -> post200("/nation/disband", nation.king,
                    java.util.Map.of("requestId", newRequestId())));
            try {
                disband.get(1, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(troopsOf(mate)).isEqualTo(800L);
            } finally { release.countDown(); }
            holder.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("到期扫描拿到旧准备中快照，亡国后必须重读取消态而不能重复出发")
    void anOldDueScanSnapshotCannotDepartADisbandedNationalRally() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(nation.king, 1_000L);
        giveTroops(mate, 800L);
        String rallyId = post200("/rally/nation", nation.king, request(4))
                .get("rally").get("rallyId").asText();
        post200("/rally/join", mate, new RallyJoinReq(newRequestId(), rallyId,
                List.of(new RallyTroop(UNIT, 200L)), List.of()));
        Rally stale = socialStore.rallyOf(rallyId).orElseThrow();
        post200("/nation/disband", nation.king, java.util.Map.of("requestId", newRequestId()));
        assertThat(social.settleDueRally(stale, stale.prepareUntil())).isEmpty();
        assertThat(socialStore.rallyOf(rallyId)).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
        assertThat(troopsOf(mate)).isEqualTo(800L);
    }

    @Autowired private com.ironoath.web.service.NationAppService nationApp;

    @Test
    @DisplayName("国家最后一次保存版本冲突：保留活国，重试重新读版且不重退兵")
    void disbandRecoversAfterTheFinalNationSaveConflicts() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        post200("/rally/nation", nation.king, request(4));
        var conflict = new java.util.concurrent.atomic.AtomicBoolean(true);
        installSeam(nationApp, "nations", com.ironoath.web.nation.NationStore.class,
                nationStore, (method, args) -> {
            if (method.getName().equals("save") && args[0] instanceof com.ironoath.core.nation.Nation n
                    && n.isDisbanded() && conflict.getAndSet(false)) {
                var newer = nationStore.findById(n.id()).orElseThrow();
                nationStore.save(newer, newer.version());
                throw new IllegalStateException("injected Nation version conflict");
            }
            return invoke(nationStore, method, args);
        });
        var req = java.util.Map.of("requestId", newRequestId());
        postExpectServerFailure("/nation/disband", nation.king, req);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isFalse();
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
        post200("/nation/disband", nation.king, req);
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isTrue();
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("国家最后写入成功但响应丢失：读回亡国终态，退兵与结果均成功")
    void disbandHandlesAnUnknownFinalNationSaveOutcome() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        post200("/rally/nation", nation.king, request(4));
        var injected = new java.util.concurrent.atomic.AtomicBoolean();
        installSeam(nationApp, "nations", com.ironoath.web.nation.NationStore.class,
                nationStore, (method, args) -> {
            Object result = invoke(nationStore, method, args);
            if (method.getName().equals("save") && args[0] instanceof com.ironoath.core.nation.Nation n
                    && n.isDisbanded() && !injected.getAndSet(true)) {
                throw new IllegalStateException("injected Nation acknowledgement lost");
            }
            return result;
        });
        post200("/nation/disband", nation.king, java.util.Map.of("requestId", newRequestId()));
        assertThat(injected).isTrue();
        assertThat(nationStore.findById(nation.nationId).orElseThrow().isDisbanded()).isTrue();
        assertThat(troopsOf(nation.king)).isEqualTo(1_000L);
    }

    @FunctionalInterface
    private interface SeamInvocation {
        Object call(Method method, Object[] args) throws Throwable;
    }

    private <T> void installSeam(Object target, String fieldName, Class<T> port, T delegate,
                                 SeamInvocation invocation) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object original = field.get(target);
        Object proxy = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port},
                (ignored, method, args) -> invocation.call(method, args));
        field.set(target, proxy);
        seamRestorers.add(() -> {
            try { field.set(target, original); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        });
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    // ---------- 夹具 ----------

    /**
     * 一次国家集结的发起请求。武将留空：本类测的是组织与人数上限，不是编成。
     *
     * <p><b>目标用野怪格而不是 {@code RallyEndpointTest} 那样打发起人自己的城</b>：
     * 国家集结打本国成员会被攻击闸门按「同一个国家的联盟之间不能互相攻击」（13009）挡掉，
     * 而那条规则本身是对的（你不能把自己的国民当成集结目标）。空地与野怪不构成 PVP、
     * 直接放行，所以这里要测的组织流程与"打谁"仍然无关。
     */
    private NationRallyReq request(int maxMembers) {
        return new NationRallyReq(newRequestId(), new SocialCoord(TARGET_X, TARGET_Y),
                SocialTargetType.MONSTER, maxMembers, 10,
                List.of(new RallyTroop(UNIT, 300L)), List.of());
    }

    /**
     * 造一个已建国的联盟：国王 + (memberCount-1) 名普通国民。
     *
     * <p>人数是这一族用例的自变量 —— 上限是折叠出来的，"3 个人的国"与"5 个人的国"
     * 必须能造得出来，用例才不必写死数字。
     */
    private Nation nation(int memberCount) throws Exception {
        String king = newPlayer(16);
        List<String> mates = new ArrayList<>();
        seq++;
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "国家集结盟" + seq,
                        String.format("R%03d", seq % 1000)))
                .get("alliance").get("id").asText();
        for (int i = 1; i < memberCount; i++) {
            String mate = newPlayer(16);
            post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
            mates.add(mate);
        }
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), "集结国" + seq, 100L, 200L))
                .get("nation").get("nationId").asText();
        return new Nation(king, mates, allianceId, nationId);
    }

    private record Nation(String king, List<String> mates, String allianceId, String nationId) {
    }

    /** 已入籍的第二个联盟：盟主 + 一名成员。{@link #joinSecondAlliance} 造它。 */
    private record SecondAlliance(String leader, String mate, String allianceId) {
    }

    /**
     * 往国家里再塞一个联盟，并把它提到官职档。
     *
     * <p><b>为什么这一格非做不可</b>：{@link #nation(int)} 造出来的国恒只有一个联盟，
     * 于是「退国只结清这一盟、不误伤别的联盟」在 #825/#831 里只有服务级断言、端点上始终没有读数。
     *
     * <p><b>为什么要 appoint</b>：{@code role_permission} 里 NATION 档的 START_RALLY 只开给国王与
     * 官职档，民意代表与普通国民发不起国家集结（同 {@link #memberTierCannotStartANationalRally}）。
     * 不提档的话「B 自己发起的那一支」根本没有输入，用例就只能让 A 替 B 发起 —— 那不是被测的那件事。
     */
    private SecondAlliance joinSecondAlliance(Nation nation) throws Exception {
        seq++;
        String leader = newPlayer(16);
        String mate = newPlayer(16);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "入籍盟" + seq,
                        String.format("B%03d", seq % 1000)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));

        JsonNode joined = post200("/nation/join", leader,
                new NationJoinReq(newRequestId(), nation.nationId)).get("nation");
        assertThat(joined.get("allianceCount").asInt())
                .as("夹具前提：这个国里真的有两个联盟（前提不成立时后面的「没误伤」是空跑）").isEqualTo(2);

        post200("/nation/appoint", nation.king,
                new NationAppointReq(newRequestId(), leader, NationOffice.GENERAL));
        return new SecondAlliance(leader, mate, allianceId);
    }

    /**
     * 让指定集结的前 {@code times} 次带版本写真的撞乐观锁。
     *
     * <p><b>做法</b>：把 {@code SocialAppService.store} 换成一层只实现 {@code saveRally} 的委托代理。
     * 命中时先<b>把库里那一版原样按 version+1 再写回去</b>（模拟"另一个人刚好提交了一版"），
     * 再抛 {@code IllegalStateException} —— 这正是 {@code settleOneRally} 注释里那种并发。
     *
     * <p><b>为什么不能只抛不改库</b>：库里版本没动的话，"重试时拿旧 expectedVersion 再写一次"与
     * "重试时 {@code requireRally} 重读"两种写法都会绿，那这一支只测到"走了第二趟循环"，
     * 没测到"第二趟凭什么写得进去"。抬了版本之后不重读就必然再撞。
     *
     * <p>抬版本用反射改 {@code Rally.version}：那是 {@code private long}（非 final），
     * 而 {@code Rally.restore} 这个公开重建工厂要 15 个参数、其中 {@code minMembers} 只能从
     * {@code minMembersRequired()}（= min(max, min)）反推 —— 为了一个测试夹具去猜领域字段，
     * 比反射一个字段更容易在下次改表时静默失真。
     */
    private void injectConflicts(String rallyId, int times) throws Exception {
        realStore = (SocialStore) storeField().get(social);
        conflicts = new SaveRallyConflicts(realStore, rallyId, times);
        installedProxy = Proxy.newProxyInstance(SocialStore.class.getClassLoader(),
                new Class<?>[]{SocialStore.class}, conflicts);
        storeField().set(social, installedProxy);
        assertThat(storeField().get(social))
                .as("注入器确实装上了（没装上的话下面每一条读数都是空跑）").isSameAs(installedProxy);
    }

    /** 只在测试里用的撞锁注入器（见 {@link #injectConflicts}）。 */
    private static final class SaveRallyConflicts implements InvocationHandler {

        private static final java.lang.reflect.Field RALLY_VERSION;

        static {
            try {
                RALLY_VERSION = Rally.class.getDeclaredField("version");
                RALLY_VERSION.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("Rally 的版本字段改名了，撞锁注入器要跟着改", e);
            }
        }

        private final SocialStore delegate;
        private final String rallyId;
        private int toThrow;

        /** 命中过那支集结的带版本写次数 —— "这一支走没走到"的读数，必须进每条判据。 */
        int targetedWrites;

        SaveRallyConflicts(SocialStore delegate, String rallyId, int toThrow) {
            this.delegate = delegate;
            this.rallyId = rallyId;
            this.toThrow = toThrow;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("saveRally") && args != null && args.length == 2
                    && args[0] instanceof Rally rally && rallyId.equals(rally.rallyId())) {
                targetedWrites++;
                if (toThrow > 0) {
                    toThrow--;
                    Rally asRead = delegate.rallyOf(rallyId).orElseThrow();
                    long versionAsRead = asRead.version();
                    RALLY_VERSION.setLong(asRead, versionAsRead + 1L);
                    delegate.saveRally(asRead, versionAsRead);
                    throw new IllegalStateException(
                            "集结 " + rallyId + " 乐观锁冲突（测试植入的并发，不是真实抢锁）");
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * 失败路径不能用 {@link #perform}（它断言 200）：结清撞锁冒出去时兜底处理器给的是
     * 500 + {@code SYSTEM_ERROR}，而那正是"整笔失败"的可观察面。
     */
    private JsonNode postExpectServerFailure(String url, String playerId, Object req)
            throws Exception {
        MvcResult result = mockMvc.perform(post(url).header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andExpect(status().isInternalServerError()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long troopsOf(String playerId) {
        return armies.findByPlayerId(playerId).map(ArmyState::totalTroops).orElse(0L);
    }

    private void giveTroops(String playerId, long count) {
        ArmyState army = armies.findByPlayerId(playerId).orElse(null);
        if (army == null) {
            armies.insertIfAbsent(playerId, new ArmyState());
            army = armies.findByPlayerId(playerId).orElseThrow();
        }
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
        // 给兵之后必须重算战力：圈层校验读的是存档里的匹配战力，只改军队不刷新存档
        // 会让"打自己的城"被判成实力悬殊而拒 —— 那是夹具漏了一步，不是产品逻辑
        powerRefreshService.refresh(playerId);
    }

    /** 建国要主城 16 级、建盟要金币，两样都在这一处备好；新手保护期也要先解掉。 */
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                newRequestId(), "dev-" + UUID.randomUUID(), "国家集结测试", 1_700_000_000_000L, ""))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req)));
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
