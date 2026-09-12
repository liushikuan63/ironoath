// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 行军携带的一个兵种。用 unit 表的行 id（含阶级），不用兵种类型 —— 战斗结算需要知道具体是 T几。
 */
public record MarchUnit(
        String unitId,
        long count)
{
}
