package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.core.season.SeasonSettlement;

/**
 * 职责：赛季快照的 MongoDB 文档（一季 × 一榜一份）。
 * 依赖：{@link SeasonSettlement.Snapshot}。
 *
 * <p>{@code _id} 用 {@code seasonId:board}，唯一约束就是"快照不可更改"这条规则的执行点：
 * {@code MongoSeasonBoardStore.saveSnapshotIfAbsent} 靠撞号判断"已经拍过了"，
 * 与账本 {@code recordIfAbsent} 是同一个形状。
 *
 * <p><b>条目内嵌成一个数组而不是一季一份行文档</b>：快照拍下之后就是只读的，
 * 没有"改其中一条"的需求，而整份读出来正是结算与申诉要的形状（一次查询）。
 * 一季一榜一份文档的体量按单服上限量级（几千条）远在 16MB 之内。
 */
public record SeasonBoardSnapshotDocument(
        @Id String id,
        String seasonId,
        String board,
        long snapshotAt,
        List<SeasonSettlement.Entry> entries) {

    /** 集合名。 */
    public static final String COLLECTION = "season_board_snapshot";

    public static String keyOf(String seasonId, SeasonSettlement.Board board) {
        return seasonId + ":" + board;
    }

    static SeasonBoardSnapshotDocument of(String seasonId, SeasonSettlement.Snapshot snapshot) {
        return new SeasonBoardSnapshotDocument(keyOf(seasonId, snapshot.board()), seasonId,
                snapshot.board().name(), snapshot.snapshotAt(), List.copyOf(snapshot.entries()));
    }

    SeasonSettlement.Snapshot toSnapshot() {
        return new SeasonSettlement.Snapshot(SeasonSettlement.Board.valueOf(board), snapshotAt,
                entries == null ? List.of() : List.copyOf(entries));
    }
}
