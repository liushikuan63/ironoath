// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /player/frame 请求体：佩戴或卸下。【frameId】为 null 表示卸下 —— 与「戴一个空框」区分开。
 */
public record WearFrameReq(
        String requestId,
        String frameId)   // 要戴上的头像框 id；null = 卸下
{
}
