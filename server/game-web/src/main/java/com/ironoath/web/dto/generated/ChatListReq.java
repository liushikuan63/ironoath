// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /chat/list 请求体（拉取某频道的最近消息）。用 POST 是因为要带频道与游标，而 GET 的查询串在私聊频道上会泄漏对象 id 到访问日志里。
 */
public record ChatListReq(
        ChatChannel channel,   // 频道
        String toPlayerId,   // 私聊对象；<b>只有 PRIVATE 频道需要</b>，其余频道忽略。私聊的会话键是由<b>两个人</b>的 id 拼出来的，拉历史却不带对象就算不出键 —— 少这个字段的结果是「发得出去、刷新即丢」。它走请求体而不是 GET 查询串，理由与本 DTO 用 POST 同一条：对象 id 出现在 URL 里就会落进访问日志与代理日志。<b>缺它时报明确的错误，不返回空列表</b>：空列表会被客户端读成「这段会话没有历史」，从而安静地丢掉一整屏消息。
        String beforeMessageId,   // 游标：只要这条之前的消息；首次拉取为 null
        int limit)   // 最多要几条。服务端会夹到 global.CHAT_LOCAL_HISTORY_MAX
{
}
