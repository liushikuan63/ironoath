// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 带目标成员的联盟请求（踢人、转让盟主）。
 */
public record AllianceMemberReq(
        String requestId,   // 幂等键
        String memberId)   // 目标成员玩家 id
{
}
