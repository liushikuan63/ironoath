// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 所有养成操作（升级/升星/觉醒/技能/装备/合成）的统一响应：改完之后的武将状态。统一成一个类型是因为客户端对这六种操作的界面反馈完全相同（刷新属性条 + 播一次成长动效），拆成六个响应类型只会让客户端写六份一样的代码。
 */
public record HeroGrowResp(
        HeroView hero,
        List<ItemCount> consumed,   // 实际消耗的道具/碎片。客户端据此播放扣减动画，不要自己算
        long serverNow)
{
}
