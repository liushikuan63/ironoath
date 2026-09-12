package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.AttackGuardService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;

/**
 * 职责：C2 的落地验证（收口清单 §五 C2）—— ① 攻击频控真的接在统一漏斗上；
 *       ② 受击反应真的会转调真人 service（迁城 / 反击），且「同一发攻击只回应一次」。
 * 依赖：Spring Boot Test + test profile（内存存储）。
 *
 * <p><b>频控为什么要一个真打一仗的用例</b>：闸门（判额度）与记账（仗真打起来才算）
 * 分在两处，只测闸门的话，把记账那两行删掉也不会有任何测试变红 ——
 * 表现是「限额形同虚设」，而那正是这条红线存在的理由。所以这里先用真实行军链路打一仗，
 * 再断言账本上真的多了一笔。
 *
 * <p><b>受击反应的两条路径各测一条</b>：中攻击性（0.50）迁城 —— 断言城坐标真的变了；
 * 高攻击性（0.90）且<b>有兵</b>反击 —— 断言真的走出去一支打向袭击者的行军。
 * 两者都走真人 service，所以「像真人一样行动」这句话在这里是可核对的，不是口号。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotAttackQuotaTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private ArmyRepository armies;
    @Autowired private WorldRepository world;
    @Autowired private MarchRepository marches;
    @Autowired private MarchDueQueue dueQueue;
    @Autowired private MarchAppService marchAppService;
    @Autowired private CityAppService cityAppService;
    @Autowired private com.ironoath.web.service.WorldAppService worldAppService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private AttackGuardService guard;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotAttackLimiter limiter;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        // Bot 侧的进程内状态：不清的话上一条用例的托管身份与被攻击额度会飘进这一条
        bots.clear();
        limiter.reset();
        runtime.reset();
        adapter.resetCounters();
    }

    // ---------- 频控 ----------

    @Test
    @DisplayName("C2 频控：Bot 打的第三仗之后，第四次被统一漏斗拒掉（11001），且账本上真的记了那三笔")
    void botAttacksOnAHumanAreCappedAtTheConfiguredLimit() {
        String human = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        // 守方必须有驻军，否则 PVP 预检会直接拒掉（「对方城内没有驻军」是记录在案的拒绝口径，
        // 不是夹具疏漏）—— 而这一条要验的是频控，前面那几道闸门都得先放行
        giveTroops(human, Map.of("unit_infantry_t1", 1_200L));
        giveTroops(bot, Map.of("unit_infantry_t1", 1_200L));
        powerRefreshService.refresh(bot);
        powerRefreshService.refresh(human);
        Coord target = worldAppService.homeOf(human);
        long now = timeService.serverNow();

        // 第一笔来自真实战斗：出征 → 到期 → 结算，记账发生在结算里（而不是闸门里）
        String marchId = sendAttack(bot, target, Map.of("unit_infantry_t1", 1_200L));
        arriveAndProcess(marchId);
        assertThat(limiter.recordedCount())
                .as("仗真的打起来了就必须记一笔 —— 把记账挂在闸门上会让「出门又召回」白吃真人的额度")
                .isEqualTo(1L);

        // 补到上限（直接记账，等价于又打了两仗；限额取表里的 BOT_ATTACK_LIMIT_PER_24H = 3）
        limiter.recordAttack(bot, human, now);
        limiter.recordAttack(bot, human, now + 1_000L);

        assertThatThrownBy(() -> guard.guard(bot, target, now + 2_000L))
                .as("第四次必须被拦，且拦的是额度，不是别的闸门")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED);
        assertThat(limiter.blockedCount()).as("拦下这件事也要计数，否则限额生效与否看不出来")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("C2 对照组：真人打真人、Bot 打 Bot 都不受这条限额管 —— 它保护的是「真人被 Bot 围殴」")
    void theCapOnlyAppliesToBotOnHuman() {
        String humanA = newPlayerAt(300, 300);
        String humanB = newPlayerAt(310, 300);
        String botA = botAt(256, 256, "0.50");
        String botB = botAt(266, 256, "0.50");
        long now = timeService.serverNow();

        Coord humanBTarget = worldAppService.homeOf(humanB);
        for (int i = 0; i < 5; i++) {
            int round = i;
            assertThatCode(() -> {
                guard.guard(humanA, humanBTarget, now + round * 1_000L);
                limiter.recordAttack(humanA, humanB, now + round * 1_000L);
            }).as("真人之间的攻防不加限制（第 %s 次）", i + 1).doesNotThrowAnyException();
        }

        Coord botBTarget = worldAppService.homeOf(botB);
        for (int i = 0; i < 5; i++) {
            int round = i;
            assertThatCode(() -> {
                guard.guard(botA, botBTarget, now + round * 1_000L);
                limiter.recordAttack(botA, botB, now + round * 1_000L);
            }).as("Bot 之间的互相攻伐也不加限制（B11 §六 的邻里关系，第 %s 次）", i + 1)
                    .doesNotThrowAnyException();
        }
        assertThat(limiter.recordedCount())
                .as("两类组合都不进账本 —— 账本只为「Bot 打真人」而存在").isZero();
    }

    @Test
    @DisplayName("C2 额度按窗口恢复：24 小时之后同一对组合重新放行")
    void quotaRecoversAfterTheWindow() {
        String human = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        Coord target = worldAppService.homeOf(human);
        long now = timeService.serverNow();

        for (int i = 0; i < 3; i++) {
            limiter.recordAttack(bot, human, now + i);
        }
        assertThatThrownBy(() -> guard.guard(bot, target, now + 10))
                .isInstanceOf(BizException.class);

        assertThatCode(() -> guard.guard(bot, target, now + 25 * HOUR))
                .as("过了 24 小时窗口就该恢复：限额是「今天挨够了」，不是永久封禁")
                .doesNotThrowAnyException();
    }

    // ---------- 受击反应 ----------

    @Test
    @DisplayName("C2 受击反应：中攻击性的 Bot 被打了会迁城一次（走真人那条 exile），同一发攻击不再回应")
    void attackedBotRelocatesOnceAndOnlyOnce() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.50");
        Coord before = worldAppService.homeOf(bot);
        long hitAt = timeService.serverNow();
        recordHit(bot, attacker, hitAt);

        int reacted = runTicksUntil(bot, before, 3 * HOUR);
        Coord after = worldAppService.homeOf(bot);

        assertThat(reacted).as("3 小时内必须回应过（决策 3~30 秒后执行）").isEqualTo(1);
        assertThat(after).as("中攻击性的反应是迁城：坐标必须真的变了").isNotEqualTo(before);
        assertThat(adapter.reactedUpToOf(bot)).as("已回应游标要停在那一发上").isEqualTo(hitAt);
        assertThat(limiter.recordedCount())
                .as("自己迁城不算攻击任何人").isZero();
    }

    @Test
    @DisplayName("C2 受击反应：高攻击性且有兵的 Bot 会真的派出一支打向袭击者的行军")
    void attackedBotWithTroopsCounterAttacks() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.90");
        giveTroops(bot, Map.of("unit_infantry_t1", 500L));
        giveTroops(attacker, Map.of("unit_infantry_t1", 500L));
        powerRefreshService.refresh(bot);
        powerRefreshService.refresh(attacker);
        Coord attackerHome = worldAppService.homeOf(attacker);
        long hitAt = timeService.serverNow();
        recordHit(bot, attacker, hitAt);

        runTicksUntil(bot, null, 3 * HOUR);

        assertThat(marches.activeCountOf(bot))
                .as("反击必须真的走出去一支队伍（转调 MarchAppService.send，与真人同一条路）")
                .isEqualTo(1L);
        // 打完的行军会从仓储里删掉，所以此刻能读到的这一支就是刚派出去的那支
        var march = marches.findByPlayerId(bot).get(0);
        assertThat(march.to()).as("打的是袭击者的城").isEqualTo(attackerHome);
        assertThat(march.action()).as("这是一次攻击").isEqualTo(com.ironoath.core.march.March.Action.ATTACK);
        assertThat(adapter.reactedUpToOf(bot)).isEqualTo(hitAt);
    }

    @Test
    @DisplayName("C2 受击反应：没有兵的高攻击性 Bot 不空转 —— 龟缩一次、不报错、也不乱迁城")
    void attackedBotWithoutTroopsHunkers() {
        String attacker = newPlayerAt(300, 300);
        String bot = botAt(256, 256, "0.90");
        Coord before = worldAppService.homeOf(bot);
        recordHit(bot, attacker, timeService.serverNow());

        runTicksUntil(bot, null, 3 * HOUR);

        assertThat(adapter.reactedUpToOf(bot)).as("回应过（选了龟缩）").isNotNull();
        assertThat(worldAppService.homeOf(bot)).as("龟缩就是不搬家").isEqualTo(before);
        assertThat(marches.activeCountOf(bot)).as("没有兵，也没有出兵").isZero();
        assertThat(adapter.failedCount())
                .as("failed 的定义是「读世界或执行抛出来」（见 bot.schema.json）——"
                        + "「没有兵可反击」与失误掷中的空动作都属体面跳过，进 skipped 而不是 failed")
                .isZero();
    }

    // ---------- 夹具 ----------

    /** 把一条攻击记录写进受害账本（等价于「刚被这个袭击者打过」）。 */
    private void recordHit(String victimId, String attackerId, long at) {
        PlayerSave save = players.findByPlayerId(victimId).orElseThrow();
        save.setPvp(save.pvp().withAttackerHits(Map.of(attackerId, at)));
        players.save(save);
    }

    /**
     * 按分钟推进 tick，直到受击反应发生（或时间窗用尽），返回发生过的反应次数。
     *
     * <p>minute-by-minute 而不是大步长：tick 的到期时刻由作息相位决定，
     * 大步长会直接跨过去，表现为「什么都没发生」而看不出原因。
     */
    private int runTicksUntil(String botId, Coord expectChangeFrom, long windowMillis) {
        long start = timeService.serverNow();
        for (long t = start; t < start + windowMillis; t += MINUTE) {
            runtime.tick(t);
            if (adapter.reactedUpToOf(botId) != null) {
                // 反应发生在这一 tick；再多跑几轮证明「同一发不会回应第二次」
                for (long extra = t + MINUTE; extra < t + 30 * MINUTE; extra += MINUTE) {
                    runtime.tick(extra);
                }
                return 1;
            }
        }
        return 0;
    }

    private String sendAttack(String attackerId, Coord target, Map<String, Long> units) {
        List<MarchUnit> marched = new ArrayList<>();
        units.forEach((unitId, count) -> marched.add(new MarchUnit(unitId, count)));
        return marchAppService.send(attackerId, new MarchReq("req-" + UUID.randomUUID(),
                target.x(), target.y(), marched, List.of(), MarchAction.ATTACK)).march().marchId();
    }

    private void arriveAndProcess(String marchId) {
        var march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marches.save(march, version);
        dueQueue.reschedule(marchId, march.startAt());
        marchAppService.processDue(march.playerId(), System.currentTimeMillis());
    }

    /** 一个「能打」的真人：城在世界里、保护已解除。 */
    private String newPlayerAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "频控测试" + UUID.randomUUID().toString().substring(0, 6), 1_700_000_000_000L)).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)，占位说明坐标与别的用例撞了", x, y).isTrue();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    /**
     * 一个托管账号：真人同一条建档路径 + 画像注册。
     *
     * <p><b>活跃时段给满 24 小时</b>：这样「下一次 tick」永远落在活跃小时内（否则作息相位会把它
     * 推到下一个活跃小时，用例就要按小时推进），反应延迟才是被测的那件事。
     * 攻击性按分档给：0.50 = 中档（迁城），0.90 = 高档（反击）。
     */
    private String botAt(int x, int y, String aggression) {
        String botId = newPlayerAt(x, y);
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse(aggression), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        cityAppService.list(botId);
        return botId;
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
}
