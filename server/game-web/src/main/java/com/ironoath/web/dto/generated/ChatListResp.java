// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /chat/list 响应体。
 */
public record ChatListResp(
        List<ChatMessageView> messages,   // 消息，按时间升序（客户端直接从上往下画）
        int historyCap,   // 客户端本地聊天历史该留多少条（服务端下发的上限）。**为什么必须下发**：这个数住在 global.CHAT_LOCAL_HISTORY_MAX，而**客户端没有配置表加载器**（global.json 不进包）⇒ 客户端拿不到表。请求里的 limit 那一处**有服务端兜底**（服务端会夹到同一个表值，发大了也越不过去），但**客户端本地缓存留多少条没有兜底** —— 那一处完全由客户端自己那份数决定，表一改客户端就静默留着旧上限（内存多占、屏上翻得更远），而没有任何测试会红。⇒ 所以下发的是「本地缓存上限」这一个数。
        boolean hasMore,   // 是否还有更早的消息
        long serverNow)   // 服务端时间戳
{
}
