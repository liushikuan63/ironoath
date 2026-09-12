// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /quest/claim 的响应。**回实际发出的奖励明细**而不是只回 ok：客户端要弹「获得 X×N」，而那份明细必须来自发放器真正发出去的东西（装不下转邮件的那部分也在里面），不是客户端自己按表猜的。
 */
public record QuestClaimResp(
        String questId,   // 领的哪一条任务。
        List<QuestReward> rewards,   // 本次实际发放的奖励明细（已按 type+id 聚合）。
        int claimableCount,   // 领取之后还剩几条可领。与 list 的同一字段同源，省掉客户端再查一次列表。
        long serverNow)   // 服务端时刻。
{
}
