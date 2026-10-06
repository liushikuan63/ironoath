package com.ironoath.web.store.mongo;

import com.ironoath.core.nation.WarScoreBoard;
import org.springframework.data.annotation.Id;

/**
 * 职责：国战战事的 MongoDB 文档模型。
 * 依赖：{@link WarScoreBoard.Snapshot}（业务状态整体就是它）。
 *
 * <p>与 {@link NationDocument} 同一条取舍：<b>整个聚合序列化成 {@code state} 一个字段</b>，
 * 文档级只剩纯索引列。好处有两层：
 * <ol>
 *   <li>{@code save} 的写白名单永远只有 {@code state} 一列（加一门 {@code check-mongo-set-coverage}
 *       要求每个文档字段都被 {@code $set}，字段越少越不可能漏 —— 漏一列的症状不是报错而是<b>静默丢档</b>，
 *       本仓在 B25-S2 与 #16 各吃过一次）；</li>
 *   <li>内核加一个状态字段时，落盘自动跟着走，不需要改 Document 与 save 两处
 *       —— 而 {@link WarScoreBoard.Snapshot} 少带一项的表现是「复活出一个假状态」
 *       （见那份快照自己的注释），能被忘在第二处才是真危险。</li>
 * </ol>
 *
 * <p>{@code startedAt} 这一列是<b>纯索引列</b>：值永远由 {@code state.startedAt()} 派生，不参与任何判定
 * （判定读的还是 {@code state} 里那一份）。它存在的唯一理由是 {@code findLatest} 要按它排序，
 * 而排序必须有索引可依 —— 与 {@code NationDocument.name} / {@code memberAllianceIds} 同一族。
 * 主键 {@code warId} 由 {@code WarStore#documentIdOf} 从 {@code startedAt} 推出，
 * <b>两套实现共用那一个函数</b>，不各写一份。
 */
public record WarDocument(
        @Id String warId,
        long startedAt,
        WarScoreBoard.Snapshot state) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "war";

    static WarDocument fromDomain(String warId, WarScoreBoard board) {
        WarScoreBoard.Snapshot state = board.toSnapshot();
        return new WarDocument(warId, state.startedAt(), state);
    }
}
