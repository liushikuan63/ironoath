// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 只带 unitId 的请求（取消训练、加速某一批训练）。
 */
public record ArmyUnitReq(
        String requestId,
        String unitId,
        Long seconds,   // 加速秒数；加速请求必填
        String itemId)   // 用加速道具时填道具 id，否则为 null
{
}
