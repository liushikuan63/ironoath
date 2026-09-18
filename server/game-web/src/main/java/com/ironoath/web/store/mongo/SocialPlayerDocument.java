package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.web.social.SocialStore;

/**
 * 职责：社交域里"按玩家存"的两类状态 —— 解散保护期与未读社交事件。
 * 依赖：{@link SocialStore.SocialEvent}。
 *
 * <p>两者都是 playerId 直接定位、生命周期只跟这个玩家走，所以共用一个文档。
 * 拆成两个集合只会让"删号清理"多一处遗漏。
 */
public record SocialPlayerDocument(
        @Id String playerId,
        /** 包装类型：pushEvent 先建档时这个字段还不存在，基本类型会在反序列化时炸。 */
        Long disbandProtectedUntil,
        List<EventEntry> unreadEvents,
        /**
         * 拉黑名单（B22 §一 3）：我拉黑过的人，按加入顺序。旧文档没有这个字段 ⇒ 读出来是 null，
         * 按空表处理（与其它可选字段同一条约定）。
         */
        List<String> blockedPlayerIds) {

    public static final String COLLECTION = "social_player";

    /** 拉黑名单字段（拉黑与取消拉黑都按它读写）。 */
    public static final String FIELD_BLOCKED = "blockedPlayerIds";

    record EventEntry(String eventId, String type, String title, String body,
                      Long coordX, Long coordY, String relatedId, long occurredAt, long expireAt) {
    }

    static EventEntry entryOf(SocialStore.SocialEvent event) {
        return new EventEntry(event.eventId(), event.type(), event.title(), event.body(),
                event.coordX(), event.coordY(), event.relatedId(), event.occurredAt(), event.expireAt());
    }

    static SocialStore.SocialEvent domainOf(EventEntry entry) {
        return new SocialStore.SocialEvent(entry.eventId(), entry.type(), entry.title(), entry.body(),
                entry.coordX(), entry.coordY(), entry.relatedId(), entry.occurredAt(), entry.expireAt());
    }

    static List<SocialStore.SocialEvent> domainOf(List<EventEntry> entries) {
        List<SocialStore.SocialEvent> out = new ArrayList<>(entries.size());
        for (EventEntry entry : entries) {
            out.add(domainOf(entry));
        }
        return out;
    }
}