// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 集结承诺出征的一个兵种条目。用 unit 表的行 id（含阶级），不用兵种类型 —— 与 MarchUnit / StageUnit 同一口径：按兵种类型会让 T5 兵被当成 T1 用。生成器不支持跨文件 $ref，所以这里又是一份拷贝，由 SocialContractParityTest 断言与 world 协议的 MarchUnit、stage 协议的 StageUnit 字段名与语义一致。
 */
public record RallyTroop(
        String unitId,   // unit 表的行 id，含阶级（如 unit_infantry_t3）
        long count)   // 数量
{
}
