// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 邮件列表（B12 §二 草案形状）。**读这一次会顺带清理已过期的邮件**（惰性清理），所以「列表短了」不需要一条额外的对账逻辑。
 */
public record MailListResp(
        List<MailView> mails,   // 未过期的邮件，按 `createdAt` 倒序。
        int unreadCount,   // 未读封数。**服务端算**：红点判据与列表同源才不会出现「列表里全读过而红点亮着」。
        int claimedCount)   // 本次列表里「没有附件可领」的封数（含领过的与纯通知的）。给客户端决定要不要显示「暂无可领」这一档 —— 它和 `unreadCount` 一样不许客户端自己数。
{
}
