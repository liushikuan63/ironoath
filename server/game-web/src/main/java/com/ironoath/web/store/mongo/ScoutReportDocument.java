package com.ironoath.web.store.mongo;

import com.ironoath.core.scout.ScoutReport;
import org.springframework.data.annotation.Id;

/**
 * 职责：侦查报告的 MongoDB 文档（一份报告一文档）。
 * 依赖：{@link ScoutReport.Report}（报告本体就是那个 record）。
 *
 * <p>文档体不摊平，理由与 {@link BattleReportDocument} 同一条：Report 本来就是 record，
 * 摊平等于在存储层再造一份"什么算一份完整的侦查报告"，少抄一个字段的表现是
 * "报告列表在、点开看不到观察到的兵力"。
 *
 * <p>只有 {@code scoutPlayerId} 提到文档级：它是"我的情报"列表的过滤列。
 * 排序与过期判定用嵌套路径（{@code report.createdAt} / {@code report.expiresAt}），
 * 因为它们只服务于索引，不需要多写一份可以互相矛盾的副本。
 */
public record ScoutReportDocument(
        @Id String reportId,
        String scoutPlayerId,
        ScoutReport.Report report) {

    /** 集合名。 */
    public static final String COLLECTION = "scout_report";

    /** 排序与清理用的嵌套路径（内存版同一条口径，两侧共用一份字段名）。 */
    public static final String FIELD_CREATED_AT = "report.createdAt";
    public static final String FIELD_EXPIRES_AT = "report.expiresAt";
}
