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
}
