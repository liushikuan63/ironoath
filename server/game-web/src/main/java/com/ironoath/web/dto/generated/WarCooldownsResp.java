// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /nation/war/cooldowns 的响应：**我国**对全部仍在冷却中的目标的剩余秒数。
 *
 * **为什么单开一个口而不是塞进 `WarStatusResp`**：#755 现读代码确认 —— `WarStatusResp` 是**全服一份**的视图（谁读都是同一份），而冷却是**按国家那一对**算的：一个全服标量装不下 N×N 个冷却状态。所以按请求者下发一张只含「我国 × 各目标」的表，目标这一维由每一行自己带。
 *
 * **为什么只回还没解禁的**：面板的用法是"把候选目标里还在冷却的那些灰掉并写下还要等多久"，解禁的目标不需要任何标注（它们本来就该是亮的）。
 */
public record WarCooldownsResp(
        List<WarCooldownView> cooldowns,   // 我国对各个仍在冷却中的目标。按国家表的稳定顺序，不按剩余时间排（同一份库存上每次读出来的顺序一致，"为什么这一条排前面"才可复现）。
        long serverNow)   // 服务端时间戳。
{
}
