// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /player/frame 响应体：操作之后的完整框列表（客户端照它重画，不自己改本地状态）。
 */
public record WearFrameResp(
        List<AvatarFrameView> frames,
        long serverNow)
{
}
