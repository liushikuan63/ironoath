// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/setRole 请求体（任命职位）。
 */
public record AllianceRoleReq(
        String requestId,   // 幂等键
        String memberId,   // 目标成员玩家 id
        AllianceRole role)   // 新职位。能不能任命由 role_permission 表决定，不由客户端判断
{
}
