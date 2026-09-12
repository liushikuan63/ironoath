// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 兵力条目：unitId（含阶级）→ 数量。与 army 协议的 MarchUnit 形状相同，但生成器不支持跨文件 $ref，所以这里各有一份。两者的字段名与语义必须保持一致，由 StageContractParityTest 断言。
 */
public record StageUnit(
        String unitId,   // unit 表的行 id，含阶级（如 unit_infantry_t3）
        long count)
{
}
