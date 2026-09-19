// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 离线汇总的边界与阈值。「自上次登录以来」的起点是 previousLoginAt（**不是** profile.lastLoginAt —— 那个值在本次登录时已被推进成现在，拿它算出来永远是 0）。两个阈值来自 global 表（OFFLINE_REPORT_MIN_IDLE_MINUTES / OFFLINE_REPORT_MIN_ITEMS），客户端只按它们判定「值不值得弹」，不自己填数。
 */
public record OfflineReportView(
        Long previousLoginAt,   // 上次登录时刻（服务端毫秒时间戳）。**新号是 null** —— 没有「上一次」可言，此时汇总没有起点，客户端不该弹
        long minIdleMinutes,   // 距上次登录不足这么多分钟就不打扰（来源：global.OFFLINE_REPORT_MIN_IDLE_MINUTES）
        int minItems)   // 至少要有这么多条可汇总的明细才值得弹（来源：global.OFFLINE_REPORT_MIN_ITEMS）
{
}
