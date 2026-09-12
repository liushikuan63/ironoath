// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 当前规则阶段。取值与 game-core 的 SeasonTimeline.Phase 逐一对应。
 *
 * 注意它是**规则阶段**而不是**目标阶段**：season 表里的五段（开垦/立盟/争锋/问鼎/结算）是目标阶段，而这里的 PREPARE/EXPAND/... 决定「能不能 PVP、王城是否开放、是否结算」。立盟期与争锋期是两个目标、同一条 EXPAND 规则，所以两者不能按序号对齐 —— 映射走 season 表的 rulePhase 列。
 */
public enum SeasonPhase {
    PREPARE,
    EXPAND,
    CAPITAL_WAR,
    SETTLE,
    REST
}
