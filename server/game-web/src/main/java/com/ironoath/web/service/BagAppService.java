package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.ChestCfg;
import com.ironoath.config.cfg.ChestDropCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.chest.ChestOpener;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.BagItem;
import com.ironoath.web.dto.generated.BagListResp;
import com.ironoath.web.dto.generated.ItemRarity;
import com.ironoath.web.dto.generated.ItemUseReq;
import com.ironoath.web.dto.generated.ItemUseResp;
import com.ironoath.web.dto.generated.OpenBatchReq;
import com.ironoath.web.dto.generated.OpenBatchResp;
import com.ironoath.web.dto.generated.ResourceAmount;
import com.ironoath.web.dto.generated.ResourceType;
import com.ironoath.web.dto.generated.RewardItemView;
import com.ironoath.web.dto.generated.SpeedUpResp;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.reward.ServerSeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：背包与道具的应用服务 —— 列表、使用道具、批量开箱（B04 §3/§4）。
 * 依赖：game-core（背包、开箱器、奖励发放器、锁与幂等端口）、game-config（道具与掉落表）。
 *
 * <p><b>所有产出都走 {@link RewardService}，本类一行都不直接改存档。</b>
 * 这不是风格偏好而是 B04 的硬约束：发放器内部走资源模块的上限校验与事件通知，
 * 绕过它就会得到「资源加上了但顶部资源条没刷新」「超上限的部分没人管」这类不一致。
 * 本类只负责三件事：校验请求、扣掉消耗品、把产出交给发放器。
 *
 * <p><b>扣与发的顺序恒为「先扣后发，发失败退还」</b>：反过来在库存不足时会让玩家白拿一次产出，
 * 那是可以直接刷的漏洞；而先扣的唯一风险是「扣了但发放失败」，用 try/catch 退还即可闭合。
 * 一个能被玩家主动触发的漏洞，比一个只在异常路径出现的短暂不一致严重得多。
 *
 * <p><b>批量开箱的种子来自 {@link ServerSeedSource}，不来自任何请求字段</b>，
 * 否则客户端可以离线枚举出「哪个 requestId 开得出好东西」再发那个请求 ——
 * 计算虽然在服务端，结果却已经被玩家挑过了。详见 ServerSeedSource 的类注释。
 */
@Service
public class BagAppService {

    private static final Logger LOG = LoggerFactory.getLogger(BagAppService.class);

    /** 玩家锁的获取超时，与城建保持一致。 */
    private static final long LOCK_TIMEOUT_MS = 3000L;

    /** 背包分页的「全部」取值。用空串而不是 null，避免 URL 里出现字面量 null。 */
    public static final String PAGE_ALL = "";

    /**
     * 排序键里数量段的跨度。
     *
     * <p>数量是「多者靠前」，而 sortKey 整体升序，所以数量段要反转。
     * 跨度取 100 万远大于 item 表里最大的 stackMax（9999），
     * 保证反转后不会溢出到相邻的稀有度/类型段里去。
     */
    private static final long COUNT_SPAN = 1_000_000L;

    private final ConfigRegistry configs;
    private final InventoryRepository inventories;
    private final RewardPorts.Bag bagPort;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final RewardService rewardService;
    private final CityAppService cityAppService;
    private final ArmyAppService armyAppService;
    private final com.ironoath.web.tech.TechAppService techAppService;
    private final ServerSeedSource serverSeeds;
    /** 奖励展示名的唯一实现（以前本类自己有一份，与任务侧那份对碎片的处理不一致）。 */
    private final RewardNames names;

    /**
     * @param inventories 只用于 {@link #list} 的只读快照。<b>任何写操作都不许走它</b> ——
     *                    自己缓存一份 Inventory 再带旧版本落库，会在中间夹着一次发奖时
     *                    以过期版本提交（PlayerBag.add 会推进版本号），
     *                    轻则乐观锁冲突，重则把刚发出去的道具整份覆盖掉。
     * @param bagPort     背包写入的唯一入口，与发放器用的是同一个适配器
     */
    public BagAppService(ConfigRegistry configs, InventoryRepository inventories,
                         RewardPorts.Bag bagPort,
                         PlayerRepository players, PlayerLock playerLock,
                         IdempotencyStore idempotency, TimeService timeService,
                         RewardService rewardService, CityAppService cityAppService,
                         ArmyAppService armyAppService,
                         com.ironoath.web.tech.TechAppService techAppService,
                         ServerSeedSource serverSeeds,
                         RewardNames names) {
        this.configs = configs;
        this.inventories = inventories;
        this.bagPort = bagPort;
        this.players = players;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.rewardService = rewardService;
        this.cityAppService = cityAppService;
        this.armyAppService = armyAppService;
        this.techAppService = techAppService;
        this.serverSeeds = serverSeeds;
        this.names = names;
    }

    // ---------- B04 §3：背包列表 ----------

    /**
     * 背包列表，按类型分页，服务端排好序（B04 §3：稀有度 &gt; 类型 &gt; 数量）。
     *
     * <p><b>不加锁</b>：本方法只读不改，没有需要串行化的读-改-写。
     * 仓储返回的是副本，所以也不会读到撕裂状态。给只读接口加玩家锁，
     * 只会让「打开背包」排在「升级建筑」后面，白白增加感知延迟。
     *
     * @param typeFilter 道具类型过滤（SPEEDUP/RESOURCE/CHEST/MATERIAL/BUFF），空串表示全部
     */
    public BagListResp list(String playerId, String typeFilter) {
        requirePlayer(playerId);
        String filter = typeFilter == null ? PAGE_ALL : typeFilter.trim().toUpperCase();
        if (!filter.isEmpty()) {
            // 先解析成枚举再比名字：拼错的类型名必须报错，静默返回空列表会让客户端以为背包是空的
            parseType(filter);
        }

        Inventory bag = inventories.findByPlayerId(playerId).orElse(null);
        if (bag == null) {
            // 从没拿到过任何道具的玩家还没有背包文档，这是正常状态而不是错误
            return new BagListResp(List.of(), 0, (int) configs.longParam("BAG_INITIAL_CAPACITY"));
        }

        List<BagItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : bag.snapshot().entrySet()) {
            if (e.getValue() <= 0L) {
                continue;   // 数量为 0 的条目在 Inventory 里已被清掉，这里是防御性跳过
            }
            ItemCfg cfg = itemCfg(e.getKey());
            if (!filter.isEmpty() && !cfg.type().name().equals(filter)) {
                continue;   // 分页：只保留请求的那一类
            }
            items.add(toBagItem(cfg, e.getValue()));
        }
        items.sort(Comparator.comparingLong(BagItem::sortKey));
        return new BagListResp(items, bag.capacityUsed(), bag.capacityMax());
    }

    /**
     * 排序键：稀有度 &gt; 类型 &gt; 数量，整体升序（B04 §3）。
     *
     * <p><b>三段权重都来自契约里的枚举声明顺序，不在这里写死映射表</b>：
     * {@link ItemRarity} 按 N→SSR 声明，所以「越稀有排越前」= ordinal 反序；
     * {@link ItemCfg.Type} 按 SPEEDUP→BUFF 声明，直接用作类型段。
     * 在 Java 里另写一份「SSR=0, SR=1…」的映射就是第二处真源，改契约时必然漂移。
     *
     * <p>数量段反转（多者靠前）用 {@link #COUNT_SPAN} 做补数，跨度远大于任何 stackMax，
     * 所以不会串到相邻段。
     */
    public static long sortKey(ItemRarity rarity, ItemCfg.Type type, long count) {
        long rarityRank = (ItemRarity.values().length - 1L) - rarity.ordinal();
        long typeRank = type.ordinal();
        long typeKinds = ItemCfg.Type.values().length;
        long countRank = COUNT_SPAN - Math.min(Math.max(count, 0L), COUNT_SPAN);
        return (rarityRank * typeKinds + typeRank) * COUNT_SPAN + countRank;
    }

    private BagItem toBagItem(ItemCfg cfg, long count) {
        ItemRarity rarity = ItemRarity.valueOf(cfg.rarity().name());
        // 表里的 sellable / sellPriceGold **已随 B24 裁决④ 从表里删掉**（2026-09-13 先撤协议与客户端按钮、
        // 2026-09-19 连表列一起删）：
        // 服务端没有 /bag/sell、B04 也没有出售规则，下发它们只会换来一个「点了只会失败」的按钮 ——
        // 与 #18/#19「卖了没用」同一族。数据留在表里，规则定了再随协议回来。
        return new BagItem(cfg.id(), cfg.name(), cfg.type().name(), rarity,
                cfg.obtainFrom() == null ? "" : cfg.obtainFrom(),
                count, cfg.stackMax(),
                sortKey(rarity, cfg.type(), count));
    }

    // ---------- B04 §4：使用道具 ----------

    /**
     * 使用道具。行为按类型分派（B04 §4 的表格）：
     * <ul>
     *   <li>加速类 → 按 {@code effectKind} 转交建造 / 训练 / 研究三个域之一；建造与训练必须给
     *       targetId（要加速的建筑或兵种），研究一次一队列、不给 targetId</li>
     *   <li>资源类 → 直接发放 count × effectValue，支持一次开 N 个</li>
     *   <li>宝箱 → 拒绝并指向 {@code /item/openBatch}，因为它的产出可能不是资源，
     *       塞不进 ItemUseResp.granted 的类型里</li>
     *   <li>材料 / buff → 明确未开放（材料属 B06 武将合成，buff 属 B12 外围系统）</li>
     * </ul>
     */
    public ItemUseResp useItem(String playerId, ItemUseReq req) {
        validateUseRequest(playerId, req);
        ItemCfg item = itemCfg(req.itemId());
        if (req.count() <= 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID, "count 必须为正，实际=" + req.count());
        }
        long now = timeService.serverNow();

        if (item.type() == ItemCfg.Type.SPEEDUP) {
            return useSpeedUp(playerId, req, item, now);
        }

        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doUseItem(playerId, req, item, now));
        } catch (RuntimeException e) {
            // 失败必须释放幂等键：副作用没产生，否则玩家重试会被永久挡在门外
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 加速类道具按 effectKind 分流到对应的域。
     *
     * <p>不在本类的锁里做，也<b>不在这里占幂等键</b>：{@code useItem} 对 SPEEDUP 类在这一步就返回了，
     * 键由被分流到的那个域占（它才知道自己改了什么）。三路现在一致：研究 {@code speedUpByItem}、
     * 训练 {@code ArmyAppService.speedUp}、建造 {@code CityAppService.useSpeedUpItem} 各自占键 ——
     * 建造那一路是本轮补上的（B04 时期它不占，症状是弱网重投一次 /item/use 白扣一张付费建造令）。
     *
     * <p><b>同一个 requestId 只能被占一次</b>，所以分流之前更不能占：占了之后下面任何一路都必然报
     * {@code REQUEST_DUPLICATED}。这也是本方法把 requestId 原样往下传而不是在本层判重的原因。
     *
     * <p><b>分流必须在扣道具之前完成</b>：三个域的扣道具与状态变更是同一段事务，
     * 先扣再发现「这个域不认这种道具」就得退还，多一条失败路径。
     *
     * <p>三类加速共用一个入口（{@code /item/use}）而不是三个端点，是因为玩家的操作是同一个
     * （「用一张加速令」），差别只在道具本身指向哪个系统 —— 让客户端去选端点，
     * 等于把「这张令是加速建造还是加速训练」的判断推给客户端，而它只该读配置。
     */
    private ItemUseResp useSpeedUp(String playerId, ItemUseReq req, ItemCfg item, long now) {
        // targetId 要不到由效果决定：建造与训练各自可能有多个对象在跑，研究是「一次一队列」
        // （B20 §五①），队列里那一项就是被加速的那一项 —— 没有可指的对象。
        // 两个方向都判而不是只判「缺」：静默吞掉多余的 targetId 会掩盖客户端的路由 bug，
        // 那种 bug 的表现是「加速了另一个东西」，排查时毫无线索。
        boolean needsTarget = item.effectKind() != ItemCfg.EffectKind.REDUCE_RESEARCH_SECONDS;
        boolean hasTarget = req.targetId() != null && !req.targetId().isBlank();
        if (needsTarget != hasTarget) {
            throw new BizException(ErrorCode.PARAM_INVALID, needsTarget
                    ? "加速道具 " + item.id() + " 必须指定 targetId（要加速的建筑或兵种 id）"
                    : "研究加速不需要 targetId（一次一队列，队列里那一项就是被加速的那一项）");
        }
        long reduced = switch (item.effectKind()) {
            case REDUCE_BUILD_SECONDS -> cityAppService.useSpeedUpItem(
                    playerId, req.targetId(), item.id(), req.count(), now, req.requestId()).reducedSeconds();
            // 训练加速一次只用 1 个道具：/item/use 的 count 语义是「一次用几个」，
            // 但训练令的价值单位就是「一张 = effectValue 秒」，多张应当由客户端连续调用，
            // 否则一次用 10 张会把倒计时直接抹平，玩家看不到中间过程也来不及反悔
            case REDUCE_TRAIN_SECONDS -> armyAppService.speedUp(playerId,
                    new com.ironoath.web.dto.generated.ArmyUnitReq(
                            req.requestId(), req.targetId(), null, item.id())).reducedSeconds();
            case REDUCE_RESEARCH_SECONDS -> techAppService
                    .speedUpByItem(playerId, req.requestId(), item.id(), req.count()).reducedSeconds();
            default -> throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + item.id() + " 标记为加速类但效果是 " + item.effectKind()
                            + "，配置不一致（应为 REDUCE_*_SECONDS 之一）");
        };
        // 加速类道具不产出资源，granted 与 overflow 恒为空；reducedSeconds 是唯一的产出
        return new ItemUseResp(req.count(), List.of(), List.of(), null, reduced, null, now);
    }

    /**
     * 按道具类型分派使用行为（B04 §4 的表格）。
     *
     * <p>这是个<b>穷尽 switch</b>，没有 default 分支：item 表每加一种 type，
     * 这里就会编译失败而不是静默走到某个兜底路径。「加了新道具类型但忘了实现使用逻辑」
     * 的表现是玩家点了没反应，那种 bug 靠测试很难覆盖到，靠编译器一定能。
     */
    private ItemUseResp doUseItem(String playerId, ItemUseReq req, ItemCfg item, long now) {
        return switch (item.type()) {
            case RESOURCE -> useResourceItem(playerId, req, item, now);
            case CHEST -> throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "宝箱 " + item.id() + " 请走 /item/openBatch：开箱产出可能是道具或武将碎片，"
                            + "塞不进 /item/use 的资源清单响应里");
            // B04 §4 对材料的要求是「背包内展示，使用时跳转对应系统」——
            // 所以这里拒绝并指明去哪个端点，才是正确行为，而不是「未实现」
            case MATERIAL -> throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "材料 " + item.id() + " 在武将界面里被消耗：经验书走 /hero/levelUp、"
                            + "技能书走 /hero/skillUp、觉醒石走 /hero/awaken、武将碎片走 /hero/compose 或 /hero/starUp");
            case EQUIP -> throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "装备 " + item.id() + " 不是「使用」出去的，请走 /hero/equip 穿到武将身上"
                            + "（装备的价值在于穿戴后的属性与套装加成，直接消耗掉等于把它销毁）");
            case BUFF -> useBuffItem(playerId, req, item, now);
            case SPEEDUP -> throw new IllegalStateException(
                    "加速道具不应走到这里，应在 useItem 里就转交城建域: " + item.id());
        };
    }

    /**
     * 免战牌（B08 §5「闭城死守」那条出路的道具侧）。
     *
     * <p><b>为什么这张牌必须真的有效</b>：它在 {@code shop.json} 里标价出售、在
     * {@code activity_pvp_win} 里当奖励发放，而此前 {@code BUFF} 类道具一律抛
     * {@code NOT_IMPLEMENTED} —— 玩家花金币买到的是一个会失败的按钮。那不属于「功能还没做」，
     * 属于「公示过的付费内容不成立」，是 B15 的红线。
     *
     * <p><b>时长从使用时刻起按张数累加</b>（不叠到旧到期时刻之后）：多张一次用等于一次买长，
     * 而分多次用会各自从当天起算 —— 后者是玩家本来就接受的常见规则，
     * 也更简单：不会出现「今天用两张比明天各用一张更亏」这种要靠算术才看得懂的差别。
     *
     * <p>扣道具与写存档不是同一段事务，所以照 {@link #useResourceItem} 的做法：写失败必须退还道具。
     */
    private ItemUseResp useBuffItem(String playerId, ItemUseReq req, ItemCfg item, long now) {
        if (req.targetId() != null && !req.targetId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "targetId 只对加速类道具有意义，" + item.id() + " 是 BUFF 类道具");
        }
        ItemCfg.EffectKind kind = item.effectKind();
        boolean closing = kind == ItemCfg.EffectKind.CLOSE_CITY;
        if (!closing && kind != ItemCfg.EffectKind.GRANT_SHIELD) {
            // 刻意不静默放过：新增一个 buff 效果类型时，这里必须被改到，
            // 否则它会以「用了没反应」的形态上线，而那只意味着道具白扣了。
            // GRANT_RALLY_BONUS 就落在这里 —— 它要的「集结加成」在任何表里都没有定义数值
            // （global 只有人数上限与准备时长），造一个数字上线就是拿付费道具当试验品。
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + item.id() + " 的 buff 效果 " + kind + " 尚未实现"
                            + "（已实现：GRANT_SHIELD 免战牌、CLOSE_CITY 闭城死守令）。"
                            + "它在商店或活动奖励里出现时，这一条就是付费内容不成立，见 收口清单 #19");
        }
        if (bagPort.remove(playerId, item.id(), req.count()) == 0L) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "需要 " + item.name() + " " + req.count() + " 个，当前持有 "
                            + bagPort.countOf(playerId, item.id()) + " 个");
        }
        long until = now + item.effectValue() * req.count() * 1000L;
        long peaceUntil;
        try {
            com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId)
                    .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND,
                            "存档不存在：" + playerId));
            // 闭城走 withClosedUntil：它会同时把免战推到同一时刻（关门必然不打仗）
            save.setPvp(closing ? save.pvp().withClosedUntil(until) : save.pvp().withPeaceUntil(until));
            // 回读而不是直接用 until：withClosedUntil 内部按 max 合并，落库的可能比算出的更晚
            peaceUntil = save.pvp().peaceUntil();
            players.save(save);
        } catch (RuntimeException e) {
            refund(playerId, item.id(), req.count());
            throw e;
        }
        LOG.info("使用 buff 道具 playerId={} item={} 个数={} 效果={} 停战至={} 闭城至={}"
                        + "（免战期间不可被攻击也不可主动出击；闭城另加「不可出兵采集」）",
                playerId, item.id(), req.count(), kind, peaceUntil, closing ? until : null);
        return new ItemUseResp(req.count(), List.of(), List.of(), null, null, peaceUntil, now);
    }

    /** 资源类道具：扣 count 个 → 发放 count × effectValue 的 effectTarget 资源。 */
    private ItemUseResp useResourceItem(String playerId, ItemUseReq req, ItemCfg item, long now) {
        if (req.targetId() != null && !req.targetId().isBlank()) {
            // 静默忽略 targetId 会掩盖客户端的路由 bug：那种 bug 的表现是
            // 「点了加速却开出了资源箱」，玩家侧看是一次错误产出，排查时却毫无线索
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "targetId 只对加速类道具有意义，" + item.id() + " 是 " + item.type() + " 类道具");
        }
        if (item.effectKind() != ItemCfg.EffectKind.GRANT_RESOURCE) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + item.id() + " 标记为资源类但效果是 " + item.effectKind()
                            + "，配置不一致（effectKind 应为 GRANT_RESOURCE）");
        }
        String resourceType = item.effectTarget();
        if (resourceType == null || resourceType.isBlank()) {
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "道具 " + item.id() + " 缺少 effectTarget，无法确定发放哪种资源");
        }
        ResourceType.valueOf(resourceType);   // 拼错的资源名必须当场炸，不能发到不存在的资源上

        // 原子扣减：不足则一个都不扣，绝不扣成负数（B04 验收 10）
        if (bagPort.remove(playerId, item.id(), req.count()) == 0L) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "需要 " + item.name() + " " + req.count() + " 个，当前持有 "
                            + bagPort.countOf(playerId, item.id()) + " 个");
        }

        GrantResult result;
        try {
            result = rewardService.grant(playerId,
                    List.of(new RewardItem(RewardType.RESOURCE, resourceType,
                            item.effectValue() * req.count())),
                    RewardContext.toMail("item_use", item.id(), req.requestId()));
        } catch (RuntimeException e) {
            refund(playerId, item.id(), req.count());
            throw e;
        }
        reportCompensation(playerId, item.id(), req.count(), result);

        LOG.info("使用资源道具 playerId={} item={} 个数={} 发放={} 溢出={} 邮件={}",
                playerId, item.id(), req.count(), result.granted(), result.overflow(), result.mailId());
        return new ItemUseResp(req.count(), toAmounts(result.granted()), toAmounts(result.overflow()),
                result.mailId(), null, null, now);
    }

    // ---------- B04 §4：批量开箱 ----------

    /**
     * 批量开箱（B04 验收 3：一次开 100 个只发 1 次网络请求；验收 4：同 seed 结果完全一致）。
     *
     * <p>流程是「校验 → 扣箱 → 抽 → 发」，抽与发都在玩家锁内。
     * 抽 100 次是纯内存计算（{@code ChestOpener} 每次抽取 fork 一个独立子流），
     * 实测 300 次战斗模拟才 390ms，100 次抽取远在其下，不会把锁持有到影响其它请求。
     */
    public OpenBatchResp openBatch(String playerId, OpenBatchReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        requirePlayer(playerId);
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        if (req.count() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "count 必须为正，实际=" + req.count());
        }
        ItemCfg item = itemCfg(req.itemId());
        if (item.type() != ItemCfg.Type.CHEST) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + item.id() + " 不是宝箱（类型=" + item.type() + "），不能批量开启");
        }
        ChestCfg chest = chestOf(req.itemId());
        if (req.count() > chest.maxBatchCount()) {
            // 明确拒绝而不是静默截断：静默只开 maxBatchCount 个却扣了 count 个是吞道具，
            // 静默只扣 maxBatchCount 个则会让客户端的计数与服务端对不上
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "宝箱 " + item.name() + " 单次最多开 " + chest.maxBatchCount()
                            + " 个，请求了 " + req.count() + " 个");
        }

        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                    () -> doOpenBatch(playerId, req, chest, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private OpenBatchResp doOpenBatch(String playerId, OpenBatchReq req, ChestCfg chest, long now) {
        // 原子扣减：不足则一个都不扣（B04 验收 10）
        if (bagPort.remove(playerId, req.itemId(), req.count()) == 0L) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "需要 " + chest.name() + " " + req.count() + " 个，当前持有 "
                            + bagPort.countOf(playerId, req.itemId()) + " 个");
        }

        long seed = serverSeeds.nextSeed();
        List<RewardItem> drawn;
        GrantResult result;
        try {
            drawn = ChestOpener.openBatch(dropTable(chest), seed, req.count());
            result = rewardService.grant(playerId, drawn,
                    RewardContext.toMail("chest", req.itemId(), req.requestId()));
        } catch (RuntimeException e) {
            refund(playerId, req.itemId(), req.count());
            throw e;
        }
        reportCompensation(playerId, req.itemId(), req.count(), result);

        // 种子必须落日志：它是「同 seed 两次结果一致」这条验收在生产环境唯一的可查凭证
        LOG.info("批量开箱 playerId={} chest={} 个数={} seed={} 抽出={} 溢出={} 邮件={}",
                playerId, req.itemId(), req.count(), seed, drawn, result.overflow(), result.mailId());
        return new OpenBatchResp(req.count(), toViews(result.granted()), toViews(result.overflow()),
                result.mailId(), seed, now);
    }

    /**
     * 退还已扣的道具（发放失败时的回滚）。
     *
     * <p>退不进去必须响亮报错而不是静默：{@code add} 返回实际入包量，
     * 背包满格或堆叠到顶时会小于请求量，那就是玩家真的丢了道具，
     * 必须留下可查的 ERROR 与补偿线索（B04 禁止项：不得静默吞掉失败）。
     *
     * <p>堆叠上限由 {@code PlayerBag} 自己从配置读，不在这里传：
     * 传进来就意味着调用方缓存了一份上限，item 表热更后那份缓存就是过期的。
     */
    private void refund(String playerId, String itemId, long count) {
        long back = bagPort.add(playerId, itemId, count);
        if (back < count) {
            LOG.error("【道具退还失败】playerId={} item={} 应退={} 实退={} 背包容量={}/{} traceId={} "
                            + "退还不足说明玩家真的丢了道具，必须人工补偿",
                    playerId, itemId, count, back,
                    bagPort.capacityUsed(playerId), bagPort.capacityMax(playerId),
                    TraceContext.traceId());
        }
    }

    /** 从 chest_drop 表组装掉落表。顺序即配置声明顺序，也就是聚合结果的展示顺序。 */
    private ChestOpener.Table dropTable(ChestCfg chest) {
        List<ChestOpener.Drop> drops = new ArrayList<>();
        for (ChestDropCfg row : configs.all(ChestDropCfg.class)) {
            if (!row.chestId().equals(chest.id())) {
                continue;
            }
            drops.add(new ChestOpener.Drop(row.rewardId(),
                    RewardType.valueOf(row.rewardType().name()),
                    row.weight(), row.count(), row.rare()));
        }
        if (drops.isEmpty()) {
            // 宝箱在 chest 表登记了却没有任何掉落项，是配置漏填。空表会让 ChestOpener 构造期就失败，
            // 这里提前给出指向配置的报错，比一个 NullPointerException 好排查得多
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "宝箱 " + chest.id() + " 在 chest_drop 表里没有任何掉落项");
        }
        return new ChestOpener.Table(chest.id(), drops, chest.pityThreshold());
    }

    /**
     * 取宝箱配置。
     *
     * <p>不在 chest 表里的宝箱道具一律拒绝，并且要区分两种情况：
     * 武将招募匣走的是 gacha 表（四档概率 + 双保底 + UP 锁定，属 B06），
     * 与资源宝箱的权重模型完全不同，绝不能拿空掉落表糊弄过去。
     */
    private ChestCfg chestOf(String itemId) {
        ItemCfg item = itemCfg(itemId);
        try {
            return configs.get(ChestCfg.class, itemId);
        } catch (ConfigException e) {
            if (item.effectKind() == ItemCfg.EffectKind.OPEN_GACHA) {
                throw new BizException(ErrorCode.NOT_IMPLEMENTED,
                        "武将招募匣 " + itemId + " 走 gacha 卡池（四档概率 + 双保底 + UP 锁定），"
                                + "由 B06 武将系统交付，不能与资源宝箱共用权重模型");
            }
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "道具 " + itemId + " 是宝箱但 chest 表里没有登记，无法开箱");
        }
    }

    // ---------- 内部辅助 ----------

    private void validateUseRequest(String playerId, ItemUseReq req) {
        requirePlayer(playerId);
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        if (req.itemId() == null || req.itemId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "itemId 不得为空");
        }
    }

    private void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (players.findByPlayerId(playerId).isEmpty()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId=" + playerId);
        }
    }

    private ItemCfg itemCfg(String itemId) {
        try {
            return configs.get(ItemCfg.class, itemId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "道具配置不存在: " + itemId);
        }
    }

    private static ItemCfg.Type parseType(String name) {
        try {
            return ItemCfg.Type.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的道具类型: " + name);
        }
    }

    /**
     * 发放过程出错时必须响亮报告（B04 验收 7：不静默，落日志 + 补偿队列有记录）。
     *
     * <p>{@link com.ironoath.core.reward.RewardGrantor} 已经落了 ERROR 日志并进补偿队列，
     * 这里再记一条带 traceId 的 ERROR 是为了让「玩家投诉没拿到东西」能被一次检索定位：
     * 发放器那条日志只有 playerId 与奖励内容，没有请求上下文。
     */
    private void reportCompensation(String playerId, String itemId, long count, GrantResult result) {
        if (result.hasCompensation()) {
            LOG.error("【发放失败已进补偿队列】playerId={} item={} 个数={} traceId={} compensationId={} 溢出={}",
                    playerId, itemId, count, TraceContext.traceId(), result.compensationId(), result.overflow());
        }
    }

    /** 奖励里的资源条目转协议类型；非资源条目跳过（/item/use 的资源道具只会产出资源）。 */
    private static List<ResourceAmount> toAmounts(List<RewardItem> items) {
        Map<ResourceType, Long> merged = new LinkedHashMap<>();
        for (RewardItem r : items) {
            if (r.type() == RewardType.RESOURCE) {
                merged.merge(ResourceType.valueOf(r.id()), r.count(), Long::sum);
            }
        }
        List<ResourceAmount> out = new ArrayList<>(merged.size());
        merged.forEach((type, amount) -> out.add(new ResourceAmount(type, amount)));
        return out;
    }

    /** 奖励转协议视图，名字由服务端从配置表解析（客户端不得自行翻译）。 */
    private List<RewardItemView> toViews(List<RewardItem> items) {
        List<RewardItemView> out = new ArrayList<>(items.size());
        for (RewardItem r : items) {
            out.add(new RewardItemView(
                    com.ironoath.web.dto.generated.RewardType.valueOf(r.type().name()),
                    r.id(), r.count(), rewardName(r)));
        }
        return out;
    }

    /**
     * 奖励的展示名。<b>唯一实现在 {@link RewardNames}</b>；本类原来是第二份，
     * 且它对 HERO_FRAGMENT 回裸 id（玩家在背包里看到 {@code hero_ssr_01}），
     * 而任务面板同一张奖励回「SSR 武将碎片」。现在两边同源，
     * <b>背包侧因此变成可读名字</b> —— 那是修正，不是回归。
     */
    private String rewardName(RewardItem reward) {
        return names.nameOf(reward);
    }
}
