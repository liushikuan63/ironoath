// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 领取结果。奖励走 `RewardService.grantReward`，背包装不下时由邮件兜底（B04 验收 2 已落地），所以这里没有「失败但没提示」的那条暗路。
 */
public record ActivityClaimResp(
        boolean claimed,   // 本次领取是否被接受。同请求重复提交返回业务拒绝，不再次发奖；客户端随后重新读取列表、余额和红点确认权威状态。
        List<ActivityReward> rewards,   // 本次直接入账的奖励明细。签到溢出金币转邮件并由message说明，全部转邮件时明细可以为空；普通活动沿用已有奖励回执口径。
        ActivityState state,   // 领取之后这一行的新状态。领完同轮就要红点熄灭（验收 8），所以状态必须回来 —— 让客户端猜「现在是不是还要亮」等于把判定搬到客户端。
        String message)   // 可选的服务端到账说明，区分入余额与转邮箱。旧回应缺少时按已有奖励明细显示。
{
}
