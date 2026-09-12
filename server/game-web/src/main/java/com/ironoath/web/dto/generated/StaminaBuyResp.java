// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /stamina/buy 响应体。
 */
public record StaminaBuyResp(
        StaminaResp stamina,
        long granted,   // 实际到账的体力。<b>可能小于请求量</b>：超出上限的部分永久损失，而金币照扣 —— 所以客户端必须在购买前用 StaminaResp.cap 提示玩家「继续购买会溢出」。服务端不替玩家做这个判断，但服务端会在响应里照实说给了多少，绝不静默吞掉
        long costGold,   // 本次实际扣除的金币（多次购买时是递增单价之和）
        long boughtToday)   // 今日累计购买次数
{
}
