// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /ops/reward/compensation/resolve 请求：把一笔欠账标成已处理。
 *
 * **为什么只有人工销账、没有自动重投**：一次发放失败的原因大多是「这个玩家此刻放不下」而不是「再试一次就好」，盲目重投会把一次失败变成 N 次重复发放（那是经济口子）。兑付走已经带幂等键与审计的 POST /ops/mail/send，销账时把那封邮件的 id 写进 resolution —— 于是台账留下的是一条可核对的凭证，不是一句「处理过了」。
 */
public record CompensationResolveReq(
        String compensationId,   // 要销的那一条。
        String actor,   // 处理人标识。**不许留空**：「谁把这笔债销掉的」与「谁欠的」同样重要，否则这张表变成一个可以悄悄抹平的地方。
        String resolution)   // 处理说明，期望写成兑付通路留下的凭证（如 mail_xxx）。
{
}
