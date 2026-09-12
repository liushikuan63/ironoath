package com.ironoath.core.city;

import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：城内建筑与建造队列的聚合根 —— B03 的放置、升级、队列、加速、取消、完成结算。
 * 依赖：game-common 的 ErrorCode / FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>服务端不持有任何倒计时对象</b>（B00 陷阱 1、B03 禁止项）：
 * 「还剩多久」永远是 {@code finishAt - now} 现算，所有方法都要求调用方把 {@code now} 传进来。
 * 于是本类可以在单测里把时间直接推进十天，不需要 sleep，也不需要任何调度器。
 *
 * <p>本类只做<b>状态变更与规则校验</b>，不碰资源：扣资源、返还资源都返回「应该扣/返多少」，
 * 由应用层配合分布式锁与幂等键落库（B00 陷阱 3：禁止先查后改）。
 * 这样资源事务的边界留在应用层，本类保持纯逻辑、可脱离容器单测。
 */
public final class CityState {

    private final Map<String, BuildingInstance> buildings = new LinkedHashMap<>();
    /** 网格占用：坐标键 "x,y" → 建筑实例 id。 */
    private final Map<String, String> gridOccupancy = new LinkedHashMap<>();
    /** 已通过特权/道具开启的额外队列数。 */
    private int extraQueues;
    /** 本建筑正在升级的实例 id 集合大小即已用队列数，通过 {@link #usedQueues()} 现算，不单独存。 */

    public int extraQueues() {
        return extraQueues;
    }

    public void addExtraQueue(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("额外队列数必须为正：" + count);
        }
        this.extraQueues += count;
    }

    // ---------- 完整快照（持久化与深拷贝的唯一定义） ----------

    /**
     * 一座建筑的持久化形态，字段与 {@link BuildingInstance#restore} 一一对应。
     *
     * <p>{@code upgradeFinishAt} 用 {@code Long}：没在建造中的建筑没有完成时刻，
     * 用 0 会让「已完成」与"从未建造"混成同一个值（{@code pendingFinishTimes} 就分不出来了）。
     */
    public record BuildingSnapshot(String instanceId, String configId, int level, int gridX, int gridY,
                                   BuildingStatus status, Long upgradeFinishAt, long upgradeStartedAt,
                                   long upgradeTotalSeconds, long upgradeOriginalSeconds,
                                   int helpCount, long lastMovedAt, long lastFinishedAt) {
    }

    /**
     * 城建存档的完整快照。
     *
     * <p><b>为什么这个定义在领域里，而不是留在各套存储里</b>：它原本住在
     * {@code InMemoryCityStore.copyOf}，是存储层的一份私有副本。补第二种存储（Mongo）时
     * 私有副本会变成两份，而两份「什么算完整」迟早会不一致 —— 症状不是报错，
     * 是"换到第二种存储后某个字段静默不持久化"（玩家重启就丢一次迁城记录这类）。
     * 现在内存深拷贝与 Mongo 文档都从这里出，字段加法只有一个地方需要改。
     *
     * <p>网格占用 {@code gridOccupancy} <b>不进快照</b>：它是 buildings 的派生索引，
     * 由 {@link #restoreBuilding} 按坐标重建。持久化一份派生索引就是给"两份真相"开个头 ——
     * 一旦两者不一致，表现是"空地看起来被占着"这种没法自愈的脏数据。
     */
    public record Snapshot(List<BuildingSnapshot> buildings, int extraQueues) {
    }

    /** 取出完整快照（不可变，可安全跨线程/跨存储传递）。 */
    public Snapshot snapshot() {
        List<BuildingSnapshot> out = new ArrayList<>(buildings.size());
        for (BuildingInstance b : buildings.values()) {
            out.add(new BuildingSnapshot(b.instanceId(), b.configId(), b.level(), b.gridX(), b.gridY(),
                    b.status(), b.upgradeFinishAt(), b.upgradeStartedAt(), b.upgradeTotalSeconds(),
                    b.upgradeOriginalSeconds(), b.helpCount(), b.lastMovedAt(), b.lastFinishedAt()));
        }
        return new Snapshot(List.copyOf(out), extraQueues);
    }

    /**
     * 由快照重建存档。
     *
     * <p>重建顺序是"先造实例、再 restore 全字段、最后登记进网格"：
     * 构造函数会按传入坐标登记占位，而 {@link BuildingInstance#restore} 只改字段不挪格子，
     * 所以必须用<b>原始坐标</b>构造，否则迁城后的建筑会留在旧格子上占位。
     */
    public static CityState fromSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        CityState state = new CityState();
        for (BuildingSnapshot b : snapshot.buildings()) {
            BuildingInstance instance = new BuildingInstance(b.instanceId(), b.configId(),
                    b.level(), b.gridX(), b.gridY());
            instance.restore(b.level(), b.gridX(), b.gridY(), b.status(), b.upgradeFinishAt(),
                    b.upgradeStartedAt(), b.upgradeTotalSeconds(), b.upgradeOriginalSeconds(),
                    b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
            state.restoreBuilding(instance);
        }
        if (snapshot.extraQueues() > 0) {
            state.addExtraQueue(snapshot.extraQueues());
        }
        return state;
    }

    // ---------- 查询 ----------

    public List<BuildingInstance> buildings() {
        return List.copyOf(buildings.values());
    }

    public BuildingInstance building(String instanceId) {
        BuildingInstance b = buildings.get(instanceId);
        if (b == null) {
            throw new IllegalArgumentException("建筑实例不存在: " + instanceId);
        }
        return b;
    }

    /** 按配置 id 找实例（B03 阶段每种建筑最多一个实例）。找不到返回 null，由调用方决定是新建还是报错。 */
    public BuildingInstance findByConfigId(String configId) {
        for (BuildingInstance b : buildings.values()) {
            if (b.configId().equals(configId)) {
                return b;
            }
        }
        return null;
    }

    /** 已占用的队列数 = 正在升级或已暂停的建筑数。 */
    public int usedQueues() {
        int used = 0;
        for (BuildingInstance b : buildings.values()) {
            if (b.isUpgrading()) {
                used++;
            }
        }
        return used;
    }

    public boolean isGridFree(int x, int y) {
        return !gridOccupancy.containsKey(gridKey(x, y));
    }

    // ---------- 放置 ----------

    /**
     * 放置一个建筑（等级 0，尚未建造）。
     *
     * @throws com.ironoath.common.BizException 地块被占用、越界，或违反地形限制
     */
    public BuildingInstance place(String instanceId, String configId, int gridX, int gridY,
                                  CityRules rules, boolean isWall, boolean isMainCity) {
        validateGrid(gridX, gridY, rules, isWall, isMainCity);
        String key = gridKey(gridX, gridY);
        if (gridOccupancy.containsKey(key)) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                    "地块已被占用：" + key + "，占用者=" + gridOccupancy.get(key));
        }
        if (buildings.containsKey(instanceId)) {
            throw new IllegalArgumentException("建筑实例 id 重复: " + instanceId);
        }
        BuildingInstance instance = new BuildingInstance(instanceId, configId, 0, gridX, gridY);
        buildings.put(instanceId, instance);
        gridOccupancy.put(key, instanceId);
        return instance;
    }

    /**
     * 登记一个已恢复状态的建筑实例 —— 仅供仓储反序列化使用，业务代码请用 {@link #place}。
     *
     * <p>与 place 的区别：place 会校验地块合法性并把等级置 0；
     * 反序列化时数据已通过校验，等级与升级状态都要原样保留。
     * 分成两条路径是为了避免恢复存档时误触发校验失败（例如配置把网格改小了，
     * 老存档里就有越界坐标，此时应该读出来再迁移，而不是直接拒绝加载）。
     */
    public void restoreBuilding(BuildingInstance instance) {
        if (buildings.containsKey(instance.instanceId())) {
            throw new IllegalArgumentException("建筑实例 id 重复: " + instance.instanceId());
        }
        String key = gridKey(instance.gridX(), instance.gridY());
        if (gridOccupancy.containsKey(key)) {
            throw new IllegalStateException("网格占用冲突：" + key + " 已被 "
                    + gridOccupancy.get(key) + " 占用，无法恢复 " + instance.instanceId());
        }
        buildings.put(instance.instanceId(), instance);
        gridOccupancy.put(key, instance.instanceId());
    }

    /**
     * 地块合法性校验（B03 §1：城内固定网格、边缘格才能建城墙、中心格固定为主城）。
     *
     * @throws com.ironoath.common.BizException 带结构化 need/current 的错误
     */
    public void validateGrid(int x, int y, CityRules rules, boolean isWall, boolean isMainCity) {
        int max = rules.gridSize() - 1;
        if (x < 0 || y < 0 || x > max || y > max) {
            throw com.ironoath.common.BizException.class.cast(
                    new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                            "需要 0~" + max + " 范围内的坐标，当前 (" + x + ", " + y + ")"));
        }
        boolean edge = x == 0 || y == 0 || x == max || y == max;
        boolean center = isCenter(x, y, rules.gridSize());

        if (rules.wallEdgeOnly() && isWall && !edge) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                    "需要边缘格才能建城墙，当前 (" + x + ", " + y + ") 是内部格");
        }
        if (rules.centerIsMainCity() && center && !isMainCity) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                    "中心格 (" + centerCoord(rules.gridSize()) + ") 固定为主城，不能建其它建筑");
        }
        if (rules.centerIsMainCity() && isMainCity && !center) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                    "主城必须建在中心格 " + centerCoord(rules.gridSize()) + "，当前 (" + x + ", " + y + ")");
        }
    }

    private static boolean isCenter(int x, int y, int gridSize) {
        int c = gridSize / 2;
        return x == c && y == c;
    }

    private static String centerCoord(int gridSize) {
        int c = gridSize / 2;
        return "(" + c + ", " + c + ")";
    }

    private static String gridKey(int x, int y) {
        return x + "," + y;
    }

    /**
     * 换位（B03 §1：有冷却，防频繁调整）。
     *
     * @return 还需等待的秒数；0 表示可以换位（调用方应在拿到 0 时才真正调用 {@link #moveTo}）
     */
    public long moveCooldownRemaining(String instanceId, CityRules rules, long now) {
        BuildingInstance b = building(instanceId);
        long elapsed = now - b.lastMovedAt();
        long cooldownMs = rules.moveCooldownSeconds() * 1000L;
        return elapsed >= cooldownMs ? 0L : (cooldownMs - elapsed + 999L) / 1000L;
    }

    public void moveTo(String instanceId, int newX, int newY, CityRules rules,
                       boolean isWall, boolean isMainCity, long now) {
        BuildingInstance b = building(instanceId);
        long cooldown = moveCooldownRemaining(instanceId, rules, now);
        if (cooldown > 0L) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_MOVE_COOLDOWN,
                    "需要冷却结束才能换位，当前还需 " + cooldown + " 秒");
        }
        validateGrid(newX, newY, rules, isWall, isMainCity);
        String targetKey = gridKey(newX, newY);
        if (gridOccupancy.containsKey(targetKey)) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_GRID_INVALID,
                    "目标地块已被占用：" + targetKey);
        }
        gridOccupancy.remove(gridKey(b.gridX(), b.gridY()));
        gridOccupancy.put(targetKey, instanceId);
        b.moveTo(newX, newY, now);
    }

    // ---------- 升级前置校验 ----------

    /**
     * 升级前置校验（B03 §2 的五项，缺一不可）。
     *
     * <p>校验顺序是刻意的：先查「不可改变的硬条件」（地块、是否已在升级、队列），
     * 再查「玩家可以去补的条件」（主城等级、前置建筑、资源）。
     * 顺序反了会出现「提示资源不足，玩家去采了半天资源，回来才发现队列满了」。
     *
     * @param targetLevel     目标等级（= 当前等级 + 1）
     * @param requireMainLevel 该等级要求的主城等级
     * @param mainCityLevel   当前主城等级
     * @param requireBuildingId 前置建筑配置 id，null 表示无前置
     * @param requireBuildingLevel 前置建筑要求等级
     * @param maxLevel        该建筑的最高等级
     * @param cost            升级消耗：资源 id → 数量
     * @param resources       当前持有：资源 id → 数量
     * @param rules           城建规则
     * @param inNewbieProtect 是否处于新手保护期
     */
    public UpgradeCheck validateUpgrade(String instanceId, int targetLevel, int maxLevel,
                                        int requireMainLevel, int mainCityLevel,
                                        String requireBuildingId, int requireBuildingLevel,
                                        Map<String, Long> cost, Map<String, Long> resources,
                                        CityRules rules, boolean inNewbieProtect, long now) {
        BuildingInstance b = buildings.get(instanceId);
        if (b == null) {
            return UpgradeCheck.fail(ErrorCode.CITY_BUILDING_NOT_FOUND,
                    "建筑已放置", "建筑实例 " + instanceId + " 不存在");
        }
        if (targetLevel > maxLevel) {
            return UpgradeCheck.fail(ErrorCode.CITY_BUILDING_MAX_LEVEL,
                    "等级不超过 " + maxLevel, "目标等级 " + targetLevel);
        }
        // ⑤ 该建筑不在升级中
        if (b.isUpgrading()) {
            return UpgradeCheck.fail(ErrorCode.CITY_UPGRADING,
                    "建筑空闲", b.configId() + " 正在升级（剩余 " + b.remainingSeconds(now) + " 秒）");
        }
        // ④ 有空闲建造队列
        int available = rules.availableQueues(inNewbieProtect, extraQueues);
        if (usedQueues() >= available) {
            return UpgradeCheck.fail(ErrorCode.CITY_QUEUE_FULL,
                    "空闲建造队列（当前上限 " + available + "，可开启第 " + (available + 1) + " 队列）",
                    "已用 " + usedQueues() + " / " + available);
        }
        // ① 主城等级
        if (mainCityLevel < requireMainLevel) {
            return UpgradeCheck.fail(ErrorCode.CITY_MAIN_LEVEL_LOW,
                    "主城 " + requireMainLevel + " 级", "主城 " + mainCityLevel + " 级");
        }
        // ② 前置建筑
        if (requireBuildingId != null && !requireBuildingId.isBlank()) {
            BuildingInstance pre = findByConfigId(requireBuildingId);
            int preLevel = pre == null ? 0 : pre.level();
            if (preLevel < requireBuildingLevel) {
                return UpgradeCheck.fail(ErrorCode.CITY_PRE_BUILDING_LOW,
                        requireBuildingId + " " + requireBuildingLevel + " 级",
                        requireBuildingId + " " + preLevel + " 级");
            }
        }
        // ③ 资源充足 —— 逐项检查，报出第一个缺口（玩家一次只需要知道最缺的那个）
        for (Map.Entry<String, Long> e : cost.entrySet()) {
            long need = e.getValue();
            long have = resources.getOrDefault(e.getKey(), 0L);
            if (have < need) {
                return UpgradeCheck.fail(ErrorCode.CITY_RESOURCE_LOW,
                        e.getKey() + " " + need, e.getKey() + " " + have);
            }
        }
        return UpgradeCheck.ok();
    }

    // ---------- 升级操作 ----------

    /**
     * 开始升级。调用方必须已通过 {@link #validateUpgrade} 并完成资源扣减。
     *
     * <p>{@code availableQueues} 由调用方传入而不是存在聚合里：规则的唯一来源是配置表，
     * 聚合内再存一份就会出现两个真相，热更配置后必然不同步。
     * 这里再校验一次队列（而不是完全信任 validateUpgrade 的结论），是因为
     * 校验与落地之间可能有别的请求插入 —— B03 验收 2 要求并发 10 个请求只有 1 个成功。
     *
     * @param durationSeconds 升级耗时（秒），由 Formula 按 BUILDING_TIME 曲线算出
     * @param availableQueues 当前可用队列数，来自 {@link CityRules#availableQueues}
     * @return 完成时刻（服务端毫秒时间戳）
     */
    public long startUpgrade(String instanceId, long durationSeconds, int availableQueues, long now) {
        BuildingInstance b = building(instanceId);
        if (b.isUpgrading()) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_UPGRADING,
                    "建筑已在升级中: " + instanceId);
        }
        if (usedQueues() >= availableQueues) {
            throw new com.ironoath.common.BizException(ErrorCode.CITY_QUEUE_FULL,
                    "建造队列已满：已用 " + usedQueues() + " / " + availableQueues);
        }
        b.startUpgrade(now, durationSeconds);
        return b.upgradeFinishAt();
    }

    /**
     * 已到点但尚未收割的升级完成时刻，升序去重。
     *
     * <p>惰性结算必须按这些时刻切段：产出速率在每次升级完成时发生阶跃（等级 +1），
     * 若把整个离线窗口都按「读档时的等级」追溯，玩家就能靠离线期间完成的一次升级
     * 白拿整个窗口的高等级产量 —— 这是一个不需要任何技巧、只要挂机就能触发的漏洞。
     * 正确用法是：对每个时刻先按当前状态结算到该时刻，再收割，最后结算到现在。
     *
     * <p>暂停中的建筑不计入（与 {@link #collectFinished} 口径一致：只有 UPGRADING 会被收割）。
     */
    public List<Long> pendingFinishTimes(long now) {
        java.util.TreeSet<Long> times = new java.util.TreeSet<>();
        for (BuildingInstance b : buildings.values()) {
            if (b.status() == BuildingStatus.UPGRADING
                    && b.upgradeFinishAt() != null
                    && b.upgradeFinishAt() <= now) {
                times.add(b.upgradeFinishAt());
            }
        }
        return new ArrayList<>(times);
    }

    /**
     * 加速（B03 §3）。免费与付费共用本方法，来源区分由应用层负责埋点与防刷。
     *
     * @return 实际提前的秒数（会被剩余时间截断，<b>不会出现负数</b>，B03 禁止项）
     */
    public long speedUp(String instanceId, long reduceSeconds, long now) {
        return building(instanceId).speedUp(reduceSeconds, now);
    }

    /**
     * 帮助加速：把「本次实际授予的加速比例」折算成秒数，从该建筑的完成时刻里扣掉。
     *
     * <p><b>上限不在这里判</b>（2026-09-11 口径裁决）：单个目标最多能被帮到多少由社交侧的
     * {@code HelpLedger}（来源 {@code global.HELP_SPEEDUP_TOTAL_CAP}）说了算，它算出的
     * {@code grantedFixed} 就是"这一次实际给多少比例"。城建侧若再按自己的 20% 判一次，
     * 同一件事就有两个家、两个数 —— 那正是这条裁决要消掉的东西。
     *
     * <p><b>基数取「原始总时长」而不是当前剩余</b>：用剩余时长做基数的话，减少量会逐次复利缩水
     * （第一次减 1%，第二次只减 0.99%，…），永远追不上上限。比例由社交侧给，
     * 但"比例乘在谁身上"是建筑的属性，所以这一条仍归这里。
     *
     * @param ratioFixed 本次授予的加速比例（定点），由调用方按账本结果传入
     * @return 实际提前的秒数（受剩余时长截断，不会为负）
     */
    public long speedUpByRatio(String instanceId, long ratioFixed, long now) {
        BuildingInstance b = building(instanceId);
        if (b.upgradeOriginalSeconds() <= 0L) {
            return 0L;
        }
        long seconds = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(b.upgradeOriginalSeconds()), ratioFixed));
        long applied = speedUp(instanceId, Math.max(1L, seconds), now);
        if (applied > 0L) {
            // 记账只表示"被帮过几次"（面板展示用），不再参与任何上限计算
            b.addHelp();
        }
        return applied;
    }

    /**
     * 取消升级（B03 §2：返还 60% 资源）。
     *
     * @param cost 当初投入的资源：资源 id → 数量
     * @return 应返还的资源：资源 id → 数量。实际返还由应用层落库
     */
    public Map<String, Long> cancelUpgrade(String instanceId, Map<String, Long> cost, CityRules rules) {
        BuildingInstance b = building(instanceId);
        b.cancelUpgrade();
        Map<String, Long> refund = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : cost.entrySet()) {
            long back = FixedPoint.round(FixedPoint.mul(FixedPoint.of(e.getValue()), rules.cancelRefundFixed()));
            if (back > 0L) {
                refund.put(e.getKey(), back);
            }
        }
        return refund;
    }

    public void pause(String instanceId) {
        building(instanceId).pause();
    }

    public void resume(String instanceId) {
        building(instanceId).resume();
    }

    /**
     * 收割所有已到点的升级（等级 +1、回到空闲、释放队列）。
     *
     * <p>由请求触发（读取城内列表时、领取时），<b>不由定时器触发</b> —— 这是惰性结算在城建上的体现。
     *
     * <p>完成时刻记的是 {@code upgradeFinishAt} 而不是入参 {@code now}：
     * 从 upgradeFinishAt 到 now 这段时间建筑已经是新等级了，产率阶跃的边界必须落在那一刻，
     * 记成 now 会让玩家白丢这一段产量。
     *
     * @return 本次完成的建筑实例 id 列表，顺序与建筑插入顺序一致
     */
    public List<String> collectFinished(long now) {
        List<String> finished = new ArrayList<>();
        for (BuildingInstance b : buildings.values()) {
            if (b.status() == BuildingStatus.UPGRADING
                    && b.upgradeFinishAt() != null
                    && b.upgradeFinishAt() <= now) {
                b.finishUpgrade(b.upgradeFinishAt());
                finished.add(b.instanceId());
            }
        }
        return finished;
    }
}
