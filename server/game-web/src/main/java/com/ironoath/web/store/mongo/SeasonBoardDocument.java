package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import com.ironoath.core.season.SeasonSettlement;

/**
 * 职责：赛季榜上一条的 MongoDB 文档（一季 × 一榜 × 一人）。
 * 依赖：{@link SeasonSettlement.Entry}。
 *
 * <p>{@code _id} 用 {@code seasonId:board:playerId} —— 与 {@code season_ledger} 的
 * {@code seasonId:playerId} 是同一种思路（键即唯一约束），多了 {@code board} 一段是因为
 * 一季有多张榜（B14 §二），少了它两张榜的同一个人在存储里会互相覆盖。
 * {@code seasonId} 由 {@code SeasonTimeline} 生成、{@code board} 是枚举名，都不接受外部输入，
 * 所以这个拼接不会被玩家构造出歧义。
 *
 * <p><b>一条一行，而不是"一季一份大文档"</b>：上报是写路径上的热调用
 * （每次战力重算都会调），整榜重写会随玩家数线性放大写放大；一行一条之后一次上报只改一行。
 * 代价是读整榜要一次查询 —— 那是结算期每季几次的操作，值得。
 */
public record SeasonBoardDocument(
        @Id String id,
        String seasonId,
        String board,
        String playerId,
        String name,
        long score) {

    /** 集合名。 */
    public static final String COLLECTION = "season_board";

    public static String keyOf(String seasonId, SeasonSettlement.Board board, String playerId) {
        return seasonId + ":" + board + ":" + playerId;
    }

    static SeasonBoardDocument of(String seasonId, SeasonSettlement.Board board,
                                  SeasonSettlement.Entry entry) {
        return new SeasonBoardDocument(keyOf(seasonId, board, entry.id()), seasonId, board.name(),
                entry.id(), entry.name(), entry.score());
    }

    SeasonSettlement.Entry toEntry() {
        return new SeasonSettlement.Entry(playerId, name, score);
    }
}
