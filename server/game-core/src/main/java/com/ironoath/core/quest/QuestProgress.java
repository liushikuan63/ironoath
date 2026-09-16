package com.ironoath.core.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ironoath.core.event.GameEvent;

/**
 * 职责：一个玩家的任务进度账本 —— 被事件推动，不轮询（B12 §1、验收 7）。
 * 依赖：{@link GameEvent}、{@link GoalType}。<b>不依赖配置表也不依赖奖励发放</b>：
 * 配置在 web 层被装配成 {@link Def}，奖励领取由 web 层调 B04 的 RewardGrantor。
 *
 * <p><b>奖励内容刻意不进本类</b>：任务域只回答「完成了没有、领了没有」，
 * 「发什么、发不下怎么办（背包满 / 超出仓库容量）」是 B04 通用发放器的职责。
 * 把 reward 列表抄一份进来的话，奖励口径就有了两个家，
 * 而两个家的症状是「任务发的东西和邮件补发的东西不一样」。
 *
 * <p><b>进度为什么必须被事件记下来而不能事后反推</b>：
 * 「累计训练 20 个兵」无法从当前状态算出 —— 兵可能已经战死、被派出去、或用于治疗。
 * 唯一能知道「累计训过多少」的时刻就是训练发生的那一刻。
 * 这也是 B00「事件总线化」这条硬约束在任务系统上的具体形态。
 */
public final class QuestProgress {

    /** 任务类型。决定重置周期，不决定奖励（奖励在配置表里）。 */
    public enum QuestType {
        /** 主线：永不重置，且有前置链。 */
        MAIN,
        /** 支线：永不重置。 */
        SIDE,
        /** 每日：跨天清零（进度与领取状态都清）。 */
        DAILY,
        /** 每周：跨周清零。 */
        WEEKLY,
        /**
         * 成就（B17 §4）：长期目标，**永不重置、跨赛季保留**（与 MAIN 的保留口径一致）。
         *
         * <p>成就不另开一套框架：它就是 quest 表里一种 questType，奖励、进度、领取、红点全部复用。
         * 「跨赛季保留」这条承诺的落点是<b>类型本身不重置</b> —— 赛季结算不碰任务进度账本，
         * 所以这里不需要、也不该有"赛季开始时特意保留成就"的分支（那种分支一旦漏写就是丢进度）。
         */
        ACHIEVEMENT;

        public boolean resets() {
            return this == DAILY || this == WEEKLY;
        }
    }

    /**
     * 一条任务的定义（由 web 层从 quest 表装配）。
     *
     * @param goalTarget 目标细分，null 表示不限定（与 {@link GameEvent#matchesTarget} 同一条规则）
     * @param preQuestId 前置任务 id，null 表示无前置
     */
    public record Def(String questId, QuestType type, GoalType goalType, String goalTarget,
                      long goalValue, String preQuestId) {
        public Def {
            if (questId == null || questId.isBlank()) {
                throw new IllegalArgumentException("questId 不得为空");
            }
            if (type == null || goalType == null) {
                throw new IllegalArgumentException("type 与 goalType 都不得为 null");
            }
            if (goalValue < 1L) {
                throw new IllegalArgumentException("任务 " + questId + " 的 goalValue 必须为正，实际=" + goalValue
                        + "：为 0 的任务一创建就是完成状态，玩家会看到一个不用做就能领的奖励");
            }
            goalTarget = goalTarget == null || goalTarget.isBlank() ? null : goalTarget;
            preQuestId = preQuestId == null || preQuestId.isBlank() ? null : preQuestId;
        }
    }

    /**
     * 一条任务的当前进度。
     *
     * @param current  累加型是累计量，状态型是<b>最近一次事件带来的当前值</b>（可升可降）
     * @param claimed  奖励是否已领。领取是幂等的：重复领必须被拒而不是再发一次
     */
    public record Entry(String questId, QuestType type, GoalType goalType, String goalTarget,
                        long goalValue, String preQuestId, long current, boolean claimed) {

        public boolean complete() {
            return current >= goalValue;
        }
    }

    private final String playerId;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** 已领取的前置任务，用于判定后续任务是否解锁。 */
    private final Map<String, Boolean> claimedById = new LinkedHashMap<>();
    private String dayKey;
    private String weekKey;
    private long eventCount;

    private QuestProgress(String playerId, String dayKey, String weekKey) {
        this.playerId = playerId;
        this.dayKey = dayKey;
        this.weekKey = weekKey;
    }

    /**
     * 开一本新账本：全部任务进度为 0。
     *
     * @param dayKey 当前日期键（{@code DayKey.of(now)}）。每日/每周任务靠它判断跨天
     */
    public static QuestProgress open(String playerId, List<Def> defs, String dayKey, String weekKey) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (defs == null || defs.isEmpty()) {
            throw new IllegalArgumentException("任务定义为空：那样账本里一条任务都没有，"
                    + "玩家的任务面板会永远空白，而日志里一切正常");
        }
        QuestProgress progress = new QuestProgress(playerId, dayKey, weekKey);
        for (Def def : defs) {
            if (progress.entries.containsKey(def.questId())) {
                throw new IllegalArgumentException("任务 id 重复: " + def.questId());
            }
            progress.entries.put(def.questId(), new Entry(def.questId(), def.type(), def.goalType(),
                    def.goalTarget(), def.goalValue(), def.preQuestId(), 0L, false));
            progress.claimedById.put(def.questId(), Boolean.FALSE);
        }
        return progress;
    }

    /** 从存档恢复。 */
    public static QuestProgress restore(String playerId, List<Entry> restored, String dayKey,
                                        String weekKey) {
        QuestProgress progress = new QuestProgress(playerId, dayKey, weekKey);
        for (Entry entry : restored) {
            progress.entries.put(entry.questId(), entry);
            progress.claimedById.put(entry.questId(), entry.claimed());
        }
        return progress;
    }

    /**
     * 消费一个事件（作为 {@code GameEventBus.Listener} 注册进去）。
     *
     * <p><b>未解锁的任务不累计</b>：前置没领就攒进度，会让玩家在解锁的瞬间
     * 看到一串「已完成」—— 那既不是他刚做的事，也让主线失去了引导节奏。
     * 这是一条<b>口径裁定</b>（B12 没写），已记录待确认。
     *
     * <p><b>状态型是「置位」不是「累加」</b>：见 {@link GoalType} 的类注释。
     */
    public void onEvent(GameEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不得为 null");
        }
        if (!event.playerId().equals(playerId)) {
            // 别人的事件不该进这本账。总线是按类型派发的，不带玩家过滤，
            // 所以每个账本必须自己认人 —— 漏了这一条，全服的任务进度会串号
            return;
        }
        eventCount++;
        for (Entry entry : entries.values()) {
            if (entry.goalType() != event.goalType() || !event.matchesTarget(entry.goalTarget())) {
                continue;
            }
            if (!unlocked(entry)) {
                continue;
            }
            long next = entry.goalType().accumulates()
                    ? entry.current() + event.amount()
                    : event.amount();
            // 上限截断到 goalValue：累计到 10 倍的量对「完成与否」没有任何额外信息，
            // 却让存档里的数字一直涨，而玩家在面板上看到的是「200/20」这种莫名其妙的东西
            entries.put(entry.questId(), withCurrent(entry, Math.min(next, entry.goalValue())));
        }
    }

    /**
     * 领取奖励的资格判定。<b>本类不发奖</b>，只把状态从「可领」推进到「已领」。
     *
     * @throws IllegalStateException 未完成、已领过、或未解锁
     */
    public void claim(String questId) {
        Entry entry = require(questId);
        if (!unlocked(entry)) {
            throw new IllegalStateException("任务 " + questId + " 尚未解锁：前置任务 "
                    + entry.preQuestId() + " 还没领取");
        }
        if (entry.claimed()) {
            throw new IllegalStateException("任务 " + questId + " 的奖励已经领过了");
        }
        if (!entry.complete()) {
            throw new IllegalStateException("任务 " + questId + " 还没完成："
                    + entry.current() + "/" + entry.goalValue());
        }
        entries.put(questId, new Entry(entry.questId(), entry.type(), entry.goalType(), entry.goalTarget(),
                entry.goalValue(), entry.preQuestId(), entry.current(), true));
        claimedById.put(questId, Boolean.TRUE);
    }

    /**
     * 跨天/跨周重置。
     *
     * <p><b>惰性调用，不跑定时器</b>（B00 陷阱 2）：读任务面板时先调一次，
     * 由调用方把当前 dayKey 传进来。日键相同就什么都不做。
     *
     * <p>重置的是<b>进度与领取状态</b>，不是任务本身：每日任务每天都是同一条，
     * 只是进度归零、可以重新领一次。
     *
     * @param newDayKey  新的日期键
     * @param newWeekKey 新的周键（跨周才重置 WEEKLY；传 null 表示调用方不区分周）
     * @return 被重置的任务条数
     */
    public int rollover(String newDayKey, String newWeekKey) {
        if (newDayKey == null || newDayKey.isBlank()) {
            throw new IllegalArgumentException("newDayKey 不得为空");
        }
        boolean dayChanged = !newDayKey.equals(dayKey);
        boolean weekChanged = newWeekKey != null && !newWeekKey.equals(weekKey);
        if (!dayChanged && !weekChanged) {
            return 0;
        }
        int reset = 0;
        for (Entry entry : entries.values()) {
            boolean shouldReset = (entry.type() == QuestType.DAILY && dayChanged)
                    || (entry.type() == QuestType.WEEKLY && weekChanged);
            if (!shouldReset) {
                continue;
            }
            entries.put(entry.questId(), new Entry(entry.questId(), entry.type(), entry.goalType(),
                    entry.goalTarget(), entry.goalValue(), entry.preQuestId(), 0L, false));
            claimedById.put(entry.questId(), Boolean.FALSE);
            reset++;
        }
        dayKey = newDayKey;
        if (newWeekKey != null) {
            weekKey = newWeekKey;
        }
        return reset;
    }

    /**
     * 当前周键。<b>由调用方给，不从 dayKey 里截一段</b>：
     * 截前 8 位得到的是「年-月」而不是「第几周」，那样跨月与跨周会被判成同一件事，
     * 而每月 1 号那条周任务会被莫名其妙地重置两次。
     */
    public String weekKey() {
        return weekKey;
    }

    public Entry entry(String questId) {
        return require(questId);
    }

    public List<Entry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    /** 已完成且未领取的任务数 —— 红点系统的输入（B12 §4：叶子节点注册条件函数）。 */
    public int claimableCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (claimable(entry)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 某个任务此刻能不能领。<b>与 {@link #claimableCount()} 同一条判定</b>：
     * 视图里的 {@code claimable} 标志与徽标数各算一遍的结局，是「徽标说 1、列表里一行都点不了」——
     * 两个数字各自都有日志，谁都不像 bug。
     */
    public boolean claimable(String questId) {
        return claimable(require(questId));
    }

    /** 某个任务是否已解锁（前置已领或没有前置）。协议里的 {@code locked} 是它的反面。 */
    public boolean unlocked(String questId) {
        return unlocked(require(questId));
    }

    private boolean claimable(Entry entry) {
        return unlocked(entry) && entry.complete() && !entry.claimed();
    }

    /** 已解锁的任务数。 */
    public int unlockedCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (unlocked(entry)) {
                count++;
            }
        }
        return count;
    }

    /** 消费过的事件数。<b>长期为 0 说明没有任何业务在发事件</b>，那是接线漏了。 */
    public long eventCount() {
        return eventCount;
    }

    public String playerId() {
        return playerId;
    }

    public String dayKey() {
        return dayKey;
    }

    /** 前置任务已领取（或没有前置）时才算解锁。 */
    private boolean unlocked(Entry entry) {
        return entry.preQuestId() == null || Boolean.TRUE.equals(claimedById.get(entry.preQuestId()));
    }

    private Entry require(String questId) {
        Entry entry = entries.get(questId);
        if (entry == null) {
            throw new IllegalArgumentException("任务不存在: " + questId + "，现有=" + entries.keySet());
        }
        return entry;
    }

    private static Entry withCurrent(Entry entry, long current) {
        return new Entry(entry.questId(), entry.type(), entry.goalType(), entry.goalTarget(),
                entry.goalValue(), entry.preQuestId(), current, entry.claimed());
    }
}
