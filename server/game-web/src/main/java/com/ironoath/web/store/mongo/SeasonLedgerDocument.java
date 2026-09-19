package com.ironoath.web.store.mongo;

import com.ironoath.web.season.SeasonLedgerStore;
import org.springframework.data.annotation.Id;

/**
 * 职责：赛季结算记录的 MongoDB 文档（一条 = 一季一人）。
 * 依赖：{@link SeasonLedgerStore.Record}。
 *
 * <p>{@code _id} 用 {@code seasonId + ":" + playerId} —— 与 {@code SeasonSettlement} 内部
 * 那张幂等表的键格式<b>完全一致</b>，读代码的人不必在两套键之间做心算映射。
 * 这里的假设写清楚：{@code seasonId} 由 {@code SeasonTimeline} 生成（形如 {@code season_<起始日>}），
 * 不接受任何外部输入，所以这个拼接不会被玩家构造出歧义。
 *
 * <p>{@code seasonId} / {@code playerId} 两列是从 {@code id} 里派生的纯索引列（与
 * {@link NationDocument} 的 {@code name} 同一条纪律）：{@code find(playerId)} 要跨季查荣耀，
 * 靠扫 {@code _id} 前缀做不了索引。
 *
 * <p><b>刻意不用"每季一个集合"</b>（尽管 {@code SeasonSettlement.archiveCollection()} 那个名字
 * 容易让人以为如此）：动态集合名会让索引创建、按玩家查荣耀、以及归档清理各写一遍分支，
 * 而这三处都没有业务收益 —— 一个 seasonId 字段 + 一条复合索引就够。
 */
public record SeasonLedgerDocument(
        @Id String id,
        String seasonId,
        String playerId,
        SeasonLedgerStore.Record record,
        /**
         * 已花掉的赛季币（B24 裁决① 的赛季商店）。**可空**：这一列是后加的，
         * 库里已有的文档没有它 —— 读出来是 null 就当 0，而扣减那条查询必须显式带上"字段缺失"那一支，
         * 否则老号永远扣不动（这是这一列最容易漏的地方）。
         */
        Long spent) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "season_ledger";

    /** 与 {@code SeasonSettlement} 内部幂等表同一条键格式。 */
    public static String keyOf(String seasonId, String playerId) {
        return seasonId + ":" + playerId;
    }

    static SeasonLedgerDocument of(String seasonId, SeasonLedgerStore.Record record) {
        return new SeasonLedgerDocument(keyOf(seasonId, record.playerId()), seasonId,
                record.playerId(), record, null);
    }

    /** 已花掉的数（缺字段 = 没花过）。 */
    long spentOrZero() {
        return spent == null ? 0L : spent;
    }
}
