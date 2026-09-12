// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 客户端的埋点攒批策略，由服务端下发（B16 §3：10 条或 10 秒触发）。
 *
 * **为什么由服务端下发而不是客户端写死**：客户端没有配置表加载器，写死 10 条 / 10 秒就是铁律 1 禁止的硬编码；而这两个数字是运营口径 —— 分析侧发现漏斗数据太粗时会想把批调小，那时不该要求客户端发版。
 *
 * **为什么搭 AppVersionResp 的车而不是单开端点**：版本检查是每个会话的第一个请求，它本身就在下发「这个客户端应当如何行为」（要不要强制更新、在不在灰度里），埋点策略属于同一类。多一个端点意味着多一次弱网下的往返，而弱网恰恰是埋点最需要工作的场景。
 */
public record TrackPolicy(
        int maxBatchSize,   // 攒够多少条发一批。来源 global.TRACK_BATCH_MAX_SIZE。客户端据此决定何时触发一次上报请求；服务端按同一个值攒批落库，两边同源所以不会出现「客户端发 50 条而服务端按 10 条拒收」这种漂移。
        int flushSeconds)   // 最长攒多少秒。来源 global.TRACK_BATCH_FLUSH_SECONDS。这一路是给低频玩家兜底的：只按条数的话，一个点两下就退出的玩家那两个事件永远发不出去，而「进来就退」正是流失分析最需要的样本。时间窗从队首事件算起，不是从上一次发送算起。
{
}
