// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /level-reward/claim 的响应。**回实际发出的奖励明细**而不是只回 ok：客户端要弹「获得 木材×N」，而那份明细必须来自发放器真正发出去的东西（超出仓储上限、转邮件补发的那部分也在里面），不是客户端按表自己猜的。
 */
public record LevelRewardClaimResp(
        long level,   // 刚领掉的那一级。
        List<LevelRewardItem> rewards,   // 本次实际发放的奖励明细。
        int claimableCount,   // 领完之后还剩几级可领（与 list 的同一字段同源）。
        long serverNow)   // 服务端时刻。
{
}
