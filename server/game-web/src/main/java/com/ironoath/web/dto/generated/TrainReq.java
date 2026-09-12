// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /army/train 请求体。时间 = 单位时间 × count（B05 §二），批量不等于加速。
 */
public record TrainReq(
        String requestId,
        String unitId,
        long count)
{
}
