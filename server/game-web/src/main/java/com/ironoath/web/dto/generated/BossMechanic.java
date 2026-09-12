// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * BOSS 机制，取值与 stage 表的 bossMechanic 一致（CI 校验）。
 *
 * B09 §二 要求 BOSS「有机制而非纯数值」。当前内核尚未实现这三种机制，所以带机制的关卡会被服务端明确拒绝（NOT_IMPLEMENTED）而不是当普通关打 —— 静默降级会让玩家以为「BOSS 也不过如此」，而机制补上之后同一关突然变难，会被理解成偷偷加强。
 */
public enum BossMechanic {
    NONE,
    REINFORCEMENT,
    SHIELD_PHASE,
    COUNTER_STRIKE
}
