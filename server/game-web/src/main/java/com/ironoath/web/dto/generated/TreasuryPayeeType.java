// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国库支出的落点类型（B13 §3 的三个用途在 2026-09-11 的裁决里收敛成两类）。PLAYER = 官职俸禄，钱发给某个玩家（走发放器，GOLD 与国库资金 1:1）；SINK = 国家科技 / 国战增益，钱被子系统消耗、没有收款人。**不允许「其它」**：一个自由字符串的支给对象会让「支给谁」写成任意东西，而国库日志存在的理由就是纠纷发生时能查。
 */
public enum TreasuryPayeeType {
    PLAYER,
    SINK
}
