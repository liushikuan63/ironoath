// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /pay/order 请求体：下单。
 */
public record CreateOrderReq(
        String requestId,   // 幂等键。下单必须幂等：弱网下客户端重发一次下单请求，若不幂等就会造出两笔订单，而玩家只会付其中一笔的钱 —— 另一笔会永久停在 PENDING，最后在补单队列里变成一条谁也说不清的记录。
        String productId,   // 商品 id。**价格不由客户端传**：客户端传价格等于把定价权交出去，改一下请求体就能一分钱买月卡。价格永远由服务端按 productId 查表。
        int count)   // 购买份数。有单次限购的商品由服务端按限购规则裁剪或拒绝，客户端传的只是意愿。
{
}
