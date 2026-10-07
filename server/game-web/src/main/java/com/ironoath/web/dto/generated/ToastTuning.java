// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 飘字队列的三个参数（B04 验收 8「多个奖励按顺序播放、不可同时堆叠遮挡」）。**为什么必须下发而不是客户端写死**：这三个数住在 contract/config/global.json（TOAST_GAP_MS / TOAST_MAX_QUEUE / TOAST_STUCK_TIMEOUT_MS），是本仓红线「客户端不抄配置表」覆盖的数值 —— 客户端硬编码一份就等于给队列上限造了第二个真相，运营改表时只有服务端那一半会动。
 */
public record ToastTuning(
        int gapMs,   // 两条飘字之间的间隔毫秒。来源 global.TOAST_GAP_MS
        int maxQueued,   // 队列长度上限，超出丢弃并告警。来源 global.TOAST_MAX_QUEUE
        int stuckTimeoutMs)   // 单条飘字最长播放时间，超时强制推进（降级路径）。来源 global.TOAST_STUCK_TIMEOUT_MS
{
}
