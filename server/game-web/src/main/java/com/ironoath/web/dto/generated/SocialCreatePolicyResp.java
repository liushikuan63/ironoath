// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /social/createPolicy 响应体（B26 S2）。一次回两个层级：社交面板本来就要同时画小队与联盟两页，分两次拉会让两页的可用性来自不同时刻。
 */
public record SocialCreatePolicyResp(
        SocialCreatePolicy squad,   // 小队创建政策
        SocialCreatePolicy alliance,   // 联盟创建政策
        long serverNow)   // 服务端时间戳
{
}
