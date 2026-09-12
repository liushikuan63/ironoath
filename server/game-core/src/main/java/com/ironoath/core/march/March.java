package com.ironoath.core.march;

import com.ironoath.core.world.Coord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：行军实体 —— <b>一条带状态的记录，不是定时器</b>（B07 §2）。
 * 依赖：game-core 的 Coord（纯 Java，零框架）。
 *
 * <p><b>兵力按 unitId（含阶级）记，不按兵种记</b>。B07 原先用 {@code Map<UnitKind, Long>}，
 * 后果是一次出征会把 T5 兵降成 T1：归队时只能按「该兵种最低阶级」入账，
 * 因为阶级信息在出发那一刻就丢掉了。那个损失不报错、不为负，
 * 只表现为玩家打完一场仗战力莫名其妙少一大截。
 * 按 unitId 记之后，归队与战损分摊（{@code TierSplit}）都能精确到阶级。
 *
 * <p><b>这是 B07 的头号红线</b>：到达由服务端延迟队列（Redisson {@code RDelayedQueue}
 * 或 ZSET 到期扫描）触发，绝不用 per-march 的 {@code Timer} / {@code ScheduledExecutorService} ——
 * 1000 支行军就是 1000 个定时器，必崩。CI 的 check-layering.sh 里有一条静态检查守着这件事。
 *
 * <p><b>兵种用 EnumMap 而不是 HashMap</b>：B07 禁止项明写「不要用 HashMap 存兵种并依赖其迭代顺序」。
 * 迭代顺序会影响「负载怎么算」「战报里兵种怎么排」，HashMap 的顺序还随 JVM 与容量变化 ——
 * 那会让同一个行军在两台机器上算出不同的负载上限，而这种 bug 永远无法在单机复现。
 *
 * <p><b>状态机的合法迁移在本类内校验</b>，不放在应用层：
 * 「已到达的行军又被召回」这类非法迁移如果只在某一个 service 里挡，
 * 换一个入口（补偿、GM 工具、Bot）就会漏。
 */
public final class March {

    /** 行军状态。 */
    public enum Status {
        /** 去程中。 */
        MARCHING,
        /** 已到达空地并驻扎。 */
        STATIONED,
        /** 在资源点采集中。 */
        GATHERING,
        /** 返程中。 */
        RETURNING,
        /** 交战中（B08/B09 接线后使用）。 */
        FIGHTING
    }

    /** 目标类型，决定到达后的行为分支（B07 §2 的表格）。 */
    public enum TargetType {
        EMPTY,
        MONSTER,
        RESOURCE,
        PLAYER_CITY,
        ALLIANCE_BUILDING
    }

    /** 玩家选择的到达行为。与 {@link TargetType} 分开：目标是什么由地图决定，做什么由玩家决定。 */
    public enum Action {
        /** 攻击（野怪 / 玩家城）。 */
        ATTACK,
        /** 采集（资源点）。 */
        GATHER,
        /** 驻扎（空地）。 */
        STATION,
        /** 侦查。 */
        SCOUT,
        /** 增援驻守（自家城 / 联盟建筑）。 */
        GARRISON
    }

    private final String id;
    private final String playerId;
    private final Coord from;
    private final Coord to;
    private final long startAt;
    private long arriveAt;
    private Long returnArriveAt;
    private final Map<String, Long> units;
    private final List<String> heroes;
    private final long loadCap;
    private long load;
    private Status status;
    private final TargetType targetType;
    private final String targetId;
    private final Action action;
    /** 采集/驻扎的开始时刻，用于结算已采集量。null 表示尚未开始。 */
    private Long gatherStartAt;
    /**
     * 返程的开始时刻。
     *
     * <p>必须单独存：返程的位置插值是 {@code to → from}，起点时刻是「召回那一刻」而不是 startAt。
     * 用 startAt 当起点会让召回后的位置立刻跳到路径中间甚至跳回出发点 ——
     * 那正是 B07 验收 1 要防的「瞬移」。
     */
    private Long returnStartAt;
    /**
     * 返程的起点坐标。
     *
     * <p><b>必须单独存，不能一律用 {@code to}</b>：去程中途被召回时，队伍此刻在路径中间，
     * 返程应当从<b>那个位置</b>往回走。若用 to 当返程起点，召回的一瞬间队伍会瞬移到目的地
     * 再开始往回走 —— 那正是 B07 验收 1 要防的「瞬移」，而且玩家一眼就能看出来。
     * 已到达后（驻扎/采集/交战结束）再召回，返程起点才是 to。
     */
    private Coord returnFrom;

    /**
     * 这支行军属于哪次集结；null 表示普通行军（个人出征）。
     *
     * <p><b>为什么行军需要知道自己属于哪次集结</b>：集结出发时成员各自的兵被合并成一支行军，
     * 而行军只有一个主人（发起人）。返程时 {@link #arriveHome} 会把全部幸存兵力记到主人名下 ——
     * 于是其他成员的兵凭空变成了发起人的。要按承诺比例把兵分回各人，
     * 就必须能从这支行军找回那次集结。
     *
     * <p>非 final 且用 {@link #attachToRally} 单向绑定而不是进构造器：
     * 构造器已经有 11 个参数、调用点遍布行军链路与测试夹具，
     * 为一个只有集结路径会用的字段去改全部调用点，代价大于收益。
     * 代价是「集结行军必有 rallyId」这条不变量由唯一的创建点保证，而不是由类型保证 ——
     * 所以那个创建点必须只有一处（见 {@code SocialAppService} 的集结出发）。
     */
    private String rallyId;
    /** 队伍速度（取最慢兵种），存下来是为了召回时不必重新解析配置。 */
    private final int teamSpeed;

    public March(String id, String playerId, Coord from, Coord to, long startAt, long arriveAt,
                 Map<String, Long> units, List<String> heroes, long loadCap, int teamSpeed,
                 TargetType targetType, String targetId, Action action) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("marchId 不得为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("起点与终点都不得为 null，marchId=" + id);
        }
        if (startAt <= 0L || arriveAt < startAt) {
            throw new IllegalArgumentException("时间戳非法：startAt=" + startAt + ", arriveAt=" + arriveAt
                    + "，到达时刻不得早于出发时刻");
        }
        if (loadCap < 0L) {
            throw new IllegalArgumentException("负载上限不得为负：" + loadCap);
        }
        if (teamSpeed < 1) {
            throw new IllegalArgumentException("队伍速度必须 >= 1，否则行军永远到不了，实际=" + teamSpeed);
        }
        if (targetType == null || action == null) {
            throw new IllegalArgumentException("targetType 与 action 都不得为 null");
        }
        if (units == null || units.isEmpty()) {
            // B07 的 MARCH_NO_TROOP 错误码对应这条：不派兵的行军没有意义，
            // 而且会让「负载上限 = Σ 兵数 × load」算出 0，采集永远采不到东西
            throw new IllegalArgumentException("行军必须带兵，marchId=" + id);
        }
        Map<String, Long> copied = normalize(units, id);
        long total = 0L;
        for (long count : copied.values()) {
            total += count;
        }
        if (copied.isEmpty() || total <= 0L) {
            throw new IllegalArgumentException("行军必须带至少 1 个兵，marchId=" + id);
        }
        this.id = id;
        this.playerId = playerId;
        this.from = from;
        this.to = to;
        this.startAt = startAt;
        this.arriveAt = arriveAt;
        this.units = copied;
        this.heroes = heroes == null ? List.of() : List.copyOf(heroes);
        this.loadCap = loadCap;
        this.load = 0L;
        this.status = Status.MARCHING;
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.teamSpeed = teamSpeed;
    }

    // ---------- 读取 ----------

    public String id() {
        return id;
    }

    public String playerId() {
        return playerId;
    }

    public Coord from() {
        return from;
    }

    public Coord to() {
        return to;
    }

    public long startAt() {
        return startAt;
    }

    public long arriveAt() {
        return arriveAt;
    }

    public Long returnArriveAt() {
        return returnArriveAt;
    }

    /** 返程开始时刻；未在返程中为 null。位置插值要用它，不能用 startAt。 */
    public Long returnStartAt() {
        return returnStartAt;
    }

    /** 返程起点坐标；未在返程中为 null。去程中途召回时它是路径中间的某个位置，不是目的地。 */
    public Coord returnFrom() {
        return returnFrom;
    }

    /** 队伍构成的只读视图，unitId（含阶级）→ 数量，<b>按 unitId 字典序迭代</b>。 */
    public Map<String, Long> units() {
        return Collections.unmodifiableMap(units);
    }

    public long totalUnits() {
        long sum = 0L;
        for (long count : units.values()) {
            sum += count;
        }
        return sum;
    }

    public List<String> heroes() {
        return heroes;
    }

    public long loadCap() {
        return loadCap;
    }

    public long load() {
        return load;
    }

    public Status status() {
        return status;
    }

    public TargetType targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public Action action() {
        return action;
    }

    public int teamSpeed() {
        return teamSpeed;
    }

    public Long gatherStartAt() {
        return gatherStartAt;
    }

    /**
     * 当前所在位置（插值用）。
     *
     * <p>服务端返回的是<b>按真实时间算出的格坐标</b>，客户端拿它做动画纠偏（B07 验收 10）。
     * 客户端自己按 {@code (now - startAt) / (arriveAt - startAt)} 插值只是表现层的平滑，
     * 权威位置永远是这里算出来的 —— 否则客户端时钟偏 5 分钟，行军就会显示在错误的格子上。
     */
    public Coord positionAt(long now) {
        if (status == Status.RETURNING) {
            long t0 = returnStartAt == null ? now : returnStartAt;
            long t1 = returnArriveAt == null ? t0 : returnArriveAt;
            // 返程起点是「召回那一刻队伍所在的位置」，不是目的地
            return interpolate(returnFrom == null ? to : returnFrom, from, t0, t1, now);
        }
        if (status == Status.MARCHING) {
            return interpolate(from, to, startAt, arriveAt, now);
        }
        // 驻扎/采集/交战都停在目标格
        return to;
    }

    private static Coord interpolate(Coord a, Coord b, long t0, long t1, long now) {
        if (t1 <= t0) {
            return b;
        }
        if (now <= t0) {
            return a;
        }
        if (now >= t1) {
            return b;
        }
        // 整数插值：先乘后除，避免 (now-t0)/(t1-t0) 先整除成 0
        long span = t1 - t0;
        long elapsed = now - t0;
        int x = (int) (a.x() + (b.x() - a.x()) * elapsed / span);
        int y = (int) (a.y() + (b.y() - a.y()) * elapsed / span);
        return Coord.of(Math.max(0, x), Math.max(0, y));
    }

    /** 已行军的比例（定点 0~10000），供客户端进度条与服务端召回计算共用。 */
    public long progressFixed(long now) {
        long t0 = startAt;
        long t1 = status == Status.RETURNING && returnArriveAt != null ? returnArriveAt : arriveAt;
        if (t1 <= t0) {
            return com.ironoath.common.num.FixedPoint.SCALE;
        }
        if (now <= t0) {
            return 0L;
        }
        if (now >= t1) {
            return com.ironoath.common.num.FixedPoint.SCALE;
        }
        return (now - t0) * com.ironoath.common.num.FixedPoint.SCALE / (t1 - t0);
    }

    // ---------- 状态迁移 ----------

    /**
     * 到达目标格。按 {@link #action} 决定进入哪个状态。
     *
     * @throws IllegalStateException 已到达过、或在返程中被要求到达
     */
    public void arrive(long now) {
        if (status != Status.MARCHING) {
            throw new IllegalStateException("只有去程中的行军能到达，当前状态=" + status
                    + "，marchId=" + id);
        }
        if (now < arriveAt) {
            throw new IllegalStateException("还没到点就被要求到达：now=" + now + ", arriveAt=" + arriveAt
                    + "。这说明延迟队列提前触发了，必须查队列实现而不是改这里");
        }
        status = switch (action) {
            case GATHER -> Status.GATHERING;
            case STATION, GARRISON -> Status.STATIONED;
            // ATTACK 与 SCOUT 的后续由战斗/侦查结算决定，这里先标记为交战态，
            // 结算完成后由调用方转成 RETURNING（打完就回家）
            case ATTACK, SCOUT -> Status.FIGHTING;
        };
        if (status == Status.GATHERING) {
            gatherStartAt = now;
        }
    }

    /** 开始采集（到达资源点之后）。 */
    public void startGathering(long now) {
        if (status != Status.GATHERING) {
            throw new IllegalStateException("只有采集中的行军能记录采集起点，当前状态=" + status);
        }
        if (gatherStartAt == null) {
            gatherStartAt = now;
        }
    }

    /**
     * 装载采集到的资源。
     *
     * <p>受 {@link #loadCap} 约束，超出部分装不进去（留在资源点）。
     * 负载上限 = Σ(兵数 × 该兵种 load)，所以「带多少兵去采」是纯粹的运力取舍。
     *
     * @return 实际装载量
     */
    public long addLoad(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("装载量不得为负：" + amount);
        }
        long room = loadCap - load;
        long actual = Math.min(amount, Math.max(0L, room));
        load += actual;
        return actual;
    }

    /**
     * 从这支行军的负载里取走至多 {@code amount}（采集被抢夺时用，B09 验收 7）。
     *
     * <p><b>与 {@link #addLoad} 是一对，两边都返回实际发生量</b>：
     * 取走的一方夹到当前负载，接收的一方夹到剩余容量，
     * 于是调用方可以断言「取走的 == 收到的」这条守恒 ——
     * 与 B05 那个「分摊比例凭空吞兵」的 bug、{@code TierSplit} 的分摊守恒是同一条纪律。
     * 少了任何一个返回值，缺口就会静默消失：资源既不在守方手里也不在攻方手里，
     * 而两边的日志都显示正常。
     *
     * <p><b>在途负载不套仓库的保护额度</b>（{@code ResourceProtection}）：
     * 仓库额度保护的是「放在家里的存货」，而在途负载是玩家自己派兵出门、
     * 冒着被拦风险采来的。给在途负载也套一层保护额度，等于让「出门采集」比
     * 「放在家里」更安全 —— 那会反过来鼓励所有人把兵派出去，与 B08 的生态意图相反。
     *
     * @return 实际取走的量（<= amount，也 <= 当前负载）
     */
    public long seizeLoad(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("夺取量不得为负：" + amount);
        }
        long actual = Math.min(amount, Math.max(0L, load));
        load -= actual;
        return actual;
    }

    /**
     * 绑定到一次集结。<b>只能绑定一次，且必须在入库前调用</b>。
     *
     * <p>已绑定就抛，而不是静默覆盖：一支行军属于两次集结在语义上不可能，
     * 而允许覆盖的后果是返程分兵时按<b>错误的承诺比例</b>分配 ——
     * 兵会分给没参加这场仗的人，而账面上总量守恒，看不出任何异常。
     */
    public void attachToRally(String rallyId) {
        if (rallyId == null || rallyId.isBlank()) {
            throw new IllegalArgumentException("rallyId 不得为空：普通行军不该调用这个方法");
        }
        if (this.rallyId != null) {
            throw new IllegalStateException("行军 " + id + " 已属于集结 " + this.rallyId
                    + "，不能再绑定到 " + rallyId);
        }
        this.rallyId = rallyId;
    }

    /** 所属集结 id；普通行军为 null。 */
    public String rallyId() {
        return rallyId;
    }

    /** 是否是一支集结行军。返程分兵与战报归属都靠它分派。 */
    public boolean isRallyMarch() {
        return rallyId != null;
    }

    /**
     * 召回。返回耗时 = 已行军距离 / 速度（B07 §2、验收 7），<b>兵力零损失</b>。
     *
     * <p>用「已走的时间」而不是「已走的格数」来算返程：
     * 格数插值会丢小数（走了 3.7 格记成 3 格），而时间是精确的，
     * 返程时间 = 已消耗时间 ⇒ 位置连续、不会出现「召回后反而往前走了一格」。
     *
     * @return 到家时刻
     */
    public long recall(long now) {
        if (status == Status.RETURNING) {
            throw new IllegalStateException("已在返程中，不能重复召回，marchId=" + id);
        }
        if (status == Status.FIGHTING) {
            // 交战中不能召回：能召回就等于「打不过就跑，一个兵都不损失」，
            // 那会让战斗失去风险，而 B00 的生态要求战斗有真实后果
            throw new IllegalStateException("交战中不能召回，marchId=" + id);
        }
        long elapsed;
        if (status == Status.MARCHING) {
            elapsed = Math.max(0L, Math.min(now, arriveAt) - startAt);
        } else {
            // 已到达（驻扎/采集）：返程就是完整的去程距离
            elapsed = arriveAt - startAt;
        }
        // 先取当前位置，再改 status：positionAt 是按 status 分派的，
        // 一旦先改成 RETURNING，positionAt 就会走返程分支、而 returnFrom 还是 null，
        // 于是回退到 to —— 召回瞬移的 bug 就回来了。顺序错了不会报错，只会静默瞬移。
        Coord currentPosition = positionAt(now);
        status = Status.RETURNING;
        returnStartAt = now;
        // 去程中途召回 ⇒ 从当前位置往回走；已到达 ⇒ 当前位置就是 to，两种情况统一处理
        returnFrom = currentPosition;
        returnArriveAt = now + elapsed;
        gatherStartAt = null;
        return returnArriveAt;
    }

    /** 到家。带着负载回来的资源由调用方入账（走 ResourceService，不绕过上限校验）。 */
    public void arriveHome(long now) {
        if (status != Status.RETURNING) {
            throw new IllegalStateException("只有返程中的行军能到家，当前状态=" + status);
        }
        if (returnArriveAt != null && now < returnArriveAt) {
            throw new IllegalStateException("还没到家：now=" + now + ", returnArriveAt=" + returnArriveAt);
        }
        status = Status.STATIONED;
    }

    /** 战斗/侦查结算完成后转入返程。 */
    public long beginReturn(long now, long travelMillis) {
        if (status != Status.FIGHTING && status != Status.STATIONED && status != Status.GATHERING) {
            throw new IllegalStateException("当前状态不能转入返程：" + status + "，marchId=" + id);
        }
        if (travelMillis < 0L) {
            throw new IllegalArgumentException("返程时长不得为负：" + travelMillis);
        }
        status = Status.RETURNING;
        returnStartAt = now;
        returnFrom = to;   // 打完/采完是在目的地，所以返程起点就是 to
        returnArriveAt = now + travelMillis;
        gatherStartAt = null;
        return returnArriveAt;
    }

    /**
     * 扣兵（战斗损失）。按 unitId 字典序扣，所以「谁先被扣」是可复现的。
     *
     * <p>入参必须<b>已经按阶级摊好</b>（见 {@code TierSplit.splitProportionally}）：
     * 战斗内核只给出「某兵种损失多少」，而本类记的是各阶级的数量，
     * 分摊规则属于军队域的知识，不该由行军实体自己拍一个。
     *
     * @return 实际扣除量（unitId → 数量）。请求扣的量超过持有量时按持有量截断，
     *         所以调用方必须比对返回值与入参 —— 差额意味着兵力对不上账，应当报警而不是忽略
     */
    public Map<String, Long> applyLosses(Map<String, Long> losses) {
        if (losses == null) {
            throw new IllegalArgumentException("losses 不得为 null（没有损失请传空 Map）");
        }
        Map<String, Long> actual = new LinkedHashMap<>();
        for (String unitId : new ArrayList<>(losses.keySet())) {
            Long lost = losses.get(unitId);
            if (lost == null || lost <= 0L) {
                continue;
            }
            long have = units.getOrDefault(unitId, 0L);
            long deduct = Math.min(have, lost);
            if (deduct <= 0L) {
                continue;
            }
            long rest = have - deduct;
            if (rest == 0L) {
                units.remove(unitId);
            } else {
                units.put(unitId, rest);
            }
            actual.put(unitId, deduct);
        }
        return actual;
    }

    /** 供仓储反序列化写回。业务代码不要用。 */
    public void restore(long arriveAt, Long returnArriveAt, Long returnStartAt, Coord returnFrom,
                        long load, Status status,
                        Long gatherStartAt, Map<String, Long> restoredUnits) {
        if (status == null) {
            throw new IllegalArgumentException("status 不得为 null");
        }
        if (arriveAt < startAt) {
            throw new IllegalArgumentException("arriveAt 不得早于 startAt");
        }
        if (load < 0L || load > loadCap) {
            throw new IllegalArgumentException("负载越界：load=" + load + ", loadCap=" + loadCap);
        }
        this.arriveAt = arriveAt;
        this.returnArriveAt = returnArriveAt;
        this.returnStartAt = returnStartAt;
        this.returnFrom = returnFrom;
        this.load = load;
        this.status = status;
        this.gatherStartAt = gatherStartAt;
        // normalize 内部先拷一份再返回新 map：入参可能就是 units() 返回的视图
        // （不可变包装套的是同一个 Map），直接 clear 会把正在读的入参一起清空 ——
        // 这个别名 bug 在 ArmyState / HeroRoster / FogOfWar 上已经踩过三次
        Map<String, Long> copy = normalize(restoredUnits, id);
        units.clear();
        units.putAll(copy);
    }

    /**
     * 一条行军的完整状态。
     *
     * <p><b>为什么这个字段表在领域里</b>：它原先写在 {@code InMemoryMarchStore.copyOf} 里，
     * 补第二种存储（Mongo）时必然变成两份，而这条存档漏字段的代价是所有仓储里最重的 ——
     * 见下方 {@code rallyId}。内存深拷贝与 Mongo 文档现在都只从 {@link #snapshot()} 出。
     *
     * @param rallyId 集结合并行军的集结 id。<b>它不在构造器也不在 {@link #restore} 里</b>
     *                （{@link #attachToRally} 是单向绑定的），所以必须被快照显式带走。
     *                漏掉的症状是整条链一个错都不报：读回来的行军 {@code isRallyMarch()} 为 false
     *                ⇒ 到家时全部幸存兵力记到发起人名下，成员的兵有去无回 ——
     *                正是 B10 返程分兵要防的那件事
     */
    public record Snapshot(String id, String playerId, Coord from, Coord to, long startAt,
                           long arriveAt, Map<String, Long> units, List<String> heroes,
                           long loadCap, int teamSpeed, TargetType targetType, String targetId,
                           Action action, Long returnArriveAt, Long returnStartAt, Coord returnFrom,
                           long load, Status status, Long gatherStartAt, String rallyId) {
    }

    /** 取出完整状态（不可变，可安全跨线程/跨存储传递）。 */
    public Snapshot snapshot() {
        return new Snapshot(id, playerId, from, to, startAt, arriveAt, units(), heroes(),
                loadCap, teamSpeed, targetType, targetId, action, returnArriveAt, returnStartAt,
                returnFrom, load, status, gatherStartAt, rallyId);
    }

    /**
     * 由快照重建。
     *
     * <p>构造时传的 {@code arriveAt} 会被 {@link #restore} 再覆盖一次 —— 这是有意的：
     * 构造器只接受"刚出发"的形状，而读回来的行军处在任意时刻，两段合起来才是完整的重建路径。
     */
    public static March fromSnapshot(Snapshot s) {
        if (s == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        March march = new March(s.id(), s.playerId(), s.from(), s.to(), s.startAt(), s.arriveAt(),
                s.units(), s.heroes(), s.loadCap(), s.teamSpeed(), s.targetType(), s.targetId(),
                s.action());
        march.restore(s.arriveAt(), s.returnArriveAt(), s.returnStartAt(), s.returnFrom(),
                s.load(), s.status(), s.gatherStartAt(), s.units());
        if (s.rallyId() != null && !s.rallyId().isBlank()) {
            march.attachToRally(s.rallyId());
        }
        return march;
    }

    /**
     * 归一化队伍构成：剔除空 id 与 0 数量，按 unitId 字典序装进 LinkedHashMap。
     *
     * <p>顺序必须确定：{@link #toString} 会把它打进日志，
     * 而战报复现要求「同一场战斗在两台机器上打出同一行日志」。
     * 调用方传 HashMap 时迭代顺序是不保证的。
     */
    private static Map<String, Long> normalize(Map<String, Long> source, String marchId) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        List<String> ids = new ArrayList<>(source.keySet());
        ids.sort(Comparator.naturalOrder());
        for (String unitId : ids) {
            if (unitId == null || unitId.isBlank()) {
                throw new IllegalArgumentException("unitId 不得为空，marchId=" + marchId);
            }
            Long count = source.get(unitId);
            if (count == null) {
                continue;
            }
            if (count < 0L) {
                throw new IllegalArgumentException("兵种 " + unitId + " 的数量不得为负：" + count
                        + "，marchId=" + marchId);
            }
            if (count == 0L) {
                continue;   // 0 个兵的条目直接丢掉，否则「派了几种兵」的统计会算错
            }
            out.put(unitId, count);
        }
        return out;
    }

    @Override
    public String toString() {
        return "March{" + id + " " + playerId + " " + from + "→" + to + " " + status
                + " 部队" + units + " 负载" + load + "/" + loadCap + "}";
    }
}
