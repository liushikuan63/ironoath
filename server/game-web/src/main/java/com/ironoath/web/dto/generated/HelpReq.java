// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /social/help 请求体（帮助某人一次）。
 */
public record HelpReq(
        String requestId,   // 幂等键。帮助会消耗我的每日额度并加速对方，重放等于双倍扣额度
        String helpRequestId)   // 要帮助的请求 id（HelpRequestView.requestId）
{
}
