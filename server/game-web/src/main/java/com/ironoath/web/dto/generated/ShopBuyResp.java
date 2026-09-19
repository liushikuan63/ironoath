// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /shop/buy 响应：这一单实际花了什么、还剩多少、限购用到哪。
 */
public record ShopBuyResp(
        String rowId,   // 买的是哪一行。
        String itemId,   // 实际入账的**道具** id；买外观时为 null（见 frameId）。与请求里的 rowId 一起构成「花在哪、拿到什么」的完整凭据 —— 客服处理「我买了但背包里没有」时要的就是这两个加上 requestId。
        String frameId,   // 实际解锁的**头像框** id；买道具时为 null。
        int count,   // 实际成交个数。
        ShopCurrency currency,   // 实际扣的货币。
        long spent,   // 实际扣掉的总额 = price × count。**回显它而不是让客户端按自己那份表快照乘**：热更之后两边算出的总价一旦不同，客户端显示的会比实际扣的多/少，而差额的投诉只会打给客服。乘积用 int64（两个 long 相乘的结果必须是 long）。
        long balance,   // 扣完之后的余额（long，理由见 ShopListResp.balance）。
        int used,   // 本周期累计已买个数（含这一单）。
        int remaining,   // 本周期还能买几个。
        long serverNow)   // 服务端时间戳。
{
}
