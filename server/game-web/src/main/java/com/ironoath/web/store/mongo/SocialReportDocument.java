package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import com.ironoath.web.social.SocialStore;

/**
 * 职责：举报留痕的 MongoDB 文档（B22 §一 3）。
 * 依赖：{@link SocialStore.ReportRecord}。
 *
 * <p><b>为什么单独一个集合而不是塞进玩家文档</b>：举报是"事件的台账"，不是某个玩家的状态 ——
 * 运营查证时按时间倒序翻全服，按玩家去翻要遍历每个人的文档。而写侧只有追加，没有更新，
 * 一张 append-only 的表也更容易按保留期清理（将来加 TTL 是加一条索引的事）。
 *
 * <p><b>字段摊平而不是整份塞一个子对象</b>：与战报文档相反。战报是"一份完整的领域对象"，
 * 摊平等于在存储层再造一份字段表；而举报只有七个标量，运营侧迟早要按 targetPlayerId 或
 * reason 聚合 —— 摊平的字段才能直接建索引与聚合。
 */
public record SocialReportDocument(
        @Id String reportId,
        String reporterId,
        String targetPlayerId,
        String messageId,
        String reason,
        String detail,
        long createdAt) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "social_report";

    /** 运营侧按它倒序翻（最近的举报在最前）。 */
    public static final String FIELD_CREATED_AT = "createdAt";
    /** 限频与新记录查询都按它过滤。 */
    public static final String FIELD_REPORTER = "reporterId";
    public static final String FIELD_TARGET = "targetPlayerId";

    static SocialReportDocument fromDomain(SocialStore.ReportRecord record) {
        return new SocialReportDocument(record.reportId(), record.reporterId(),
                record.targetPlayerId(), record.messageId(), record.reason(), record.detail(),
                record.createdAt());
    }

    SocialStore.ReportRecord toDomain() {
        return new SocialStore.ReportRecord(reportId, reporterId, targetPlayerId, messageId,
                reason, detail, createdAt);
    }
}
