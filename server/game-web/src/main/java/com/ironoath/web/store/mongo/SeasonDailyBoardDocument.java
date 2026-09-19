package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.core.season.SeasonSettlement;

/**
 * 职责：某一天某张榜的**每日快照**文档（B23 裁决②）。{@code _id} = {@code seasonId:board:dayKey}。
 * 依赖：{@link SeasonSettlement.Snapshot}。
 *
 * <p><b>为什么是独立集合而不是数据里多一个 dayKey 列</b>：裁决②要求每日快照与结算快照
 * 「不同集合」——结算是付钱依据、只有一份且不可改；每日快照是申诉时间线、一天一份且随归档清理。
 * 混在一个集合里，一次"清理超出保留期的赛季"就得靠查询条件把两者分开写对，
 * 而写错的表现不是报错：是把还在申诉窗口内的结算快照删了（不可恢复）。
 * 集合分开之后，两种清理各自扫各自的，压根不存在写错的余地。
 *
 * <p><b>一天一个文档、整榜装进数组</b>（与 {@code ChatChannelDocument} 同一种形状）：
 * 拍一次是一天一次的操作，一行一个文档会让一次拍摄变成 N 次写（N = 榜上人数），
 * 而读取总是"读那天整榜"（要算名次就必须有整榜）。代价是文档大小随人数线性增长：
 * 5000 人一榜约 0.4MB，离 16MB 的单文档上限还远；真到十万人的服要重新算这笔账。
 */
public record SeasonDailyBoardDocument(
        @Id String id,
        String seasonId,
        String board,
        String dayKey,
        long snapshotAt,
        List<RowEntry> entries) {

    /** 集合名。与 {@code season_board}（实时榜）和 {@code season_board_snapshot}（结算快照）都不同。 */
    public static final String COLLECTION = "season_daily_board";

    /** 榜上的一行。刻意不带 Bot 相关字段（身份不外泄，见 {@code NoBotFieldLeakTest} 的口径）。 */
    record RowEntry(String playerId, String name, long score) {
    }

    public static String keyOf(String seasonId, SeasonSettlement.Board board, String dayKey) {
        return seasonId + ":" + board + ":" + dayKey;
    }

    static SeasonDailyBoardDocument of(String seasonId, SeasonSettlement.Board board, String dayKey,
                                       SeasonSettlement.Snapshot snapshot) {
        List<RowEntry> rows = new ArrayList<>(snapshot.entries().size());
        for (SeasonSettlement.Entry entry : snapshot.entries()) {
            rows.add(new RowEntry(entry.id(), entry.name(), entry.score()));
        }
        return new SeasonDailyBoardDocument(keyOf(seasonId, board, dayKey), seasonId, board.name(),
                dayKey, snapshot.snapshotAt(), rows);
    }

    SeasonSettlement.Snapshot toSnapshot() {
        List<SeasonSettlement.Entry> out = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (RowEntry row : entries) {
                out.add(new SeasonSettlement.Entry(row.playerId(), row.name(), row.score()));
            }
        }
        return new SeasonSettlement.Snapshot(SeasonSettlement.Board.valueOf(board), snapshotAt,
                out);
    }
}
