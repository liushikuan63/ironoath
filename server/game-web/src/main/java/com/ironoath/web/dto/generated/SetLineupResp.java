// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /hero/lineup 响应体。带 troopCap 是因为统帅值直接决定带兵上限（B06 验收 8），换队后客户端必须立刻刷新这个数字。
 */
public record SetLineupResp(
        LineupView lineup,
        long troopCap,
        long serverNow)
{
}
