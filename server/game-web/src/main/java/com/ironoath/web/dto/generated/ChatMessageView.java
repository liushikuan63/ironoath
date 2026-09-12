// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条聊天消息。
 */
public record ChatMessageView(
        String messageId,   // 消息 id
        ChatChannel channel,   // 频道
        String senderId,   // 发送者玩家 id
        String senderName,   // 发送者昵称
        String content,   // 消息正文。**原样下发，不做任何过滤后的替换** —— 敏感词处理属 B15 合规范畴，且必须在服务端做；客户端若自行替换，双端会显示不同的文本
        long sentAt)   // 发送时刻（服务端时间戳）
{
}
