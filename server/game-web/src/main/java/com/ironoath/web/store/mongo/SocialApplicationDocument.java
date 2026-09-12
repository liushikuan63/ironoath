package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/**
 * 职责：一条入盟申请的 MongoDB 文档。
 * 依赖：无。
 *
 * <p>{@code _id} 用 {@code allianceId + ":" + playerId} 复合键：唯一约束由主键天然提供，
 * 不需要另建唯一索引。两个 id 都是服务端生成的 ASCII 标识（不含冒号），
 * 复合键不会出现歧义。
 */
public record SocialApplicationDocument(
        @Id String applicationId,
        String allianceId,
        String playerId) {

    public static final String COLLECTION = "social_application";

    static String keyOf(String allianceId, String playerId) {
        return allianceId + ":" + playerId;
    }

    static SocialApplicationDocument of(String allianceId, String playerId) {
        return new SocialApplicationDocument(keyOf(allianceId, playerId), allianceId, playerId);
    }
}