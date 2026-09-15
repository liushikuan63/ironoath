// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一封邮件在玩家视图里的一行。**只包含已经没过期且没被领走的判断所需字段**：过期与领取都是服务端的判定，客户端不参与（铁律 2）。
 */
public record MailView(
        String mailId,   // 服务端生成的邮件 id。客户端把它回传给 `POST /mail/read`；**一键领取不回传它**（见文件头的约束 2）。
        MailKind kind,   // 哪一类邮件。
        String title,   // 标题。溢出邮件由服务端生成「背包满了，先给你存着」这类话术 —— 文案不在客户端硬编码，理由与 `AppVersionResp.notice` 一致。
        String text,   // 正文。**B04 验收 2 要求写明溢出数量与原因**，所以这一格对 OVERFLOW 不是可选装饰：它要说清哪几项溢出、溢出了多少、为什么。
        List<MailReward> rewards,   // 附件明细。空数组表示这是一封纯通知（无附件可领，`claimed` 恒为 true 的语义就是「没有东西可领」）。
        boolean claimed,   // 附件是否已经领过。**没附件时为 true**（「无附件可领」与「领过了」对玩家是同一句话，分成两个字段就会有两个家）。
        boolean read,   // 读过没有。未读计数由服务端算（`MailListResp.unreadCount`），客户端不自己数 —— 数法一旦不同，红点与列表就会各说一套。
        long createdAt,   // 生成时刻（毫秒）。排序按它倒序。
        long expireAt,   // 过期时刻（毫秒）= createdAt + `global.MAIL_RETENTION_DAYS`。**下发到期时刻而不是剩余天数**：时钟以服务端为准（铁律 5），而「还剩几天」要玩家自己减。
        String sourceRef)   // 归因引用：OVERFLOW 那类写的是发奖来源（quest id、战报 id…），SYSTEM 写操作者。`RewardContext.sourceRef` 的同一条理由 —— 「玩家东西多了/少了」唯一可追的路径就是这个字段。
{
}
