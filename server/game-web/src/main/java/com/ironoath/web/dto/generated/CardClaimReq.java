// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /pay/card/claim 请求体：领取月卡日包。
 */
public record CardClaimReq(
        String requestId,   // 幂等键。日包是「点一下给东西」的写操作，弱网重发不幂等就会一天领两份。
        String productId)   // 选定月卡商品，缺字段/null只领取旧综合月卡，绝不隐式选择两新卡；服务端按自身存档和04:00业务日确定资格与份数。
{
}
