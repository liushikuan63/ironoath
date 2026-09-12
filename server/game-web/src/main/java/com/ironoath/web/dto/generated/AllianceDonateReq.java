// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/donate 请求体（B10 §二）。
 */
public record AllianceDonateReq(
        String requestId,   // 幂等键。捐献会扣资源/金币并发放贡献值，重放等于刷贡献
        int tier)   // 捐献档位：0 免费 / 1 资源 / 2 金币。**档位而不是数额**：数额由 global.DONATE_TIER_* 决定，客户端传数额就等于把定价权交给客户端
{
}
