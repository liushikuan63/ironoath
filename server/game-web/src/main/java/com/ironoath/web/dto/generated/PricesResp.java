// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /pay/prices 响应：价格表。
 */
public record PricesResp(
        List<ProductPrice> products,   // 全部在售商品。下架的商品不出现在这里，而不是标一个「已下架」—— 客户端拿到一个买不了的商品只会做出一个灰掉的按钮，而玩家会以为是 bug。
        String region)   // 本次下发的地区口径。客户端要把它记在缓存里：换区之后价格表必须重新拉，否则会用旧区的价格下单，而服务端按新区扣款。
{
}
