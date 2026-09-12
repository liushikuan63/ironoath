// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /stamina/buy 请求体。用金币买体力（B09 §5 的付费点；直购礼包属 B15）。
 */
public record StaminaBuyReq(
        String requestId,   // 幂等键。买体力要扣金币，没有幂等就等于允许重放请求刷体力
        Integer times)   // 购买几次；null 表示 1 次。合并成一次请求是为了让「连买 5 次」只扣一次锁、只写一次存档，而不是五次读改写打架
{
}
