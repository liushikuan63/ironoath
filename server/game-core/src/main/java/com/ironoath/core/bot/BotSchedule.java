package com.ironoath.core.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.ironoath.common.rng.Rng;

/**
 * 职责：Bot 的作息调度 —— 决定「什么时候 tick、tick 时考虑哪些行为」（B11 §三 调度、§四 作息、验收 10）。
 * 依赖：game-common 的 Rng（纯 Java，零框架）。
 *
 * <p><b>本类只算时间，不执行任何行为，也不持有任何定时器</b>。
 * B11 禁止项写得很硬：「不要用常驻线程轮询 Bot，必须走 Redis ZSET 延迟队列」。
 * 所以本类的输出是「下一次 tick 的服务端时间戳」，由 game-web 的调度器把它塞进
 * Redisson 的 RDelayedQueue —— 与 B07 行军到期用的是同一套机制，
 * 全项目因此只有「延迟队列 + 到期扫描」一种定时手段，没有第二种。
 *
 * <p><b>验收 10 的口径就落在本类的权重表上</b>：凌晨 2-5 点的 tick 次数必须
 * 少于晚 20-22 点的 20%。bot_schedule 表里 LOGIN 行的权重是 3-4 点对 2-3 点、
 * 20-22 点对 85-100，比值 3.6%。{@link #nightToPeakRatio} 把这个比值算出来供测试断言 ——
 * 把验收标准写成一个可计算的函数，比写在文档里可靠得多。
 */
public final class BotSchedule {

    /** 行为类型。与 bot_schedule 表的 actionType 枚举一致。 */
    public enum Action {
        LOGIN, LOGOUT, BUILD, TRAIN, GATHER, ATTACK_MONSTER, JOIN_RALLY, CHAT, DONATE
    }

    /**
     * @param ticksPerDayMin 每个 Bot 每天最少 tick 次数。来源 global.BOT_TICKS_PER_DAY_MIN
     * @param ticksPerDayMax 上限。来源 global.BOT_TICKS_PER_DAY_MAX
     * @param weights        (action, hour) → 相对权重。来源 bot_schedule 表
     */
    public record Rules(int ticksPerDayMin, int ticksPerDayMax,
                        Map<Action, Map<Integer, Integer>> weights) {
        public Rules {
            if (ticksPerDayMin < 1) {
                throw new IllegalArgumentException("ticksPerDayMin 必须 >= 1，否则 Bot 永远不会行动，实际="
                        + ticksPerDayMin);
            }
            if (ticksPerDayMax < ticksPerDayMin) {
                throw new IllegalArgumentException("ticksPerDay 区间非法：min=" + ticksPerDayMin
                        + " > max=" + ticksPerDayMax);
            }
            if (weights == null || weights.isEmpty()) {
                throw new IllegalArgumentException("权重表不得为空：没有权重就无法按真人曲线分布 tick");
            }
            Map<Action, Map<Integer, Integer>> copy = new EnumMap<>(Action.class);
            for (Map.Entry<Action, Map<Integer, Integer>> entry : weights.entrySet()) {
                // TreeMap 让小时键有序：pickHour 的累加抽取因此按 0→23 的顺序进行，
                // 结果不依赖权重表的插入顺序
                Map<Integer, Integer> byHour = new java.util.TreeMap<>();
                int total = 0;
                for (Map.Entry<Integer, Integer> hour : entry.getValue().entrySet()) {
                    if (hour.getKey() < 0 || hour.getKey() > 23) {
                        throw new IllegalArgumentException("hourOfDay 必须在 [0,23]，实际=" + hour.getKey());
                    }
                    if (hour.getValue() < 0) {
                        throw new IllegalArgumentException("权重不得为负，实际=" + hour.getValue());
                    }
                    byHour.put(hour.getKey(), hour.getValue());
                    total += hour.getValue();
                }
                if (total <= 0) {
                    throw new IllegalArgumentException("行为 " + entry.getKey()
                            + " 的权重全为 0：那等于这个行为永远不会发生，应当直接从表里删掉而不是留一行死数据");
                }
                copy.put(entry.getKey(), Collections.unmodifiableMap(byHour));
            }
            weights = Collections.unmodifiableMap(copy);
        }
    }

    private final Rules rules;

    public BotSchedule(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 抽一个 tick 小时。
     *
     * <p>按 LOGIN 行的权重加权抽取，所以凌晨抽中的概率天然低 —— 验收 10 不是靠
     * 「凌晨不要 tick」这种硬规则实现的，而是靠权重曲线的形状。
     * 硬规则的问题是它会被下一次改配置绕过，而曲线是数据。
     */
    public int pickHour(Rng rng) {
        Map<Integer, Integer> byHour = rules.weights().get(Action.LOGIN);
        if (byHour == null || byHour.isEmpty()) {
            throw new IllegalStateException("bot_schedule 缺少 LOGIN 行：没有它就无法决定 tick 时刻");
        }
        int total = 0;
        for (int weight : byHour.values()) {
            total += weight;
        }
        int roll = (int) rng.range(0, total - 1);
        int accumulator = 0;
        for (Map.Entry<Integer, Integer> entry : byHour.entrySet()) {
            accumulator += entry.getValue();
            if (roll < accumulator) {
                return entry.getKey();
            }
        }
        // 走到这里只可能是权重表被并发改动过；退回最后一个小时而不是抛错，
        // 因为 tick 时刻晚一点无害，而抛错会让这个 Bot 永远不再被调度
        return byHour.keySet().iterator().next();
    }

    /**
     * 某一小时该 Bot 会考虑哪些行为（按权重降序）。
     *
     * <p>决策树（{@link BotDecisionTree}）拿这个列表当候选，再按优先级与资源状况挑一个。
     * 两层过滤是必要的：只按权重挑会让 Bot 在资源不够时反复尝试同一件事，
     * 只按优先级挑又会让所有 Bot 的行为完全一致（一眼假）。
     */
    public List<Action> candidateActions(int hour) {
        List<Action> out = new ArrayList<>();
        for (Map.Entry<Action, Map<Integer, Integer>> entry : rules.weights().entrySet()) {
            Integer weight = entry.getValue().get(hour);
            if (weight != null && weight > 0) {
                out.add(entry.getKey());
            }
        }
        // 权重高的排前面：决策树从前往后找第一个可执行的，
        // 于是「这个小时最像真人会做的事」优先被选中
        out.sort((a, b) -> Integer.compare(
                rules.weights().get(b).getOrDefault(hour, 0),
                rules.weights().get(a).getOrDefault(hour, 0)));
        return Collections.unmodifiableList(out);
    }

    /**
     * 这个 Bot 每天 tick 几次（在 [min, max] 区间内按活跃度插值）。
     *
     * @param activenessFixed 定点 0~1.0，来自 BotProfile.ai().activeness()
     */
    public int ticksPerDay(long activenessFixed) {
        int span = rules.ticksPerDayMax() - rules.ticksPerDayMin();
        int extra = (int) (span * activenessFixed / com.ironoath.common.num.FixedPoint.SCALE);
        return rules.ticksPerDayMin() + Math.max(0, Math.min(span, extra));
    }

    /**
     * 验收 10 的比值：凌晨 2-5 点的 LOGIN 权重之和 ÷ 晚 20-22 点的 LOGIN 权重之和（定点 ×10000）。
     *
     * <p>把验收标准写成一个可计算的函数，比写在文档里可靠：文档里的「< 20%」
     * 在有人把凌晨权重从 3 调到 30 之后不会变红，而这个函数的调用方会。
     */
    public long nightToPeakRatio() {
        Map<Integer, Integer> byHour = rules.weights().get(Action.LOGIN);
        if (byHour == null) {
            throw new IllegalStateException("bot_schedule 缺少 LOGIN 行");
        }
        long night = sum(byHour, 2, 3, 4, 5);
        long peak = sum(byHour, 20, 21, 22);
        if (peak <= 0) {
            throw new IllegalStateException("晚 20-22 点的 LOGIN 权重合计为 0，验收 10 的分母不存在");
        }
        return night * com.ironoath.common.num.FixedPoint.SCALE / peak;
    }

    private static long sum(Map<Integer, Integer> byHour, int... hours) {
        long total = 0;
        for (int hour : hours) {
            total += byHour.getOrDefault(hour, 0);
        }
        return total;
    }

    public Rules rules() {
        return rules;
    }
}
