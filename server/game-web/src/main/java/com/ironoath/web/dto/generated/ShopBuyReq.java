// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /shop/buy 请求体。
 */
public record ShopBuyReq(
        String requestId,   // 幂等键。兑换会扣货币并发道具，重放等于刷道具。
        ShopCurrency currency,   // 客户端**以为**自己在哪个商店页兑换。必须与该行的 `priceCurrency` 一致，不一致直接拒绝（见本文件 description 第 2 条）。
        String rowId,   // `shop` 表的行 id。以前这个字段叫 `itemId` 却在注释里写着「shop 表的行 id」—— 一个名字两种读法的字段在商店里必然出事：同一件道具在表里可以有多行不同价格（`item_res_wood_10k` 就同时出现在金币行与联盟行），按 itemId 找行会拿错价格。
        int count)   // 买几个。**不得为 0 或负数**：`limitCount - count` 之类的判断在负数下会变成「买得越多剩得越多」。
{
}
