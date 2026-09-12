// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /world/searchTargets 请求体（B08 §8）。
 */
public record SearchTargetsReq(
        int radius,   // 搜索半径（格）。超过 SEARCH_MAX_RADIUS 会被截断到上限并记录日志 —— 不拒绝，因为玩家拖动滑块时很容易越界，拒绝会让他以为搜索坏了
        Integer maxCount)   // 最多返回几个；null 表示用服务端默认值
{
}
