// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一关的历史最好成绩。
 */
public record StageProgressView(
        String stageId,
        int stars,   // 历史最好星级。**只升不降**：重试打得更差不该扣星，否则玩家会因为怕掉星而不敢重试，而重试正是养成的动力
        int bestRounds,   // 历史最少回合数，0 表示尚未通关
        long clearedAt,   // 首次通关的服务端时刻；未通关为 0
        long sweepCount)   // 累计扫荡次数。埋点用：扫荡占比过高说明关卡内容被消耗完了
{
}
