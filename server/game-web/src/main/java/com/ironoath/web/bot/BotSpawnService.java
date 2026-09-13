package com.ironoath.web.bot;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotArchetype;
import com.ironoath.core.bot.BotNameGenerator;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotTuning;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.PlayerInitResp;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.ServerCalendar;

/**
 * 职责：Bot 孵化 —— 按密度公式把世界补到目标人口（B11 §五 B 档）。
 * 依赖：配置装配器、注册表、玩家/世界仓储、真人同一条建档路径（{@link PlayerInitService}）、在线网关。
 *
 * <p><b>本类只孵化，不驱动</b>（2026-09-11 口径裁决）：孵出来的 Bot 有存档、有城、有名字、有战力带，
 * 但<b>没有任何主动行为</b> —— 不升级、不造兵、不打野、不结盟，也没有 tick 队列。
 * 决策引擎与调度（{@code BotScheduler} / {@code BotDecisionTree}）是**另一档**，本档不含。
 *
 * <p><b>补员是懒的，由请求路径驱动</b>（服务端禁止常驻定时器）：{@code WorldAppService.viewport}
 * 是最频繁的读路径，谁来看图，谁就把这一轮补员顺手推进一次；轮次之间由
 * {@link #SWEEP_BUDGET_MILLIS} 节流。放在那条路径上的另一个好处是<b>落点有锚</b>：
 * 这一次请求的玩家就是「邻居」的锚，§四 的「新 Bot 优先孵化在真人附近 5~15 格」因此天然成立。
 *
 * <p><b>三条在这里必须写清的限制</b>（都是「不写下来，下一个人会以为是 bug」的形状）：
 * <ol>
 *   <li><b>进程内</b>：{@code BotRegistry} 的画像、{@link #nextSweepAt} 的节流、{@code BotTuning}
 *       的攻击台账都在内存里。多实例部署时两个实例会各自按自己的注册表补员（同一目标数被补两遍 ⇒ 超发），
 *       而且 A 实例孵的 Bot 在 B 实例眼里是真人。**接多实例前必须先做 Redis**（B11 §三 的同一笔债，
 *       见上线检查清单）。单实例下这些都不存在。</li>
 *   <li><b>静止 ⇒ lastLoginAt 不刷新</b>：Bot 的最近活跃时间只落在孵化那一刻，
 *       而目标搜索有 48 小时的活跃窗口（{@code SEARCH_ACTIVE_WINDOW_HOURS}）——
 *       两天之后它们会从可攻击列表里消失。这是「静止」的直接代价，不是漏接一行；
 *       运行时的每日校准（§五）接上时顺手刷新它。</li>
 *   <li><b>不落库的画像</b>：重启后 {@code BotRegistry} 为空，已经孵出来的 Bot 会被当成真人
 *       （存档里没有 isBot 字段 —— 那是 B11 禁止项与 {@code check-no-bot-privilege.sh} 共同要求的设计）。
 *       重启后它们既不会被补员逻辑回收，也不会被合规闸门识别 —— 与「注册表应当落库」是同一笔债。
 *       存档里唯一持久可查的标记是 deviceId 前缀 {@value #BOT_DEVICE_PREFIX}（不下发给任何客户端）。</li>
 * </ol>
 */
@Service
public class BotSpawnService {

    private static final Logger LOG = LoggerFactory.getLogger(BotSpawnService.class);

    /**
     * 两次补员之间的最小间隔。
     *
     * <p>刻意不做成配置项：它是<b>防写放大的节流阀</b>（与 {@code PublicEnemyBroadcaster} 的
     * sieve 预算同类），不是玩法数值 —— 玩法数值是密度曲线、原型占比、战力带，那些都在表里。
     */
    private static final long SWEEP_BUDGET_MILLIS = 60_000L;

    /**
     * 每轮补员最多孵化几个 = 单服上限 / 本除数（5000 ⇒ 50）。
     *
     * <p>不一次补满：一次请求里连造几百个存档+落位是一次可观的写放大，
     * 而目标是「懒补员」—— 缺口会在后面几次请求里被填满。
     */
    private static final long BATCH_DIVISOR = 100L;

    /**
     * 目标数的下限（{@code BotTuning.targetBotCount} 的 floor 参数）。
     *
     * <p>取 1 而不是 0：floor 的语义是「真人为 0 的窗口里也不让地图彻底空掉」
     * （那个方法的注释写了理由），而 0 会让这个参数失去意义。更大的值都是拍脑袋 ——
     * 真人在线时目标数由密度曲线决定，floor 只在「一个人都没有」时起作用。
     */
    private static final int SPAWN_FLOOR = 1;

    /** 落点探测的轮数：每轮向环带要一个候选，占用了就换一个。 */
    private static final int PLACEMENT_PROBE_LIMIT = 64;

    /**
     * Bot 的 deviceId 前缀。这是存档里唯一<b>持久可查</b>的 Bot 标记：
     * 客户端看不到 deviceId，所以它不违反 §四「不做标识」，而运维与将来的画像重建可以按它捞人。
     */
    private static final String BOT_DEVICE_PREFIX = "bot-";

    private final ConfigRegistry configs;
    private final BotRulesAssembler assembler;
    private final BotRegistry registry;
    private final PlayerRepository players;
    private final PlayerInitService playerInit;
    private final WorldRepository world;

    /** 下一次允许补员的时刻。见类注释第 1 条：进程内节流，多实例下必须外置。 */
    private volatile long nextSweepAt;

    public BotSpawnService(ConfigRegistry configs, BotRulesAssembler assembler, BotRegistry registry,
                           PlayerRepository players, PlayerInitService playerInit,
                           WorldRepository world) {
        this.configs = configs;
        this.assembler = assembler;
        this.registry = registry;
        this.players = players;
        this.playerInit = playerInit;
        this.world = world;
    }

    /** 测试辅助：清空节流预算。进程内状态，不复位会让别的用例把时间轴顶到未来（症状是「没反应」）。 */
    public void clearThrottle() {
        nextSweepAt = 0L;
    }

    /**
     * 请求路径上的补员入口（由视野下发调用）。
     *
     * <p><b>绝不让孵化失败影响这次请求</b>：读图是玩家最频繁的操作，为一条「世界上应当有 Bot」
     * 的维护动作让玩家看到 500 是把优先级搞反了。所以异常在这里收口，只留日志。
     *
     * @param anchorPlayerId 锚玩家（本次请求的人）。他的城就是落点中心 ——
     *                       §四 要的「一出城就有邻居」，邻居当然是落在他旁边
     * @param center         玩家这次请求的视野中心。锚玩家还没落位时用它兜底
     * @param worldRules     世界规则。由调用方传入而不是本类自己装配：那 9 个参数的家在
     *                       {@code WorldAppService.rules()}，在这里再拼一份就是两个家
     * @param onlineHumans   在线真人数。由调用方传入（它是<b>传输层的事实</b>：在线连接数，
     *                       而 Bot 从不建立连接 ⇒ 这个数天然只含真人）。本类因此不依赖推送网关，
     *                       补员也能在不启 WebSocket 的测试里按任意人口驱动 ——
     *                       验收 3/11 要抽 100 个样本，而真实连接数在单测里凑不出来
     */
    public void sweep(long now, String anchorPlayerId, Coord center, WorldGenerator.Rules worldRules,
                      int onlineHumans) {
        if (now < nextSweepAt) {
            return;
        }
        nextSweepAt = now + SWEEP_BUDGET_MILLIS;
        try {
            topUp(now, anchorPlayerId, center, worldRules, onlineHumans);
        } catch (RuntimeException e) {
            LOG.warn("Bot 补员这一轮失败（不影响本次视野下发）：{}", e.getMessage(), e);
        }
    }

    /**
     * 补员一次，返回本轮实际孵化的数量。
     *
     * <p>先看目标数再决定要不要付后面那几次批量读的代价：达标时这条路径只有一行判断 ——
     * 它是每个视野请求都会走的。
     */
    public int topUp(long now, String anchorPlayerId, Coord center, WorldGenerator.Rules worldRules,
                     int onlineHumans) {
        BotTuning tuning = new BotTuning(assembler.tuningRules());
        long dayOffset = ServerCalendar.daysSinceOpen(configs, now);
        int target = tuning.targetBotCount(onlineHumans, dayOffset, SPAWN_FLOOR);
        int have = registry.size();
        int deficit = target - have;
        if (deficit <= 0) {
            return 0;
        }
        int batch = (int) Math.min(deficit, batchCap(tuning));

        // 一次批量读拿到三样东西：真人均值战力的样本、已占用的昵称（含 Bot 自己）、落点参照
        Map<String, Coord> cities = world.allCities();
        Map<String, PlayerSave> saves = players.findByPlayerIds(cities.keySet());
        List<String> humans = registry.humansIn(saves.keySet());
        long humanAverage = averageMatchPower(saves, humans);
        Set<String> takenNames = new HashSet<>(saves.size() * 2);
        saves.values().forEach(save -> takenNames.add(save.nickName()));
        // 锚：锚玩家的城（本次请求的人就在他城边上看图）→ 没有就用视野中心兜底。
        // 锚玩家为 null 时直接走兜底：world.cityOf(null) 在存储实现上是未定义的
        Coord anchor = anchorPlayerId == null
                ? center
                : world.cityOf(anchorPlayerId).orElse(center);

        List<BotArchetype> archetypes = assembler.archetypes();
        BotNameGenerator names = assembler.nameGenerator();
        long seed = now * 31L + have;
        Rng rng = Rng.of(seed);
        int spawned = 0;
        for (int i = 0; i < batch; i++) {
            try {
                // 名字先取、并且立刻加进 takenNames：同一轮里连孵 50 个，
                // 前面生成的名字必须对后面可见，否则一轮之内就会自撞
                String nickName = names.generate(rng, takenNames);
                takenNames.add(nickName);
                if (spawnOne(pick(archetypes, rng), nickName, rng, humanAverage, anchor, worldRules, now)) {
                    spawned++;
                }
            } catch (RuntimeException e) {
                // 一个失败不该带走整轮：剩下的还在缺口里，下一轮与下一次请求都还有机会
                LOG.warn("孵化一个 Bot 失败，继续下一个：{}", e.getMessage(), e);
            }
        }
        if (spawned > 0) {
            LOG.info("Bot 补员：目标={} 现有={} 本轮孵化={} 在线真人={} 开服第{}天 真人均值战力={} 种子={}",
                    target, have, spawned, onlineHumans, dayOffset, humanAverage, seed);
        }
        return spawned;
    }

    // ---------- 单个 Bot 的孵化 ----------

    /**
     * 孵化一个 Bot：建档（走真人同一条路）→ 赋值战力带 → 落位 → 登记画像。
     *
     * <p>名字由 {@link #topUp} 生成并保证唯一（它持有 {@code taken} 集合），
     * 这里只负责把这个世界公民造出来。
     *
     * @return true 表示这个 Bot 真的进入了世界（有城、有画像）
     */
    private boolean spawnOne(BotArchetype archetype, String nickName, Rng rng,
                             long humanAverage, Coord anchor, WorldGenerator.Rules worldRules, long now) {
        long avatarId = configs.longParam("INIT_AVATAR_ID");
        // 走真人同一条建档路径（B11 头号铁律）。不自己拼 PlayerSave：那会把
        // 资源初始值 / 容量 / 保护额度 / 幂等三道防线都算成第二套（ResourceRateService 的注释
        // 写了「三个值的唯一计算入口必须只有一个」的后果）
        PlayerInitResp created = playerInit.init(new PlayerInitReq(
                // 空串而不是 null：Bot 不走微信登录，但字段本身不能是 null ——
                // 服务端把"没有 code"定义为空白字符串，避免任何调用点忘记判空
                "bot-req-" + UUID.randomUUID(), BOT_DEVICE_PREFIX + UUID.randomUUID(), nickName, now, ""));
        String botId = created.playerId();

        PlayerSave save = players.findByPlayerId(botId).orElseThrow(() -> new IllegalStateException(
                "刚建的 Bot 存档读不回来：botId=" + botId));
        // 战力带：§五 的 TargetPower。静止 Bot 没有成长路径（不升级、不造兵），
        // 所以这条赋值就是它的战力来源，而不是给它的特权捷径 —— 数值取自真人均值，
        // 且不跳过任何资源/冷却（那些它压根不使用）。运行时的每日校准接上后，这条会让位给校准 + 追赶补偿
        long targetPower = new BotTuning(assembler.tuningRules())
                .targetPower(humanAverage, archetype.powerFactorFixed());
        if (targetPower > 0L) {
            save.setPower(new PlayerPower(targetPower, targetPower, targetPower));
            players.save(save);
        }

        Optional<Coord> placed = placeNear(botId, anchor, worldRules, rng);
        if (placed.isEmpty()) {
            // 世界已经挤满（环带与螺旋都试过）。这条极罕见：留一条响亮的告警而不是静默塞一格。
            // 残留说明：刚建的存档没有城也没有画像 —— 而 allCities 是这个世界「看得到谁」的唯一入口，
            // 所以它不会被任何查询或统计看到；真正要做的是开新服（B14）× 回收不活跃 Bot（§八，运行时那一档）
            LOG.warn("Bot 落点失败，本轮跳过（世界已满？）：botId={} 锚={}", botId, anchor);
            return false;
        }

        BotProfile profile = archetype.spawnProfile(botId, avatarId, rng);
        registry.register(profile);
        LOG.info("Bot 已孵化 id={} 名字={} 原型={} 战力={} 落点={} 距锚{}格 成长系数={}",
                botId, nickName, archetype.id(), targetPower, placed.get(),
                placed.get().distanceTo(anchor), profile.growthFactorText());
        return true;
    }

    /**
     * 落点：优先落在锚的 5~15 格环带里（§四），环带放不下（贴边或已挤满）时退回
     * 与真人新号同一个确定性螺旋（{@link WorldGenerator#spawnCoord}）。
     *
     * <p><b>迷雾由运行时补点亮</b>：孵化这一刻不点（那时它还只是「一座静止的城」，
     * 不读地图），等 {@code BotRuntimeService} 把它排进作息表时调
     * {@code WorldAppService#ensureHomeExplored} —— 那一刻它才开始看世界。
     * 为什么不在本类直接调：视野下发会驱动孵化，孵化器反过来依赖世界服务就成环。
     * （2026-09-12 的 C1b-PvE 落地时补上这一步，此前 Bot 的视野为空。）
     */
    private Optional<Coord> placeNear(String botId, Coord anchor, WorldGenerator.Rules worldRules, Rng rng) {
        int min = (int) configs.longParam("BOT_SPAWN_DISTANCE_MIN");
        int max = (int) configs.longParam("BOT_SPAWN_DISTANCE_MAX");
        for (int probe = 0; probe < PLACEMENT_PROBE_LIMIT; probe++) {
            Optional<Coord> candidate = WorldGenerator.ringAround(anchor, min, max, rng);
            if (candidate.isEmpty()) {
                break;
            }
            Coord coord = candidate.get();
            if (!coord.withinWorld(worldRules.worldSize())) {
                continue;
            }
            if (world.placeCity(botId, coord)) {
                return Optional.of(coord);
            }
        }
        long index = Math.floorMod((long) botId.hashCode(), 1_000_000L);
        for (int probe = 0; probe < 4096; probe++) {
            Coord candidate = WorldGenerator.spawnCoord(worldRules, index + probe);
            if (!candidate.withinWorld(worldRules.worldSize())) {
                continue;
            }
            if (world.placeCity(botId, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ---------- 数值 ----------

    /**
     * 服务器真人的平均 matchPower —— <b>这条口径的唯一持有者</b>。
     *
     * <p>孵化用它定战力带（{@link #topUp} 里那一次批量读），Bot 的 tick 也用它回答
     * "这个 Bot 落后了吗"（{@code BotWorldAdapter}）。两处必须同一个算法：分开各算一份的下一步，
     * 就是"校准跟着活数据走"（B11 验收 3）变成一句空话。
     *
     * <p>样本 = 世界上有城的那些玩家里的<b>真人</b>（Bot 一律剔除 —— 把 Bot 算进均值是自指：
     * Bot 越多均值越贴近 Bot，下一批 Bot 又按这个均值孵化）。一个真人都没有时返回 0
     * （{@code BotTuning.targetPower} 对 0 返回 0，见它的注释 —— 不是"战力为 0"，是"没有可比对象"）。
     */
    public long humanAverageMatchPower() {
        Map<String, Coord> cities = world.allCities();
        if (cities.isEmpty()) {
            return 0L;
        }
        Map<String, PlayerSave> saves = players.findByPlayerIds(cities.keySet());
        return averageMatchPower(saves, registry.humansIn(saves.keySet()));
    }

    private static long averageMatchPower(Map<String, PlayerSave> saves, List<String> humans) {
        if (humans.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (String playerId : humans) {
            sum += saves.get(playerId).power().matchPower();
        }
        return sum / humans.size();
    }

    private static long batchCap(BotTuning tuning) {
        return Math.max(1L, tuning.rules().maxPerServer() / BATCH_DIVISOR);
    }

    /** 按占比抽一个原型。占比合计恰为 1.0（装配器断言过），所以兜底分支实际走不到。 */
    private static BotArchetype pick(List<BotArchetype> archetypes, Rng rng) {
        long roll = rng.range(0L, FixedPoint.SCALE - 1L);
        long acc = 0L;
        for (BotArchetype archetype : archetypes) {
            acc += archetype.shareFixed();
            if (roll < acc) {
                return archetype;
            }
        }
        return archetypes.get(archetypes.size() - 1);
    }
}
