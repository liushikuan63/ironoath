package com.ironoath.web.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.Tyranny;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.ws.SocialPushPublisher;

/**
 * 职责：公敌档的「每 6 小时全服广播」（B08 §4 第四档、C01 §2.3）。
 * 依赖：{@link PlayerRepository}（读暴虐值）、{@link WorldRepository}（读城坐标）、
 *       {@link PowerService}（衰减与定档口径）、{@link SocialPushPublisher}（异步推送）。
 *
 * <p><b>为什么没有定时器</b>：本项目无条件禁止服务端常驻定时调度（{@code scripts/check-layering.sh}
 * 与 B00 铁律同一条纪律），而给一条玩法开豁免的代价太大。所以走 {@code TrackFlusher} 已经立好的先例 ——
 * <b>惰性驱动</b>：视野下发是全服最频繁的读路径，谁先来读图，谁就把这条广播顺手推进一次。
 * 频率由一个派生上限兜住（见 {@link #sweepBudgetMillis}），不会每次读图都扫。
 *
 * <p><b>名单是进程内的，且只是「谁曾经是公敌」的提示，不是事实来源</b>。
 * 每一次 sweep 都会重新读存档、补衰减、再定档，所以：
 * <ul>
 *   <li>暴虐值已经衰减掉的人会在下一次 sweep 被摘出名单，不会继续被广播 —— 进程内名单就算过期，
 *       也不会有一个「不该被广播的人」被广播出去；</li>
 *   <li>代价是重启会清空名单，重新登记要等这个人下一次跨过公敌线。这是内存存储那一族待办
 *       （与 {@code MatchPool} 同源），上线前要外置。</li>
 * </ul>
 *
 * <p><b>不做离线补偿</b>：给全服每个人落一条事件是 O(在线人数×服务器规模) 的写放大，
 * 而这条公告的兜底本来就存在 —— 公敌的坐标已经不受迷雾保护（{@link Tyranny#exposesCoordinate}），
 * 离线的人一上线打开地图就看见那个红名城挂在图上了。
 */
@Service
public class PublicEnemyBroadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(PublicEnemyBroadcaster.class);

    /** 广播的事件类型，客户端据此分派。 */
    public static final String EVENT_TYPE = "PUBLIC_ENEMY";

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final WorldRepository world;
    private final PowerService powerService;
    private final SocialPushPublisher publisher;

    /** 公敌 → 上次广播时刻（0 表示还没广播过，下一次 sweep 就发）。 */
    private final Map<String, Long> lastBroadcastAt = new ConcurrentHashMap<>();
    /** 下一次允许 sweep 的时刻。 */
    private volatile long nextSweepAt;
    private final java.util.concurrent.atomic.AtomicLong broadcasts = new java.util.concurrent.atomic.AtomicLong();

    public PublicEnemyBroadcaster(ConfigRegistry configs, PlayerRepository players,
                                  WorldRepository world, PowerService powerService,
                                  SocialPushPublisher publisher) {
        this.configs = configs;
        this.players = players;
        this.world = world;
        this.powerService = powerService;
        this.publisher = publisher;
    }

    /** 广播间隔（毫秒），来自 {@code global.PUBLIC_ENEMY_BROADCAST_SECONDS}。 */
    public long intervalMillis() {
        return configs.longParam("PUBLIC_ENEMY_BROADCAST_SECONDS") * 1000L;
    }

    /**
     * 两次 sweep 之间的最小间隔：广播间隔的六十分之一（6 小时 ⇒ 6 分钟）。
     *
     * <p>刻意从间隔<b>派生</b>而不是再写一个常数：再写一个数就多一个「改了间隔却忘了改节流」的机会，
     * 而那种漂移的症状是广播频率和公告上的那句话不一致。六十分之一保证一个间隔内最多漏 1/60 的延迟，
     * 对一条 6 小时一次的公告没有感知差别。
     */
    private long sweepBudgetMillis() {
        return Math.max(1L, intervalMillis() / 60L);
    }

    /**
     * 档位变化时登记或摘出。由战斗结算在算出新档位之后调用。
     *
     * <p>这里<b>不直接广播</b>：广播发生在 sweep，因为只有那里知道「上一次是什么时候发的」。
     * 在结算里广播会让一个人被连续碾压十次就发十条公告。
     */
    public void noteLevel(String playerId, Tyranny.Level level) {
        if (Tyranny.triggersBroadcast(level)) {
            lastBroadcastAt.putIfAbsent(playerId, 0L);
        } else {
            lastBroadcastAt.remove(playerId);
        }
    }

    /**
     * 推进一次广播。调用方是视野下发，所以本方法<b>必须</b>在预算内早退，
     * 否则一次拖图就会变成读 N 个人存档。
     */
    public void sweep(long now) {
        if (now < nextSweepAt || lastBroadcastAt.isEmpty()) {
            return;
        }
        nextSweepAt = now + sweepBudgetMillis();
        long intervalMillis = intervalMillis();
        Tyranny.Rules rules = powerService.tyrannyRules();
        for (String playerId : List.copyOf(lastBroadcastAt.keySet())) {
            PlayerSave save = players.findByPlayerId(playerId).orElse(null);
            if (save == null) {
                lastBroadcastAt.remove(playerId);
                continue;
            }
            // 每一次都重新补衰减再定档：名单只是提示，存档才是事实
            long tyranny = Tyranny.decay(save.pvp().tyranny(),
                    powerService.daysSince(save.pvp().tyrannyTouchedAt(), now), rules);
            Tyranny.Level level = Tyranny.levelOf(tyranny, rules);
            if (!Tyranny.triggersBroadcast(level)) {
                lastBroadcastAt.remove(playerId);
                LOG.info("公敌已自然回落，摘出广播名单 playerId={} 暴虐={} 档位={}", playerId, tyranny, level);
                continue;
            }
            Long last = lastBroadcastAt.get(playerId);
            if (last != null && last != 0L && now - last < intervalMillis) {
                continue;
            }
            // 先登记再发：推送是有界队列，满了会丢。宁可少一条公告，也不能因为丢了一条
            // 就在同一个 6 小时窗口里反复重投（那会把公告变成刷屏）
            lastBroadcastAt.put(playerId, now);
            broadcast(playerId, save, tyranny, now, intervalMillis);
        }
    }

    private void broadcast(String playerId, PlayerSave save, long tyranny, long now,
                           long intervalMillis) {
        Long x = world.cityOf(playerId).map(c -> (long) c.x()).orElse(null);
        Long y = world.cityOf(playerId).map(c -> (long) c.y()).orElse(null);
        SocialStore.SocialEvent event = new SocialStore.SocialEvent(
                "evt_public_enemy_" + playerId + "_" + now, EVENT_TYPE,
                "公敌 " + save.nickName() + " 正在被全服围剿",
                "坐标已暴露，任何人对他的攻击都有围剿加成", x, y, playerId, now,
                now + intervalMillis);
        int candidates = publisher.broadcastToOnline(EVENT_TYPE, event);
        broadcasts.incrementAndGet();
        LOG.info("公敌全服广播 playerId={} 暴虐={} 在线候选={} 人（离线者靠坐标暴露兜底）",
                playerId, tyranny, candidates);
    }

    /** 当前在广播名单上的人数（监控与埋点用）。 */
    public int trackedCount() {
        return lastBroadcastAt.size();
    }

    /** 这个人此刻是否在广播名单上。总量指标回答不了「具体那一个还在不在」，所以两个都要有。 */
    public boolean isTracked(String playerId) {
        return lastBroadcastAt.containsKey(playerId);
    }

    /** 累计发出的公敌公告条数（监控与埋点用）。 */
    public long broadcastCount() {
        return broadcasts.get();
    }

    /**
     * 清空名单与节流预算。语义等同于重启（这份名单本来就在进程内）。
     *
     * <p>存在的理由与仓库里每个内存存储的 {@code clear()} 相同：用例之间必须能复位。
     * 不复位的后果不是脏数据而是<b>互相顶掉节流预算</b> —— 一条用例把时间轴推到未来做 sweep，
     * 下一条用例用真实当前时间就永远早退，于是失败看起来像「广播坏了」。
     */
    public void clear() {
        lastBroadcastAt.clear();
        nextSweepAt = 0L;
    }
}
