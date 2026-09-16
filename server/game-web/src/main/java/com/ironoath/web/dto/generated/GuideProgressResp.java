// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 上报结果。三个字段各说各的：有没有推进、是否走完、下一步是哪一步。
 */
public record GuideProgressResp(
        boolean advanced,   // 本次上报是否真的推进了进度。**状态没达成时为 false**（而不是报错）—— 玩家点了「我做完了」但主城还没升到 2 级是正常情况：引导要留在这一步等他。
        boolean finished,   // 全部步骤已走完（或被跳完）。客户端据此收起引导层，不再弹下一步。
        Long nextStepIndex)   // 推进之后该做哪一步；`finished` 为 true 时为 null。
{
}
