package com.ironoath.core.stage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：玩家的章节副本进度（B09 §4/§6）—— 每关的星级、最少回合、通关时刻、扫荡次数。
 * 依赖：无（纯 Java，可脱离容器单测）。
 *
 * <p><b>星级只升不降</b>：重试打得更差不该扣星。扣星会让玩家因为怕掉星而不敢重试，
 * 而重试正是养成的动力 —— 他会停在「打不过了但也不敢试」的状态，
 * 既不消耗体力也不产生付费动机，这是最糟的一种流失。
 *
 * <p><b>三个星条件分开存，不只存一个总数</b>：玩家看到 2/3 时需要知道差的是哪一条，
 * 否则他只能反复试。而「差哪一条」正是驱动他去练兵、换阵型、升武将的信息。
 * 只存总数的话，补齐条件时无法判断该点亮哪一颗星。
 */
public final class StageProgress {

    /**
     * 单关的成绩。
     *
     * @param cleared      是否通关（第一星）
     * @param noLoss       是否曾经无损通关（第二星）。<b>无损 = 己方阵亡为 0，伤兵允许</b> ——
     *                     战斗内核里攻方损失约等于「敌方总兵力 / LANCHESTER_K」，与自己带多少兵无关，
     *                     所以「阵亡与伤兵都为 0」在任何关卡都不可达，一颗永远拿不到的星比
     *                     一颗定义稍宽的星更糟。历史最好，不是最近一次
     * @param withinRounds 是否曾经在回合上限内通关（第三星）
     * @param bestRounds   历史最少回合数；未通关为 0
     * @param clearedAt    首次通关的服务端时刻；未通关为 0
     * @param sweepCount   累计扫荡次数
     */
    public record Record(boolean cleared, boolean noLoss, boolean withinRounds,
                         int bestRounds, long clearedAt, long sweepCount) {

        public Record {
            if (bestRounds < 0) {
                throw new IllegalArgumentException("bestRounds 不得为负：" + bestRounds);
            }
            if (clearedAt < 0L || sweepCount < 0L) {
                throw new IllegalArgumentException("时间戳与次数不得为负：clearedAt=" + clearedAt
                        + ", sweepCount=" + sweepCount);
            }
            if (!cleared && (noLoss || withinRounds)) {
                // 没通关却拿到了星：三个星条件都以「通关」为前提，出现这个状态说明写入方漏了判定
                throw new IllegalArgumentException("未通关的关卡不得有任何星：noLoss=" + noLoss
                        + ", withinRounds=" + withinRounds);
            }
        }

        public int stars() {
            return (cleared ? 1 : 0) + (noLoss ? 1 : 0) + (withinRounds ? 1 : 0);
        }

        /** 未挑战过。 */
        public static Record none() {
            return new Record(false, false, false, 0, 0L, 0L);
        }
    }

    private final Map<String, Record> byStage = new LinkedHashMap<>();
    private long version;

    public Record of(String stageId) {
        return byStage.getOrDefault(stageId, Record.none());
    }

    public boolean cleared(String stageId) {
        return of(stageId).cleared();
    }

    public int stars(String stageId) {
        return of(stageId).stars();
    }

    /** 只读视图，保持插入顺序（按首次挑战的先后）。 */
    public Map<String, Record> all() {
        return Collections.unmodifiableMap(byStage);
    }

    public long version() {
        return version;
    }

    /**
     * 记录一次挑战结果。
     *
     * @param rounds 本次实际回合数；未通关时用于比较但不会写入 bestRounds
     * @return 本次获得的星数（不是历史最好），供响应下发「本次 2 星」
     */
    public int recordResult(String stageId, boolean won, boolean noLoss, boolean withinRounds,
                            int rounds, long now) {
        requireText(stageId);
        if (rounds < 0) {
            throw new IllegalArgumentException("回合数不得为负：" + rounds);
        }
        if (now <= 0L) {
            throw new IllegalArgumentException("now 必须为正的服务端时间戳，实际=" + now);
        }
        int earned = (won ? 1 : 0) + (won && noLoss ? 1 : 0) + (won && withinRounds ? 1 : 0);
        Record old = of(stageId);
        if (!won) {
            // 失败不改星级，但仍然写回：sweepCount 等字段不该因为一次失败而丢失
            byStage.put(stageId, old);
            return 0;
        }
        boolean firstClear = !old.cleared();
        // 最少回合取历史最小；首次通关时 bestRounds 还是 0，
        // 所以这里的 0 要当作「没有记录」而不是「0 回合通关」
        int bestRounds = old.bestRounds() == 0 || rounds < old.bestRounds() ? rounds : old.bestRounds();
        Record next = new Record(true,
                old.noLoss() || noLoss,
                old.withinRounds() || withinRounds,
                bestRounds,
                firstClear ? now : old.clearedAt(),
                old.sweepCount());
        byStage.put(stageId, next);
        return earned;
    }

    /** 累加扫荡次数。扫荡不改星级 —— 星级是「打过最好的一次」，扫荡只是重复已知结果。 */
    public void addSweeps(String stageId, long count) {
        requireText(stageId);
        if (count < 0L) {
            throw new IllegalArgumentException("扫荡次数不得为负：" + count);
        }
        Record old = of(stageId);
        byStage.put(stageId, new Record(old.cleared(), old.noLoss(), old.withinRounds(),
                old.bestRounds(), old.clearedAt(), old.sweepCount() + count));
    }

    /** 供仓储反序列化写回。业务代码不要用。 */
    public void restore(Map<String, Record> restored, long restoredVersion) {
        byStage.clear();
        if (restored != null) {
            // 先拷一份再 clear：入参可能就是 all() 返回的视图，
            // 直接 clear 会把正在读的入参一起清空（这个别名 bug 在本项目已经踩过四次）
            byStage.putAll(new LinkedHashMap<>(restored));
        }
        version = restoredVersion;
    }

    /** 持久化成功后由仓储调用。 */
    public void incrementVersion() {
        version++;
    }

    public StageProgress copy() {
        StageProgress copy = new StageProgress();
        copy.restore(byStage, version);
        return copy;
    }

    private static void requireText(String stageId) {
        if (stageId == null || stageId.isBlank()) {
            throw new IllegalArgumentException("stageId 不得为空");
        }
    }
}
