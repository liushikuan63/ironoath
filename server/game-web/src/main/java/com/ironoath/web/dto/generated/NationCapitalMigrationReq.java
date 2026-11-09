// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/capital/migrate 请求体：迁都免费且无冷却。坐标范围、权限、国王离线代行和驻军状态由服务端按当前状态校验。
 */
public record NationCapitalMigrationReq(
        String requestId,   // 幂等键。
        long x,   // 意向都城横坐标；世界范围和目标格冲突由服务端检查。
        long y)   // 意向都城纵坐标；世界范围和目标格冲突由服务端检查。
{
}
