package com.ironoath.core.quest;

/**
 * 职责：任务目标类型 —— 「进度怎么长」的分类（B12 §1 的统一 GoalType）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>13 个取值分成两类，而这个分类是任务系统最容易搞错的地方</b>：
 * <ul>
 *   <li><b>累加型</b>（{@link #accumulates()} 为 true）：进度只增不减，事件带增量。
 *       「训练 20 个兵」是累计训练量，训完之后兵死了也不回退</li>
 *   <li><b>状态型</b>（false）：进度是<b>当前状态</b>，事件带快照值，可以升也可以降。
 *       「持有 10000 粮」是此刻的库存 —— 玩家花掉粮食，进度就该退回去；
 *       「加入小队」是一个布尔状态，退了小队就不算完成</li>
 * </ul>
 *
 * <p><b>把状态型当累加型实现是最常见也最隐蔽的错误</b>：
 * 「累计获得 10000 粮」永远比「当前持有 10000 粮」容易达成，
 * 于是主线任务会在玩家还没真正囤下粮时就完成，而下一个任务（训练兵）正需要那批粮 ——
 * 玩家会卡在「任务说完成了，但我什么都做不了」的地方，而日志里一切正常。
 * 反过来把累加型当状态型实现，则会出现「训练了 20 个兵但因为死过兵，进度退回 15」。
 *
 * <p>取值与 {@code contract/config/quest.json} 的 goalType 枚举逐一对应，
 * 由 {@code ConfigTablesAcceptanceTest} 一类的枚举一致性检查守着。
 */
public enum GoalType {

    /** 升级建筑 N 次。goalTarget 是 building 表的行 id（如 main_city），为空表示任意建筑。 */
    UPGRADE_BUILDING(true),
    /**
     * 当前持有某种资源达到 N。<b>状态型</b>：goalTarget 是资源 id（如 GRAIN）。
     * 花掉就退回去 —— 见类注释，这是本枚举里最容易被实现错的一个。
     */
    REACH_RESOURCE(false),
    /** 累计训练 N 个兵。goalTarget 是 unit 表的行 id（含阶级）。 */
    TRAIN_UNIT(true),
    /** 累计击杀 N 只野怪。goalTarget 是 mapmonster 的行 id，为空表示任意等级。 */
    KILL_MONSTER(true),
    /** 累计抽卡 N 次。goalTarget 是 gacha 池 id，为空表示任意池。 */
    GACHA_PULL(true),
    /** 加入小队。<b>状态型</b>：值为 1 表示已在小队中，0 表示不在（退队后任务不再算完成）。 */
    JOIN_SQUAD(false),
    /** 加入联盟。<b>状态型</b>，同 JOIN_SQUAD。 */
    JOIN_ALLIANCE(false),
    /** 研究科技到 N 级。<b>状态型</b>：科技等级会因赛季重置而回落，累加型会记住一个再也回不去的数。 */
    RESEARCH_TECH(false),
    /** 累计通关 N 个章节。 */
    CLEAR_CHAPTER(true),
    /** 累计通关 N 个关卡。goalTarget 是 stage 的行 id 或章节前缀。 */
    CLEAR_STAGE(true),
    /** 累计采集 N 单位资源。goalTarget 是资源 id。 */
    GATHER_RESOURCE(true),
    /** 累计帮助队友 N 次（B10 的互助）。 */
    HELP_SQUAD(true),
    /** 累计参加 N 次集结（B10）。 */
    JOIN_RALLY(true);

    private final boolean accumulates;

    GoalType(boolean accumulates) {
        this.accumulates = accumulates;
    }

    /**
     * true = 累加型（事件带增量，进度只增不减）；false = 状态型（事件带快照，进度可升可降）。
     *
     * <p>消费方<b>必须</b>按这个标志分派，不能一律累加。
     */
    public boolean accumulates() {
        return accumulates;
    }
}
