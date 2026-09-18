// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /social/block 与 /social/unblock 的请求体（B22 §一 3）。**拉黑不是封禁**：它只切断交流（私聊拒收 + 频道消息过滤），不改变任何战斗 / PVP / 外交关系 —— B13 的冲突优先级写着国家 > 联盟 > 小队，私人恩怨不该凌驾其上。
 */
public record BlockReq(
        String requestId,   // 幂等键。重复拉黑同一个人应当是幂等的结果（在名单里就是成功），但重放不该产生两条账。
        String targetPlayerId)   // 要拉黑 / 取消拉黑的人。
{
}
