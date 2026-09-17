// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 研究结果。花掉多少、剩下多少一并回：国库是公共账，界面若不回余额，就得再拉一次列表才能确认钱真的只扣了一次。
 */
public record NationTechResearchResp(
        String techId,
        int level,   // 研究完之后的等级（这一行是即时生效，没有队列与完成时刻）。
        long costTreasury,   // 本次从国库扣掉的数额（走 `Nation.Sink.NATIONAL_TECH` 核销，日志里那行 `sink:national_tech` 记的就是它）。
        long treasuryAfter)   // 扣完之后国库余额。
{
}
