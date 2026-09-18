// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /social/follow 与 /social/unfollow 的请求体（B22 §一 4 的"关注"，§五 裁决④：单向，不做双向申请）。
 *
 * **为什么单向**：双向申请就是第二套审批流程，而联盟入盟/申请已经把"请求-同意"这条路走通了；关注是一层更轻的社交（我想看他在不在线、想随时私聊他），**对方不需要做任何事**。
 */
public record FollowReq(
        String requestId,   // 幂等键：重复关注同一个人应当是幂等的结果，但重放不该产生第二条账。
        String targetPlayerId)   // 要关注 / 取消关注的玩家。
{
}
