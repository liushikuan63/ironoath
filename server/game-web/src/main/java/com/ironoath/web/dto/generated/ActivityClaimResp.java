// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 领取结果。奖励走 `RewardService.grantReward`，背包装不下时由邮件兜底（B04 验收 2 已落地），所以这里没有「失败但没提示」的那条暗路。
 */
public record ActivityClaimResp(
        boolean claimed,   // 本次是否真的发了奖。**同 requestId 重放时也为 true**（幂等重放返回的是同一次领取的结果，而不是再发一份）。
        List<ActivityReward> rewards,   // 本次入账的奖励明细（含溢出转邮件的那部分，`name` 与邮件里的一致）。空数组只在「什么都没发」时出现，而那意味着领取被拒（错误码见下）。
        ActivityState state)   // 领取之后这一行的新状态。领完同轮就要红点熄灭（验收 8），所以状态必须回来 —— 让客户端猜「现在是不是还要亮」等于把判定搬到客户端。
{
}
