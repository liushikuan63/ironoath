package com.ironoath.core.activity;

import com.ironoath.core.quest.GoalType;

/**
 * 职责：活动条件类型（对应 {@code activity.json} 的 conditionType 列）—— 决定「订阅哪个领域事件、怎么长」。
 * 依赖：{@link GoalType}（事件总线的目标类型）。纯 Java，零框架。
 *
 * <p><b>这张枚举是表列与事件总线之间的唯一映射</b>：表里写 {@code DONATE_TOTAL}、
 * 总线上的事件带着 {@code GoalType.ALLIANCE_DONATE}，两者的对应只在这里写一次。
 * 写第二遍（例如在监听器里按字符串比较）的结局是：改一处忘一处，
 * 而症状是"这个活动永远停在 0/2000"，日志里没有任何一条错误。
 *
 * <p><b>只有 {@link #LOGIN_DAYS} 一个的推进不是"加法"</b>：它是连续签到（streak），
 * 同一天重复登录只算一天、断一天归零重数（B17 §五②，依据是表自己的 why 写着"连续"）。
 * 这条差别用 {@link #consecutive()} 显式表达，而不是靠"名字里有 LOGIN"这种约定 ——
 * 约定在下一个活动类型加进来时就会失效。
 */
public enum ActivityCondition {

    /** 连续登录天数。**跨天判定用 {@code DayKey}（UTC+8）**，窗口锚是玩家首登（见 {@link ActivityType}）。 */
    LOGIN_DAYS(GoalType.LOGIN_DAY, true),
    /** 累计击杀野怪。与任务表共用 {@code GoalType.KILL_MONSTER} —— 同一条击杀事件推进两边。 */
    KILL_MONSTER_TOTAL(GoalType.KILL_MONSTER, false),
    /** 累计参加集结。 */
    JOIN_RALLY_TOTAL(GoalType.JOIN_RALLY, false),
    /** 累计联盟捐献（按捐献档位的资源量计，与联盟贡献值同源）。 */
    DONATE_TOTAL(GoalType.ALLIANCE_DONATE, false),
    /** 累计 PVP 获胜次数。 */
    PVP_WIN_TOTAL(GoalType.PVP_WIN, false),
    /** 累计升级建筑次数。 */
    UPGRADE_COUNT(GoalType.UPGRADE_BUILDING, false),
    /** 累计小队互助次数。 */
    HELP_COUNT(GoalType.HELP_SQUAD, false);

    private final GoalType goalType;
    private final boolean consecutive;

    ActivityCondition(GoalType goalType, boolean consecutive) {
        this.goalType = goalType;
        this.consecutive = consecutive;
    }

    /** 这一类条件订阅的领域事件类型。 */
    public GoalType goalType() {
        return goalType;
    }

    /** true = 连续语义（同一天只算一天、断一天归零）；false = 累加语义（事件带增量，只增不减）。 */
    public boolean consecutive() {
        return consecutive;
    }
}
