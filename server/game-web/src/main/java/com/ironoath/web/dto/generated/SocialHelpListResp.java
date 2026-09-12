// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/helpRequests 响应体：可互助的请求列表 + 同一个数算出来的红点。两者必须由服务端同一次遍历给出 —— 列表与徽标分开算早晚漂移，表现是「红点说 5 条、点进去只有 3 条，一键帮助却帮了 5 次」。
 */
public record SocialHelpListResp(
        List<HelpRequestView> requests,   // 同组织里未过期、不是自己发的请求，**含已经帮过的**（每行的 alreadyHelped 标出来）。列表里放已经帮过的是因为玩家要知道「我帮过谁」，而不只是「还有谁能帮」。
        int pendingHelps,   // 徽标数：列表里 alreadyHelped=false 的行数，再按今日剩余额度截断。与 SocialSummaryResp.pendingHelps 同源同值
        int helpRemainingToday,   // 今日还能帮几次，与摘要里那个数是同一个来源
        long serverNow)
{
}
