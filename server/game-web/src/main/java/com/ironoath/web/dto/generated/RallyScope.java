// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 集结的发起层级。三级的人数上限分别取 global.RALLY_MAX_SIZE_SQUAD(5) / _ALLIANCE(20) / _NATION(50)。国家层在 B13 落地前不会产生，枚举先留位以免届时改协议。
 */
public enum RallyScope {
    SQUAD,
    ALLIANCE,
    NATION
}
