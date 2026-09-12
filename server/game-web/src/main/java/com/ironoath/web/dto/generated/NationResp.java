// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国家操作的统一响应：返回操作后的完整视图，而不是只回一个 ok。客户端据此刷新面板，不需要再发一次查询 —— 少一次往返在弱网下就是少一次超时。
 */
public record NationResp(
        NationView nation,   // 操作后的国家视图。
        long serverNow)   // 服务端时间戳。
{
}
