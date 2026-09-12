// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 地图实体类型。与 game-core 的 WorldGenerator.EntityType 一致，另外多一个 MARCH —— 行军不是格子上的静态内容而是移动实体，所以生成器不产出它，只由 viewport 组装时按当前位置投影进来。BUILDING 预留给 B10 联盟建筑。
 */
public enum WorldEntityType {
    EMPTY,
    CITY,
    MONSTER,
    RESOURCE,
    MARCH,
    BUILDING
}
