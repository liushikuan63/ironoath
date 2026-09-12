// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /ops/track/batch 请求体。一次上报一批（B16 §3：10 条或 10 秒触发）。
 *
 * **不带 requestId**：见本文件 description 的约束 2。
 */
public record TrackBatchReq(
        List<TrackEvent> events)   // 本批事件，按发生顺序。批大小上限来自 global.TRACK_BATCH_MAX_SIZE，服务端按 global.PERF_PAYLOAD_MAX_BYTES 校验体积 —— 客户端把上限调大而服务端不同步的话，这一批会在网关就被拒掉，症状是「埋点全丢」而不是「部分丢」。
{
}
