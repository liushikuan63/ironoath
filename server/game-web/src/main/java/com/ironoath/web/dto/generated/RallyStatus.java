// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 集结状态。PREPARING 期间成员可以加入或退出，DEPARTED 之后不可更改 —— B10 验收 11 要求「倒计时结束时所有参与部队统一出发」，若出发后还能加人，就会出现「大部队已经打完了我才到」的部队白送一次行军时间。
 */
public enum RallyStatus {
    PREPARING,
    DEPARTED,
    ARRIVED,
    CANCELLED
}
