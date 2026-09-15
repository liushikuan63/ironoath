// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 运维读回的**一封**邮件（B12 §2 的补发核对）。列表带附件明细是刻意的：
 * 「补了什么」正是这一格要回答的问题，而它只有几条（`MailAppService` 把一封的附件夹在 32 条内）。
 * 与崩溃列表**不带堆栈**是同一类取舍的反面 —— 那里一条堆栈 20KB 会打死只读端点，这里一条附件几十字节。
 */
public record OpsMailRow(
        String mailId,   // 补发回执上那个号，原样读回来。
        String playerId,   // 收件人。
        MailKind kind,   // 哪一类（SYSTEM=人工补发，OVERFLOW=发奖溢出）。
        String title,   // 标题。正文刻意**不**进列表：一封溢出邮件的正文把所有附件又列了一遍，列表要的是一屏能扫完的索引；要看正文按 mailId 走玩家侧那条端点。
        String actor,   // 操作者（工单号或运维账号），从 `sourceRef` 的 `ops:` 前缀剥出来。**OVERFLOW 那类是空串** —— 没有人工操作者，与"没查这条字段"是两件事，所以回空串而不是省略。
        String sourceRef,   // 归因原文（`ops:工单-6` / `battle:report-77`）。`RewardContext.sourceRef` 同一条理由：事后追查只认这个字段。
        List<MailReward> rewards,   // 附件明细（已解析过名字）。空数组=纯通知。
        boolean claimed,   // 附件领了没有。**没附件时也是 true**（与玩家侧同一口径，不另立一套）。
        boolean read,   // 玩家读过没有。「补出去了但没人看」是工单要回答的第二问。
        long createdAt,   // 发出时刻（毫秒）。
        long expireAt)   // 过期时刻（毫秒）。**读侧只看得到还没过期的那批**：过期即被清，而本端点扫的就是这份存储 —— 超出保留期的补发历史今天不存在，这是设计的事实，不是查询失败。
{
}
