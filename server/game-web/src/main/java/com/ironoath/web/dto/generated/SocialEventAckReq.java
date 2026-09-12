// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /social/ackEvents 请求体（标记事件已读）。离线补偿的事件必须能被标记已读，否则每次上线都会重新收到同一批（验收 12）。
 */
public record SocialEventAckReq(
        String requestId,   // 幂等键
        List<String> eventIds)   // 要标记已读的事件 id。空数组表示全部标记
{
}
