package com.ironoath.web.store.mongo;

import com.ironoath.web.battle.BattleReport;
import java.util.List;
import org.springframework.data.annotation.Id;

/**
 * 职责：战报的 MongoDB 文档模型。
 * 依赖：{@link BattleReport}（整份战报就是它，回放需要的 {@code BattleResult} 也在里面）。
 *
 * <p><b>为什么整份塞进 {@code report} 而不摊平字段</b>：{@link BattleReport} 本来就是 record，
 * 摊平等于在存储层再造一份"什么算一份完整战报"的字段表 —— 那条路本项目已经走过四次
 * （城建档、武将、行军、支付订单），每一次的代价都是"少抄一个字段不报错，只是回放里少了东西"。
 *
 * <p>文档级只额外挂 {@code ownerId} 一列：它是列表查询与索引的键，值永远等于
 * {@code report.ownerId()}，不参与任何判定。留着的理由与 {@code PayOrderDocument.totalCents}
 * 同一条 —— 让"我的战报列表"这条每次进页面都会发的查询走索引而不是钻子文档。
 */
public record BattleReportDocument(
        @Id String reportId,
        String ownerId,
        BattleReport report,
        /**
         * 这份战报被分享到的频道键（B22 §一 2）。**不在 {@code report} 里面**：记录本身不可变、
         * 且 `save` 的语义是"同 id 不覆盖"，所以分享是一个独立的追加字段（`$addToSet`）。
         * 旧文档没有这个字段 ⇒ 读出来是 null，调用方按空表处理。
         */
        List<String> sharedChannels) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "battle_report";

    /** 列表按它倒序；嵌套路径写一次就好，两侧排序口径由等价测试钉住。 */
    public static final String FIELD_CREATED_AT = "report.createdAt";
    public static final String FIELD_EXPIRES_AT = "report.expiresAt";
    /** 分享账所在字段（分享与校验可见性都按它读写）。 */
    public static final String FIELD_SHARED_CHANNELS = "sharedChannels";

    static BattleReportDocument fromDomain(BattleReport report) {
        return new BattleReportDocument(report.reportId(), report.ownerId(), report, List.of());
    }

    BattleReport toDomain() {
        return report;
    }
}
