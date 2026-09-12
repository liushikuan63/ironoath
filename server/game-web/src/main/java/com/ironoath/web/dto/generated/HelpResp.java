// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 帮助类操作的响应体（单次帮助与一键帮助共用）。
 */
public record HelpResp(
        int helped,   // 本次实际帮助了几条。一键帮助时可能少于可帮助项数 —— 每日额度用完就会停，照实返回而不是报错
        int skipped,   // 跳过的条数（我已帮过的 + 额度不足的）
        int helpRemainingToday,   // 帮助后我今天还剩几次
        int pendingHelps,   // 帮助后的红点数。**验收 6 要求「红点清零」**，所以必须下发帮助后的值让客户端能直接对上
        long speedupGranted,   // 本次给对方合计削减的时长比例（定点）。受 global.HELP_SPEEDUP_TOTAL_CAP 约束，所以它可能小于「帮助次数 × 1%」—— 下发实际值而不是让客户端自己乘，否则玩家会以为被吞了
        long serverNow)   // 服务端时间戳
{
}
