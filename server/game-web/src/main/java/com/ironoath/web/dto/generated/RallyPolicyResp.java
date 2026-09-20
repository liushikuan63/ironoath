// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /rally/policy 响应体（B26 S13）：小队与联盟两份政策一次给全（与 SocialCreatePolicyResp 同形状，少一次往返）。国家层级暂不在这里：B13 的国战集结还没有玩家入口，给了就是一个没人读的字段。
 */
public record RallyPolicyResp(
        RallyPolicyView squad,
        RallyPolicyView alliance,
        long serverNow)   // 服务端时间戳
{
}
