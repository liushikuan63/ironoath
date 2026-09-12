// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /squad/create 请求体（B10 §二）。
 */
public record SquadCreateReq(
        String requestId,   // 幂等键。创建会写入组织表并占名字，重放会建出两个同名小队
        String name)   // 小队名。长度与敏感词校验在服务端
{
}
