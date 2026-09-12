// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 玩家选择的到达行为。与 TargetType 分开：目标是什么由地图决定，做什么由玩家决定。
 */
public enum MarchAction {
    ATTACK,
    GATHER,
    STATION,
    SCOUT,
    GARRISON
}
