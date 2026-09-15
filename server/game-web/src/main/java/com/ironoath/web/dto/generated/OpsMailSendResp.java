// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 补发回执。
 */
public record OpsMailSendResp(
        String mailId,   // 生成出来的邮件 id。工单要贴这个号，玩家报「补发的没收到」时才有据可查 —— 这正是本轮换掉 `TransientRewardPorts` 假 mailId 的理由。
        long expireAt,   // 这一封的过期时刻（按 `MAIL_RETENTION_DAYS` 算）。
        int rewardCount)   // 附件条数（不是数量之和）。回执里给这个数是为了让运维核对「发出去的那封确实带着它以为带着的几条」。
{
}
