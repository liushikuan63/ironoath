package com.ironoath.web.season;

import java.util.List;

import com.ironoath.core.season.SeasonSettlement;

/**
 * 职责：赛季榜与快照的持久化端口（B14 §3「结算按快照」× §5 可申诉）。
 * 依赖：game-core 的榜条目与快照值对象。
 *
 * <p>与 {@link SeasonLedgerStore}（账本 = 「这一季这个人付过钱」）同一套约定：
 * 端口定义在 {@code web/season}，实现放在 {@code store/memory} 与 {@code store/mongo}。
 *
 * <p><b>为什么这一档必须落库</b>：{@code SeasonSettlement} 的实时榜与快照原先都在进程里，
 * 而结算是一条<b>可重复触发的运营入口</b>（换一个 requestId 就能再跑一次）。
 * 重启之后榜是空的 —— 此时再结算会发生两件都不是"没发钱"的事：
 * <ol>
 *   <li><b>按一张冷榜拍快照</b>：快照一旦拍下就冻结（不可重拍），而账本会把这次发奖记成
 *       "已经付过" ⇒ 名次算错的那一批人<b>永久拿不到</b>本该属于他的奖励；</li>
 *   <li>已经拍过的快照丢进程即丢，重跑会重拍一张，于是"结算依据"有两个版本 ——
 *       而快照存在的全部意义就是"申诉时能证明用的是哪一刻的数据"。</li>
 * </ol>
 * 所以这个端口存的是<b>结算依据</b>，与账本（付款凭据）一起才构成"可重放"：
 * 同样的输入重跑一次得到同样的结果，而不是按当时恰好还在内存里的东西另算一遍。
 *
 * <p><b>排序口径由端口保证</b>（分数降序、同分按 id 升序，与
 * {@link SeasonSettlement} 内部那条排序逐字一致）：两个实现各排一次而口径不同，
 * 表现是"内存版第 3 名、mongo 版第 4 名"——而名次直接决定发多少赛季币。
 */
public interface SeasonBoardStore {

    /**
     * 上报一条（同季、同榜、同 id 覆盖；分数是累计量，不累加）。
     *
     * <p><b>这是写路径上的热调用</b>（{@code PowerRefreshService} 每次重算战力都会调），
     * 所以实现的形状必须是"改一行"，不能是"重写整张榜"。
     */
    void report(String seasonId, SeasonSettlement.Board board, SeasonSettlement.Entry entry);

    /** 某榜的全部条目，按分数降序、同分按 id 升序。 */
    /**
     * 累加型上报（B23 的击杀榜）：把 {@code delta} 加到这名玩家在这张榜上的现有值上；没有这一行就建一行。
     *
     * <p><b>与 {@link #report} 的差别是语义而不是实现</b>：`report` 说的是"这个值现在是多少"
     * （战力会涨会跌，取最新一次），`accumulate` 说的是"又发生了多少次"（击杀只增不减）。
     * 拿 `report` 报击杀就得由调用方自己维护累计总数 —— 而那本账会散在每个调用点上，
     * 也经不起"两个人同时在同一场结算里加击杀"。
     *
     * <p>{@code entry.score()} 在这里是**增量**而不是新值。
     */
    void accumulate(String seasonId, SeasonSettlement.Board board, SeasonSettlement.Entry entry,
                    long delta);

    List<SeasonSettlement.Entry> board(String seasonId, SeasonSettlement.Board board);

    /** 名次（1 起）；不在榜上返回 0。 */
    int rankOf(String seasonId, SeasonSettlement.Board board, String playerId);

    /** 取快照；没拍过返回 null（读侧宽容，与 {@code SeasonLedgerStore.find} 同一条）。 */
    SeasonSettlement.Snapshot snapshot(String seasonId, SeasonSettlement.Board board);

    /**
     * 存下快照。<b>已有则返回 false</b>：快照不可更改是它作为申诉依据的前提，
     * 所以多实例并发拍快照时只有先落库的那一份算数，后到的实例必须改用库里那一份
     * （见 {@code SeasonSettlementService#settle} 的处理）。
     */
    boolean saveSnapshotIfAbsent(String seasonId, SeasonSettlement.Snapshot snapshot);

    /**
     * 存下**每日**快照（B23 裁决②）。键是 {@code seasonId:board:dayKey}，与结算快照
     * <b>不同集合</b>：结算是"付钱依据"，每日快照是"申诉时间线"，同集合会让两套清理策略互相牵制。
     * <b>已有则返回 false</b>（与 {@link #saveSnapshotIfAbsent} 同一条幂等语义）——
     * "同一天重复读只拍一份"就靠它，而不是靠调用方先查后写（那在并发下会拍出两份）。
     *
     * @param dayKey 日期键 {@code yyyyMMdd}（UTC+8，来自 {@code DayKey}）
     */
    boolean saveDailyIfAbsent(String seasonId, SeasonSettlement.Board board, String dayKey,
                              SeasonSettlement.Snapshot snapshot);

    /** 某一天的每日快照；那天没拍过返回 null（读侧宽容）。 */
    SeasonSettlement.Snapshot daily(String seasonId, SeasonSettlement.Board board, String dayKey);

    /**
     * 这一天以前拍过的所有日期键，**升序**（最早的一天在前）。
     *
     * <p>存在的理由只有一个但很硬：{@code RANK_SNAPSHOT_EMPTY} 的 detail 要给出
     * "可选的最早一天"，否则玩家翻到一个没有快照的日期时只能看到一句"查不到"，
     * 既不知道是自己记错了还是那天本来就没有。
     */
    List<String> dailyDays(String seasonId, SeasonSettlement.Board board);

    /**
     * 删掉一个赛季的**榜、结算快照与每日快照**。返回删掉的条目 + 快照文档总数（含每日）。
     *
     * <p>与 {@link SeasonLedgerStore#purgeSeason} 配对使用：归档保留策略说「留 3 个赛季」，
     * 而一个赛季的归档由账本 + 榜 + 结算快照 + 每日快照四处组成，漏掉任何一处的表现都不是报错，
     * 而是「旧季一半还在库里」—— 而每日快照是这四处里最新的一处，
     * 漏掉它的症状是"申诉期早过了，时间线还占着存储"。
     */
    int purgeSeason(String seasonId);

    /** 测试辅助：清空（进程内实现用；Mongo 实现按本季删除自己的两个集合内容）。 */
    void clear();

    /** 写侧共用的键校验：两个实现都要拒绝空键，否则会在存储里造出无法命中的孤儿行。 */
    static void requireKey(String seasonId, SeasonSettlement.Board board) {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId 不得为空：榜与快照都按它分季");
        }
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：一季有多张榜，缺了它读到的会是另一张");
        }
    }

    /**
     * 日期键的写侧校验（每日快照专用）。空日期键会在存储里造出一行"谁也命不中"的孤儿数据 ——
     * 与 {@link #requireKey} 同一条理由，也同一条形状：判定只有一处实现，两个实现都调它。
     */
    static void requireDayKey(String dayKey) {
        if (dayKey == null || dayKey.isBlank()) {
            throw new IllegalArgumentException("dayKey 不得为空：每日快照的键里有它");
        }
    }
}
