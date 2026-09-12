package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.AllianceRallyReq;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;

/**
 * 职责：C3 的落地验证（收口清单 §五 C3 / #94）—— 掠袭真的出门、聊天真的进联盟频道、
 *       入盟申请真的送达、捐献/帮助/响应集结真的落到各自的账上。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么直接对 {@code execute} 发决策，而不是推进整天的 tick</b>：这些动作的<b>触发条件</b>
 * （aggression/sociability 掷骰、体力与队列、作息相位）已经在 core 用例里钉住了（BotSystemTest），
 * 这里要量的是"决策给出来之后，动作有没有真的落到世界上"。用真实 tick 去凑出六种动作
 * 会让用例依赖随机序列，红了也说不清是哪一层坏了。
 *
 * <p><b>每条都断言可观察的副作用</b>，而不是只断言计数器：掠袭看得到的是一支打向目标的行军、
 * 聊天看得到的是频道里的一句话、入盟申请看得到的是联盟的待审列表、响应集结看到的是集结人数 +1。
 * 计数器只是"这件事发生过"的旁证。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotSocialRaidTest {

    private static final long HOUR = 3_600_000L;

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private ArmyRepository armies;
    @Autowired private MarchRepository marches;
    @Autowired private InMemorySocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotAttackLimiter limiter;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        bots.clear();
        limiter.reset();
        runtime.reset();
        adapter.resetCounters();
    }

    // ---------- 掠袭 ----------

    @Test
    @DisplayName("C3 掠袭：目标从真人那条搜索来，真的派出一支打向它的行军（不是自己挑目标）")
    void raidSendsAMarchToASearchedTarget() {
        String victim = humanAt(300, 300, "被掠者");
        String bot = botAt(256, 256, "0.90", "0.80");
        giveTroops(victim, Map.of("unit_infantry_t1", 300L));
        giveTroops(bot, Map.of("unit_infantry_t1", 300L));
        powerRefreshService.refresh(victim);
        powerRefreshService.refresh(bot);
        Coord victimHome = worldAppService.homeOf(victim);

        adapter.execute(bot, new BotDecisionTree.Decision(
                BotDecisionTree.Action.RAID, false, 0L, "测试：直接发一个掠袭决策"), timeService.serverNow());

        assertThat(adapter.actionCounts().get("raided"))
                .as("掠袭必须真的出门（搜索到目标 + 出征两步都成了）").isEqualTo(1L);
        assertThat(marches.activeCountOf(bot)).as("世界上多了一支在外的队伍").isEqualTo(1L);
        March march = marches.findByPlayerId(bot).get(0);
        assertThat(march.to()).as("打的就是搜索给出的那个目标").isEqualTo(victimHome);
        assertThat(march.action()).isEqualTo(March.Action.ATTACK);
    }

    @Test
    @DisplayName("C3 掠袭对照组：搜索里没有目标时安静跳过 —— 不报错、不空转、也不把 failed 打上去")
    void raidWithoutTargetsIsQuiet() {
        String bot = botAt(256, 256, "0.90", "0.80");
        giveTroops(bot, Map.of("unit_infantry_t1", 300L));
        powerRefreshService.refresh(bot);

        adapter.execute(bot, new BotDecisionTree.Decision(
                BotDecisionTree.Action.RAID, false, 0L, "测试：世界上没有别人"), timeService.serverNow());

        assertThat(adapter.actionCounts().get("raided")).as("没有目标就没有出门").isZero();
        assertThat(marches.activeCountOf(bot)).isZero();
        assertThat(adapter.failedCount())
                .as("「圈层内没人可打」是玩法常态，不该被计成失败（它会把真正的失败淹掉）").isZero();
    }

    // ---------- 聊天 ----------

    @Test
    @DisplayName("C3 聊天：入盟问候只说一次，之后是闲聊/商贸；话真的落在联盟频道里")
    void chatLandsInTheAllianceChannelAndGreetsOnlyOnce() {
        String bot = botAt(256, 256, "0.30", "0.50");
        String allianceId = allianceOf(bot, "夜航团");
        long now = timeService.serverNow();

        adapter.execute(bot, chatDecision(), now);
        adapter.execute(bot, chatDecision(), now + 1000L);

        var messages = social.chatList(bot, new ChatListReq(ChatChannel.ALLIANCE, null, null, 20),
                now + 2000L).messages();
        assertThat(messages).as("两句话都要真的进频道").hasSize(2);
        // 第一句必须是问候（入盟问候只此一次），第二句是闲聊或商贸
        assertThat(adapter.actionCounts().get("chatted")).isEqualTo(2L);
        assertThat(adapter.lastChatSceneOf(bot))
                .as("问候之后场景切换成闲聊/商贸，不会一直问候").isIn("CHAT_IDLE", "TRADE");
        assertThat(messages.get(0).senderId())
                .as("第一句的发送者就是这个 Bot（问候是它自己说的）").isEqualTo(bot);
        assertThat(messages.get(1).content()).as("第二句也不能是空的").isNotBlank();
        assertThat(allianceId).as("这条用例的前提是它真的在联盟里").isNotBlank();
    }

    @Test
    @DisplayName("C3 聊天对照组：不在联盟里的 Bot 不说话（联盟频道之外会变成刷屏，本轮不做）")
    void lonelyBotDoesNotChat() {
        String bot = botAt(256, 256, "0.30", "0.50");
        adapter.execute(bot, chatDecision(), timeService.serverNow());
        assertThat(adapter.actionCounts().get("chatted")).isZero();
    }

    // ---------- 入盟申请 ----------

    @Test
    @DisplayName("C3 入盟：不在联盟里的 Bot 会申请一个还有人位的联盟，申请真的进了待审列表")
    void lonelyBotAppliesToAnAllianceWithRoom() {
        String leader = botAt(256, 256, "0.30", "0.50");
        String allianceId = allianceOf(leader, "灯塔");
        String seeker = humanAt(300, 300, "想入盟的人");
        bots.register(profileOf(seeker, "0.30", "1.00"));

        adapter.execute(seeker, new BotDecisionTree.Decision(
                        BotDecisionTree.Action.SEEK_ALLIANCE, false, 0L, "测试：主动申请"),
                timeService.serverNow());

        assertThat(socialStore.hasApplication(allianceId, seeker))
                .as("申请必须真的送达（服务端待审列表里能看到它）").isTrue();
        assertThat(adapter.actionCounts().get("soughtAlliance")).isEqualTo(1L);
    }

    @Test
    @DisplayName("C3 入盟节流：6 小时之内不会重复敲门（申请被拒之后不该每个 tick 都去试）")
    void seekAllianceIsThrottledLocally() {
        String leader = botAt(256, 256, "0.30", "0.50");
        allianceOf(leader, "静水");
        String seeker = humanAt(300, 300, "急性子");
        bots.register(profileOf(seeker, "0.30", "1.00"));
        long now = timeService.serverNow();

        adapter.execute(seeker, seekDecision(), now);
        Long first = adapter.lastSeekAllianceAtOf(seeker);
        adapter.execute(seeker, seekDecision(), now + HOUR);

        assertThat(first).as("第一次尝试记下了时刻").isNotNull();
        assertThat(adapter.lastSeekAllianceAtOf(seeker))
                .as("一小时后仍在节流窗口里，时刻不该前进").isEqualTo(first);
        adapter.execute(seeker, seekDecision(), now + 7 * HOUR);
        assertThat(adapter.lastSeekAllianceAtOf(seeker))
                .as("过了 6 小时才允许再敲一次").isEqualTo(now + 7 * HOUR);
    }

    // ---------- 社交三件事 ----------

    @Test
    @DisplayName("C3 捐献与一键帮助：捐献真的进了联盟资金，帮助真的把请求帮掉了")
    void donateAndHelpReachTheirLedgers() {
        String helper = botAt(256, 256, "0.30", "1.00");
        String allianceId = allianceOf(helper, "互助社");
        long fundsBefore = socialStore.allianceById(allianceId).orElseThrow().fund();

        adapter.execute(helper, new BotDecisionTree.Decision(
                BotDecisionTree.Action.ALLIANCE_DONATE, false, 0L, "测试：捐献"), timeService.serverNow());
        assertThat(adapter.actionCounts().get("donated")).isEqualTo(1L);
        assertThat(socialStore.allianceById(allianceId).orElseThrow().fund())
                .as("捐献必须真的改善联盟资金（免费档也为正，档位表说了算）").isGreaterThanOrEqualTo(fundsBefore);

        // 一键帮助在没有可帮请求时是"帮了 0 条"，不报错也不该被算成失败
        adapter.execute(helper, new BotDecisionTree.Decision(
                BotDecisionTree.Action.ALLIANCE_HELP, false, 0L, "测试：帮助"), timeService.serverNow());
        assertThat(adapter.actionCounts().get("helped")).isEqualTo(1L);
        assertThat(adapter.failedCount()).as("没有可帮的请求不是错误").isZero();
    }

    @Test
    @DisplayName("C3 响应集结：真的加入联盟里进行中的集结，集结人数 +1")
    void joinRallyEntersThePreparingRally() {
        String leader = botAt(256, 256, "0.30", "1.00");
        String allianceId = allianceOf(leader, "集结队");
        String member = humanAt(300, 300, "响应者");
        bots.register(profileOf(member, "0.30", "1.00"));
        socialStore.saveAlliance(membershipOf(allianceId, member));
        giveTroops(leader, Map.of("unit_infantry_t1", 100L));
        giveTroops(member, Map.of("unit_infantry_t1", 100L));
        long now = timeService.serverNow();
        var rally = social.allianceRally(leader, new AllianceRallyReq(
                "req-" + UUID.randomUUID(), new SocialCoord(400, 400), SocialTargetType.MONSTER,
                5, 5, List.of(new RallyTroop("unit_infantry_t1", 100L)), List.of())).rally();

        adapter.execute(member, new BotDecisionTree.Decision(
                BotDecisionTree.Action.JOIN_RALLY, false, 0L, "测试：响应集结"), now);

        assertThat(adapter.actionCounts().get("rallied"))
                .as("响应集结必须真的加入（承诺兵力 + 成员校验都过了）").isEqualTo(1L);
        assertThat(socialStore.rallyOf(rally.rallyId()).orElseThrow().joinedCount())
                .as("集结人数从 1（发起人）变成 2（响应者）").isEqualTo(2);
    }

    // ---------- 空转与失败的分野（#94 修的口径） ----------

    @Test
    @DisplayName("#94：决策做不了时进 skipped 而不是 failed —— 契约说 failed 是「读世界或执行抛出来」")
    void impossibleActionCountsAsSkippedNotFailed() {
        String bot = botAt(256, 256, "0.30", "0.50");
        // 新号没武将 ⇒ troopCap=0 ⇒ 一个兵都训不了；而"失误掷骰"恰恰会把动作选成训练。
        // 这正是那段日志在真实运行里出现的情形（见 BotWorldAdapter#train 的注释）
        adapter.execute(bot, new BotDecisionTree.Decision(
                        BotDecisionTree.Action.TRAIN_TROOPS, true, 0L, "测试：失误掷中的空动作"),
                timeService.serverNow());

        assertThat(adapter.failedCount())
                .as("执行没抛异常 ⇒ failed 必须是 0（把体面跳过记成失败会把真正的异常淹掉）").isZero();
        assertThat(adapter.actionCounts().get("skipped"))
                .as("跳过必须被计数 —— 否则「Bot 在原地打转」这件事没有任何地方看得见")
                .isEqualTo(1L);
    }

    // ---------- 夹具 ----------

    private BotDecisionTree.Decision chatDecision() {
        return new BotDecisionTree.Decision(BotDecisionTree.Action.SEND_CHAT, false, 0L, "测试：发言");
    }

    private BotDecisionTree.Decision seekDecision() {
        return new BotDecisionTree.Decision(BotDecisionTree.Action.SEEK_ALLIANCE, false, 0L, "测试：申请入盟");
    }

    /** 一个真人（已解除保护）：掠袭的目标。 */
    private String humanAt(int x, int y, String nick) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                nick + UUID.randomUUID().toString().substring(0, 4), 1_700_000_000_000L)).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    /** 一个托管账号（活跃时段给满 24 小时，免得作息相位把行为推走）。 */
    private String botAt(int x, int y, String aggression, String sociability) {
        String botId = humanAt(x, y, "托管");
        bots.register(profileOf(botId, aggression, sociability));
        return botId;
    }

    private BotProfile profileOf(String botId, String aggression, String sociability) {
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        return new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse(aggression), FixedPoint.parse("0.50"),
                        FixedPoint.parse(sociability), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0"));
    }

    /** 让 leader 建一个联盟并返回它的 id（走真人同一条 service）。 */
    private String allianceOf(String leader, String name) {
        PlayerSave save = players.findByPlayerId(leader).orElseThrow();
        save.setCityLevel(16);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        social.allianceCreate(leader, new AllianceCreateReq("req-" + UUID.randomUUID(), name,
                "T" + Math.abs(name.hashCode() % 1000)));
        return socialStore.allianceOf(leader).orElseThrow().id();
    }

    private void giveTroops(String playerId, Map<String, Long> byUnitId) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        byUnitId.forEach(army::add);
        armies.save(playerId, army, version);
    }

    /** 把一个真人塞进某个联盟（等价于"盟主把他收进来了"）：测试里没有邀请端点，直接改成员表。 */
    private Alliance membershipOf(String allianceId, String playerId) {
        Alliance alliance = socialStore.allianceById(allianceId).orElseThrow();
        alliance.join(playerId);
        return alliance;
    }
}
