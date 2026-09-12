// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /stage/sweep 响应体。
 */
public record SweepResp(
        List<SweepResult> results,   // 每次扫荡的结果，按执行顺序。**逐次下发而不是只给合计**：每次都是独立的一场战斗（各自的 seed 与浮动），只给合计会让玩家无法核对，也无法复现某一次
        List<StageReward> totalRewards,   // 按奖励类型聚合后的合计，供「一键领取」的飘字使用
        long staminaCost,
        long staminaCharged,
        int executed,   // 实际执行次数。体力不够时会少于请求次数 —— **照实返回而不是报错**：已经扫了的几次必须给奖励，整批失败会让玩家损失已扣的体力
        StageProgressView progress,
        long serverNow)
{
}
