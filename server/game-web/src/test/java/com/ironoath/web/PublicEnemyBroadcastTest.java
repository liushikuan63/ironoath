package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.Tyranny;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.service.PublicEnemyBroadcaster;
import com.ironoath.web.service.WorldAppService;

/**
 * 职责：公敌档「每 6 小时全服广播」的落地验证（B08 §4 第四档、C01 §2.3）。
 * 依赖：Spring Boot Test + test profile（内存存储）。
 *
 * <p><b>这一档此前只有 {@code Tyranny.triggersBroadcast} 这个判定函数，零调用点</b> ——
 * 与 {@code BotTuning#mayHoldOffice} 同一家族：写了判定不等于接上了
 * （那条已于 2026-09-11 接上，见收口清单 #89）。
 * 本类盯的是三件「没接上也不会报错」的事：
 * <ol>
 *   <li>登记之后真的会发出一条公告，而不是永远等在名单上；</li>
 *   <li>一个广播间隔内<b>只</b>发一条（重复读图不该刷屏）；</li>
 *   <li>暴虐值衰减到不再触发广播时会<b>自己摘出</b>名单 —— 公告说的是现在的靶子，不是历史。</li>
 * </ol>
 *
 * <p>计数断言全部取「增量」：广播器是进程内单例，同一份 Spring 上下文里别的用例也可能留下登记，
 * 绝对值会随执行顺序漂移。
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicEnemyBroadcastTest {

    @Autowired private PublicEnemyBroadcaster broadcaster;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PlayerRepository players;
    @Autowired private PowerService powerService;

    /** 复位广播器：名单与节流预算都是进程内的。 */
    @BeforeEach
    void resetBroadcaster() {
        // 名单与节流预算都是进程内的。不复位的话，一条用例把时间轴推到未来做 sweep，
        // 另一条用真实当前时间的就会被节流挡到早退 —— 症状和「广播根本没接上」一模一样
        broadcaster.clear();
    }

    @Test
    @DisplayName("登记过的公敌：推进一次就发公告，而半个间隔之内再推进不会发第二条，满一个间隔才再来一条")
    void broadcastsOncePerInterval() {
        long interval = broadcaster.intervalMillis();
        // 刻意从「现在 + 两个间隔」起步：sweep 有一个节流预算，别的用例先跑过就会把它顶到未来，
        // 用真实当前时间的用例会被无辜地早退。整条用例都在同一个合成时间轴上走，节流就与执行顺序无关
        long t0 = System.currentTimeMillis() + 2 * interval;
        // 7000 而不是 700：见 publicEnemy 的说明（跨过日切也不能掉出公敌档）
        String enemy = publicEnemy(7_000L, t0);
        long before = broadcaster.broadcastCount();

        broadcaster.sweep(t0);
        assertThat(broadcaster.broadcastCount())
                .as("名单上有他却从来不发，这条公告就只活在文档里").isEqualTo(before + 1);

        // 越过 sweep 的节流预算（间隔的 1/60），但还没到一个广播间隔
        broadcaster.sweep(t0 + interval / 60L + 1L);
        assertThat(broadcaster.broadcastCount())
                .as("6 小时一条的公告被拖图刷成刷屏，等于没有公告").isEqualTo(before + 1);

        // 满一个间隔：该再来一条
        broadcaster.sweep(t0 + interval + interval / 60L + 2L);
        assertThat(broadcaster.broadcastCount()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("暴虐值衰减到不再触发广播时自动摘出名单：不先摘人，公敌就成了一块摘不掉的标签")
    void dropsFromListOnceDecayed() {
        long now = System.currentTimeMillis();
        // 五天前冲到 700，按 20%/日 衰减只剩约 229 ⇒ 强横档，不再触发广播
        String exEnemy = publicEnemy(700L, now - 5L * 24 * 3_600_000L);
        broadcaster.noteLevel(exEnemy, Tyranny.Level.PUBLIC_ENEMY);
        assertThat(broadcaster.isTracked(exEnemy)).as("夹具：先让他进名单").isTrue();

        long interval = broadcaster.intervalMillis();
        // +一个间隔是为了越过 sweep 的节流预算，保证这里验的是定档分支而不是节流
        broadcaster.sweep(now + interval);

        assertThat(broadcaster.isTracked(exEnemy))
                .as("衰减后已经不是公敌，却继续每 6 小时替他上一次全服公告")
                .isFalse();
    }

    // ---------- 夹具 ----------

    /**
     * 造一个处于指定暴虐值的玩家并登记进广播名单。
     *
     * <p>档位由生产同一套定档算法算出（{@code Tyranny.levelOf} + 表里的三档阈值），
     * 测试里不写死「700 就是公敌」—— 阈值改了这条用例要跟着改，而不是悄悄测到别的东西。
     *
     * <p>第一条用例为什么用 7000：广播间隔是 6 小时，而暴虐值按<b>日切</b>补衰减。
     * 一条推进到 6 小时之后的用例如果踩上日切就会少 20%，700 会变成 560 掉出公敌档 ——
     * 那样这条断言就成了「今天几点跑测试」的函数。7000 衰减一天仍有 5600，跨过日切也还是公敌。
     */
    private String publicEnemy(long tyranny, long touchedAt) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "公敌测试",
                1_700_000_000_000L)).playerId();
        worldAppService.homeOf(playerId);
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPvp(save.pvp().withTyranny(tyranny, touchedAt));
        players.save(save);
        broadcaster.noteLevel(playerId, Tyranny.levelOf(tyranny, powerService.tyrannyRules()));
        return playerId;
    }
}
