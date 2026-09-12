// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 只带 heroId 的请求（升星、碎片合成）。
 */
public record HeroIdReq(
        String requestId,
        String heroId)
{
}
