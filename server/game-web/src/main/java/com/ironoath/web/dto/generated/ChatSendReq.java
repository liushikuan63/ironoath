// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /chat/send 请求体。
 */
public record ChatSendReq(
        String requestId,   // 幂等键。聊天重放会导致同一句话发两遍，而这恰好会撞上防刷屏限流，玩家看到的是「我发一句话却提示刷屏」
        ChatChannel channel,   // 频道
        String content,   // 正文。长度上限在服务端校验
        String toPlayerId)   // 私聊对象；非私聊频道为 null
{
}
