// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /world/recall 响应体。返回耗时 = 已行军距离 / 速度，兵力零损失（B07 验收 7）。
 */
public record RecallResp(
        MarchView march,
        long returnArriveAt,
        long returnSeconds,   // 返程耗时（秒）。客户端要显示「X 分钟后到家」
        long serverNow)
{
}
