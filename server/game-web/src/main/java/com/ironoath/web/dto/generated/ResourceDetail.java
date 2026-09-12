// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 单一资源的明细：当前状态 + 产出分解 + 是否满仓。
 */
public record ResourceDetail(
        ResourceType type,
        long current,
        long cap,
        long protectedAmount,   // 受保护不可掠夺量（B04 验收 6）
        long perHour,   // 实际每小时产量，必须等于 breakdown 各行之和
        long lastSettle,
        boolean full,   // 是否已满仓停产。UI 据此显示红色「已满」警告（B04 验收 1/11）
        List<OutputBreak> breakdown)
{
}
