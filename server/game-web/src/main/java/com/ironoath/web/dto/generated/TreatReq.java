// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /army/treat 请求体。一次治疗全部伤兵（B05 §二没有「按兵种分次治疗」，那会让医院队列变成第二个训练队列）。
 */
public record TreatReq(
        String requestId)
{
}
