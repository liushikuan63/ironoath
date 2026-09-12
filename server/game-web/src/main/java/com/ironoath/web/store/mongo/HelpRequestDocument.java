package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import com.ironoath.web.social.SocialStore;

/**
 * 职责：一条待帮助请求的 MongoDB 文档。
 * 依赖：{@link SocialStore.HelpRequest}。
 *
 * <p>完成或取消后由服务层移除，所以这张表的大小被"当前进行中的升级/治疗"封顶，
 * 不需要 TTL 索引。helpCount 用 {@code $inc} 原子推进，避免"读到旧值 +1 再写回"的丢计数。
 */
public record HelpRequestDocument(
        @Id String requestId,
        String fromPlayerId,
        String fromPlayerName,
        String kind,
        String targetKey,
        String targetDesc,
        long finishAt,
        int helpedCount) {

    public static final String COLLECTION = "social_help_request";

    static HelpRequestDocument fromDomain(SocialStore.HelpRequest request) {
        return new HelpRequestDocument(request.requestId(), request.fromPlayerId(),
                request.fromPlayerName(), request.kind(), request.targetKey(), request.targetDesc(),
                request.finishAt(), request.helpedCount());
    }

    SocialStore.HelpRequest toDomain() {
        return new SocialStore.HelpRequest(requestId, fromPlayerId, fromPlayerName, kind, targetKey,
                targetDesc, finishAt, helpedCount);
    }
}