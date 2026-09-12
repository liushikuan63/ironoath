// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 资源数量条目。用数组而不是 Map 是为了让客户端能保持配置表顺序展示。
 */
public record ResourceAmount(
        ResourceType type,
        long amount)
{
}
