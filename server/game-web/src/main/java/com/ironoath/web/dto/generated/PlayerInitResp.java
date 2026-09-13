// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.Map;

/**
 * POST /player/init 响应体（data 字段内容）
 */
public record PlayerInitResp(
        String playerId,
        String authToken,   // 会话票据（B15 §三）。客户端之后每个请求都要带 X-Auth-Token；没有微信登录体系的环境返回空串
        long serverNow,   // 服务端时间戳，客户端据此算偏移（铁律 5）
        PlayerProfile profile,
        int cityLevel,   // 主城等级，新号 = 1
        Map<ResourceType, ResourceState> resources,   // 五种资源快照
        PowerSnapshot power,
        Long protectUntil)   // 新手保护到期时间（服务端毫秒时间戳）；null 表示无保护
{
}
