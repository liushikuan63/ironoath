// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /hero/lineup 请求体。三个位置都可为 null（表示空位），但主将为空时整队视为未编成。
 */
public record SetLineupReq(
        String requestId,
        int presetIndex,
        String main,
        String sub1,
        String sub2)
{
}
