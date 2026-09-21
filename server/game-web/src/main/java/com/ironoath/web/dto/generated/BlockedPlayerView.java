// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 名单里的一位玩家。id 与显示名分开：前者是机器用的，后者是给人看的。
 */
public record BlockedPlayerView(
        String playerId,   // 玩家 id（发 /social/unblock 时用它）。
        String name)   // 显示名（服务端解析好的昵称）。查不到存档时服务端回「未知玩家」—— **绝不回 id**（#323 同一条口径）。
{
}
