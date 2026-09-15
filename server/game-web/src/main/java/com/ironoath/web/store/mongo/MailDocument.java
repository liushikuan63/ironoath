package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import com.ironoath.web.mail.MailStore;

/**
 * 职责：邮件的 MongoDB 文档形状（B12 §2 的生产存储）。
 * 依赖：{@link MailStore.MailRecord}。
 *
 * <p><b>整体存一份 {@code mail} 子文档，不摊平</b>：与 {@code BattleReportDocument} 同一条决定 ——
 * 摊平等于在存储层再造一份「什么算一封完整的邮件」的字段表，而少抄一个字段不会报错，
 * 只会让玩家的邮件里少一条附件（本项目已经走过四次这条路）。
 *
 * <p>文档级额外挂两列：{@code playerId} 是「我的邮件」这条每次进面板都会发的查询的键，
 * {@code expireAt} 是清理的键。两列的值永远等于子文档里的同名字段，
 * <b>不参与任何判定</b>（判定只读 {@link #mail()}），所以它们漂移的最好下场是查到空列表而不是错判已领 ——
 * 这也是它们敢被冗余出来的前提。
 */
public record MailDocument(
        @Id String mailId,
        String playerId,
        Long expireAt,
        MailStore.MailRecord mail) {

    public static final String COLLECTION = "mail";
    public static final String FIELD_PLAYER_ID = "playerId";
    public static final String FIELD_EXPIRE_AT = "expireAt";
    /** 子文档里的排序键：列表按它倒序，所以索引必须建在它上面而不是文档级字段上。 */
    public static final String FIELD_CREATED_AT = "mail.createdAt";
    /** 领取判定读的过期时刻（子文档内那份）。文档级 {@code expireAt} 只服务索引与读取过滤。 */
    public static final String FIELD_MAIL_EXPIRE_AT = "mail.expireAt";
    /** 领取时刻（子文档内）。条件更新用 {@code mail.claimedAt = null} 表达「还没人领过」。 */
    public static final String FIELD_CLAIMED_AT = "mail.claimedAt";
    public static final String FIELD_READ_AT = "mail.readAt";

    public static MailDocument of(MailStore.MailRecord record) {
        return new MailDocument(record.mailId(), record.playerId(), record.expireAt(), record);
    }

    public MailStore.MailRecord toRecord() {
        return mail;
    }
}
