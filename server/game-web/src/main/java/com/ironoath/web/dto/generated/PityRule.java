// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 保底规则。全部来自 gacha 表，客户端不得自行推算。
 */
public record PityRule(
        long ssrPity,   // 累计多少抽未出 SSR 则必出
        long srPity,
        long ssrUpGuarantee)   // 限定池专用：连续多少次 SSR 非 UP 后，下一次 SSR 必为 UP。0 表示该池无 UP 保底（标准池）
{
}
