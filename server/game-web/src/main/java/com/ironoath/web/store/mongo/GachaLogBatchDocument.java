package com.ironoath.web.store.mongo;

import com.ironoath.core.gacha.GachaLogStore;
import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：抽卡日志的 MongoDB 文档模型 —— <b>一批一个文档</b>。
 * 依赖：{@link GachaLogStore.Entry}。
 *
 * <p><b>为什么不是一个 entry 一个文档</b>：端口要求"一次十连的 10 条要么全写要么全不写"，
 * 而多文档写入在 MongoDB 里只有事务才能做到原子，事务又要求副本集 —— 部署门槛会凭空多一层。
 * 一批装进一个文档，原子性就是单文档写入的天然属性，不需要事务也不需要副本集。
 * 而"为什么必须原子"写在端口上：写一半会让「这次十连到底出了什么」变成无法回答的问题，
 * 合规核查问的正是这个。
 *
 * <p>{@code firstDrawnAt} / {@code lastDrawnAt} 是批次内条目时间戳的两端，只为**裁剪候选集**用
 * （查询用 {@code lastDrawnAt >= since}、清理用 {@code firstDrawnAt < cutoff}，都不会漏掉条目）；
 * 逐条的时间仍然以 {@code entries} 里各自的 {@code drawnAt} 为准。
 *
 * <p>刻意<b>不建 TTL 索引</b>：TTL 是整文档删除，而保留期的口径是"条"，
 * 由 {@link GachaLogStore#purgeBefore(long)} 按 {@code global.GACHA_LOG_RETENTION_DAYS} 驱动。
 * 两处各删一半，就会出现"表里写 90 天、实际按文档创建时间 90 天整批消失"的漂移。
 */
public record GachaLogBatchDocument(
        @Id String batchId,
        String playerId,
        long firstDrawnAt,
        long lastDrawnAt,
        List<GachaLogStore.Entry> entries) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "gacha_log";

    /** 复合索引名，与 {@link MongoIndexes} 里创建的保持一致。 */
    public static final String INDEX_PLAYER_DRAWN_AT = "idx_player_id_last_drawn_at";
}
