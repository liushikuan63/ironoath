// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /item/openBatch 请求体。B04 §2 原契约只有 itemId 与 count，这里补了 requestId —— 开箱是有副作用的写操作，断网重放会让玩家白丢一箱，必须走与城建同一套幂等（B00 陷阱 3）。
 */
public record OpenBatchReq(
        String requestId,
        String itemId,   // 必须是 chest 表里登记过的宝箱道具 id
        int count)   // 一次开几个。100 是协议天花板（B04 验收 3 就是按 100 定的），逐箱的实际上限取 chest.maxBatchCount，两者取小。
{
}
