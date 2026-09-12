// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 医院状态。capacity 为 0 时所有伤兵都会因超容量直接死亡（B05 §1.5），客户端必须据此显示红色警告。
 */
public record HospitalView(
        long capacity,
        long used,
        boolean treating,
        Long treatFinishAt,
        long treatRemainingSeconds,
        long treatSecondsPerWounded,   // 每个伤兵的治疗秒数。下发是为了让客户端能预估「治好这些要多久」而不必自己读配置
        long treatCostRatio)   // 治疗消耗占训练消耗的比例（定点 ×10000）
{
}
