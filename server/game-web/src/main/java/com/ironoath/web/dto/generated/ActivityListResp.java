// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 当前玩家可见的活动与签到列表。各分类按服务端配置顺序显示；已停用的旧登录奖励仅在原窗口内合法待领时保留。
 */
public record ActivityListResp(
        List<ActivityView> activities,   // 逐行视图。含已过期的行（`state=EXPIRED`）—— 列表里要看得见「哪个活动结束了」，这与邮件过期即消失不同：活动的窗口是**轮换**的，玩家需要知道下一轮什么时候开始。
        long serverNow,   // 服务端当前时刻（毫秒）。剩余时间由它与 `windowEndAt` 相减得出，不用客户端本地时钟（铁律 5）。
        int claimableCount)   // 有可领奖的行数。红点叶 `activity/claimable` 与它是同一个判定（`> 0` 即亮）—— 客户端不自己数一遍。
{
}
