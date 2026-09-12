// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 资源快照（城建列表用）。字段语义与 player.schema.json 的 ResourceState 一致。
 */
public record ResourceStateView(
        long current,
        long cap,
        long protectedAmount,
        long perHour,
        long lastSettle)
{
}
