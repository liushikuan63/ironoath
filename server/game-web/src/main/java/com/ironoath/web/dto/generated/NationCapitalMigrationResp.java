// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/capital/migrate 的结果：服务端确认保存后的国家都城坐标。
 */
public record NationCapitalMigrationResp(
        String nationId,   // 迁都所属国家标识。
        long capitalX,   // 服务端确认后的都城横坐标。
        long capitalY,   // 服务端确认后的都城纵坐标。
        long serverNow)   // 服务端时间戳。
{
}
