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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotChatBook;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotChatEvent;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;

/**
 * 职责：B11 §四「事件触发模板句库」的落地验证（收口清单 #97）——那 5 个事件场景
 * （HELP_REQUEST / RALLY_CALL / ATTACKED / VICTORY / DEFEAT）真的有人发、真的进频道。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么必须有用例盯这件事</b>：`bot_chat` 表里这 5 个场景有 11 行语料，
 * 而在本档之前<b>它们永远不会被抽到</b>——表做了、句子写了、机制没接。
 * 「表里有、代码里没有」这一族的唯一防线就是用例，因为它在运行时表现为「什么都没发生」。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotEventChatTest {

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private ArmyRepository armies;
    @Autowired private InMemorySocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotAttackLimiter limiter;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;
    @Autowired private ApplicationEventPublisher events;

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

    // ---------- 事件 → 发言 ----------

    @Test
    @DisplayName("事件触发：受击的 Bot 真的在联盟频道说一句（那 11 行语料此前永远不会被抽到）")
    void attackedBotSpeaksInAllianceChannel() {
        String bot = botInAlliance("被打的邻居");
        long now = timeService.serverNow();

        events.publishEvent(new BotChatEvent(bot, bot, BotChatBook.Scene.ATTACKED, now));

        var messages = social.chatList(bot, new ChatListReq(ChatChannel.ALLIANCE, null, null, 20),
                now + 1000L).messages();
        assertThat(messages).as("ATTACKED 场景的话必须真的进联盟频道").hasSize(1);
        assertThat(messages.get(0).senderId()).isEqualTo(bot);
        assertThat(messages.get(0).content()).isNotBlank();
        assertThat(adapter.lastChatSceneOf(bot))
                .as("场景要如实记下来：它是「哪一类话被抽到」的唯一可查证据")
                .isEqualTo("ATTACKED");
        assertThat(adapter.actionCounts().get("chatted")).isEqualTo(1L);
    }

    @Test
    @DisplayName("战果分场景：赢说 VICTORY、输说 DEFEAT —— 映射只有一处，不许调用方自己写三元")
    void battleScenesFollowTheOutcome() {
        assertThat(BotChatBook.Scene.ofBattle(true)).isEqualTo(BotChatBook.Scene.VICTORY);
        assertThat(BotChatBook.Scene.ofBattle(false)).isEqualTo(BotChatBook.Scene.DEFEAT);
    }

    @Test
    @DisplayName("五个事件场景逐一到场：每个都能抽出话并真的发出去（缺一个就是一行死语料）")
    void everyEventDrivenSceneCanActuallySpeak() {
        // 主城提到 8 级：五个场景的最低门槛分别是 1/4/1/1/1（RALLY_CALL 的「打野集结」要 4 级、
        // 8 级才够到「推隔壁那座城」）——用 1 级城去断言 RALLY_CALL 说话是我第一版算错了，
        // 而门槛本身是表的设计（1 级 Bot 喊「打野集结」会露馅）
        String bot = botInAlliance("挨个说一遍", 8);
        List<BotChatBook.Scene> eventScenes = List.of(
                BotChatBook.Scene.HELP_REQUEST, BotChatBook.Scene.RALLY_CALL,
                BotChatBook.Scene.ATTACKED, BotChatBook.Scene.VICTORY, BotChatBook.Scene.DEFEAT);

        long base = timeService.serverNow();
        int spoken = 0;
        for (BotChatBook.Scene scene : eventScenes) {
            // 时间往前推，避开"同内容 10 秒内最多 3 条"的防刷屏窗口（同一场景两次抽到的
            // 可能是同一句，撞上就会被限流拒掉——那是正确行为，但会让本用例验不到"场景可达"）
            if (adapter.speakOnEvent(bot, scene, base + spoken * 60_000L)) {
                spoken++;
            }
        }

        assertThat(spoken)
                .as("8 级的主城必须让 5 个事件场景全都说得出话（表里每个场景的 minCityLevel 都在 8 以内；"
                        + "抽不出来说明门槛配错了或场景没接上）")
                .isEqualTo(eventScenes.size());
    }

    // ---------- 真实结算路径发布事件（不是只有手发的假事件） ----------

    @Test
    @DisplayName("端到端：真的打赢一场野怪，VICTORY 事件由结算方发布（联盟频道里多出一句战报口风）")
    void aRealHuntVictoryPublishesTheEvent() {
        String bot = botInAlliance("打野的");
        giveTroops(bot, Map.of("unit_infantry_t1", 500L));
        long now = timeService.serverNow();

        // 走真人同一条出征 → 到点 → 结算（与 MonsterHuntEndpointTest 同一条夹具手法），
        // 目的不是再验一次战斗，而是验「结算真的发布了聊天事件」——
        // 手发事件那几条用例证明不了这一点，而「判定写了没发布」正是这一族最典型的漏法
        Coord monsterCell = findMonsterCell(bot);
        String marchId = marchAppService.send(bot, new MarchReq(newRequestId(),
                monsterCell.x(), monsterCell.y(),
                List.of(new com.ironoath.web.dto.generated.MarchUnit("unit_infantry_t1", 500L)),
                List.of(), com.ironoath.web.dto.generated.MarchAction.ATTACK)).march().marchId();
        rewindAndProcess(marchId);

        var messages = social.chatList(bot, new ChatListReq(ChatChannel.ALLIANCE, null, null, 20),
                timeService.serverNow() + 1000L).messages();
        assertThat(messages)
                .as("打野结算必须发出聊天事件：打赢说 VICTORY、打输说 DEFEAT，二者必居其一")
                .hasSize(1);
        assertThat(adapter.lastChatSceneOf(bot))
                .as("场景必须是 VICTORY 或 DEFEAT（结算方按胜负选，不由这里猜）")
                .isIn("VICTORY", "DEFEAT");
        assertThat(now).isPositive();
    }

    // ---------- 不该说话的人不说话 ----------

    @Test
    @DisplayName("真人事件不发 Bot 的话：事件发生在真人身上时安静的什么都不做")
    void humanEventsProduceNoBotChat() {
        String human = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "路过的真人", 1_700_000_000_000L)).playerId();
        long now = timeService.serverNow();

        events.publishEvent(new BotChatEvent(human, human, BotChatBook.Scene.ATTACKED, now));

        assertThat(adapter.actionCounts().get("chatted")).as("真人身上没有 Bot 会说话").isZero();
    }

    @Test
    @DisplayName("不在联盟里的 Bot 因事件也不说话：五个场景全都发生在联盟内部，没有联盟就没有听众")
    void botOutsideAllianceStaysSilent() {
        String lonely = botAt(256, 256);
        long now = timeService.serverNow();

        assertThat(adapter.speakOnEvent(lonely, BotChatBook.Scene.HELP_REQUEST, now)).isFalse();
        assertThat(adapter.actionCounts().get("chatted")).isZero();
    }

    // ---------- 事件守卫 ----------

    @Test
    @DisplayName("非事件场景不许走事件通道：闲聊/商贸/入盟问候只能由 tick 的自发节奏说")
    void idleScenesAreRefusedByTheEventChannel() {
        String bot = botInAlliance("爱闲聊的");
        long now = timeService.serverNow();
        for (BotChatBook.Scene idle : List.of(BotChatBook.Scene.CHAT_IDLE,
                BotChatBook.Scene.TRADE, BotChatBook.Scene.ALLIANCE_JOIN)) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new BotChatEvent(bot, bot, idle, now))
                    .as("场景 %s 不是事件触发型", idle)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是事件触发型");
        }
    }

    // ---------- 夹具 ----------

    @Autowired private com.ironoath.web.service.MarchAppService marchAppService;
    @Autowired private com.ironoath.core.march.MarchDueQueue dueQueue;
    @Autowired private com.ironoath.core.march.MarchRepository marchStore;
    @Autowired private com.ironoath.web.service.WorldAppService worldAppService;
    @Autowired private com.ironoath.config.ConfigRegistry configs;

    /** 家附近的一只野怪（世界确定性生成，必有）。 */
    private Coord findMonsterCell(String botId) {
        Coord home = worldAppService.homeOf(botId);
        for (int radius = 1; radius < 60; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    Coord candidate = Coord.of(home.x() + dx, home.y() + dy);
                    if (!candidate.withinWorld((int) configs.longParam("WORLD_SIZE"))
                            || world.cityAt(candidate).isPresent()) {
                        continue;
                    }
                    if (worldAppService.cellAt(candidate).entityType()
                            == com.ironoath.core.world.WorldGenerator.EntityType.MONSTER) {
                        return candidate;
                    }
                }
            }
        }
        throw new AssertionError("家附近 60 格内必须有野怪");
    }

    /** 把行军推到「已到点」并触发结算（服务端不跑定时器，测试也不 sleep）。 */
    private void rewindAndProcess(String marchId) {
        var march = marchStore.findById(marchId).orElseThrow();
        long version = marchStore.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marchStore.save(march, version);
        dueQueue.reschedule(marchId, march.startAt());
        marchAppService.processDue(march.playerId(), timeService.serverNow());
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

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 一个 Bot，并且真的在一个联盟里（五个事件场景都只在联盟频道说）。 */
    private String botInAlliance(String nickPrefix) {
        return botInAlliance(nickPrefix, 1);
    }

    /** 同上，但把主城拉到指定等级（事件语料有等级门槛，测试要能覆盖到高门槛场景）。 */
    private String botInAlliance(String nickPrefix, int cityLevel) {
        String bot = botAt(256, 256);
        var botSave = players.findByPlayerId(bot).orElseThrow();
        botSave.setCityLevel(cityLevel);
        players.save(botSave);
        // 建盟需要主城 10 级 + 真实扣金币（B10 门槛），所以让一个"真人"当盟主、
        // 把 Bot 直接塞进成员表 —— 与 BotSocialRaidTest 的 membershipOf 同一条做法，
        // 避免为了造一个联盟把等级与金币两道门槛也抄进本用例
        String leader = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "盟主" + nickPrefix, 1_700_000_000_000L)).playerId();
        var save = players.findByPlayerId(leader).orElseThrow();
        save.setCityLevel(16);
        var gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        social.allianceCreate(leader, new AllianceCreateReq("req-" + UUID.randomUUID(),
                "事件聊天" + nickPrefix, "T" + Math.abs(nickPrefix.hashCode() % 1000)));
        var alliance = socialStore.allianceOf(leader).orElseThrow();
        alliance.join(bot);
        socialStore.saveAlliance(alliance);
        return bot;
    }

    /** 一个托管账号（活跃时段给满 24 小时）。 */
    private String botAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "事件测试" + UUID.randomUUID().toString().substring(0, 4), 1_700_000_000_000L)).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y))).isTrue();
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new BotProfile(playerId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("1.00"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        return playerId;
    }
}
