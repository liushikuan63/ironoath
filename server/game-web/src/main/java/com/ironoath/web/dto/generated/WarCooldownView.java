// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条「我国对某个目标还要等多久」。**只对请求者本国算**：冷却判据是那一对两国之间最近那一场（`WarStore#findLatestBetween`，双向对称），所以同一个目标在两边都会出现——防守方看到的是「对方还在冷却」，这正是设计意图（同一对两国在冷却期内靠乒乓互宣刷击杀，是 #754 那一族要防的形状）。
 */
public record WarCooldownView(
        String targetNationId,   // 目标国家 id。**不得直接上屏**。
        String targetNationName,   // 目标国名（服务端下发）。缺名时面板给回退语，不许印 id。
        long remainingSec)   // 还差多少秒解禁。**恒为正**：解禁的目标不出现在这份表里（一份满是 0 的表只是噪声）。
{
}
