// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /player/frames 响应体：**全部**头像框（含没拥有的）。没拥有的也要下发 —— 让玩家看见有什么可拿，正是收集类外观存在的意义（与商店把等级不够的货也列出来同一条口径）。
 */
public record AvatarFrameListResp(
        List<AvatarFrameView> frames,
        long serverNow)
{
}
