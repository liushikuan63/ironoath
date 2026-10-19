// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 客户端网络层与时钟校准的参数包。**为什么由服务端下发**：这些数住在 `global.json` 里，而**客户端没有配置表加载器**（`global.json` 不进包，客户端只有生成的类型、没有表数据）⇒ 客户端拿不到表，要么写死一份、要么从服务端拿。写死的那份会与服务端悄悄漂移，症状是「客户端发 50 条、服务端按 10 条攒批」这类**谁都没报错**的偏差（与 `TrackPolicy` 随 `/ops/app/version` 下发是同一条理由）。**它不是「服务端的重试参数」**：它是**客户端网络层与时钟校准的策略**，服务端读同一张表只是为了让两端口径一致。
 */
public record NetworkTuning(
        int maxRetryAttempts,   // 最大重试次数。来源 global.NET_RETRY_MAX_ATTEMPTS
        int retryBaseDelayMs,   // 首次退避时长（毫秒）。来源 global.NET_RETRY_BASE_DELAY_MS
        int retryMaxDelayMs,   // 退避上限（毫秒）。来源 global.NET_RETRY_MAX_DELAY_MS
        int offlineQueueMax,   // 离线队列上限（条）。来源 global.NET_OFFLINE_QUEUE_MAX
        int requestTimeoutMs,   // 单请求超时（毫秒）。**它不在 global.json 里**（表里没有这一行）—— 这是客户端自己的超时上限，与表无关；放在这里是为了让客户端的「网络层参数」集中在一处下发，而不是散在客户端各处写死。
        int timeSyncAlphaFixed,   // 偏移平滑系数（定点 ×10000）。来源 global.TIME_SYNC_ALPHA
        int timeSyncJitterFactorFixed,   // 抖动剔除倍数（定点 ×10000）。来源 global.TIME_SYNC_JITTER_FACTOR
        int timeSyncInitialBestRttMs)   // 首次采样前的最佳 RTT 兜底（毫秒）。来源 global.TIME_SYNC_INITIAL_BEST_RTT_MS
{
}
