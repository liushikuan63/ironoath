// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.Map;

/**
 * POST /ops/config/manifest 请求体：客户端报上自己手里各表的 hash。
 */
public record ConfigManifestReq(
        Map<String, String> tableHashes)   // 客户端本地各表的 hash。缺一张表（新装或首次登录）就不放这个 key —— 缺 key 与「hash 为空串」语义不同：前者是「我没有这张表」，后者是「我有一张内容未知的表」，而后者不该存在，所以不允许。
{
}
