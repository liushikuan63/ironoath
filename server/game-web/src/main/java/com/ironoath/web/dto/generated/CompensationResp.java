// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/reward/compensation 响应：发奖失败欠下的账（只读，需运维令牌）。
 *
 * **为什么必须有出口**：B04 验收 7 要求「grantReward 异常时不静默：落日志 + 补偿队列有记录」。日志那一半早就有了，记录这一半此前只在内存里 —— 重启之后连「欠过谁」都查不出来，玩家投诉变成无据可查。只读、不改任何状态，所以这条端点不新增任何写路径（与 /ops/pay/debt 同一条纪律）。
 */
public record CompensationResp(
        long pendingCount,   // 还没处理的总笔数（不受 limit 影响）。**对账口径与支付负债一样：这个数必须最终归零**。与 listed 分开给，是为了不让「只列了 20 条」被读成「一共只有 20 笔」。
        int listed,   // 本响应实际带出的条数（受 limit 约束）。
        List<CompensationRow> rows)   // 待处理的欠账，**最旧的在前**（运维按先欠先处理的顺序清账）。
{
}
