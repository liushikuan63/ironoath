// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /army/autoTrain 请求体。开启（enabled=true）时必须给全 unitId / count / batchBudget；关闭只需要 requestId 与 enabled=false —— 关闭时不必再报一遍目标，否则玩家会以为关不掉。
 */
public record AutoTrainReq(
        String requestId,
        boolean enabled,
        String unitId,   // 续训哪个兵种；enabled=true 时必填
        Long count,   // 每批数量；enabled=true 时必填
        Integer batchBudget,   // 最多自动排几批；enabled=true 时必填，上限见 global.json 的 AUTO_TRAIN_MAX_BATCHES
        Long targetCount)   // 补兵模式的目标兵力；传 null / 不传 = 续训模式（不设目标）
{
}
