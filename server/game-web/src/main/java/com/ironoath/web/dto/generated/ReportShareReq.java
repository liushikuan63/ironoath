// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /battle/share 请求体（B22 §一 2）。**分享不发奖励**：B15 禁止「诱导分享解锁奖励」，所以这里没有、也不该有 reward 字段 —— 分享就是把自己打过的一场战报贴到小队/联盟频道里。
 */
public record ReportShareReq(
        String requestId,   // 幂等键。分享重放会让频道里出现两条一样的分享，而聊天限流恰好会把第二条拦成「发得太快」—— 玩家看到的是「我只分享了一次，却提示刷屏」。
        String reportId,   // 要分享的战报 id。**必须是自己的**：服务端按 `ownerId` 校验，别人的回 `REPORT_NOT_OWNED`。战报里有自己的兵力构成与坐标，替别人分享等于替别人公开。
        ShareChannel channel)   // 目标频道。发的人必须是那个频道的成员（未入盟发联盟频道回 `SOCIAL_CHAT_CHANNEL_INVALID`）—— 与 `/chat/send` 同一处校验，不另立一套。
{
}
