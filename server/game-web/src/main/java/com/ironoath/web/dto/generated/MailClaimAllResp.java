// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一键领取的结果（B12 §二 草案的 `MailClaimAllResp`）。三个数各说各的：领了几封、领到什么、哪几封没领上。
 */
public record MailClaimAllResp(
        int claimed,   // 这次成功领到的**封数**（不是附件条数 —— 一封可以有三条附件，两个数会打架）。
        List<MailReward> rewards,   // 这次实际入账的奖励明细（按 type+id 聚合）。与 `QuestClaimResp.rewards` 同一条口径：写的是入账量，不是申请量。
        List<MailClaimFailure> failed)   // 没领上的邮件（含原因）。空数组表示全部领上了。
{
}
