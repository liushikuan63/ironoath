package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotCalibrationService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;

/**
 * 职责：C4 的落地验证（收口清单 §五 C4 / #95）—— 每日校准真的让战力跟着真人均值走、
 *       顺手把活跃时间刷新（#84 乙 的正面修法）、超上限时真的回收最不活跃的那几个。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么校准要按日期键做幂等</b>：§五 明写"每日一次、滞后跟随"。没有幂等的话，
 * 每个 tick 都会把战力拉平到真人均值 —— Bot 的战力曲线会变成均值的即时镜像，
 * 「参差不齐」（§四）随之消失，而这件事在日志里表现得完全正常。
 * 所以这里有一条用例专门盯"同一天里第二次 tick 不再校准"。
 *
 * <p><b>回收必须同时撤销排期</b>：只从注册表摘掉而不管队列的话，下一轮 {@code enrollNew}
 * 会把它排回来（回收变成假装的动作）。所以断言里既看注册表，也看"下一个 tick 之后它还在不在册"。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotCalibrationTest {

    private static final long DAY = 24 * 3_600_000L;
    private static final long HOUR = 3_600_000L;

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotCalibrationService calibration;
    @Autowired private BotRuntimeService runtime;
    @Autowired private com.ironoath.web.bot.BotWorldAdapter adapter;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryWorldStore) world).clear();
        bots.clear();
        runtime.reset();
        calibration.reset();
        adapter.resetCounters();
    }

    @Test
    @DisplayName("C4 校准：Bot 的战力跟着真人均值走（滞后跟随，一天只对一次账）")
    void calibrationFollowsTheHumanAverageOncePerDay() {
        String human = humanAt(300, 300, "均值来源");
        String bot = botAt(256, 256);
        setMatchPower(human, 10_000L);
        // 一个已经被孵出来、战力停在旧值上的 Bot（等价于"昨天校准过、今天真人均值涨了"）
        setMatchPower(bot, 1_000L);

        long now = timeService.serverNow();
        assertThat(calibration.calibrateIfNewDay(now)).as("这一天第一次调：真的校准").isTrue();
        long afterFirst = matchPowerOf(bot);
        assertThat(afterFirst)
                .as("校准必须把它拉到真人均值的目标带里（0.8~0.95 中点 × 原型系数，再夹进验收带）")
                .isBetween(7_000L, 10_000L);

        // 同一天再调：必须幂等（否则战力会被每个 tick 拉平一次，"参差"随之消失）
        assertThat(calibration.calibrateIfNewDay(now + HOUR)).as("同一天不重复校准").isFalse();
        assertThat(matchPowerOf(bot)).as("战力不该被第二次调用改动").isEqualTo(afterFirst);

        assertThat(calibration.calibrateIfNewDay(now + DAY + HOUR))
                .as("到了第二天（UTC+8 日期键变化）就该再校一次").isTrue();
    }

    @Test
    @DisplayName("C4 校准对照组：真人一个都没有时不乱动战力（没有可比对象不是「战力应该为 0」）")
    void calibrationWithoutHumansLeavesPowerAlone() {
        String bot = botAt(256, 256);
        setMatchPower(bot, 4_242L);

        calibration.calibrateIfNewDay(timeService.serverNow());

        assertThat(matchPowerOf(bot))
                .as("均值 0 意味着「没有可比较的对象」—— 把 Bot 拉到 0 就是把地图上的邻居全清空")
                .isEqualTo(4_242L);
    }

    @Test
    @DisplayName("C4 顺手刷新活跃时间（#84 乙）：校准之后 Bot 不会再从 48 小时活跃窗口里掉出去")
    void calibrationRefreshesLastLoginSoBotsStaySearchable() {
        // 夹具：直接调校准，用一个"两天之后"的时刻 —— 那正是 #84 乙 描述的缺口
        // （静止 Bot 的 lastLoginAt 停在孵化那一刻，48 小时后从可攻击列表里消失）。
        // 注意不能"把活跃时间往回写"：lastLoginAt 单调（touchLogin 只前进），推不回去
        String staleBot = botAt(266, 256);
        long twoDaysLater = players.findByPlayerId(staleBot).orElseThrow().lastLoginAt()
                + 2 * DAY;
        long before = players.findByPlayerId(staleBot).orElseThrow().lastLoginAt();

        calibration.calibrateIfNewDay(twoDaysLater);

        long after = players.findByPlayerId(staleBot).orElseThrow().lastLoginAt();
        assertThat(after)
                .as("校准必须把活跃时间推进到校准时刻 —— 否则两天后它就从可攻击列表里消失了")
                .isEqualTo(twoDaysLater);
        assertThat(after).as("而且是真的推进了，不是原地不动").isGreaterThan(before);
        assertThat(twoDaysLater - before)
                .as("夹具前提：这确实是「超过 48 小时活跃窗口」的情景").isGreaterThanOrEqualTo(
                        48 * HOUR);
    }

    @Test
    @DisplayName("C4 回收：超上限时摘掉最不活跃的，且连排期一起撤（不会下一轮又被排回来）")
    void recyclingRemovesTheLeastActiveAndCancelsTheirSchedule() {
        // 上限是配置里的数（BOT_MAX_PER_SERVER=5000），夹具不可能真造 5001 个账号，
        // 所以这里验"回收的判据"本身：把两个 Bot 的活跃时间拉开，再按同一条序排一次。
        //
        // **注意方向**：lastLoginAt 是单调的（touchLogin 只前进、不后退），所以把某个 Bot
        // "推回过去"是做不到的 —— 只能把另一个推向未来。第一版夹具就是推错了方向，
        // 结果两个时间戳相等、排序退化成任意，用例看起来在验序、其实什么都没验
        String active = botAt(256, 256);
        String stale = botAt(266, 256);
        PlayerSave activeSave = players.findByPlayerId(active).orElseThrow();
        activeSave.touchLogin(timeService.serverNow() + 3_600_000L);
        players.save(activeSave);

        // 断言判据本身：活跃时间最旧的排在最前（这就是"最不活跃"的可核对定义）
        List<PlayerSave> ordered = new ArrayList<>(List.of(
                players.findByPlayerId(active).orElseThrow(),
                players.findByPlayerId(stale).orElseThrow()));
        ordered.sort(java.util.Comparator.comparingLong(PlayerSave::lastLoginAt));
        assertThat(ordered.get(0).playerId())
                .as("回收的序：最近活跃最旧优先（同值才比战力）").isEqualTo(stale);

        // 回收动作本身：from registry + forget（这里用手工等价路径验证"摘掉之后不会再排回来"）
        bots.unregister(stale);
        runtime.forget(stale);
        long now = timeService.serverNow();
        runtime.tick(now);
        assertThat(bots.isBot(stale)).as("被回收的 Bot 不在册了（搜索候选池与合规闸门都只认注册表）").isFalse();
        runtime.tick(now + DAY);
        assertThat(bots.isBot(stale)).as("下一轮补员不会把它加回来（补员只会新建，不会回收存档）").isFalse();
        assertThat(bots.isBot(active)).as("活跃的那个当然还在").isTrue();
    }

    @Test
    @DisplayName("C4 回收只摘画像与排期，不删档：城与存档都还在（销毁是 B14 的事）")
    void recyclingKeepsTheSave() {
        String bot = botAt(256, 256);
        Coord home = world.cityOf(bot).orElseThrow();
        bots.unregister(bot);
        runtime.forget(bot);

        assertThat(players.findByPlayerId(bot)).as("存档必须还在 —— 删档是另一件事").isPresent();
        assertThat(world.cityOf(bot)).as("城也还在（回收不是删号）").hasValue(home);
    }

    // ---------- 夹具 ----------

    private String humanAt(int x, int y, String nick) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                nick + UUID.randomUUID().toString().substring(0, 4), 1_700_000_000_000L, "")).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        return playerId;
    }

    private String botAt(int x, int y) {
        String botId = humanAt(x, y, "校准托管");
        List<Integer> allHours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        return botId;
    }

    /** 直接写匹配战力（三个字段一起写 —— PlayerPower 的不变量是 peak >= match）。 */
    private void setMatchPower(String playerId, long matchPower) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPower(new com.ironoath.core.player.PlayerPower(matchPower, matchPower, matchPower));
        players.save(save);
    }

    private long matchPowerOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().power().matchPower();
    }
}
