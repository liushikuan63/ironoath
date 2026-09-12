package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.web.social.SocialStore;

/**
 * 职责：一个聊天频道的 MongoDB 文档（{@code _id} 就是 channelKey）。
 * 依赖：{@link SocialStore.ChatMessage}。
 *
 * <p><b>为什么一个频道一个文档、消息放在数组里</b>：内存实现就是"频道 → 有序队列"，
 * 而分页游标按消息在队列中的位置定位。一条消息一个文档需要再造一个单调序号才能复刻这个顺序，
 * 而序号本身又会成为第二个真相来源。频道文档 + {@code $push/$slice} 让"追加 + 裁剪"
 * 成为一次原子写，保留条数上限也落在数据库一侧，不需要读取整段历史再写回。
 *
 * <p><b>已知边界</b>：MongoDB 单文档 16MB，消息数组靠 historyCap 封顶
 * （global.CHAT_LOCAL_HISTORY_MAX，量级是几百条）。上限若被改到不合理的量级，
 * 这里会先撞文档大小限制而不是静默丢消息 —— 那种失败是响亮的。
 */
public record ChatChannelDocument(@Id String channelKey, List<MessageEntry> messages) {

    public static final String COLLECTION = "social_chat";

    record MessageEntry(String messageId, String channel, String senderId, String senderName,
                        String content, long sentAt) {
    }

    static MessageEntry entryOf(SocialStore.ChatMessage message) {
        return new MessageEntry(message.messageId(), message.channel(), message.senderId(),
                message.senderName(), message.content(), message.sentAt());
    }

    static SocialStore.ChatMessage domainOf(MessageEntry entry) {
        return new SocialStore.ChatMessage(entry.messageId(), entry.channel(), entry.senderId(),
                entry.senderName(), entry.content(), entry.sentAt());
    }

    static List<SocialStore.ChatMessage> domainOf(List<MessageEntry> entries) {
        List<SocialStore.ChatMessage> out = new ArrayList<>(entries.size());
        for (MessageEntry entry : entries) {
            out.add(domainOf(entry));
        }
        return out;
    }
}