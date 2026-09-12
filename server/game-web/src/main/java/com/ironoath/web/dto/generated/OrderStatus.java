// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 订单状态。取值必须与 game-core 的 PayOrder.Status 一致（由 PayContractParityTest 断言）。
 *
 * FAILED 与「发货失败」不是一件事：FAILED 是支付本身没成功（取消、超时、验签不过），发货失败时订单仍然是 SUCCESS 并进补单队列 —— 玩家已经付了钱，把订单标成 FAILED 等于账面上否认收到过这笔钱。
 */
public enum OrderStatus {
    PENDING,
    SUCCESS,
    FAILED
}
