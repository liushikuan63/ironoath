// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/viewport 响应体。chunks 只含版本变化的块；staleChunks 列出客户端缓存已过期的块键（含因 payload 上限被截断而需要再取的块）。
 */
public record ViewportResp(
        long serverNow,
        List<ChunkData> chunks,
        List<String> staleChunks,
        List<String> exploredChunks,   // 本次视口内已探索（无迷雾）的块。不在列表里的块客户端要盖黑色遮罩（B07 §3）
        List<String> fogChunks)   // 本次视口内仍是迷雾的块。<b>这些块的 entities 一律不下发</b> —— 下发了就等于把迷雾做成了纯客户端遮罩，改一下客户端就能透视全图
{
}
