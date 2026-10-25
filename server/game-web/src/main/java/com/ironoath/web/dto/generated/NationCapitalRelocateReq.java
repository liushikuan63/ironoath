// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/capital/relocate. requestId is the durable idempotency key. The king may decide at any time; when the king has been offline for at least seven days, any other recorded Nation office holder may decide. The server rejects active COMBAT and attack marches still approaching the old capital. The occupied capital garrison moves with the new capital after COMBAT completion.
 */
public record NationCapitalRelocateReq(
        String requestId,   // Durable idempotency key.
        String nationId,   // Nation whose capital is moving.
        int x,   // New capital x coordinate.
        int y)   // New capital y coordinate.
{
}
