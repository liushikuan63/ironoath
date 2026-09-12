// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /season/settle 请求体。
 *
 * **结算必须带 requestId**：它是幂等键的第一半（另一半是 seasonId+playerId，在领域层）。没有 requestId 的话，运营脚本重试一次就多结算一次，而多出来的那一次不会报错 —— 只会让玩家发现奖励数字对不上。
 */
public record SeasonSettleReq(
        String requestId,   // 幂等键。
        Integer pageSize)   // 一页处理多少人。缺省取 global.SEASON_SETTLE_PAGE_SIZE，可以调小、不得超过它。
{
}
