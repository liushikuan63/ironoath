// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/blocks 响应体：我拉黑了谁。**只回我自己的名单**：对方拉没拉黑我是看不到的（那会变成一种骚扰反馈），而发消息时服务端会给出"被对方拒收"的说清方向的错误。
 */
public record BlockListView(
        List<String> blockedPlayerIds)   // 我拉黑的玩家 id，按加入顺序（最近的在前）。
{
}
