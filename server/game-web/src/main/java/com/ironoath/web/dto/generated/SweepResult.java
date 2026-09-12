// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一次扫荡的结果（B09 §三）。
 */
public record SweepResult(
        String reportId,   // 这一次的战斗战报 id。扫荡同样落战报 —— 「扫荡不播放动画」不等于「扫荡没有战斗」，少了战报就没法排查「我扫荡 10 次为什么只拿到 7 次的奖励」
        StageStars stars,
        List<StageReward> rewards)
{
}
