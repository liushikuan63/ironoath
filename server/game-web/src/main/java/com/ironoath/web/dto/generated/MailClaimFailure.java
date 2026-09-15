// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一封没领成功的邮件。**它不会被删、也不会被标记成已领**（B12 验收 5：失败邮件保留），玩家下一次一键领取还会再试。
 */
public record MailClaimFailure(
        String mailId,   // 哪一封。
        String reason)   // 为什么没领上（人看得懂的一句）。写侧失败时服务端同时打 WARN —— 玩家的「领不到」与运维的「为什么」必须是同一句话，不能一个给人一个给日志。
{
}
