// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 研究队列（一次一队列，所以它就是「那一项」而不是数组）。空闲时 `techId` 与 `finishAt` 为 null、两个时刻字段为 0 —— 与 `BuildingView` 的 `startedAt/totalSeconds` 同一条读法。
 */
public record TechQueueView(
        String techId,   // 在研究哪一行；null = 队列空着。
        Long finishAt,   // 完成时刻（服务端毫秒）。到点之后**不靠定时器**推进：下一次读取时才结算（全项目无 `@Scheduled`，与城建 `collectFinished` 同一套惰性结算）。
        long startedAt,   // 开始时刻（毫秒），配合 `totalSeconds` 让客户端能自己画进度条而不必反复请求。
        long totalSeconds,   // 本次研究总时长（秒）。
        long remainingSeconds)   // 服务端算好的剩余秒数（`max(0, finishAt - serverNow)`）。客户端拿 `serverNow` 相减只能算个起点，真正的「还剩多久」以这里为准 —— 绝不为负。
{
}
