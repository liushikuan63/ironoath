// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/speedUp 响应体。
 */
public record SpeedUpResp(
        String buildingId,
        long reducedSeconds,   // 实际提前的秒数，会被剩余时间截断（不会出现负数，B03 禁止项）
        long remainingSeconds,
        boolean finished)   // 是否已加速到完成
{
}
