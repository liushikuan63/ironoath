// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 集结发起/加入的响应体（小队、联盟、国家三层共用，靠 RallyView.scope 区分）。
 */
public record RallyResp(
        RallyView rally,   // 集结视图
        long serverNow)   // 服务端时间戳
{
}
