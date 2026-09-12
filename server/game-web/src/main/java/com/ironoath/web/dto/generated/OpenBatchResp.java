// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /item/openBatch 响应体。results 已按稀有度聚合排序（B04 验收 3）。seed 回传是为了可复现：客服接到「我开了 100 个什么都没有」的申诉时，用 (seed, count) 重跑 ChestOpener 就能还原当时的每一次抽取。
 */
public record OpenBatchResp(
        long consumed,   // 实际消耗的宝箱数量
        List<RewardItemView> results,   // 抽到并成功入账的奖励
        List<RewardItemView> overflow,   // 资源超上限或背包满而装不下的部分，已转邮件（B04 验收 2）
        String mailId,   // 溢出转邮件的邮件 id，无溢出时为 null
        long seed,   // 本次开箱使用的随机种子。由服务端生成，客户端无法影响（B04 禁止项：不得在客户端本地开箱）。
        long serverNow)
{
}
