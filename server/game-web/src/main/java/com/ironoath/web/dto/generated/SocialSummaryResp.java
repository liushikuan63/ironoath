// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/summary 响应体（B10 §二）。三层社交一屏给全：小队、联盟、国家（B13 接入前恒为 null）。pendingInvites 与 pendingHelps 是红点数据 —— B10 验收 6 要求「一键帮助全部，红点清零」，所以红点数必须由服务端给出而不是客户端自己数列表。
 */
public record SocialSummaryResp(
        SquadView squad,   // 我的小队；未加入为 null
        AllianceView alliance,   // 我的联盟；未加入为 null
        String nationId,   // 我的国家 id（B13 接入前恒为 null）；未加入为 null
        int pendingInvites,   // 待处理的邀请数（红点）
        int pendingHelps,   // 可帮助但未帮助的请求数（红点）。**已经扣掉我帮过的与超出每日额度的**，否则红点会一直亮着而点进去发现什么都做不了
        int helpRemainingToday,   // 我今天还能帮助几次。来源 global.HELP_DAILY_LIMIT，下发是为了让客户端能显示「今日剩余 12 次」而不是自己读配置
        List<SocialEventView> events,   // 未读的社交事件（离线补偿，验收 12）。已读的不重复下发
        long serverNow)   // 服务端时间戳
{
}
