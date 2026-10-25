// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * （Schema 未提供说明）
 */
public record MidasGameItemGoodsInfo(
        String ProductId,
        int Quantity,
        String OrigPrice,   // 官方回调中的金额字符串，按两位小数换算为分校验。
        String ActualPrice,   // 官方回调中的实付金额字符串。
        String Attach,
        int OrderSource)
{
}
