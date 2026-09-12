// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /gacha/history 响应：最近 50 次抽取记录（B15 §三 合规要求）。
 *
 * 上限 50 来自 B15 文档；服务端日志保留 90 天（global.GACHA_LOG_RETENTION_DAYS），两者是不同的口径 —— 50 条是给玩家看的窗口，90 天是给监管与客服取证的窗口。
 */
public record GachaHistoryResp(
        List<GachaRecord> records,   // 最近 50 条，按时间倒序（最新的在前）。
        int retentionDays,   // 日志保留天数，来自 global.GACHA_LOG_RETENTION_DAYS。**下发它是为了合规可核**：监管问「你们保留多久」时，答案应当能在产品里被玩家和检查者同时看到，而不是只在某个文档里。
        long serverNow)   // 服务端时间戳。
{
}
