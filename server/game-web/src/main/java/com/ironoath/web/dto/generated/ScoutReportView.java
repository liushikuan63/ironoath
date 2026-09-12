// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一份敌情报告。<b>必须带 errorFixed 与情报时间</b>：不带误差幅度，玩家会把带误差的数字当精确值来做决策，那比没有情报更糟；不带时间，玩家无法判断这份情报还能不能用（B07 验收 9：超时置灰）。
 */
public record ScoutReportView(
        String reportId,
        Coord target,
        String targetId,
        int targetLevel,   // 目标等级。<b>这是报告里唯一无误差的字段</b> —— 等级从外观就能看出来，给它加误差只会让玩家觉得系统在耍他
        long createdAt,
        long expiresAt,
        boolean expired,   // 是否已过期。true 时 UI 必须置灰且不可用于决策
        long remainingMs,
        long errorFixed,   // 误差幅度（定点）。UI 显示成「±12%」
        List<ScoutMetric> metrics,
        Long seed,   // 误差种子。下发是为了可复现（客服核查「这份情报为什么这么离谱」），不是为了让客户端重算
        long serverNow)
{
}
