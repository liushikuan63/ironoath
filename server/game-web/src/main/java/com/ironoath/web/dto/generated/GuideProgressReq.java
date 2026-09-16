// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 上报一步的结果。
 */
public record GuideProgressReq(
        String stepId,   // 玩家针对哪一步上报。服务端会校验它**是不是当前那一步**（`GUIDE_STEP_OUT_OF_ORDER`）：不校验顺序就等于允许重放刷进度。
        GuideAction action,   // 做完还是跳过。
        String requestId)   // 幂等键，与任务/邮件/活动领奖同一条要求（弱网重投不该把两步并作一步）。
{
}
