// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 标已读的回执：只回新的未读封数。
 */
public record MailReadResp(
        String mailId,   // 回显被标的那一封（客户端据此把那一行的粗体去掉，不用重拉列表）。
        int unreadCount)   // 标完之后的未读封数。
{
}
