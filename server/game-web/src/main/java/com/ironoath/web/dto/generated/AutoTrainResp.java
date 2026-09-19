// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /army/autoTrain 响应体：落库后的策略本身，客户端照它重画开关与停止原因。
 */
public record AutoTrainResp(
        AutoTrainView autoTrain,
        long serverNow)
{
}
