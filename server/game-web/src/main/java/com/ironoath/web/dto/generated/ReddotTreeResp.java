// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/reddot 响应体：服务端算好的完整红点树。刻意是**整体下发而不是增量**：客户端合并会让已经消失的红点永远留着（假红点），而那是 B12 验收 1 直接判失败的现象。
 */
public record ReddotTreeResp(
        List<ReddotNodeView> nodes,   // 树的第一层节点（每个是一棵子树的根）。
        int leafCount,   // 已注册叶子数。下发是为了让「红点判断有没有散落到业务模块里」变成一个可断言的数：它应当随功能数增长，而不是长期停在个位数
        long serverNow)
{
}
