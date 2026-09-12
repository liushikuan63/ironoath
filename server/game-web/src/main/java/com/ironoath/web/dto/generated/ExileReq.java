// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /world/exile 请求体。B08 §5 的流亡迁城：免费随机落点 + 落地后一段时间免战，且有冷却。玩家不能指定落点（能指定就等于可以精准迁到仇人隔壁或资源点正上方），所以除了幂等键什么都没有。
 */
public record ExileReq(
        String requestId)
{
}
