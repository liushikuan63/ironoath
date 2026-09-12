// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 产出明细的一行（B04 §2 转化关键 UI）。isPercent=true 时客户端显示成「+120 (+10%)」，否则显示成「+600」。所有行的 amount 之和必须精确等于该资源的实际每小时产量（B04 验收 5，误差 0）——这个不变量由服务端 ResourceOutputCalculator.Breakdown 在构造期强制。
 */
public record OutputBreak(
        String source,   // 来源标签，直接展示，如「农田 Lv8」「科技加成」
        long amount,   // 该行贡献的每小时产量
        boolean isPercent,   // 是否为百分比加成行
        Long percentFixed)   // 百分比值（定点 ×10000），仅 isPercent=true 时有意义
{
}
