// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一个 chunk 的完整内容。<b>版本号是增量下发的核心</b>：客户端上报手里的版本，服务端只回版本更高的块（B07 验收 6）。
 */
public record ChunkData(
        String key,   // chunk 键，格式 "cx:cy"
        long version,
        List<WorldEntity> entities,
        Boolean truncated)   // 本块因 payload 上限被截断（实体没发完）。<b>必须下发</b>：客户端据此立刻再请求一次，否则玩家会看到一个「明明有怪却显示为空地」的地图，而他没有任何线索知道为什么
{
}
