// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /world/march 响应体。durationSec 与 arriveAt 都由服务端算，客户端不参与（B07 禁止项：不要用客户端定时器决定到达）。
 */
public record MarchResp(
        MarchView march,
        int distance,   // 曼哈顿距离（格）。下发它是为了让客户端能显示「距离 37 格」而不必自己算 —— 距离口径必须与服务端一致
        long durationSec,
        long serverNow)
{
}
