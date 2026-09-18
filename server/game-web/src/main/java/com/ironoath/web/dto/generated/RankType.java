// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 榜的类型（B23 §一 1）。与 SeasonSettlement.Board 的四个值一一对应 —— 刻意不复用那个枚举：那个是结算侧的领域类型，客户端不该依赖结算的内部形状。
 */
public enum RankType {
    POWER,
    KILL,
    ALLIANCE,
    NATION
}
