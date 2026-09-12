// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /item/use 请求体。加速类道具必须给 targetId（B04 §4：弹出可选目标，选择后应用）。
 */
public record ItemUseReq(
        String requestId,
        String itemId,
        long count,   // 一次使用几个。资源类支持一次开 N 个（B04 §4）
        String targetId)   // 加速类道具的目标建筑实例 id
{
}
