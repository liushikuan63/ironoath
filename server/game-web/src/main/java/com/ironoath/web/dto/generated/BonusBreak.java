// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 加成明细的一行。zone 必填 —— B06 硬约束 1 要求每个加成都标明落在哪个乘区，玩家侧的「为什么我这么强」与开发侧的「数值为什么算错」都靠它。
 */
public record BonusBreak(
        String source,   // 来源标签，直接展示，如「裴惊澜 Lv60 ★4（主将）」
        long value,   // 定点加成值（×10000），如 6200 = +62%
        BonusZone zone)
{
}
