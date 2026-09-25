package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.PowerBandGuard;
import com.ironoath.core.power.Protection;
import com.ironoath.core.power.TargetSearch;
import com.ironoath.core.power.Tyranny;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.DistanceBand;
import com.ironoath.web.dto.generated.ResourceHint;
import com.ironoath.web.dto.generated.SearchTargetsReq;
import com.ironoath.web.dto.generated.SearchTargetsResp;
import com.ironoath.web.dto.generated.TargetBrief;
import com.ironoath.web.power.MatchPool;
import com.ironoath.web.reward.ServerSeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：目标搜索应用服务（B08 §8）—— 组装候选池、调用 game-core 的纯搜索、映射成响应。
 * 依赖：世界仓储（城位置）、玩家仓储（批量读存档）、{@link PowerService}（口径装配）、
 * {@link PowerRefreshService}（自己的战力当场重算）。
 *
 * <p><b>本类不含任何规则</b>。过滤条件、权重、分档阈值全在 {@link TargetSearch}（game-core，纯 Java），
 * 这样验收 11 的「1000 玩家采样」能在纯 JUnit 里跑而不必启动 Spring。
 * 本类只做三件容器才能做的事：读存档、取服务端时间、发种子。
 *
 * <p><b>自己的战力当场重算，候选的战力读存档</b>，这个不对称是有意的：
 * <ul>
 *   <li>自己是判定的基准，错一点整个区间就平移了，所以必须重算（{@link PowerRefreshService}）。</li>
 *   <li>候选有上千个，逐个重算等于一次搜索打出几千次存储读。存档里的战力由
 *       {@code PowerRefreshInterceptor} 在每次写操作后刷新，因此它是新鲜的。</li>
 * </ul>
 * 刻意<b>不读 {@link MatchPool} 的缓存值</b>：池是进程内的，多实例部署时会滞后，
 * 而滞后的后果是「本该能搜到的人搜不到」—— 玩家会把它理解成圈层坏了，
 * 这比多读一次存档严重得多。池的用途是排行榜与联盟列表（B08 §1 的另外两个订阅方）。
 *
 * <p><b>响应里没有精确距离、没有精确资源量、没有 isBot</b>（验收 12、B07 禁止项、B11 合规）。
 * 距离与富度在 {@link TargetSearch} 里就已经被压成三档，本类拿到的就是档位 ——
 * 精确值根本不进入本类，所以不存在「不小心序列化出去」的可能。
 */
@Service
public class TargetSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(TargetSearchService.class);

    private final ConfigRegistry configs;
    private final WorldRepository world;
    private final PlayerRepository players;
    private final PowerService powerService;
    private final PowerRefreshService powerRefreshService;
    private final WorldAppService worldAppService;
    private final MatchPool matchPool;
    private final ServerSeedSource seeds;
    private final TimeService timeService;

    public TargetSearchService(ConfigRegistry configs, WorldRepository world,
                               PlayerRepository players, PowerService powerService,
                               PowerRefreshService powerRefreshService,
                               WorldAppService worldAppService, MatchPool matchPool,
                               ServerSeedSource seeds, TimeService timeService) {
        this.configs = configs;
        this.world = world;
        this.players = players;
        this.powerService = powerService;
        this.powerRefreshService = powerRefreshService;
        this.worldAppService = worldAppService;
        this.matchPool = matchPool;
        this.seeds = seeds;
        this.timeService = timeService;
    }

    /**
     * 搜索可攻击目标。
     *
     * <p>半径超上限时<b>截断而不是拒绝</b>：玩家拖滑块很容易越界，
     * 返回一个错误码会让他以为搜索坏了（协议注释里已经写明这条口径）。
     */
    public SearchTargetsResp search(String playerId, SearchTargetsReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        long now = timeService.serverNow();
        TargetSearch.Rules searchRules = powerService.searchRules();
        PowerBandGuard.Rules bandRules = powerService.bandRules();
        Protection.Rules protectionRules = powerService.protectionRules();
        Tyranny.Rules tyrannyRules = powerService.tyrannyRules();

        int radius = searchRules.resolveRadius(req.radius());
        if (req.radius() != null && req.radius() > searchRules.maxRadius()) {
            LOG.info("搜索半径超出上限，已截断 playerId={} 请求={} 上限={}",
                    playerId, req.radius(), searchRules.maxRadius());
        }

        // 自己：当场重算，这是整个区间的基准
        PlayerPower selfPower = powerRefreshService.refresh(playerId).power();
        Coord selfCoord = worldAppService.homeOf(playerId);
        PlayerSave self = players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND,
                        "存档不存在：" + playerId));
        TargetSearch.Candidate selfCandidate = candidateOf(
                self, selfCoord, selfPower.matchPower(), protectionRules, now);

        Pool pool = candidatePool(selfCoord, radius, protectionRules, now);
        List<TargetSearch.Scored> scored = TargetSearch.search(selfCandidate, pool.candidates(),
                new TargetSearch.Request(radius, req.maxCount(), searchRules.activeCutoff(now)),
                searchRules, bandRules, Rng.of(seeds.nextSeed()));
        List<TargetBrief> targets = new ArrayList<>(scored.size());
        for (TargetSearch.Scored item : scored) {
            targets.add(briefOf(item, tyrannyRules, pool.names()));
        }
        PowerBandGuard.Band band = PowerBandGuard.bandOf(selfPower.matchPower(), 1, bandRules);
        LOG.info("目标搜索 playerId={} 半径={} 候选={} 命中={} 匹配战力={} 区间=[{},{}] 池大小={}",
                playerId, radius, pool.candidates().size(), targets.size(), selfPower.matchPower(),
                band.lowerBound(), band.upperBound(), matchPool.size());
        return new SearchTargetsResp(targets, selfPower.matchPower(),
                band.lowerBound(), band.upperBound(),
                TargetSearch.MIN_RADIUS, searchRules.defaultRadius(), searchRules.maxRadius(),
                now);
    }

    /**
     * 候选池与它的昵称表。
     *
     * <p>昵称不放进 {@link TargetSearch.Candidate}：纯搜索的过滤与排序都用不到它，
     * 而 game-core 的候选对象每多一个字段，验收 11 那种上千次的采样构造就多一份噪音。
     * 昵称只是响应组装时需要，所以留在应用层。
     */
    private record Pool(List<TargetSearch.Candidate> candidates, Map<String, String> names) {
    }

    /**
     * 组装候选池：全世界已落位的城 → 半径内 → 批量读存档 → 转成 {@link TargetSearch.Candidate}。
     *
     * <p><b>先用坐标做半径过滤，再批量读存档</b>。反过来做（先读全部存档再过滤）
     * 会让一次搜索读入全服玩家 —— 而半径本身就能砍掉绝大部分候选，
     * 这个顺序差别在 1000 人的服上是「读 1000 份」和「读几十份」的区别。
     *
     * <p>TODO(B16)：半径过滤应当下推到存储层（空间索引），
     * 见 {@link WorldRepository#allCities()} 的注释。
     */
    private Pool candidatePool(Coord selfCoord, int radius, Protection.Rules protectionRules,
                               long now) {
        Map<String, Coord> cities = world.allCities();
        List<String> nearby = new ArrayList<>();
        cities.forEach((candidateId, coord) -> {
            if (coord != null && selfCoord.distanceTo(coord) <= radius) {
                nearby.add(candidateId);
            }
        });
        if (nearby.isEmpty()) {
            return new Pool(List.of(), Map.of());
        }
        Map<String, PlayerSave> saves = players.findByPlayerIds(nearby);
        List<TargetSearch.Candidate> candidates = new ArrayList<>(saves.size());
        Map<String, String> names = new java.util.LinkedHashMap<>(saves.size());
        saves.forEach((candidateId, save) -> {
            Coord coord = cities.get(candidateId);
            if (coord == null) {
                return;
            }
            // 存档里的战力由 PowerRefreshInterceptor 在每次写操作后刷新，所以是新鲜的。
            // 池里没有这个玩家（例如从未发生过写操作）时，回落到存档值 —— 两者本就同源
            long matchPower = matchPool.matchPowerOr(candidateId, save.power().matchPower());
            candidates.add(candidateOf(save, coord, matchPower, protectionRules, now));
            names.put(candidateId, save.nickName());
        });
        return new Pool(List.copyOf(candidates), names);
    }

    /** 把一份存档转成搜索用的候选。所有过滤都在 game-core 里做，这里只负责取数。 */
    private TargetSearch.Candidate candidateOf(PlayerSave save, Coord coord, long matchPower,
                                               Protection.Rules protectionRules, long now) {
        Protection.Status status = Protection.statusOf(now, save.cityLevel(),
                save.protectUntil(), save.pvp().victimShieldUntil(),
                save.pvp().peaceUntil(), protectionRules);
        // 暴虐值也要按天补衰减：搜索是读路径，不衰减就会把一个几个月前的大佬永久标成公敌
        long tyranny = Tyranny.decay(save.pvp().tyranny(),
                powerService.daysSince(save.pvp().tyrannyTouchedAt(), now),
                powerService.tyrannyRules());
        return new TargetSearch.Candidate(save.playerId(), coord, matchPower,
                status.isProtected(),
                // 联盟数据在 B10 才落到存档上。传 null 而不是空串：
                // TargetSearch 的「非同盟」过滤只在两边都非 null 时生效，
                // 空串会让所有无联盟玩家被判成「同盟」，于是谁都搜不到谁
                null,
                save.lastLoginAt(),
                resourceFill(save),
                tyranny);
    }

    /**
     * 库存富度 = 基础资源总库存 / 总容量，定点 [0, 1.0]。
     *
     * <p>只算 BASE 资源，不算金币：金币是付费货币，
     * 把它算进「这个人富不富」会让付费玩家的仓库看起来永远是满的，
     * 于是他们被排序推到最前面 —— 那等于用搜索机制给付费玩家挂了一个「来打我」的牌子。
     */
    private long resourceFill(PlayerSave save) {
        long current = 0L;
        long cap = 0L;
        for (ResourceCfg resource : configs.allResources()) {
            if (resource.kind() != ResourceCfg.Kind.BASE || !save.hasResource(resource.id())) {
                continue;
            }
            PlayerResourceState state = save.resource(resource.id());
            current += state.current();
            cap += state.cap();
        }
        if (cap <= 0L) {
            return 0L;
        }
        return FixedPoint.div(FixedPoint.of(current), FixedPoint.of(cap));
    }

    private TargetBrief briefOf(TargetSearch.Scored item, Tyranny.Rules tyrannyRules,
                                Map<String, String> names) {
        Tyranny.Level level = Tyranny.levelOf(item.candidate().tyranny(), tyrannyRules);
        String playerId = item.candidate().playerId();
        return new TargetBrief(
                playerId,
                // 昵称取不到时回落到 id：搜到目标却显示空白名字，玩家会以为是数据坏了
                names.getOrDefault(playerId, playerId),
                new com.ironoath.web.dto.generated.Coord(
                        item.candidate().coord().x(), item.candidate().coord().y()),
                item.candidate().matchPower(),
                item.powerRatio(),
                DistanceBand.valueOf(item.distanceBand().name()),
                ResourceHint.valueOf(item.resourceHint().name()),
                item.candidate().shielded(),
                // 平民档不下发：那一档没有任何效果，下发只会让 UI 多一个无意义的标签
                level == Tyranny.Level.COMMONER ? null : level.name());
    }
}
