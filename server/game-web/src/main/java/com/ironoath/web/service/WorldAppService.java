package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.core.march.March;
import com.ironoath.core.power.Tyranny;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.scout.ScoutReport;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.ChunkData;
import com.ironoath.web.dto.generated.ChunkVersion;
import com.ironoath.web.dto.generated.MarchStatus;
import com.ironoath.web.dto.generated.ScoutListResp;
import com.ironoath.web.dto.generated.ScoutMetric;
import com.ironoath.web.dto.generated.ScoutReportView;
import com.ironoath.web.dto.generated.ViewportReq;
import com.ironoath.web.dto.generated.ViewportResp;
import com.ironoath.web.dto.generated.WorldEntity;
import com.ironoath.web.dto.generated.WorldEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：世界地图应用服务 —— 分块视野下发、家坐标落位、迷雾、侦查报告（B07 §1/§3）。
 * 依赖：game-core 的世界生成与仓储、game-config（mapmonster / global）。
 *
 * <p><b>三条红线在本类的落地</b>：
 * <ol>
 *   <li><b>绝不一次性下发整张地图</b>：{@link #viewport} 只返回以中心 chunk 为核心的 3×3，
 *       且只返回版本号比客户端手里更高的那些块（B07 验收 6：无变化时实体数为 0）。</li>
 *   <li><b>payload 上限是硬约束</b>：按块累加<b>实际序列化后的 UTF-8 字节数</b>，
 *       超过 VIEWPORT_PAYLOAD_MAX_BYTES 就停止追加并把该块标为 stale，让客户端下次再取。
 *       宁可多一次往返，也不能让一次响应把弱网玩家卡住（B07 验收 5：≤ 20KB）。</li>
 *   <li><b>迷雾是服务端的，不是客户端遮罩</b>：未探索的块<b>根本不下发实体</b>。
 *       如果下发了、只靠客户端盖一层黑，那改一下客户端就能透视全图 ——
 *       而迷雾与「情报有误差」是 B07 里仅有的两个紧张感来源，透视等于把这一层策略删掉。</li>
 * </ol>
 *
 * <p><b>WorldEntity 里没有 isBot 字段</b>（B07 禁止项 + B11 合规：前 7 天不做任何 Bot 标识）。
 * 字段不存在比字段值为 false 更安全 —— 不存在的字段无法被误用，
 * 而一个恒为 false 的字段迟早会被某个客户端拿去写 {@code if (!isBot)}。
 */
@Service
public class WorldAppService {

    private static final Logger LOG = LoggerFactory.getLogger(WorldAppService.class);

    /** 找空位落城时向外螺旋的最大尝试次数。超过就报错而不是随便塞一格（重叠的城更难查）。 */
    private static final int SPAWN_PROBE_LIMIT = 4096;

    private final ConfigRegistry configs;
    private final WorldRepository world;
    private final PlayerRepository players;
    private final TimeService timeService;
    /**
     * 只为「按天补衰减后再定档」这一件事服务：暴虐值的衰减算法已有唯一归属
     * （{@code PowerService#daysSince} 与 {@code tyrannyRules}），在这里另写一份天数计算，
     * 就是同一批玩家在两个入口看到两个答案的那种漂移。
     */
    private final PowerService powerService;
    /**
     * 公敌广播的<b>惰性驱动入口</b>：服务端不起常驻定时器（B00 铁律 + 分层 CI 卡口），
     * 而视野下发是全服最频繁的读路径，所以由它顺手推进。方法内部有频率预算，
     * 不在预算内就直接返回，不会让一次拖图变成一轮全服存档扫描。
     */
    private final PublicEnemyBroadcaster publicEnemies;
    /**
     * Bot 补员的<b>惰性驱动入口</b>。与 {@link #publicEnemies} 同一条纪律：服务端不起常驻定时器，
     * 由视野下发这条最频繁的读路径顺手推进；补员轮次之间有节流预算（见 {@code BotSpawnService}）。
     */
    private final com.ironoath.web.bot.BotSpawnService botSpawn;
    /**
     * 在线人数只用于一件事：Bot 补员的目标人口（B11 §五）。Bot 从不建立连接（§八 性能预算），
     * 所以这个数天然只含真人 —— 「在线真人数」不需要另算一份。
     */
    private final com.ironoath.web.ws.PushGateway pushGateway;

    public WorldAppService(ConfigRegistry configs, WorldRepository world,
                           PlayerRepository players, TimeService timeService,
                           PowerService powerService, PublicEnemyBroadcaster publicEnemies,
                           com.ironoath.web.bot.BotSpawnService botSpawn,
                           com.ironoath.web.ws.PushGateway pushGateway) {
        this.configs = configs;
        this.world = world;
        this.players = players;
        this.timeService = timeService;
        this.powerService = powerService;
        this.publicEnemies = publicEnemies;
        this.botSpawn = botSpawn;
        this.pushGateway = pushGateway;
    }

    // ---------- 家坐标 ----------

    /**
     * 取玩家的世界坐标；没有就落位。
     *
     * <p>落位用 {@link WorldGenerator#spawnCoord} 的确定性推导（黄金角螺旋铺开），
     * 冲突时向外螺旋找空位。<b>不用随机找空格</b>：随机会让同一个玩家在不同时刻
     * 落到不同坐标（除非额外存一份），而确定性推导天然幂等。
     */
    public Coord homeOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        var existing = world.cityOf(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WorldGenerator.Rules rules = rules();
        // 用 playerId 的哈希作为螺旋起点序号：不同玩家从不同位置开始铺开，
        // 而不是所有人都从 index 0 开始挤在同一个点
        long index = Math.floorMod((long) playerId.hashCode(), 1_000_000L);
        for (int probe = 0; probe < SPAWN_PROBE_LIMIT; probe++) {
            Coord candidate = WorldGenerator.spawnCoord(rules, index + probe);
            if (!candidate.withinWorld(rules.worldSize())) {
                continue;
            }
            if (world.placeCity(playerId, candidate)) {
                // 自家城周围必须一开始就是亮的：新号落地看到一片全黑会以为游戏坏了，
                // 而 B00 的五分钟体验要求开局就顺
                ensureHomeExplored(playerId);
                LOG.info("玩家城落位 playerId={} coord={} chunk={} 探测次数={}",
                        playerId, candidate, candidate.chunkKey(rules.chunkSize()), probe);
                return candidate;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR,
                "找不到空闲坐标落城：已探测 " + SPAWN_PROBE_LIMIT + " 次。"
                        + "这说明世界已经挤满，应当开新服（B14 赛季）而不是继续塞");
    }

    /**
     * 把某个玩家「家门口」的块点亮。<b>幂等</b>（已亮则不写），缺失玩家城时什么都不做。
     *
     * <p>三处共用的唯一实现：新号落位（{@link #homeOf}）、流亡迁城（{@link #relocateCity}）、
     * 以及 Bot 开始活动时补点亮（{@code BotRuntimeService} 在把 Bot 排进作息表时调用 ——
     * 孵化器自己点不了：它不能反向依赖本服务，见 {@code BotSpawnService#placeNear} 的注释）。
     * 各写一份的后果是「新号落地是亮的、Bot 落地是黑的」这类只有对比才看得出的差异。
     *
     * <p><b>已亮则不写</b>不只是省一次写：{@code saveFog} 是带乐观锁的整份覆盖，
     * 这一步经常发生在别的迷雾写入（行军路过）之后 —— 无脑照写会平白多撞一次版本。
     */
    public void ensureHomeExplored(String playerId) {
        Coord home = world.cityOf(playerId).orElse(null);
        if (home == null) {
            // 没有城就没有「家门口」。不在这里替它落位 —— 那是 homeOf 的职责；
            // 本方法在 Bot 的 tick 路径上被调用，不能有「顺手造一座城」的副作用
            return;
        }
        WorldGenerator.Rules rules = rules();
        FogOfWar fog = world.fogOf(playerId);
        if (fog.isExplored(home, rules.chunkSize())) {
            return;
        }
        fog.explorePath(home, home, rules.chunkSize());
        fog.explore(home, rules.chunkSize());
        world.saveFog(playerId, fog, fog.version());
    }

    /**
     * 用服务端种子在<b>确定性螺旋</b>上取一个随机起点，向外找到第一个空位落城（B08 §5 流亡迁城的落点）。
     *
     * <p>与 {@link #homeOf} 共用同一套落位规则（{@code WorldGenerator.spawnCoord} + 向外螺旋探测），
     * 只是起点从「playerId 哈希」换成「服务端种子的随机数」—— 刻意不另写一套「随机坐标」：
     * 两处落位规则一旦分家，就会出现「新号能落到而迁城的人落不到」这类谁也说不清的差异。
     *
     * <p>铁律 4：随机必须可复现，所以这里接收 seed 而不是自己取熵，seed 由调用方下发并进日志。
     *
     * <p>B08 原文只写「随机迁城」，<b>没有规定最小距离</b>，所以这里不擅自加一条
     * 「至少离旧家 N 格」—— 那是一条产品口径。若将来要加，进配置表并在这里读，别写常数。
     */
    public Coord randomFreeCoord(long seed) {
        WorldGenerator.Rules rules = rules();
        long start = Math.floorMod(com.ironoath.common.rng.Rng.of(seed).nextLong(), 1_000_000L);
        for (int probe = 0; probe < SPAWN_PROBE_LIMIT; probe++) {
            Coord candidate = WorldGenerator.spawnCoord(rules, start + probe);
            if (!candidate.withinWorld(rules.worldSize()) || world.cityAt(candidate).isPresent()) {
                continue;
            }
            return candidate;
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR,
                "找不到空闲落点：已探测 " + SPAWN_PROBE_LIMIT + " 次（seed=" + seed + "）。"
                        + "这说明世界已经挤满，应当开新服（B14 赛季）而不是继续塞");
    }

    /**
     * 整城迁移，并把新家周围点亮。
     *
     * @return true 迁移成功；false 表示目标格已被占用（调用方另找一格）
     */
    public boolean relocateCity(String playerId, Coord to) {
        if (!world.moveCity(playerId, to)) {
            return false;
        }
        WorldGenerator.Rules rules = rules();
        // 与落位同一条理由：新家必须是亮的。搬完家发现自己门口一片全黑，
        // 玩家的第一反应不是「迷雾」而是「游戏把我的城搬进了一个坏掉的格子」
        ensureHomeExplored(playerId);
        LOG.info("整城迁移 playerId={} 新坐标={} 新块={} 旧块已一并变新", playerId, to,
                to.chunkKey(rules.chunkSize()));
        return true;
    }

    /** 玩家主城等级，用于侦查的等级差计算。没有存档时返回 0。 */
    public int playerLevelOf(String playerId) {
        return players.findByPlayerId(playerId).map(p -> p.cityLevel()).orElse(0);
    }

    // ---------- 格子内容 ----------

    /**
     * 某格的最终内容 = 确定性生成结果 + 玩家造成的覆盖。
     *
     * <p>覆盖只有两种：玩家城（优先于一切）与已消耗（资源点采空 / 野怪打掉后回到空地）。
     * <b>消耗过的格子不会重新生成</b> —— 否则玩家会发现「刚打掉的野怪又回来了」，
     * 而刷新应当是显式的运营行为（B14 赛季重置），不是查询的副作用。
     */
    public WorldGenerator.Cell cellAt(Coord coord) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        if (world.cityAt(coord).isPresent()) {
            return new WorldGenerator.Cell(WorldGenerator.EntityType.CITY, null,
                    playerLevelOf(world.cityAt(coord).orElseThrow()));
        }
        if (world.isConsumed(coord)) {
            return WorldGenerator.Cell.empty();
        }
        return WorldGenerator.generate(rules(), catalog(), coord);
    }

    /**
     * 这个玩家此刻「看得见」的实体：以家为中心的视野 footprint（与客户端一次 viewport 的九宫格
     * 同口径）内<b>已探索</b>块的实体。
     *
     * <p>与 {@link #viewport} 共用 {@code entitiesOf} 的装配（不是第二份）：Bot 的打野/采集
     * 目标选择要像玩家一样「先看得见才打得到」，而玩家看到的正是这条口径 ——
     * 未探索的块客户端只有黑色遮罩，实体根本不下发。
     *
     * <p>它不下发协议、不驱动孵化、不发推送，是 viewport 的<b>只读切面</b>。
     * 代价与 viewport 同形（每个已探索块一遍实体装配），调用方应当按需使用（一次 PvE 决策），
     * 不要放进高频循环。
     */
    public java.util.List<WorldEntity> visibleEntitiesOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        Coord home = world.cityOf(playerId).orElse(null);
        if (home == null) {
            // 没有城就没有视野。也不在这里落位，理由见 ensureHomeExplored
            return java.util.List.of();
        }
        WorldGenerator.Rules rules = rules();
        FogOfWar fog = world.fogOf(playerId);
        java.util.List<String> keys = new java.util.ArrayList<>(
                FogOfWar.viewportChunks(home, rules.chunkSize(), rules.viewportChunks()));
        // viewportChunks 本身已按行优先稳定输出；这里再排一次序，是为了不让「选目标的并列裁决」
        // 依赖另一个方法的实现细节（顺序变了，实体列表顺序就变）
        java.util.Collections.sort(keys);
        java.util.List<WorldEntity> out = new java.util.ArrayList<>();
        Map<Coord, String> cityIndex = cityIndex();
        for (String key : keys) {
            if (fog.chunks().contains(key)) {
                out.addAll(entitiesOf(key, rules, cityIndex));
            }
        }
        return out;
    }

    /** 世界规则，逐项取自 global 表（铁律 1：不硬编码）。 */
    public WorldGenerator.Rules rules() {
        return new WorldGenerator.Rules(
                (int) configs.longParam("WORLD_SIZE"),
                (int) configs.longParam("WORLD_CHUNK_SIZE"),
                (int) configs.longParam("VIEWPORT_CHUNK_COUNT"),
                configs.longParam("VIEWPORT_PAYLOAD_MAX_BYTES"),
                configs.fixedParam("WORLD_MONSTER_DENSITY"),
                configs.fixedParam("WORLD_RESOURCE_DENSITY"),
                (int) configs.longParam("WORLD_MONSTER_LEVEL_RING"),
                (int) configs.longParam("WORLD_SPAWN_MARGIN"),
                configs.longParam("WORLD_SEED"));
    }

    /** 生成目录：等级 → mapmonster 行 id，以及资源点可产出的资源。 */
    public WorldGenerator.Catalog catalog() {
        // mapmonster 表按等级升序声明，所以下标 = 等级 - 1。
        // 用 LinkedHashMap 去重后按等级排序，避免表里等级乱序时取错怪
        Map<Integer, String> byLevel = new LinkedHashMap<>();
        for (MapmonsterCfg monster : configs.all(MapmonsterCfg.class)) {
            byLevel.putIfAbsent((int) monster.level(), monster.id());
        }
        List<String> ordered = new ArrayList<>(byLevel.size());
        for (int level = 1; level <= byLevel.size() + 8; level++) {
            String id = byLevel.get(level);
            if (id != null) {
                ordered.add(id);
            }
        }
        if (ordered.isEmpty()) {
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "mapmonster 表为空，世界生成无法确定野怪种类");
        }
        // 资源点只产 BASE 资源，不产金币：金币是付费货币，
        // 让它躺在地上等人捡会直接削掉 B15 的付费点
        List<String> resourceTypes = new ArrayList<>();
        for (var resource : configs.allResources()) {
            if (resource.kind() == com.ironoath.config.cfg.ResourceCfg.Kind.BASE) {
                resourceTypes.add(resource.id());
            }
        }
        return new WorldGenerator.Catalog(ordered, resourceTypes);
    }

    // ---------- 分块视野 ----------

    /**
     * 视野下发。
     *
     * <p>流程：算出 3×3 的 chunk 键 → 过滤掉客户端已是最新的 → 逐块组装实体 →
     * 累加<b>实际序列化字节数</b>，超限就停止并把剩下的标为 stale。
     *
     * <p>迷雾块<b>只回块键不回实体</b>（放进 fogChunks），客户端据此盖黑色遮罩。
     */
    public ViewportResp viewport(String playerId, ViewportReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        long now = timeService.serverNow();
        // 公敌档的全服广播由这一条最频繁的读路径惰性驱动（服务端禁常驻定时器）。
        // 放在增量判断<b>之前</b>：客户端就算一个块都不重新要，也仍然在读图，
        // 而广播的受众恰恰是「所有在线的人」而不是「这次重新拉了块的人」
        publicEnemies.sweep(now);
        WorldGenerator.Rules rules = rules();
        Coord center = requireCoord(req.centerX(), req.centerY(), rules.worldSize());
        // Bot 补员：同样由这条读路径惰性驱动（B11 §五，服务端禁常驻定时器）。
        // 放在实体装配<b>之前</b>：新孵化的 Bot 会落位并推进所在块的版本号，
        // 于是这一次请求就能把「新邻居」一起下发 —— 而不是等玩家下次拖图才出现。
        // 这次请求的人就是落点的锚（§四：新 Bot 优先孵化在真人玩家附近 5~15 格）
        botSpawn.sweep(now, playerId, center, rules, pushGateway.onlineCount());
        // Bot 的 tick 兜底**不在这里**：它由 WorldController 在调用本方法之后驱动。
        // 为什么放在控制器而不是这里：受击反应要转调 MarchAppService，而它依赖本服务 ——
        // 让本服务反过来依赖 Bot 运行时就会构成构造环（BeanCurrentlyInCreation）。
        // 控制器是依赖图的顶层、没有任何 bean 依赖它，所以那一脚放那里既不断链也不会成环

        Collection<String> wanted = FogOfWar.viewportChunks(center, rules.chunkSize(),
                rules.viewportChunks());
        Map<String, Long> clientVersions = new LinkedHashMap<>();
        if (req.chunkVersions() != null) {
            for (ChunkVersion cv : req.chunkVersions()) {
                clientVersions.put(cv.key(), cv.version());
            }
        }

        FogOfWar fog = world.fogOf(playerId);
        long payloadLimit = rules.payloadMaxBytes();
        long usedBytes = 0L;
        List<ChunkData> chunks = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        List<String> explored = new ArrayList<>();
        List<String> fogChunks = new ArrayList<>();
        boolean budgetExhausted = false;
        // 全世界城的坐标表。惰性取：绝大多数视野请求里所有块都探索过了，这一次读没必要付
        Map<String, Coord> allCities = null;
        // 城表索引（坐标 → 玩家）：与 allCities 同一条惰性策略，一趟请求只建一次
        Map<Coord, String> cityIndex = null;

        for (String key : wanted) {
            long serverVersion = world.chunkVersion(key);
            boolean isExplored = fog.chunks().contains(key);
            if (isExplored) {
                explored.add(key);
            } else {
                fogChunks.add(key);
            }
            if (!budgetExhausted && serverVersion == clientVersions.getOrDefault(key, -1L)) {
                // 客户端已是最新：不回实体（B07 验收 6：二次请求实体数为 0）
                continue;
            }
            if (budgetExhausted) {
                stale.add(key);
                continue;
            }
            List<WorldEntity> entities;
            if (isExplored) {
                // 城表索引一趟请求里只建一次（惰性：全都在客户端手里的那次请求一分钱不花）
                if (cityIndex == null) {
                    cityIndex = cityIndex();
                }
                entities = entitiesOf(key, rules, cityIndex);
            } else {
                // 迷雾块不下发实体：下发了就等于把迷雾做成纯客户端遮罩，改客户端即可透视全图。
                // 唯一的例外是 B08 §4 的第一档 —— 强横档起的坐标「不再受迷雾保护」，
                // 因为看不见人在哪，弱者的一切反击都无从谈起。
                if (allCities == null) {
                    allCities = world.allCities();
                }
                entities = exposedCities(key, rules, allCities, now);
            }
            ChunkData data = new ChunkData(key, serverVersion, entities, false);
            int bytes = utf8Bytes(data);
            if (usedBytes + bytes > payloadLimit) {
                // 这一块装不下：标为 stale 让客户端下次单独取，而不是把它截半下发。
                // 截半下发会让玩家看到「明明有怪却显示为空地」，而他没有任何线索知道为什么
                budgetExhausted = true;
                stale.add(key);
                LOG.info("viewport 触发 payload 上限，剩余块转为 stale playerId={} 已用={}字节 上限={}字节 块={}",
                        playerId, usedBytes, payloadLimit, key);
                continue;
            }
            usedBytes += bytes;
            chunks.add(data);
        }
        return new ViewportResp(now, chunks, stale, explored, fogChunks);
    }

    /**
     * 让某个玩家城所在的块对所有人「变新」。
     *
     * <p>调用点是暴虐档位跨过「坐标不再受迷雾保护」那条线的那一刻。为什么必须显式做一次失效：
     * 视野下发是按 chunk 版本号做增量的，而客户端<b>手里已经有</b>那些未探索的块
     * （它拿到过一个只带块键、不带实体的结果）。版本号不动，它就永远不再向服务端要这一块 ——
     * 于是「暴露」在协议上成立、在玩家地图上从来没出现过。这是「判定写了没接上」里最难发现的一种：
     * 判定对、下发也对，中间差一个失效信号。
     */
    public void invalidateCityChunk(String playerId) {
        Coord coord = world.cityOf(playerId).orElse(null);
        if (coord == null) {
            LOG.warn("要失效城所在块，但这个人还没有落位 playerId={}", playerId);
            return;
        }
        world.bumpChunkVersion(coord.chunkKey(rules().chunkSize()));
    }

    /**
     * 未探索块里仍然要下发的城：只有暴虐档位达到「坐标不再受迷雾保护」的那些人（B08 §4 第一档）。
     *
     * <p><b>先补衰减再定档</b>：暴虐值每日衰减 20% 是惰性结算，锚点在存档上。直接拿裸值判定，
     * 表现是「半年前屠过服的人永久挂在所有人的地图上」—— 而这个机制的全部目的是
     * 让<b>现在</b>在施暴的人成为靶子。与 {@code TargetSearchService} 同一口径。
     *
     * <p><b>按城表筛块，而不是逐格查 {@code cityAt}</b>：一个块是 32×32 = 1024 格，
     * 逐格查等于每个未探索块打 1024 次存储查询，而视野请求是地图拖动时最频繁的一条路径。
     * 城表一次读出来就能筛完，玩家存档再批量读一次（不是逐个读）。
     *
     * <p><b>这个块仍然出现在 {@code fogChunks} 里</b>（它确实没被探索过，语义不能改 ——
     * 把它报成已探索会让客户端永久保留一块本不该有的视野）。所以客户端必须在遮罩之上
     * 照样画出这些城，这是「暴露」这件事的表现层契约。
     */
    private List<WorldEntity> exposedCities(String chunkKey, WorldGenerator.Rules rules,
                                            Map<String, Coord> allCities, long now) {
        List<String> owners = new ArrayList<>();
        allCities.forEach((ownerId, coord) -> {
            if (coord != null && chunkKey.equals(coord.chunkKey(rules.chunkSize()))) {
                owners.add(ownerId);
            }
        });
        if (owners.isEmpty()) {
            return List.of();
        }
        Tyranny.Rules tyrannyRules = powerService.tyrannyRules();
        List<WorldEntity> out = new ArrayList<>();
        players.findByPlayerIds(owners).forEach((ownerId, save) -> {
            long tyranny = Tyranny.decay(save.pvp().tyranny(),
                    powerService.daysSince(save.pvp().tyrannyTouchedAt(), now), tyrannyRules);
            if (!Tyranny.exposesCoordinate(Tyranny.levelOf(tyranny, tyrannyRules))) {
                return;
            }
            Coord coord = allCities.get(ownerId);
            out.add(new WorldEntity(ownerId, WorldEntityType.CITY, coord.x(), coord.y(),
                    save.cityLevel(), save.nickName(), null, null, null, null));
        });
        return out;
    }

    /** 组装一个 chunk 内的全部实体：玩家城 + 野怪 + 资源点 + 行军。 */
    private List<WorldEntity> entitiesOf(String chunkKey, WorldGenerator.Rules rules,
                                         Map<Coord, String> cityIndex) {
        // 消耗表按块读（{@code consumedInChunk}），不逐格问 {@code isConsumed}（Mongo 版是一次查询，
        // 一个 32×32 的块逐格问就是 1024 次）。城表索引由调用方一趟读一次（{@link #cityIndex()}）——
        // 与 exposedCities 的注释同一条理由：「按城表筛块，而不是逐格查 cityAt」。
        // 栅格内容仍由生成器确定性给出，不落库
        Set<Coord> consumed = new java.util.HashSet<>(
                world.consumedInChunk(chunkKey, rules.chunkSize()));

        Coord origin = Coord.chunkOrigin(chunkKey, rules.chunkSize());
        List<WorldEntity> out = new ArrayList<>();

        // 行军：起点或终点落在本块的都算（B07 的 WorldEntity 含 MARCH 类型）。
        // 行军是「移动图层」，不与格子上的静态实体互斥 —— 一支队伍停在自家城门口时，
        // 城和队伍都要显示，所以这里不做任何去重
        for (March march : world.marchesInChunks(List.of(chunkKey))) {
            Coord pos = march.positionAt(timeService.serverNow());
            if (!pos.chunkKey(rules.chunkSize()).equals(chunkKey)) {
                continue;   // 已经走远了，归属新的块
            }
            out.add(new WorldEntity(march.id(), WorldEntityType.MARCH, pos.x(), pos.y(),
                    null, null, null,
                    MarchStatus.valueOf(march.status().name()), null, march.load()));
        }
        WorldGenerator.Catalog catalog = catalog();
        Map<String, PlayerSave> citySaves = citySavesOf(origin, rules, cityIndex);
        for (int dy = 0; dy < rules.chunkSize(); dy++) {
            for (int dx = 0; dx < rules.chunkSize(); dx++) {
                Coord coord = Coord.of(origin.x() + dx, origin.y() + dy);
                if (!coord.withinWorld(rules.worldSize())) {
                    continue;
                }
                WorldEntity entity = entityAt(coord, cityIndex, consumed, catalog, citySaves);
                if (entity != null) {
                    out.add(entity);
                }
            }
        }
        return out;
    }

    /**
     * 本块玩家城拥有者的存档，<b>一次批量读</b>。
     *
     * <p>城表索引已经在手，所以"这块上有谁的城"根本不用问存储；要问的只有昵称与主城等级，
     * 而那是同一次 {@code findByPlayerIds} 的事。与 {@link #exposedCities} 同一条读法。
     *
     * <p>这一趟扫的是块内的 32×32 格（纯内存查表），代价与下面那次装配同阶；反过来按城表筛
     * 就要为每个块遍历一次全服城表，玩家越多越贵。
     */
    private Map<String, PlayerSave> citySavesOf(Coord origin, WorldGenerator.Rules rules,
                                                Map<Coord, String> cityIndex) {
        Set<String> owners = new LinkedHashSet<>();
        for (int dy = 0; dy < rules.chunkSize(); dy++) {
            for (int dx = 0; dx < rules.chunkSize(); dx++) {
                String owner = cityIndex.get(Coord.of(origin.x() + dx, origin.y() + dy));
                if (owner != null) {
                    owners.add(owner);
                }
            }
        }
        return owners.isEmpty() ? Map.of() : players.findByPlayerIds(owners);
    }

    /**
     * 城表索引（坐标 → 玩家 id）。
     *
     * <p>一趟实体装配（viewport 的九块、Bot 的一次视野扫描）里只读一次 ——
     * 逐块重建一份全服城表的代价随玩家数增长，而在块数上白乘一遍没有任何收益。
     */
    private Map<Coord, String> cityIndex() {
        Map<Coord, String> ownerByCoord = new java.util.HashMap<>();
        world.allCities().forEach((ownerId, coord) -> {
            if (coord != null) {
                ownerByCoord.put(coord, ownerId);
            }
        });
        return ownerByCoord;
    }

    /**
     * 某格的地图实体；空地返回 null（空地下发没有意义，只会白占 payload）。
     *
     * <p><b>城主的存档来自调用方一次批量读回的 {@code citySaves}，这里不再点查</b>：改之前昵称一趟
     * {@code findByPlayerId}、等级又绕经 {@link #playerLevelOf} 一趟，等于每座城两次整档读取，
     * 而这条装配在拖图（一次请求九个块）与 Bot 目标选择（每 tick 每个 Bot）上。判据是往返计数：
     * {@code WorldCityQueryCountTest}。
     */
    private WorldEntity entityAt(Coord coord, Map<Coord, String> ownerByCoord, Set<Coord> consumed,
                                 WorldGenerator.Catalog catalog, Map<String, PlayerSave> citySaves) {
        String owner = ownerByCoord.get(coord);
        if (owner != null) {
            PlayerSave save = citySaves.get(owner);
            return new WorldEntity(owner, WorldEntityType.CITY, coord.x(), coord.y(),
                    save == null ? 0 : save.cityLevel(),
                    save == null ? null : save.nickName(), null, null, null, null);
        }
        if (consumed.contains(coord)) {
            return null;
        }
        WorldGenerator.Cell cell = WorldGenerator.generate(rules(), catalog, coord);
        return switch (cell.entityType()) {
            case MONSTER -> new WorldEntity(stableId("monster", coord), WorldEntityType.MONSTER,
                    coord.x(), coord.y(), cell.level(), null, null, null, null, null);
            case RESOURCE -> new WorldEntity(stableId("resource", coord), WorldEntityType.RESOURCE,
                    coord.x(), coord.y(), cell.level(), null, null, null, cell.entityId(), null);
            case EMPTY, CITY, BUILDING -> null;
        };
    }

    /**
     * 生成实体的稳定 id。
     *
     * <p>必须由坐标推导而不是随机生成：客户端用它做增量比对与节点复用，
     * 随机会让同一个野怪每次 viewport 都是「新实体」，节点池永远命中不了，
     * 表现是拖动地图时疯狂创建销毁节点（B07 验收 3/4 的内存与帧率就是这么崩的）。
     */
    private static String stableId(String kind, Coord coord) {
        return kind + "_" + coord.x() + "_" + coord.y();
    }

    // ---------- 侦查报告 ----------

    /** 我的全部报告（含已过期的，带 expired 标记）。 */
    public ScoutListResp reports(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        long now = timeService.serverNow();
        List<ScoutReportView> views = new ArrayList<>();
        for (ScoutReport.Report report : world.reportsOf(playerId)) {
            List<ScoutMetric> metrics = new ArrayList<>(report.observed().size());
            report.observed().forEach((name, value) -> metrics.add(new ScoutMetric(name, value)));
            views.add(new ScoutReportView(report.reportId(),
                    new com.ironoath.web.dto.generated.Coord(report.target().x(), report.target().y()),
                    report.targetId(), report.targetLevel(),
                    report.createdAt(), report.expiresAt(),
                    report.expired(now), report.remainingMs(now),
                    report.errorFixed(), metrics, report.seed(), now));
        }
        return new ScoutListResp(views, now);
    }

    /** 清理过期报告。保留期来自 global.SCOUT_REPORT_TTL_SECONDS。 */
    public int purgeExpiredReports() {
        long now = timeService.serverNow();
        int removed = world.purgeExpiredReports(now);
        if (removed > 0) {
            LOG.info("清理过期侦查报告 数量={} 截止={}", removed, now);
        }
        return removed;
    }

    // ---------- 内部 ----------

    private static int utf8Bytes(ChunkData data) {
        // 用实际序列化后的 UTF-8 字节数，不用「每实体 N 字节」的估算常量：
        // 估算常量一旦偏小，payload 上限就形同虚设，而这个偏差在测试里看不出来
        // （测试环境的实体名短，生产环境的玩家昵称可能是 24 个中文字符 = 72 字节）
        return JsonUtils.toJson(data).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Coord requireCoord(int x, int y, int worldSize) {
        if (x < 0 || y < 0 || x >= worldSize || y >= worldSize) {
            throw new BizException(ErrorCode.WORLD_COORD_INVALID,
                    "坐标越界：(" + x + "," + y + ")，世界边长 " + worldSize);
        }
        return Coord.of(x, y);
    }
}
