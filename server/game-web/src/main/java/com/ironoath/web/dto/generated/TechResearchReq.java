// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 开始研究一行科技（下一级）。
 */
public record TechResearchReq(
        String requestId,   // 幂等键，与城建/训练/任务同一套（弱网重投不该扣两次资源）。
        String techId)   // 要研究哪一行。服务端依次校验：行存在 → 未满级 → 队列空着 → 学院等级够 → 资源够（顺序与城建 `validateUpgrade` 一致，先把「不该花的钱」挡住再扣资源）。
{
}
