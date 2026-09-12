// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 资源类型枚举。取值必须与 contract/config/resource.json 的 rows[].id 完全一致（CI 校验）。
 *
 * <p>取值与配置表 {@code resource} 的 id 集合强制一致，由生成器在 CI 中校验。
 */
public enum ResourceType {
    WOOD,
    STONE,
    IRON,
    GRAIN,
    GOLD,
    STAMINA
}
