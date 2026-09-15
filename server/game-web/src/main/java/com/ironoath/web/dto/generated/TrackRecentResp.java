// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/track/recent 响应：某个事件名最近的若干条（只读，需运维令牌）。
 *
 * **存在理由还包括给 {@code recentOf}/{@code recentEvents} 一个生产读者**：那两个方法此前只被
 * 单测调用，与 unfulfilledCents()、crashOf() 是同一族 —— 机制在，没人能看。
 */
public record TrackRecentResp(
        String eventName,   // 本次过滤的事件名。**回显它**是为了让"读错名字读到空表"这件事在响应里就能看出来 —— 一个不名自身过滤条件的空数组，与"这个事件真的没发生"完全无法区分。
        int total,   // 当前存储里该事件的总条数（与 listed 分开回，理由同 pay/debt：翻了第一页不等于看到全部）。
        int listed,   // 本响应实际带出的条数。
        List<TrackRecentItem> events)   // 按服务端落库时刻倒序，最多 limit 条。
{
}
