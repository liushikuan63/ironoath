package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import com.ironoath.web.reward.RewardCompensationStore;

/**
 * 职责：补偿台账的 MongoDB 文档形状（B04 验收 7 的生产存储）。
 * 依赖：{@link RewardCompensationStore.Entry}。
 *
 * <p><b>整份存进 {@code entry} 子文档、且查询与索引都走子文档里的路径，一列冗余都不做</b>：
 * 邮件那份文档为了「我的邮件」这条热路径查询冗余了 {@code playerId} 与 {@code expireAt} 两列。
 * 台账不需要：它每次全服最多几百条、只在运维打开面板时读一次，
 * 而「冗余列参与判定」的代价是它哪天与子文档漂移时，一笔没处理的欠账会<b>从待处理列表里消失</b>
 * —— 那正是这张表最不能出的错。少两列冗余，就少两处会漂移的真相。
 */
public record RewardCompensationDocument(
        @Id String compensationId,
        RewardCompensationStore.Entry entry) {

    public static final String COLLECTION = "reward_compensation";
    /** 待处理判定的键（子文档内那份，没有第二份）。 */
    public static final String FIELD_RESOLVED_AT = "entry.resolvedAt";
    /** 排序键：待处理列表按它升序（最旧的在前）。 */
    public static final String FIELD_CREATED_AT = "entry.createdAt";

    public static RewardCompensationDocument of(RewardCompensationStore.Entry entry) {
        return new RewardCompensationDocument(entry.compensationId(), entry);
    }

    public RewardCompensationStore.Entry toEntry() {
        return entry;
    }
}
