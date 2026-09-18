// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/follows 响应体：我关注的人，最近关注的在前。
 */
public record FriendListView(
        List<FriendView> friends)   // 关注列表。**上限由 global.SOCIAL_FOLLOW_MAX 管**，超了在关注时就拒（见那边的 why）。
{
}
