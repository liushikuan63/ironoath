// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 取消当前研究。只有 `requestId`：一次一队列 ⇒ 队列里那一项就是被取消的那一项。
 */
public record TechCancelReq(
        String requestId)
{
}
