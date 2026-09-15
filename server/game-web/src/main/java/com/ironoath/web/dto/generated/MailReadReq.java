// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 把一封邮件标成已读。写接口所以带幂等键 —— 重复标已读没有副作用，但 `release` 的路径仓库里已经有了，不用它反而要多解释一句为什么这里例外。
 */
public record MailReadReq(
        String requestId,   // 幂等键。
        String mailId)   // 要标已读的那一封。
{
}
