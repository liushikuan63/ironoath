package com.ironoath.core.activity;

/**
 * 职责：活动类型（对应 {@code activity.json} 的 activityType 列）—— 只决定「窗口锚点用哪一块」。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>取值与表里的 activityType 逐一对应</b>（7 个取值 / 8 行表：七日登临与月常守望同属 LOGIN_STREAK），
 * 由 web 层的装配器 {@code ActivityRulesAssembler} 用 switch 表达式一一映射 ——
 * 加一个表上的取值而忘了映射会<b>编译不过</b>，而不是在运行期变成"这一行永远用错锚点"。
 *
 * <p><b>为什么锚点分类放在这里而不是从 conditionType 推</b>：
 * 裁决是以 activityType 表述的（"LOGIN_STREAK 类锚玩家首登"），conditionType 说的是"进度怎么长"。
 * 两件事共用一个枚举字段的话，将来多一行 LOGIN_STREAK 语义、不同 conditionType 的活动时就没地方放了。
 */
public enum ActivityType {

    /** 七日登临 / 月常守望：连续登录类，锚玩家首次登录时刻（B17 §五①）。 */
    LOGIN_STREAK(true),
    /** 剿匪令：击杀野怪。 */
    KILL_MONSTER(false),
    /** 集结周：参加集结。 */
    JOIN_RALLY(false),
    /** 捐献周：联盟捐献。 */
    DONATE(false),
    /** 争锋令：PVP 获胜。 */
    PVP_WIN(false),
    /** 建造冲刺：升级建筑。 */
    UPGRADE_BUILDING(false),
    /** 小队同心：小队互助。 */
    HELP_SQUAD(false);

    private final boolean playerAnchored;

    ActivityType(boolean playerAnchored) {
        this.playerAnchored = playerAnchored;
    }

    /**
     * true = 窗口锚点是<b>玩家首次登录时刻</b>；false = 锚点由调用方给（全服活动给开服时刻）。
     *
     * <p>见 B17 §五① 的裁决：全服锚会让晚进来的新号永远做不满连续七天。
     */
    public boolean playerAnchored() {
        return playerAnchored;
    }
}
