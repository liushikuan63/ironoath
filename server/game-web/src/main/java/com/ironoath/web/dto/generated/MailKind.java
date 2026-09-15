// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 邮件来源类别。只有两类是现在真有生产者的：SYSTEM=运营/客服补发（`POST /ops/mail/send`），OVERFLOW=发奖溢出转补发（`RewardPorts.Mailbox`，B04 验收 2）。战报邮件属 B12 §3 那一档，等战报入口落地再加第三种取值 —— 先声明一个没人发的类型就是「有名字没读者」那一族缺口。
 */
public enum MailKind {
    SYSTEM,
    OVERFLOW
}
