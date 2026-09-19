// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 奖励类型。取值与 `battle_pass` 表的 `fieldTypes` 声明一致（`RESOURCE` / `ITEM`）—— 只有这两种，因为战令的奖励必须进得了邮件（赛季结束未领的档位要按档补发，而邮件附件只能是这两种）。
 */
public enum BattlePassRewardType {
    RESOURCE,
    ITEM
}
