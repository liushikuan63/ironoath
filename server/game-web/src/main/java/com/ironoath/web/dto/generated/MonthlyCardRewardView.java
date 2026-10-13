// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 带服务器玩家可见名称的月卡奖励预览；id仅作为识别键。
 */
public record MonthlyCardRewardView(
        String type,
        String id,
        String name,
        long count)
{
}
