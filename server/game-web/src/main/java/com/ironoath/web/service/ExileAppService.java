package com.ironoath.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.web.dto.generated.ExileReq;
import com.ironoath.web.dto.generated.ExileResp;
import com.ironoath.web.reward.ServerSeedSource;

/**
 * 职责：流亡迁城（B08 §5 反击工具箱第 4 条）—— 免费随机落点 + 落地免战 + 滚动冷却。
 * 依赖：{@link WorldAppService}（落点与迁移两个世界原语）、{@link MarchRepository}（在外的队伍）、
 *       {@link PlayerRepository}（免战与冷却都写在存档上）、幂等与玩家锁。
 *
 * <p><b>它是公理二那条纪律的正面样本</b>：被打到没法玩的人，除了弃号与叫人，第三条出路是「走」。
 * 免战走的是 {@code PlayerPvp#withPeaceUntil}（自愿停战那本账，与 {@code Kind.PEACE} 同源），
 * <b>刻意不算进公理二那三条系统保护</b> —— 它是玩家主动选择、且要付冷却代价的出路，
 * 不是系统可怜他。界线与免战牌道具一致，写在 {@code Protection.Kind} 的注释里。
 *
 * <p><b>三件刻意不做的事</b>：
 * <ol>
 *   <li><b>不让玩家指定落点</b>：协议里除了 requestId 什么都没有。能指定落点就等于
 *       可以精准迁到仇人隔壁（挑衅）或某块肥资源点正上方（把迁城变成采集外挂），
 *       而 B08 原文给的就是「随机」。</li>
 *   <li><b>不加最小迁移距离</b>：原文没写。要加得先进配置表，见
 *       {@link WorldAppService#randomFreeCoord(long)} 的注释。</li>
 *   <li><b>不在有队伍在外时允许迁城</b>：{@code March.returnFrom} 存的是<b>坐标</b>，
 *       城搬走之后那支队伍会回到一个已经没有它主人的格子 —— 兵永远不会归队，
 *       而日志一切正常。拒绝并给出「先召回」的文案，比留下这种队伍好得多。</li>
 * </ol>
 *
 * <p><b>写入顺序</b>：先迁世界坐标，成功后才写存档里的免战与冷却。反过来会出现
 * 「免战给了、城没搬」—— 玩家付了冷却代价却没跑掉，且这个状态无法从响应里看出来。
 */
@Service
public class ExileAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ExileAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final MarchRepository marches;
    private final WorldAppService worldAppService;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ServerSeedSource seeds;

    public ExileAppService(ConfigRegistry configs, PlayerRepository players,
                           MarchRepository marches, WorldAppService worldAppService,
                           PlayerLock playerLock, IdempotencyStore idempotency,
                           TimeService timeService, ServerSeedSource seeds) {
        this.configs = configs;
        this.players = players;
        this.marches = marches;
        this.worldAppService = worldAppService;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.seeds = seeds;
    }

    /** 冷却时长（毫秒）。公开是为了让状态查询与响应下发用同一个数，不在两处各读一次表。 */
    public long cooldownMillis() {
        return configs.longParam("EXILE_COOLDOWN_SECONDS") * 1000L;
    }

    /** 落地免战时长（毫秒）。 */
    public long peaceMillis() {
        return configs.longParam("EXILE_PEACE_SECONDS") * 1000L;
    }

    /** 此刻下一次可流亡的时刻；从未流亡过返回 0（表示随时可以）。 */
    public long nextExileAt(PlayerSave save, long now) {
        Long last = save.pvp().exileAt();
        if (last == null) {
            return 0L;
        }
        long ready = last + cooldownMillis();
        return ready <= now ? 0L : ready;
    }

    /**
     * 给状态下发用的可空形态：协议里「随时可以」的表达是 {@code null}，不是 0。
     *
     * <p>0 是本类内部的哨兵值，让它跨过协议边界就会变成客户端要写 {@code if (nextExileAt > 0)}，
     * 而一个 1970 年的时间戳在两种表达看起来一模一样 —— 这类「用 0 兼任哨兵」的坑本项目踩过几次。
     */
    public Long nextExileAtOrNull(PlayerSave save, long now) {
        long ready = nextExileAt(save, now);
        return ready == 0L ? null : ready;
    }

    /** 发起一次流亡迁城。 */
    public ExileResp exile(String playerId, ExileReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        // 先占后做：迁城是「改世界坐标 + 写冷却」两件事，重放一次就等于白送一次搬家
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doExile(playerId, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private ExileResp doExile(String playerId, long now) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow(() -> new BizException(
                ErrorCode.PLAYER_NOT_FOUND, "存档不存在：" + playerId));

        long ready = nextExileAt(save, now);
        if (ready > 0L) {
            throw new BizException(ErrorCode.EXILE_ON_COOLDOWN,
                    "流亡迁城每 " + cooldownMillis() / 3_600_000L + " 小时只能一次，还有 "
                            + (ready - now) / 60_000L + " 分钟可用（nextExileAt=" + ready + "）");
        }
        long away = marches.activeCountOf(playerId);
        if (away > 0L) {
            throw new BizException(ErrorCode.EXILE_TROOPS_AWAY,
                    "还有 " + away + " 支队伍在外，先召回再搬家（在外的队伍会回到旧址，而不是搬到新家）");
        }

        Coord from = worldAppService.homeOf(playerId);
        long seed = seeds.nextSeed();
        Coord to = worldAppService.randomFreeCoord(seed);
        if (!worldAppService.relocateCity(playerId, to)) {
            // 拿不到格子是存储层的事实，不静默换一个"差不多"的位置：让玩家看到失败，
            // 而不是搬到一个他没同意、也说不清为什么的地方
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "落点在被占用的瞬间失败了（seed=" + seed + "，目标 " + to + "），请重试");
        }
        long peaceUntil = now + peaceMillis();
        save.setPvp(save.pvp().withExileAt(now).withPeaceUntil(peaceUntil));
        players.save(save);
        LOG.info("流亡迁城 playerId={} {} ⇒ {} seed={} 免战至={} 下次可流亡={}（旧块与新块都已变新）",
                playerId, from, to, seed, peaceUntil, now + cooldownMillis());
        return new ExileResp(new com.ironoath.web.dto.generated.Coord(to.x(), to.y()),
                peaceUntil, now + cooldownMillis(), seed, now);
    }
}
