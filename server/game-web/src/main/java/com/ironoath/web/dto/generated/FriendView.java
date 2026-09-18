// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条关注（B22 §二 草案的 FriendView，按要求带上在线状态）。**没有 "互相关注" 这个状态**：单向关注不构成关系，服务端也不知道对方是否也关注你（知道也不该说 —— 那等于把"谁在看你"透给被看的人）。
 */
public record FriendView(
        String playerId,   // 被关注的玩家 id。
        String name,   // 昵称（服务端拼好下发）。
        boolean online,   // 此刻是否在线（来自 WS 网关的在线快照）。
        long lastSeenAt)   // 最近活跃时刻；在线时为当前时刻。
{
}
