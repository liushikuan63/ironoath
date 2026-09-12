// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /ops/app/version 请求体：客户端启动时报上自己的版本与玩家 id。
 */
public record AppVersionReq(
        String clientVersion,   // 客户端自报版本，点分数字（形如 1.4.0）。**服务端按段比较而不是按字符串比较**：字符串序里 "1.10.0" < "1.9.0"，于是 1.9 之后的所有版本都会被判为更旧，全服被要求强制更新到一个不存在的版本 —— 那是一个会直接停服的 bug，而它在 1.9 之前永远测不出来。
        String playerId)   // 玩家 id，未登录时为 null。**灰度按它做稳定哈希**，所以未登录的人不进灰度：灰度批次里的崩溃必须能归因到具体玩家，否则那 5% 里发生的事无法排查。null 与空串在这里同义，都读作「还没有玩家身份」。
{
}
