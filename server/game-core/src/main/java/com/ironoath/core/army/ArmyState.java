package com.ironoath.core.army;

import com.ironoath.common.num.FixedPoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：玩家军队聚合根 —— 兵力、训练队列、伤兵与治疗（B05 §二）。
 * 依赖：无（纯 Java，不依赖 Spring / MongoDB / game-config）。
 *
 * <p>与 {@code CityState} 同一套做法：聚合根可变、仓储整体持久化、
 * 所有规则校验都在聚合内完成，应用层只负责加锁、扣资源与落库。
 * 于是「取消训练返还多少」「伤兵超容量死多少」这些规则可以脱离容器跑单测。
 *
 * <p><b>三条与城建同源、因此刻意保持一致的纪律</b>：
 * <ol>
 *   <li><b>惰性结算</b>：训练完成不靠定时器推进，靠 {@link #collectFinished} 在有人读的时候落地
 *       （B00 陷阱 1/2：服务端禁止跑常驻定时器）。</li>
 *   <li><b>带兵上限由调用方传入</b>：上限 = Σ上阵武将统帅值 × TROOP_PER_COMMAND + 科技加成，
 *       这个公式属于武将与科技系统（B06/B12），军队聚合不该知道它 ——
 *       与 {@code CityState.startUpgrade} 收 {@code availableQueues} 而不是自己算队列数是同一个理由：
 *       规则的唯一来源是配置表，聚合内再算一份就会出现两个真相。</li>
 *   <li><b>剩余时间永不为负</b>：加速会被截断到剩余时间（B03/B05 共同的禁止项）。</li>
 * </ol>
 *
 * <p><b>伤兵与阵亡的分界在这里，不在战斗内核里</b>：
 * 战斗内核（game-battle）算出「损失多少」并按伤兵比例与医院容量拆成 dead/wounded/overflowDead，
 * 本聚合负责把 wounded 收进医院、把 overflowDead 从兵力里真的扣掉。
 * 两边都不重复计算，分界是「谁持有状态」：内核是无状态的纯函数，状态在这里。
 */
public final class ArmyState {

    private final Map<String, Long> troops = new LinkedHashMap<>();
    private final Map<String, TrainingTask> queue = new LinkedHashMap<>();
    private final Map<String, Long> wounded = new LinkedHashMap<>();
    /** 治疗完成时刻；null 表示没有在治疗。 */
    private Long treatFinishAt;
    private long treatTotalSeconds;
    private long treatOriginalSeconds;
    /** 本次治疗的资源消耗快照，取消时按它算返还（配置热更后也不会拿到过期数字）。 */
    private Map<String, Long> treatCost = Map.of();
    private int extraSlots;

    public ArmyState() {
    }

    // ---------- 读取 ----------

    /** 各兵种的现有兵力（不含训练中、不含伤兵）。顺序与配置表一致（LinkedHashMap）。 */
    public Map<String, Long> troops() {
        return Collections.unmodifiableMap(troops);
    }

    public long countOf(String unitId) {
        return troops.getOrDefault(unitId, 0L);
    }

    /** 兵力总数，用于与带兵上限比较。 */
    public long totalTroops() {
        long sum = 0L;
        for (long count : troops.values()) {
            sum += count;
        }
        return sum;
    }

    /** 训练中已占用的兵力。它已经计入带兵上限（见 {@link #train}），所以要能被读到。 */
    public long totalTraining() {
        long sum = 0L;
        for (TrainingTask task : queue.values()) {
            sum += task.count();
        }
        return sum;
    }

    public Map<String, Long> wounded() {
        return Collections.unmodifiableMap(wounded);
    }

    public long totalWounded() {
        long sum = 0L;
        for (long count : wounded.values()) {
            sum += count;
        }
        return sum;
    }

    public Map<String, TrainingTask> queue() {
        return Collections.unmodifiableMap(queue);
    }

    public int usedSlots() {
        return queue.size();
    }

    public int extraSlots() {
        return extraSlots;
    }

    public Long treatFinishAt() {
        return treatFinishAt;
    }

    /** 治疗剩余秒数；没在治疗或已到点返回 0，<b>绝不返回负数</b>。 */
    public long treatRemainingSeconds(long now) {
        if (treatFinishAt == null) {
            return 0L;
        }
        return Math.max(0L, (treatFinishAt - now) / 1000L);
    }

    public Map<String, Long> treatCost() {
        return treatCost;
    }

    /** 本次治疗的总时长（秒），会被加速缩短。 */
    public long treatTotalSeconds() {
        return treatTotalSeconds;
    }

    /**
     * 本次治疗的<b>原始</b>总时长（秒），加速不会改变它。
     *
     * <p>必须与 treatTotalSeconds 分开存：帮助加速是「每次减 1%、有上限」，
     * 这个百分比必须以原始时长为基数，否则减少量会逐次复利缩水，
     * 上限也永远达不到（与城建的 upgradeOriginalSeconds 是同一条道理）。
     */
    public long treatOriginalSeconds() {
        return treatOriginalSeconds;
    }

    // ---------- 训练 ----------

    /**
     * 只校验、不改状态：这一批训练能不能开。
     *
     * <p>单独暴露是因为应用层的正确顺序是<b>先校验、再扣资源、最后入队</b>：
     * <ul>
     *   <li>校验先于扣费，玩家才会先看到「队列已满」这种可操作的提示，
     *       而不是「资源不足」—— 后者会让人以为要去攒资源，实际问题是队列</li>
     *   <li>扣费先于入队，是为了在资源真的不够时不留下一个已入队却没付钱的批次</li>
     * </ul>
     * {@link #train} 内部也调本方法，所以校验逻辑只有一份，不会出现两处判断不一致。
     */
    public void canTrain(String unitId, long count, int availableSlots,
                         long troopCap, long batchMax) {
        requireText(unitId, "unitId");
        if (count <= 0L) {
            throw new IllegalArgumentException("训练数量必须为正，实际=" + count);
        }
        if (batchMax <= 0L) {
            throw new IllegalArgumentException("批上限必须为正，实际=" + batchMax);
        }
        if (count > batchMax) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_TRAIN_QUEUE_FULL,
                    "单次最多训练 " + batchMax + " 个，请求了 " + count + " 个");
        }
        if (queue.containsKey(unitId)) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_TRAIN_QUEUE_FULL,
                    "兵种 " + unitId + " 已在训练中");
        }
        if (usedSlots() >= availableSlots) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_TRAIN_QUEUE_FULL,
                    "训练队列已满：已用 " + usedSlots() + " / " + availableSlots
                            + "，可开启额外队列（B15 特权）");
        }
        long after = totalTroops() + totalTraining() + count;
        if (after > troopCap) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_NOT_UNLOCKED,
                    "超出带兵上限：当前 " + (totalTroops() + totalTraining())
                            + "，上限 " + troopCap + "，本次请求 " + count
                            + "。提升上阵武将的统帅值（升级/升星/装备）可提高上限");
        }
        // 时长溢出在 train() 里按实际 perUnitSeconds 检查（canTrain 不知道单个兵要多久）
    }

    /**
     * 开始一批训练。
     *
     * <p>时间 = 单位训练时间 × 数量（B05 §二），所以一批 1000 个 T1 步兵要 60000 秒 ——
     * 这是刻意的：批量训练不等于加速训练，「一次训很多」与「训得快」是两个独立的付费点，
     * 混在一起会让加速道具失去意义。
     *
     * @param unitId          兵种配置 id
     * @param count           数量，必须为正且不超过批上限
     * @param perUnitSeconds  单个兵的训练秒数，来自 unit 表 trainTimeSec
     * @param availableSlots  可用队列条数（由调用方按基础值 + 特权算好传入）
     * @param troopCap        带兵上限（Σ武将统帅值 × TROOP_PER_COMMAND + 科技加成）
     * @param batchMax        单批上限，来自 global.TRAIN_BATCH_MAX
     * @param now             服务端当前时刻
     * @return 完成时刻
     * @throws com.ironoath.common.BizException 队列满、超批上限、超带兵上限、兵种正在训练中
     */
    public long train(String unitId, long count, long perUnitSeconds, int availableSlots,
                      long troopCap, long batchMax, long now) {
        canTrain(unitId, count, availableSlots, troopCap, batchMax);
        if (perUnitSeconds <= 0L) {
            throw new IllegalArgumentException("单位训练时间必须为正，实际=" + perUnitSeconds);
        }
        // 训练中的兵力已经计入上限：否则玩家可以先把队列塞满、再上阵低统率武将，
        // 用「已在训练」绕过带兵上限（canTrain 里已经算进去了）
        long totalSeconds = perUnitSeconds * count;
        if (totalSeconds / count != perUnitSeconds) {
            throw new IllegalArgumentException("训练总时长溢出 long：count=" + count
                    + ", perUnitSeconds=" + perUnitSeconds);
        }
        long finishAt = now + totalSeconds * 1000L;
        queue.put(unitId, new TrainingTask(unitId, count, now, finishAt, totalSeconds, totalSeconds));
        return finishAt;
    }

    /**
     * 收割所有已到点的训练（兵力入账、释放队列）。
     *
     * <p>由请求触发，<b>不由定时器触发</b> —— 这是惰性结算在军队上的体现。
     *
     * @return 本次完成的兵种 id 列表，顺序与入队顺序一致
     */
    public java.util.List<String> collectFinished(long now) {
        java.util.List<String> finished = new java.util.ArrayList<>();
        java.util.List<String> due = new java.util.ArrayList<>();
        for (TrainingTask task : queue.values()) {
            if (task.finishAt() <= now) {
                due.add(task.unitId());
            }
        }
        for (String unitId : due) {
            TrainingTask task = queue.remove(unitId);
            troops.merge(unitId, task.count(), Long::sum);
            finished.add(unitId);
        }
        return finished;
    }

    /**
     * 加速某一批训练。
     *
     * @return 实际提前的秒数（会被剩余时间截断，<b>不会出现负数</b>）
     */
    public long speedUp(String unitId, long reduceSeconds, long now) {
        TrainingTask task = queue.get(unitId);
        if (task == null) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_TRAIN_QUEUE_FULL,
                    "兵种 " + unitId + " 不在训练队列里");
        }
        if (reduceSeconds <= 0L) {
            throw new IllegalArgumentException("加速秒数必须为正，实际=" + reduceSeconds);
        }
        long remainingSeconds = task.remainingSeconds(now);
        long applied = Math.min(reduceSeconds, remainingSeconds);
        if (applied <= 0L) {
            return 0L;
        }
        queue.put(unitId, task.withFinishAt(task.finishAt() - applied * 1000L));
        return applied;
    }

    /**
     * 帮助加速训练：把「本次实际授予的加速比例」折算成秒数，从这批训练的完成时刻里扣掉。
     *
     * <p><b>上限不在这里判</b>（2026-09-11 口径裁决：社交侧为权威）：单个目标最多能被帮到多少
     * 由 {@code HelpLedger}（来源 {@code global.HELP_SPEEDUP_TOTAL_CAP}）算出的 {@code grantedFixed}
     * 决定，军队侧只负责「比例乘在谁身上」—— 这与
     * {@link com.ironoath.core.city.CityState#speedUpByRatio} 是同一条口径。
     *
     * <p><b>基数取「原始总时长」而不是当前剩余</b>：用剩余时长做基数的话，减少量会逐次复利缩水
     * （第一次减 1%，第二次只减 0.99%，…），永远追不上上限。
     *
     * <p><b>目标已经不在队列里时静默返回 0</b>：请求可能在训练完成或取消的那一刻被帮，
     * 而调用方（社交侧）对过期目标的态度是安静跳过，不是让整批帮助失败。
     *
     * @param ratioFixed 本次授予的加速比例（定点），由调用方按账本结果传入
     * @return 实际提前的秒数（受剩余时长截断，不会为负）
     */
    public long speedUpTrainingByRatio(String unitId, long ratioFixed, long now) {
        TrainingTask task = queue.get(unitId);
        if (task == null || task.originalSeconds() <= 0L) {
            return 0L;
        }
        long seconds = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(task.originalSeconds()), ratioFixed));
        return speedUp(unitId, Math.max(1L, seconds), now);
    }

    /**
     * 取消一批训练。
     *
     * <p>返还的资源由调用方按 {@code cancelRefundFixed} 计算并实际发放 ——
     * 聚合只负责状态变更，不碰资源。这与城建的 {@code cancelUpgrade} 是同一套分工。
     *
     * @return 被取消的任务（调用方据它的 count 与兵种算返还）
     */
    public TrainingTask cancel(String unitId, long now) {
        TrainingTask task = queue.remove(unitId);
        if (task == null) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.UNIT_TRAIN_QUEUE_FULL,
                    "兵种 " + unitId + " 不在训练队列里，无法取消");
        }
        if (task.finishAt() <= now) {
            // 已到点却没被收割：直接入账比取消更符合玩家预期，
            // 否则玩家会因为「点取消的那一瞬间刚好训完」而白丢一批兵
            troops.merge(unitId, task.count(), Long::sum);
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.PARAM_INVALID,
                    "兵种 " + unitId + " 已训练完成并已入账，无法取消");
        }
        return task;
    }

    // ---------- 医院 ----------

    /**
     * 收容伤兵。超出医院容量的部分<b>直接死亡</b>（B05 §1.5、B00 陷阱清单）。
     *
     * @param woundedByUnit   各兵种的伤兵数
     * @param hospitalCapacity 医院容量，由调用方按医院等级算好传入
     * @return 因超容量而死亡的总数
     */
    public long admitWounded(Map<String, Long> woundedByUnit, long hospitalCapacity) {
        if (woundedByUnit == null) {
            throw new IllegalArgumentException("woundedByUnit 不得为 null（没有伤兵请传空 Map）");
        }
        if (hospitalCapacity < 0L) {
            throw new IllegalArgumentException("医院容量不得为负：" + hospitalCapacity);
        }
        long room = hospitalCapacity - totalWounded();
        long overflowDead = 0L;
        // 按传入顺序收容：顺序即战斗结算的兵种顺序，
        // 用 LinkedHashMap 而不是 HashMap 才能让「谁被挤死了」可复现
        for (Map.Entry<String, Long> entry : woundedByUnit.entrySet()) {
            long incoming = entry.getValue();
            if (incoming < 0L) {
                throw new IllegalArgumentException("伤兵数不得为负：" + entry.getKey() + "=" + incoming);
            }
            if (incoming == 0L) {
                continue;
            }
            long admitted = Math.max(0L, Math.min(incoming, room));
            room -= admitted;
            overflowDead += incoming - admitted;
            if (admitted > 0L) {
                wounded.merge(entry.getKey(), admitted, Long::sum);
            }
        }
        return overflowDead;
    }

    /**
     * 开始治疗全部伤兵。
     *
     * @param totalSeconds 治疗总时长 = 伤兵数 × TREAT_TIME_PER_WOUNDED，由调用方算好传入
     * @param cost         治疗资源消耗（由调用方按 unit 表训练消耗 × TREAT_COST_RATIO 算好），
     *                     存快照是为了取消时能按当时的口径返还 —— 配置热更后重算会拿到过期数字
     */
    public long startTreatment(long totalSeconds, Map<String, Long> cost, long now) {
        if (treatFinishAt != null) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.PARAM_INVALID, "已在治疗中，剩余 "
                            + treatRemainingSeconds(now) + " 秒");
        }
        if (totalWounded() <= 0L) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.PARAM_INVALID, "没有伤兵需要治疗");
        }
        if (totalSeconds <= 0L) {
            throw new IllegalArgumentException("治疗时长必须为正，实际=" + totalSeconds);
        }
        treatTotalSeconds = totalSeconds;
        treatOriginalSeconds = totalSeconds;
        treatFinishAt = now + totalSeconds * 1000L;
        treatCost = cost == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        return treatFinishAt;
    }

    /** 加速治疗。返回实际提前的秒数（会被剩余时间截断，不会出现负数）。 */
    public long speedUpTreatment(long reduceSeconds, long now) {
        if (treatFinishAt == null) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.PARAM_INVALID, "当前没有在治疗");
        }
        if (reduceSeconds <= 0L) {
            throw new IllegalArgumentException("加速秒数必须为正，实际=" + reduceSeconds);
        }
        long remaining = treatRemainingSeconds(now);
        long applied = Math.min(reduceSeconds, remaining);
        if (applied <= 0L) {
            return 0L;
        }
        treatFinishAt = treatFinishAt - applied * 1000L;
        return applied;
    }

    /**
     * 帮助加速治疗。口径与 {@link #speedUpTrainingByRatio} 完全一致（上限归社交侧、以原始总时为基数），
     * 只是基数是 {@link #treatOriginalSeconds()} —— 治疗是一个玩家同时只有一份的全局倒计时，
     * 所以没有"哪个目标"这个参数。
     *
     * @param ratioFixed 本次授予的加速比例（定点），由调用方按账本结果传入
     * @return 实际提前的秒数；没在治疗（或已完成未收割）时返回 0
     */
    public long speedUpTreatmentByRatio(long ratioFixed, long now) {
        if (treatFinishAt == null || treatOriginalSeconds <= 0L) {
            return 0L;
        }
        long seconds = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(treatOriginalSeconds), ratioFixed));
        return speedUpTreatment(Math.max(1L, seconds), now);
    }

    /**
     * 收割已完成的治疗：伤兵归队。
     *
     * <p><b>归队不受带兵上限约束</b>：这些兵本来就是玩家的，治疗只是让他们从医院回到军营。
     * 若在这里卡上限，玩家会遇到「治好了却领不出来」的死锁 ——
     * 而上限本来的作用是限制训练，不是没收已有的兵。
     *
     * @return 本次归队的各兵种数量；没在治疗或未到点时返回空 Map
     */
    public Map<String, Long> collectTreated(long now) {
        if (treatFinishAt == null || treatFinishAt > now) {
            return Map.of();
        }
        Map<String, Long> returned = new LinkedHashMap<>(wounded);
        for (Map.Entry<String, Long> entry : returned.entrySet()) {
            troops.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        wounded.clear();
        treatFinishAt = null;
        treatTotalSeconds = 0L;
        treatOriginalSeconds = 0L;
        treatCost = Map.of();
        return returned;
    }

    /**
     * 取消治疗。伤兵留在医院（不治不会死），返还的资源由调用方按 {@code treatCost} 与返还比例算。
     *
     * @return 本次治疗的资源消耗快照，调用方据此计算返还额
     */
    public Map<String, Long> cancelTreatment(long now) {
        if (treatFinishAt == null) {
            throw new com.ironoath.common.BizException(
                    com.ironoath.common.ErrorCode.PARAM_INVALID, "当前没有在治疗，无法取消");
        }
        Map<String, Long> cost = treatCost;
        treatFinishAt = null;
        treatTotalSeconds = 0L;
        treatOriginalSeconds = 0L;
        treatCost = Map.of();
        return cost;
    }

    // ---------- 直接改兵力（战斗结算、掠夺、补偿） ----------

    /**
     * 扣减兵力（战斗损失、被掠夺）。
     *
     * <p>扣到 0 为止，<b>绝不为负</b>：负兵力会在下一次战斗的「Σ数量×属性」里
     * 变成负的攻击力，让被打的一方反而变强 —— 这类 bug 不报错、不崩溃，
     * 只会让战报出现无法解释的结果。
     *
     * @return 实际扣掉的数量
     */
    public long deduct(String unitId, long count) {
        requireText(unitId, "unitId");
        if (count < 0L) {
            throw new IllegalArgumentException("扣减数量不得为负：" + count);
        }
        long have = troops.getOrDefault(unitId, 0L);
        long actual = Math.min(have, count);
        long rest = have - actual;
        if (rest == 0L) {
            troops.remove(unitId);
        } else {
            troops.put(unitId, rest);
        }
        return actual;
    }

    /** 增加兵力（补偿、活动赠送）。战斗归队走 {@link #collectTreated}，不要用这个方法。 */
    public void add(String unitId, long count) {
        requireText(unitId, "unitId");
        if (count <= 0L) {
            throw new IllegalArgumentException("增加数量必须为正：" + count);
        }
        troops.merge(unitId, count, Long::sum);
    }

    public void addExtraSlot(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("额外队列数不得为负：" + count);
        }
        extraSlots += count;
    }

    /**
     * 供仓储反序列化写回。业务代码不要用。
     *
     * <p><b>先把入参各拷一份再 clear 自己的容器</b>：{@link #troops()} / {@link #queue()} /
     * {@link #wounded()} 返回的都是内部 Map 的不可变<b>视图</b>，
     * 如果调用方把某个视图传回来（「读出 → 改一项 → 写回」是很自然的写法），
     * 那么 {@code clear()} 会把入参一起清空，拷回来的就是空的 ——
     * 不报错、不抛异常，只是兵力、队列、伤兵全部凭空消失。
     */
    public void restore(Map<String, Long> restoredTroops,
                        Map<String, TrainingTask> restoredQueue,
                        Map<String, Long> restoredWounded,
                        Long restoredTreatFinishAt,
                        long restoredTreatTotalSeconds,
                        long restoredTreatOriginalSeconds,
                        Map<String, Long> restoredTreatCost,
                        int restoredExtraSlots) {
        Map<String, Long> troopsCopy = restoredTroops == null
                ? Map.of() : new LinkedHashMap<>(restoredTroops);
        Map<String, TrainingTask> queueCopy = restoredQueue == null
                ? Map.of() : new LinkedHashMap<>(restoredQueue);
        Map<String, Long> woundedCopy = restoredWounded == null
                ? Map.of() : new LinkedHashMap<>(restoredWounded);
        Map<String, Long> costCopy = restoredTreatCost == null
                ? Map.of() : new LinkedHashMap<>(restoredTreatCost);

        troops.clear();
        troopsCopy.forEach((k, v) -> {
            if (v != null && v > 0L) {
                troops.put(k, v);
            }
        });
        queue.clear();
        queue.putAll(queueCopy);
        wounded.clear();
        woundedCopy.forEach((k, v) -> {
            if (v != null && v > 0L) {
                wounded.put(k, v);
            }
        });
        treatFinishAt = restoredTreatFinishAt;
        treatTotalSeconds = restoredTreatTotalSeconds;
        treatOriginalSeconds = restoredTreatOriginalSeconds;
        treatCost = Collections.unmodifiableMap(costCopy);
        extraSlots = restoredExtraSlots;
    }

    /**
     * 一份完整的军队存档。
     *
     * <p><b>为什么这个字段表在领域里</b>：它原先写在 {@code InMemoryArmyStore.copyOf}（用八个 getter
     * 现拼），那是存储层的一份私有副本 —— 补第二种存储时它必然变成两份，而少抄一项的表现不是报错，
     * 是"换存储后某个字段静默不持久化"（训练队列的治疗进度、额外治疗位尤其容易漏）。
     *
     * <p>训练队列用 {@code List<TrainingTask>} 而不是 {@code Map}：{@link TrainingTask} 自带
     * {@code unitId}（键就是它），列形态在文档存储里映射更稳，且顺序由 {@link #fromSnapshot}
     * 按列序重建为 LinkedHashMap，玩家看到的队列顺序不变。
     */
    public record Snapshot(Map<String, Long> troops, List<TrainingTask> queue, Map<String, Long> wounded,
                           Long treatFinishAt, long treatTotalSeconds, long treatOriginalSeconds,
                           Map<String, Long> treatCost, int extraSlots) {
    }

    /** 取出完整快照（不可变，可安全跨线程/跨存储传递）。 */
    public Snapshot snapshot() {
        return new Snapshot(troops(), List.copyOf(queue().values()), wounded(), treatFinishAt(),
                treatTotalSeconds(), treatOriginalSeconds(), treatCost(), extraSlots());
    }

    /** 由快照重建。写入语义完全交给 {@link #restore} —— 那里已有"入参可能是内部视图"的防护。 */
    public static ArmyState fromSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        Map<String, TrainingTask> keyed = new LinkedHashMap<>();
        for (TrainingTask task : snapshot.queue()) {
            keyed.put(task.unitId(), task);
        }
        ArmyState state = new ArmyState();
        state.restore(snapshot.troops(), keyed, snapshot.wounded(), snapshot.treatFinishAt(),
                snapshot.treatTotalSeconds(), snapshot.treatOriginalSeconds(),
                snapshot.treatCost(), snapshot.extraSlots());
        return state;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }

    @Override
    public String toString() {
        return "ArmyState{兵力" + troops + ", 训练中" + queue.keySet() + ", 伤兵" + wounded
                + ", 治疗完成于" + treatFinishAt + "}";
    }

    /**
     * 一批训练任务。
     *
     * @param originalSeconds 原始总时长，加速不会改变它 —— 帮助加速的百分比必须以原始时长为基数，
     *                        否则减少量会逐次复利缩水（与城建的 upgradeOriginalSeconds 同理）
     */
    public record TrainingTask(String unitId, long count, long startedAt,
                               long finishAt, long totalSeconds, long originalSeconds) {

        public TrainingTask {
            requireText(unitId, "unitId");
            if (count <= 0L) {
                throw new IllegalArgumentException("训练数量必须为正：" + count);
            }
            if (startedAt <= 0L) {
                throw new IllegalArgumentException("startedAt 必须为正的服务端时间戳：" + startedAt);
            }
            if (finishAt < startedAt) {
                throw new IllegalArgumentException("完成时刻不得早于开始时刻：finishAt=" + finishAt
                        + ", startedAt=" + startedAt);
            }
            if (totalSeconds <= 0L || originalSeconds <= 0L) {
                throw new IllegalArgumentException("时长必须为正：total=" + totalSeconds
                        + ", original=" + originalSeconds);
            }
        }

        /** 剩余秒数。已到点返回 0，<b>绝不返回负数</b>。 */
        public long remainingSeconds(long now) {
            return Math.max(0L, (finishAt - now) / 1000L);
        }

        /** 进度（定点 0~10000）。已到点返回满进度，未开始返回 0。 */
        public long progressFixed(long now) {
            if (now >= finishAt) {
                return FixedPoint.SCALE;
            }
            if (now <= startedAt) {
                return 0L;
            }
            long elapsed = now - startedAt;
            long total = finishAt - startedAt;
            return Math.min(FixedPoint.SCALE, elapsed * FixedPoint.SCALE / total);
        }

        public TrainingTask withFinishAt(long newFinishAt) {
            return new TrainingTask(unitId, count, startedAt, newFinishAt, totalSeconds, originalSeconds);
        }
    }
}
