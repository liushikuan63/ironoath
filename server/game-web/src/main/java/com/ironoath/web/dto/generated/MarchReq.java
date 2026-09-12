// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/march 请求体。
 */
public record MarchReq(
        String requestId,
        int toX,
        int toY,
        List<MarchUnit> units,
        List<String> heroes,   // 随军武将 id，按主将→副将顺序；不传表示不带武将
        MarchAction action)
{
}
