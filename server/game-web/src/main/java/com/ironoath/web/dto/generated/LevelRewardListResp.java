// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /level-reward/list 的响应。纯读，不推进任何状态 —— 与 quest 的 list 不同，这里没有跨期清零要挂（等级奖励永不过期），所以它不会写库。
 */
public record LevelRewardListResp(
        List<LevelRewardRow> rows,   // 全部等级行，按 level 升序（服务端已排，客户端不需要再排 —— 行序变了玩家会看到同一屏内容换位置）。
        int claimableCount,   // 此刻可领的行数，给红点用。与 rows 里 claimable=true 的行数同源（同一次遍历算出来的两个值，不允许各算一遍）。
        long mainLevel,   // 玩家当前主城等级。面板要显示「你现在 14 级，还有 3 级可领」这类抬头，而等级真相在服务端存档上 —— 客户端没有这一位，也不许自己从别处推。
        long serverNow)   // 服务端时刻。
{
}
