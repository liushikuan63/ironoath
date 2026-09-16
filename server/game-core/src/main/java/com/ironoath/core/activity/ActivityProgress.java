package com.ironoath.core.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.event.GameEvent;

/**
 * 职责：一个玩家的活动进度账本 —— 被事件推动、按窗口惰性轮换（B17 §一）。
 * 依赖：{@link GameEvent}、{@link ActivityWindow}、{@link DayKey}。<b>不依赖配置表、不依赖奖励发放</b>：
 * 配置在 web 层被装配成 {@link Def}，奖励领取由 web 层调 B04 的 RewardService。
 *
 * <p><b>与任务账本（{@code QuestProgress}）的关系：形状同源、语义不同</b>。同源的那部分是刻意的 ——
 * 事件驱动、状态型/累加型的分派、上限截断到目标值、领取只推进状态不发奖。不同的是多了一个<b>窗口</b>维度：
 * 任务的重置周期是"每日/每周"（由 dayKey/weekKey 表达），活动的窗口是"锚点 + 时长 + 自动轮换"，
 * 而窗口的锚点分两块（全服锚 / 玩家锚，见 {@link ActivityType#playerAnchored()}）。
 *
 * <p><b>轮换是惰性的，读的时候顺手做</b>（B00 陷阱 2：禁止 @Scheduled 扫表）：
 * {@link #rollover} 由调用方在读取与推进之前调用一次，时间没跨过窗口就什么都不做。
 * 「一轮结束自动开下一轮」不需要任何后台任务 —— 下一轮是算出来的，不是被创建的。
 *
 * <p><b>过期不删记录</b>（B17 §一）：上一轮的进度留在存储里，审计与"断点续算"都要它。
 * 本类只翻转窗口归属，不做任何清理 —— 清理是运维的事，不是每次读取的副作用。
 */
public final class ActivityProgress {

    /**
     * 一条活动的定义（由 web 层从 activity 表装配）。
     *
     * @param durationDays 时长（天）。≤ 0 表示常驻（窗口无终点）
     */
    public record Def(String activityId, ActivityType type, ActivityCondition condition,
                      long goalValue, long durationDays) {
        public Def {
            if (activityId == null || activityId.isBlank()) {
                throw new IllegalArgumentException("activityId 不得为空");
            }
            if (type == null || condition == null) {
                throw new IllegalArgumentException("活动 " + activityId + " 的 type 与 condition 都不得为 null");
            }
            if (goalValue < 1L) {
                throw new IllegalArgumentException("活动 " + activityId + " 的 goalValue 必须为正，实际=" + goalValue
                        + "：为 0 的活动一创建就是可领状态，玩家会看到一个不用做就能领的奖励");
            }
            // 本类把 event.amount() 当增量用（连续型除外），所以只接受累加型目标。
            // 接进状态型（事件带的是当前值快照）会让进度变成"每次事件加一遍当前库存"——
            // 数字离谱但没有任何报错，正是最难查的那种。在这里炸，比在线上炸好
            if (!condition.goalType().accumulates()) {
                throw new IllegalArgumentException("活动 " + activityId + " 订阅了状态型目标 "
                        + condition.goalType() + "：活动的推进语义是增量，状态型请改用累加型事件"
                        + "（见 GoalType 的类注释）");
            }
        }
    }

    /**
     * 一条活动的当前进度。
     *
     * @param windowStart 这份进度属于哪一轮（窗口起点，毫秒）。窗口轮换时归零并改写它
     * @param value       当前窗口内的进度值。连续型是"连续了几天"，累加型是"累计了多少"
     * @param claimed     本窗口是否已领过
     * @param lastLoginAt 连续型专用：上一次计入的登录时刻（毫秒）。0 = 本窗口还没有登录过。
     *                    <b>存毫秒而不是日期串</b>：「同一天」与「隔一天」两个判定都能从它算出来，
     *                    存日期串则每次都要反解回时间。
     */
    public record Entry(String activityId, long windowStart, long value, boolean claimed, long lastLoginAt) {

        public Entry {
            if (activityId == null || activityId.isBlank()) {
                throw new IllegalArgumentException("activityId 不得为空");
            }
            if (windowStart <= 0L) {
                throw new IllegalArgumentException("windowStart 必须为正的服务端时间戳，实际=" + windowStart);
            }
            if (value < 0L) {
                throw new IllegalArgumentException("进度值不得为负，实际=" + value);
            }
            if (lastLoginAt < 0L) {
                throw new IllegalArgumentException("lastLoginAt 不得为负，实际=" + lastLoginAt);
            }
        }

        public boolean complete(long goalValue) {
            return value >= goalValue;
        }
    }

    /** 领取被拒的原因。**四种取值对应玩家能看懂的四句话**（detail 由 web 层拼）。 */
    public enum ClaimBlock {
        /** 可以领。 */
        NONE,
        /** 还没达标。 */
        NOT_REACHED,
        /** 窗口已过（这一轮结束了，等下一轮）。 */
        EXPIRED,
        /** 本窗口已经领过。 */
        ALREADY_CLAIMED;

        public boolean blocked() {
            return this != NONE;
        }
    }

    private final String playerId;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final long serverOpenMs;
    private long eventCount;

    private ActivityProgress(String playerId, long serverOpenMs) {
        this.playerId = playerId;
        this.serverOpenMs = serverOpenMs;
    }

    /**
     * 开一本新账本。
     *
     * @param serverOpenMs 开服时刻（六类全服活动的窗口锚）
     */
    public static ActivityProgress open(String playerId, List<Def> defs, long serverOpenMs) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (defs == null || defs.isEmpty()) {
            throw new IllegalArgumentException("活动定义为空：账本里一条活动都没有，"
                    + "玩家的活动页会永远空白，而日志里一切正常");
        }
        ActivityProgress progress = new ActivityProgress(playerId, serverOpenMs);
        for (Def def : defs) {
            if (progress.defs.containsKey(def.activityId())) {
                throw new IllegalArgumentException("活动 id 重复: " + def.activityId());
            }
            progress.defs.put(def.activityId(), def);
            progress.entries.put(def.activityId(), new Entry(def.activityId(), serverOpenMs, 0L, false, 0L));
        }
        return progress;
    }

    /**
     * 从存档恢复。
     *
     * <p><b>存档里没有的行要补出来</b>：表里新加一行活动时，老玩家存档里没有它，
     * 只恢复不补的话新活动对老号永远不可见 —— 而"改表即改行为"是本项目的硬要求（验收 1）。
     */
    public static ActivityProgress restore(String playerId, List<Def> defs, List<Entry> restored,
                                           long serverOpenMs) {
        ActivityProgress progress = open(playerId, defs, serverOpenMs);
        for (Entry entry : restored) {
            Def def = progress.defs.get(entry.activityId());
            if (def == null) {
                // 表里已经删掉的行：不恢复也不报错。它是历史，不是当前玩法
                continue;
            }
            progress.entries.put(entry.activityId(), entry);
        }
        return progress;
    }

    /**
     * 窗口轮换：把每条活动推进到 {@code nowMs} 所在的窗口。
     *
     * <p><b>事件到达前调它</b>：玩家正在新一轮里做事，旧的窗口一律推进。
     * 读取路径请用 {@link #syncOnRead} —— 那条路会留住"上一轮有进展但没领"的行，
     * 让它在列表里以 EXPIRED 的样子可见一次（见 {@link #syncOnRead} 的注释）。
     *
     * <p>轮换时<b>归零的是进度与本窗口的领取状态</b>，以及连续型的"上次登录"——
     * 窗口换了，旧窗口里的连续天数不该延续到新一轮（否则玩家跨窗口凑够 7 天，
     * 而"连续七天"这句承诺就变成"任意七天"）。
     *
     * @param playerFirstLoginMs 玩家的首次登录时刻（LOGIN_STREAK 两行的锚点）。
     *                           调用方拿不到时传 0：此时该行的窗口锚退化为开服时刻
     * @return 被轮换的活动条数
     */
    public int rollover(long nowMs, long playerFirstLoginMs) {
        return rebase(nowMs, playerFirstLoginMs, false);
    }

    /**
     * 读取前的惰性同步：只推进"没有待展示记录"的行。
     *
     * <p><b>这里解开的是 B17 文档内部的一处张力</b>：§一.1 要求"窗口过期 → 列表里标 EXPIRED、
     * 不可领、不删记录"，§五① 又要求"一轮结束自动开下一轮"。两条直译会互相抵消 ——
     * 自动轮换意味着不存在"过期的行"，EXPIRED 就成了永远发不出来的死状态，
     * 而禁止项明确不许声明没人发的状态。本实现同时保住两条：
     * <ul>
     *   <li>上一轮<b>已领</b>或<b>压根没碰</b>的行：读取时直接进入新一轮（玩家看不到任何残留）；</li>
     *   <li>上一轮<b>有进展但没领</b>的行：停在旧窗口上被读成 EXPIRED —— 玩家看得见"上一轮差了多少 / 忘了领"，
     *       但领不了（记录不删，审计要它）；这一轮一旦有新进展，事件路径把它推进到新窗口。</li>
     * </ul>
     * 两者都满足"不删记录"，也都满足"自动开下一轮"（下一轮是算出来的，不是创建出来的）。
     *
     * @return 被推进的活动条数
     */
    public int syncOnRead(long nowMs, long playerFirstLoginMs) {
        return rebase(nowMs, playerFirstLoginMs, true);
    }

    private int rebase(long nowMs, long playerFirstLoginMs, boolean keepPending) {
        int rolled = 0;
        for (Def def : defs.values()) {
            Entry entry = entries.get(def.activityId());
            if (!stale(def, entry, nowMs, playerFirstLoginMs)) {
                continue;
            }
            if (keepPending && !entry.claimed() && entry.value() > 0L) {
                // 上一轮有进展但没领：留在原地，读成 EXPIRED（见 syncOnRead 的注释）
                continue;
            }
            ActivityWindow.Span span = windowOf(def, nowMs, playerFirstLoginMs);
            entries.put(def.activityId(), new Entry(def.activityId(), span.startAt(), 0L, false, 0L));
            rolled++;
        }
        return rolled;
    }

    /**
     * 这一行对玩家显示的三态。**与契约的 `ActivityState` 一一对应**，由 web 层映射 ——
     * 三个取值各自来自一条服务端判定，没有"声明了但发不出来"的那个
     * （禁止项：不要先声明预览期/结算期之类没人发的状态）。
     */
    public enum Phase {
        /** 窗口在跑、还没达标（含已领过）—— 已领由 `claimed` 表达。 */
        RUNNING,
        /** 达标且本窗口没领过。 */
        CLAIMABLE,
        /** 上一轮的窗口已经结束（有进展但没领）—— 看得见、不可领。 */
        EXPIRED
    }

    /**
     * 消费一个事件（作为 {@code GameEventBus.Listener} 注册进去）。
     *
     * <p>只认自己的玩家（总线是按类型派发的，不带玩家过滤 —— 漏了这一步全服的进度会串号），
     * 只认自己订阅的 {@link ActivityCondition#goalType()}。
     *
     * <p><b>连续型不走"加法"</b>：见 {@link ActivityCondition#consecutive()} 与类注释。
     */
    public void onEvent(GameEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不得为 null");
        }
        if (!event.playerId().equals(playerId)) {
            return;
        }
        eventCount++;
        for (Def def : defs.values()) {
            if (def.condition().goalType() != event.goalType()) {
                continue;
            }
            Entry entry = entries.get(def.activityId());
            long next = def.condition().consecutive()
                    ? streakAfter(entry, event.at())
                    : Math.min(entry.value() + event.amount(), def.goalValue());
            long lastLoginAt = def.condition().consecutive() ? event.at() : entry.lastLoginAt();
            entries.put(def.activityId(), new Entry(def.activityId(), entry.windowStart(), next,
                    entry.claimed(), lastLoginAt));
        }
    }

    /**
     * 连续登录的下一个天数。<b>三个分支各自对应验收 5/6 的一条</b>：
     * 同一自然日重复登录不算一天（幂等）；紧接前一天则 +1；断了则从 1 重新数。
     */
    private long streakAfter(Entry entry, long loginAt) {
        if (entry.lastLoginAt() == 0L) {
            return 1L;
        }
        String lastDay = DayKey.of(entry.lastLoginAt());
        String today = DayKey.of(loginAt);
        if (lastDay.equals(today)) {
            // 同一自然日再登录一次：进度不动。这就是"同一天只算一天"的幂等点
            return entry.value();
        }
        boolean consecutive = DayKey.daysBetween(entry.lastLoginAt(), loginAt) == 1L;
        return consecutive ? entry.value() + 1L : 1L;
    }

    /**
     * 领取资格判定。**本类不发奖**，只把状态从"可领"推进到"已领"。
     *
     * <p>先问 {@link #blockOf} 再调本方法：判定只有一处（blockOf），本方法只用它做守卫。
     *
     * @throws IllegalArgumentException 活动 id 不在账本里
     * @throws IllegalStateException    当前不可领（未达标 / 已过期 / 已领过）
     */
    public void claim(String activityId, long nowMs, long playerFirstLoginMs) {
        ClaimBlock block = blockOf(activityId, nowMs, playerFirstLoginMs);
        if (block.blocked()) {
            throw new IllegalStateException("活动 " + activityId + " 当前不可领取：" + block);
        }
        Entry entry = entries.get(activityId);
        entries.put(activityId, new Entry(activityId, entry.windowStart(), entry.value(), true,
                entry.lastLoginAt()));
    }

    /**
     * 此刻能不能领、不能领是为什么。<b>红点、列表视图与领取守卫都调它</b>——
     * 三个地方各写一遍判定，迟早会出现"红点亮着但列表里点不了"。
     */
    public ClaimBlock blockOf(String activityId, long nowMs, long playerFirstLoginMs) {
        Def def = requireDef(activityId);
        Entry entry = entries.get(activityId);
        if (stale(def, entry, nowMs, playerFirstLoginMs)) {
            // 过期的判据是"这份进度还属不属于当前窗口"，不是"当前窗口有没有过去"：
            // 自动轮换下后者永远为假，EXPIRED 会变成发不出来的死状态
            return ClaimBlock.EXPIRED;
        }
        if (entry.claimed()) {
            return ClaimBlock.ALREADY_CLAIMED;
        }
        if (!entry.complete(def.goalValue())) {
            return ClaimBlock.NOT_REACHED;
        }
        return ClaimBlock.NONE;
    }

    /** 这一行对玩家显示的三态。**唯一的算法**（契约的 ActivityState 由 web 层从这里映射）。 */
    public Phase phaseOf(String activityId, long nowMs, long playerFirstLoginMs) {
        ClaimBlock block = blockOf(activityId, nowMs, playerFirstLoginMs);
        if (block == ClaimBlock.EXPIRED) {
            return Phase.EXPIRED;
        }
        // 已领过的行仍然是 RUNNING：那一格由 claimed 表达（契约里 ActivityView.claimed 的同一句话）
        return block == ClaimBlock.NONE ? Phase.CLAIMABLE : Phase.RUNNING;
    }

    /** 这份进度是否还属于当前窗口。 */
    private boolean stale(Def def, Entry entry, long nowMs, long playerFirstLoginMs) {
        return entry.windowStart() != windowOf(def, nowMs, playerFirstLoginMs).startAt();
    }

    /** 可领的活动数 —— 红点叶子 {@code activity/claimable} 的输入（与 {@link #blockOf} 同一条判定）。 */
    public int claimableCount(long nowMs, long playerFirstLoginMs) {
        int count = 0;
        for (Def def : defs.values()) {
            if (!blockOf(def.activityId(), nowMs, playerFirstLoginMs).blocked()) {
                count++;
            }
        }
        return count;
    }

    /** 某一行此刻的窗口。 */
    public ActivityWindow.Span windowOf(String activityId, long nowMs, long playerFirstLoginMs) {
        Def def = requireDef(activityId);
        return windowOf(def, nowMs, playerFirstLoginMs);
    }

    /** 某一行这一轮的锚点（供诊断与用例断言"用的是哪块锚"）。 */
    public long anchorOf(String activityId, long playerFirstLoginMs) {
        return anchorOf(requireDef(activityId), playerFirstLoginMs);
    }

    public Entry entry(String activityId) {
        Entry entry = entries.get(activityId);
        if (entry == null) {
            throw new IllegalArgumentException("活动不存在: " + activityId + "，现有=" + entries.keySet());
        }
        return entry;
    }

    public List<Entry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    /** 表里定义的活动 id（顺序即表序 —— 服务端不另行排序）。 */
    public List<Def> defs() {
        return List.copyOf(new ArrayList<>(defs.values()));
    }

    /** 消费过的事件数。<b>长期为 0 说明没有任何业务在发事件</b>，那是接线漏了。 */
    public long eventCount() {
        return eventCount;
    }

    public String playerId() {
        return playerId;
    }

    public long serverOpenMs() {
        return serverOpenMs;
    }

    private ActivityWindow.Span windowOf(Def def, long nowMs, long playerFirstLoginMs) {
        return ActivityWindow.current(anchorOf(def, playerFirstLoginMs), def.durationDays(), nowMs);
    }

    /**
     * 这一行用哪块锚：玩家锚的活动拿 {@code playerFirstLoginMs}，其余拿开服时刻。
     *
     * <p>玩家锚缺失（还没登录过 / 建档时刻读不到）时退化为开服时刻并在返回值里体现 ——
     * 退化是"这个人还没有个人锚"，不是错误；但让它静默发生就会变成"活动窗口莫名其妙对不上"。
     */
    private long anchorOf(Def def, long playerFirstLoginMs) {
        if (!def.type().playerAnchored()) {
            return serverOpenMs;
        }
        return playerFirstLoginMs > 0L ? playerFirstLoginMs : serverOpenMs;
    }

    private Def requireDef(String activityId) {
        Def def = defs.get(activityId);
        if (def == null) {
            throw new IllegalArgumentException("活动不存在: " + activityId + "，现有=" + defs.keySet());
        }
        return def;
    }
}
