// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.Map;

/**
 * GET /ops/track/recent 的一行：一条**已落库**的埋点事件原样回出来。
 *
 * **为什么要能把事件读出来**：客户端在微信小游戏运行时里打的自检行只存在于开发者工具的 Console 里，
 * 而 IDE 不把它落到任何可读文件（2026-09-16 实测：近期 WeappLog 全 grep 零命中）。于是「模拟器里
 * 到底跑通没有」这件事没有机器可读的出口，只能用人眼看 —— 而人眼看到的不会留下任何证据。
 * 把同一次自检作为事件收下并能读回来，"跑通"才第一次变成可复核的结论。
 */
public record TrackRecentItem(
        String name,   // 事件名，与客户端字典 {@code TrackEvents.ts} 一致。
        String playerId,   // 玩家 id。**可空是设计**：启动与自检这一类事件发生在拿到身份之前，而那一段恰恰是「进都没进就走了」的全部证据。
        long clientTs,   // 客户端时刻，只用于同一批内部的先后顺序。
        long serverTs,   // 服务端落库时刻。排序与窗口都按它算（铁律 5）。
        String traceId,   // 所属请求链路，用来把一条事件还原成一次完整的调用。
        Map<String, String> params)   // 事件参数，值一律字符串（与 TrackEvent.params 同一口径：类型化会让每加一种参数都要改契约）。
{
}
