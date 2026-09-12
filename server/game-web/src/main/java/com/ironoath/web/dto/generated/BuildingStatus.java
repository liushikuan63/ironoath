// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 建筑状态。与 game-core 的 BuildingStatus 枚举一一对应。
 */
public enum BuildingStatus {
    IDLE,
    UPGRADING,
    PAUSED
}
