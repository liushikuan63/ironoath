// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /social/report 请求体（B22 §一 3）。**举报只做留痕**（§五 裁决②）：服务端记下"谁、举报谁、哪条消息、什么原因、何时"，处置流程归运营侧 —— 代码不替它决定封不封号。
 */
public record ReportReq(
        String requestId,   // 幂等键。同一次举报重放会让留痕表多出一条重复记录，而运营看到的是一件事被报了两遍。
        String targetPlayerId,   // 被举报的人。<b>必填</b>：留痕与限频都按它记账，而客户端从聊天消息里本来就有发信人 id（`ChatMessageView.senderId`）—— 这不构成负担。"只有消息 id"那条路要在全服消息上建一个 id 索引，而除了它没有任何调用方需要那个索引（B22 §一 3 的原文给 messageId 打了问号，这里按"不留没人读的索引"取舍）。
        String messageId,   // 被举报的那条聊天消息 id。带上它，运营才能看到"被举报的原话"，否则只有一句转述 —— 而转述正是举报双方会各说各话的地方。
        ReportReason reason,   // 举报原因。
        String detail)   // 补充说明，可空。**会过内容安全送检**（与聊天同一条）：举报框同样是玩家自由输入，不能因为它叫"举报"就免检。
{
}
