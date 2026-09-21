package com.ironoath.core.city;

/**
 * 职责：城内一个建筑实例（可变聚合的一部分）。
 * 依赖：无（纯 Java）。
 *
 * <p>区分两个 id：{@code instanceId} 是这一块地上这个建筑的实例标识（玩家城内唯一），
 * {@code configId} 指向 contract/config/building.json 的行（全服共用）。
 * 分开是因为同一种建筑理论上可以在不同地块各有一个实例，
 * 而配置只有一份 —— 混用会让「哪个建筑」和「哪种建筑」在代码里分不清。
 *
 * <p>升级中的状态用 {@code upgradeFinishAt} 表达而不是用定时器：
 * B00 陷阱 1 与 B03 禁止项都要求服务端不得为每个建造/升级挂一个定时器。
 * 「还剩多久」永远是 {@code finishAt - now} 现算出来的，服务端不持有任何倒计时对象。
 */
public final class BuildingInstance {

    private final String instanceId;
    private final String configId;
    private int level;
    private int gridX;
    private int gridY;
    private BuildingStatus status;
    /** 升级完成时刻（服务端毫秒时间戳）。非升级中为 null。 */
    private Long upgradeFinishAt;
    /** 升级开始时刻，用于计算已消耗时间与取消返还。 */
    private long upgradeStartedAt;
    /** 本次升级已投入的总时长（秒），加速会减少剩余时长而不改变它。 */
    private long upgradeTotalSeconds;
    /**
     * 本次升级的<b>原始</b>总时长（秒），加速不会改变它。
     *
     * <p>必须与 upgradeTotalSeconds 分开存：联盟帮助是「每次减 1% 时间、上限 20%」，
     * 这个百分比必须以原始时长为基数。若按已被前几次帮助缩短的时长算，
     * 减少量会逐次复利缩水（10 次帮助实际只减了 9.55% 而不是 10%），
     * 且 20% 上限永远达不到 —— 玩家会感觉「帮了但没用」。
     */
    private long upgradeOriginalSeconds;
    /** 已获得的帮助次数（联盟/小队互助）。 */
    private int helpCount;
    /** 上次换位时刻，配合 moveCooldownSeconds 防止频繁调整。 */
    private long lastMovedAt;
    /**
     * 最近一次升级完成的时刻；0 表示从未完成过。
     *
     * <p>用途是给惰性结算切段：产出速率在升级完成的那一刻发生阶跃（等级 +1），
     * 所以 [上次结算 → 完成时刻] 必须按旧等级算、[完成时刻 → 现在] 才能按新等级算。
     * 若整段都按新等级追溯，玩家就能靠「离线期间完成一次升级」白拿整个离线窗口的高等级产量。
     */
    private long lastFinishedAt;
    /**
     * 暂停时刻；0 表示当前没在暂停。
     *
     * <p>它存在的唯一理由是"暂停要真的停下来"：恢复时按 `now - pausedAt` 顺延 finishAt / startedAt，
     * 把暂停的那段时间还给这栋楼（B03 §2）。不记这个时刻的话，暂停只是不显示倒计时，时钟照走。
     */
    private long pausedAt;

    public BuildingInstance(String instanceId, String configId, int level, int gridX, int gridY) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不得为空");
        }
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException("configId 不得为空，instanceId=" + instanceId);
        }
        if (level < 0) {
            throw new IllegalArgumentException("level 不得为负，instanceId=" + instanceId);
        }
        this.instanceId = instanceId;
        this.configId = configId;
        this.level = level;
        this.gridX = gridX;
        this.gridY = gridY;
        this.status = BuildingStatus.IDLE;
    }

    public String instanceId() {
        return instanceId;
    }

    public String configId() {
        return configId;
    }

    public int level() {
        return level;
    }

    public int gridX() {
        return gridX;
    }

    public int gridY() {
        return gridY;
    }

    public BuildingStatus status() {
        return status;
    }

    public Long upgradeFinishAt() {
        return upgradeFinishAt;
    }

    public long upgradeStartedAt() {
        return upgradeStartedAt;
    }

    public long upgradeTotalSeconds() {
        return upgradeTotalSeconds;
    }

    /** 原始总时长，帮助加速的百分比基数。 */
    public long upgradeOriginalSeconds() {
        return upgradeOriginalSeconds;
    }

    public int helpCount() {
        return helpCount;
    }

    public long lastMovedAt() {
        return lastMovedAt;
    }

    /** 最近一次升级完成时刻；0 表示从未完成过。用于给惰性结算切段。 */
    public long lastFinishedAt() {
        return lastFinishedAt;
    }

    public boolean isUpgrading() {
        return status == BuildingStatus.UPGRADING || status == BuildingStatus.PAUSED;
    }

    /** 剩余升级秒数。未在升级中返回 0；已到点返回 0（<b>绝不返回负数</b>，B03 禁止项）。 */
    public long remainingSeconds(long now) {
        if (upgradeFinishAt == null || status == BuildingStatus.PAUSED) {
            return 0L;
        }
        long remainMs = upgradeFinishAt - now;
        return remainMs <= 0L ? 0L : (remainMs + 999L) / 1000L;
    }

    /**
     * 升级进度（定点 0~10000）。未在升级中返回 1.0；不会越界（B03 验收 3）。
     *
     * <p>判定「是否已完成」用 {@code now >= upgradeFinishAt}，而不是比较已用时长与总时长：
     * 加速会把 upgradeTotalSeconds 一并压缩（极端情况下压到 1 秒），
     * 用时长比较会在已经加速到 0 remaining 时算出 0% 进度，进度条直接倒退。
     */
    public long progressFixed(long now) {
        if (upgradeFinishAt == null) {
            return com.ironoath.common.num.FixedPoint.ONE;
        }
        if (now >= upgradeFinishAt) {
            return com.ironoath.common.num.FixedPoint.ONE;
        }
        long totalMs = upgradeTotalSeconds * 1000L;
        if (totalMs <= 0L) {
            return com.ironoath.common.num.FixedPoint.ONE;
        }
        // 暂停期间用**暂停时刻**算已用时长：否则进度条会在暂停时自己往前爬（时钟还在走）。
        long clock = status == BuildingStatus.PAUSED && pausedAt > 0L ? pausedAt : now;
        long elapsedMs = Math.max(0L, clock - upgradeStartedAt);
        return com.ironoath.common.num.FixedPoint.div(
                com.ironoath.common.num.FixedPoint.of(elapsedMs),
                com.ironoath.common.num.FixedPoint.of(totalMs));
    }

    /** 开始升级。调用方必须先通过 {@link CityState} 的前置校验。 */
    void startUpgrade(long now, long durationSeconds) {
        if (durationSeconds <= 0L) {
            throw new IllegalArgumentException("升级时长必须为正，instanceId=" + instanceId);
        }
        if (isUpgrading()) {
            throw new IllegalStateException("建筑已在升级中，instanceId=" + instanceId);
        }
        this.status = BuildingStatus.UPGRADING;
        this.upgradeStartedAt = now;
        this.upgradeTotalSeconds = durationSeconds;
        this.upgradeOriginalSeconds = durationSeconds;
        this.upgradeFinishAt = now + durationSeconds * 1000L;
        this.helpCount = 0;
    }

    /**
     * 加速：把完成时刻提前 reduceSeconds 秒。
     *
     * <p>提前量被剩余时间截断 —— 剩余 10 秒时加速 60 秒只会提前 10 秒，
     * 多出来的 50 秒不退不存（B03 禁止项：不要把剩余时间算成负数）。
     *
     * @return 实际提前的秒数
     */
    long speedUp(long reduceSeconds, long now) {
        if (status != BuildingStatus.UPGRADING) {
            throw new IllegalStateException("只有升级中的建筑能加速，instanceId=" + instanceId
                    + "，当前状态=" + status);
        }
        if (reduceSeconds <= 0L) {
            throw new IllegalArgumentException("加速秒数必须为正，实际=" + reduceSeconds);
        }
        long remaining = remainingSeconds(now);
        long applied = Math.min(reduceSeconds, remaining);
        upgradeFinishAt = upgradeFinishAt - applied * 1000L;
        upgradeTotalSeconds = Math.max(1L, upgradeTotalSeconds - applied);
        return applied;
    }

    /** 记一次联盟/小队帮助。 */
    void addHelp() {
        helpCount++;
    }

    /**
     * 升级完成落地：等级 +1、回到空闲、释放队列，并记下完成时刻。
     *
     * <p>记完成时刻是给惰性结算切段用的：从这一刻起该建筑按新等级计入产率。
     */
    void finishUpgrade(long now) {
        if (!isUpgrading()) {
            throw new IllegalStateException("建筑未在升级中，无法完成，instanceId=" + instanceId);
        }
        level++;
        status = BuildingStatus.IDLE;
        upgradeFinishAt = null;
        upgradeStartedAt = 0L;
        upgradeTotalSeconds = 0L;
        upgradeOriginalSeconds = 0L;
        lastFinishedAt = now;
    }

    /** 取消升级：回到空闲，等级不变。资源返还由 {@link CityState} 负责。 */
    void cancelUpgrade() {
        if (!isUpgrading()) {
            throw new IllegalStateException("建筑未在升级中，无法取消，instanceId=" + instanceId);
        }
        status = BuildingStatus.IDLE;
        upgradeFinishAt = null;
        upgradeStartedAt = 0L;
        upgradeTotalSeconds = 0L;
        upgradeOriginalSeconds = 0L;
        helpCount = 0;
    }

    /**
     * 暂停（B03 §2："队列中可暂停 / 取消"）。
     *
     * <p><b>为什么要记 `pausedAt`</b>：单把状态翻成 PAUSED 只是"不显示倒计时"，**时钟还在走** ——
     * 恢复时 `upgradeFinishAt` 早已过期，建筑会立刻完工，玩家暂停了个寂寞。
     * 所以暂停要记下时刻，恢复时把 finishAt / startedAt 一起往后挪那一段（见 {@link #resume(long)}）。
     */
    void pause(long now) {
        if (status != BuildingStatus.UPGRADING) {
            throw new IllegalStateException("只有升级中的建筑能暂停，instanceId=" + instanceId);
        }
        status = BuildingStatus.PAUSED;
        pausedAt = now;
    }

    /** 恢复：把暂停的那段时间**还**给这栋楼（finishAt 与 startedAt 同步顺延，剩余时间不变）。 */
    void resume(long now) {
        if (status != BuildingStatus.PAUSED) {
            throw new IllegalStateException("只有已暂停的建筑能恢复，instanceId=" + instanceId);
        }
        if (pausedAt > 0L && upgradeFinishAt != null) {
            long frozen = Math.max(0L, now - pausedAt);
            upgradeFinishAt = upgradeFinishAt + frozen;
            upgradeStartedAt = upgradeStartedAt + frozen;
        }
        pausedAt = 0L;
        status = BuildingStatus.UPGRADING;
    }

    /** 暂停时刻；0 表示当前没在暂停。 */
    public long pausedAt() {
        return pausedAt;
    }

    void moveTo(int newX, int newY, long now) {
        this.gridX = newX;
        this.gridY = newY;
        this.lastMovedAt = now;
    }

    /** 供仓储反序列化写回。业务代码不要用。 */
    public void restore(int level, int gridX, int gridY, BuildingStatus status, Long upgradeFinishAt,
                        long upgradeStartedAt, long upgradeTotalSeconds, long upgradeOriginalSeconds,
                        int helpCount, long lastMovedAt, long lastFinishedAt) {
        restore(level, gridX, gridY, status, upgradeFinishAt, upgradeStartedAt, upgradeTotalSeconds,
                upgradeOriginalSeconds, helpCount, lastMovedAt, lastFinishedAt, 0L);
    }

    /** 供仓储反序列化写回（带暂停时刻的完整版本）。业务代码不要用。 */
    public void restore(int level, int gridX, int gridY, BuildingStatus status, Long upgradeFinishAt,
                        long upgradeStartedAt, long upgradeTotalSeconds, long upgradeOriginalSeconds,
                        int helpCount, long lastMovedAt, long lastFinishedAt, long pausedAt) {
        this.level = level;
        this.gridX = gridX;
        this.gridY = gridY;
        this.status = status;
        this.upgradeFinishAt = upgradeFinishAt;
        this.upgradeStartedAt = upgradeStartedAt;
        this.upgradeTotalSeconds = upgradeTotalSeconds;
        this.upgradeOriginalSeconds = upgradeOriginalSeconds;
        this.helpCount = helpCount;
        this.lastMovedAt = lastMovedAt;
        this.lastFinishedAt = lastFinishedAt;
        this.pausedAt = pausedAt;
    }
}
