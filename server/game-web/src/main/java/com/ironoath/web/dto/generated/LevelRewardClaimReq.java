// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /level-reward/claim 请求体：领取某一级的奖励。
 *
 * **requestId 是必需项而不是可选优化**：裁决②把入账挂在「玩家点按钮」这一刻，于是「点了没到账、玩家再点一次」成为常规路径而不是边缘情况。同一 requestId 重放只发一份。
 */
public record LevelRewardClaimReq(
        String requestId,   // 幂等键。缺失回 1003，重复回 1002（与任务/活动/社交同一套码）。
        long level)   // 要领的等级。表里没有这一级回 3014，主城还没到这一级回 3015，已经领过回 3016 —— 三种失败各有各的码，客户端才能给出不误导人的提示。
{
}
