// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /quest/claim 请求体：领取一条已完成任务的奖励（B12 §1「奖励走 B04 的 grantReward」）。 heroChoice 是「三选一」那类奖励的选择结果，一并发在这里而不是单开端点的理由：领奖本来就是一次性操作（幂等键 + 已领标记都在这一条路径上），把选择挂上去就不再需要第二份「是否已经选过」的状态。
 */
public record QuestClaimReq(
        String requestId,   // 幂等键。重放一次领取不该再发一份奖励 —— 而「领了没到账」的玩家会自然地再点一次，所以这个键是常规路径而不是边缘情况。
        String questId,   // 要领取的任务 id。未完成 / 已领取 / 前置未完成都会被拒（三种各有各的业务码）。
        String heroChoice)   // 从候选武将里挑的那一个（B06 §1「主线赠送：首日必得 1 名 SR」）。只有带候选列表的任务需要它：不传时若该任务有候选，服务端拒绝并回可用候选（而不是替玩家默认挑一个 —— 那会让「三选一」变成「系统选中一个」）。没有候选的任务传了它也会被拒，理由同上：多传的东西静默忽略会让客户端以为自己选上了。
{
}
