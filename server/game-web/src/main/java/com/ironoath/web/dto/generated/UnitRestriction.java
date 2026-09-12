// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 关卡的兵种限制，取值与 stage 表的 unitRestriction 一致（CI 校验）。
 *
 * **这是入场门槛，不是三星条件之一**（对 B09 §4 字面表述的一处有意偏离）：若把它做成「不满足就少一颗星」，玩家仍然可以带违规阵容进场，限制就成了装饰 —— 而它存在的目的是逼玩家换阵型（B09 §二 的原话）。做成入场门槛才真的逼得到。三星因此是「通关 / 无损 / 限时」。
 */
public enum UnitRestriction {
    NONE,
    NO_SIEGE,
    CAVALRY_ONLY,
    RANGED_ONLY
}
