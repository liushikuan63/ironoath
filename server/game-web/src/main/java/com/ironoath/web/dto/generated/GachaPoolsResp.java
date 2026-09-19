// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 全部可抽卡池的列表 —— 抽卡界面第一屏就靠它（台账 #294）。顺序照 gacha 表的行序，客户端不自己排。
 */
public record GachaPoolsResp(
        List<GachaPoolSummary> pools,
        long serverNow)
{
}
