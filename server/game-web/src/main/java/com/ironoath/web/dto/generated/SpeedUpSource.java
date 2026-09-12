// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 加速来源。免费与付费共用同一接口，用本字段区分，供埋点与防刷使用（B03 §3）。AD 有每日次数上限，GOLD 走扣费。
 */
public enum SpeedUpSource {
    AD,
    ALLIANCE,
    SQUAD,
    ITEM,
    GOLD
}
