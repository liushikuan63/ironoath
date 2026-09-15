// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 运营/客服补发一封邮件（`POST /ops/mail/send`，需 `X-Ops-Token`）。**这是唯一一条能凭空给玩家发奖励的通路，所以它同时受三道闸门约束**：运维令牌、幂等键、审计日志（谁发的、发给谁、发了什么，全部进 WARN 级日志）。
 */
public record OpsMailSendReq(
        String requestId,   // 幂等键。工单系统按时钟重投是常态，没有它同一笔补偿会发两遍。
        String playerId,   // 收件玩家。
        String title,   // 标题。
        String text,   // 正文（写清为什么补、补什么 —— 玩家拿到钱却不知道原因会变成投诉）。
        List<MailReward> rewards,   // 附件。允许为空数组（纯公告）。
        String actor)   // 操作者标识（工单号或运维账号）。与 `POST /ops/config/reload?actor=` 同一条要求：改了别人东西的操作必须能追到人。
{
}
