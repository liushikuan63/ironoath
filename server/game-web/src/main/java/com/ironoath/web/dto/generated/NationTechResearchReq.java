// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 研究一级国家科技。
 */
public record NationTechResearchReq(
        String requestId,   // 幂等键。**国家层面这条比个人更要紧**：国库是公共池，弱网重投若不去重，症状是同一级研究扣了两笔公共钱，而没有任何一个人的余额因此变少 —— 那类错账只能靠日志对。
        String techId)   // 研究哪一行。服务端依次校验：行存在 → 有权限 → 未满级 → 国家等级够 → 国库够（先把"不该花的公共钱"挡住再扣）。
{
}
