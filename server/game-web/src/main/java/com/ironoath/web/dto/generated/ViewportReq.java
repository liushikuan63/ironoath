// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET/POST /world/viewport 请求体。chunkVersions 是客户端手里各块的版本，服务端只回更新的那些。
 */
public record ViewportReq(
        int centerX,
        int centerY,
        int zoom,   // 缩放档位（B07 §1：0=世界 / 1=区域 / 2=城市）。缩到 2 时客户端应切换到城内场景
        List<ChunkVersion> chunkVersions)   // 客户端缓存的各块版本。首次请求传空数组
{
}
